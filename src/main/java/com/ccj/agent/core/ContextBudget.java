package com.ccj.agent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits a conversation into a prompt budget without breaking the wire format.
 *
 * <p>A session file grows without bound and a provider's context does not, so a long conversation has
 * to be projected onto a smaller one before it is sent. Three rules decide how:
 *
 * <ol>
 *   <li><b>Tool output goes first.</b> Old tool results are the bulk of an agent's context and the
 *       least valuable part of it: a file read twenty turns ago is usually stale, while the sentence
 *       that asked for it still matters. Eliding their <em>contents</em> keeps the call/result pairing
 *       the APIs require, so the conversation stays valid while shrinking by orders of magnitude.
 *   <li><b>Then whole exchanges go.</b> Dropping starts at the oldest end and never splits an
 *       assistant turn from the results of the calls it made — that pairing is what both wire formats
 *       validate, and half of it is a rejected request rather than a smaller one.
 *   <li><b>The current exchange is never dropped.</b> The model is answering it. If it alone does not
 *       fit, the largest result in it is truncated with an explicit marker, which is the only honest
 *       thing left to do.
 * </ol>
 *
 * <p>The session keeps everything; this only decides what one request carries.
 */
public final class ContextBudget {

  private static final String ELIDED = "(elided: %d tokens of tool output from an earlier turn)";

  /** What the truncation marker itself costs, so a cut can be sized to land under the budget. */
  private static final int MARKER_TOKENS = 32;

  /**
   * What a trim did, in the numbers the loop reports.
   *
   * @param messages the projection to send
   * @param beforeTokens estimate of the conversation as it was
   * @param afterTokens estimate of what is left
   * @param elidedResults tool results whose contents were replaced
   * @param droppedTurns exchanges removed entirely
   * @param truncated true when a single result in the current exchange had to be cut
   */
  public record Result(
      List<Message> messages,
      int beforeTokens,
      int afterTokens,
      int elidedResults,
      int droppedTurns,
      boolean truncated,
      boolean overBudget) {

    /** True when the projection carried something away: the conversation it returns is smaller. */
    public boolean trimmed() {
      return elidedResults > 0 || droppedTurns > 0 || truncated;
    }

    /** One line for the transcript, or null when nothing was trimmed and nothing was left over. */
    public String notice() {
      if (!trimmed() && !overBudget) {
        return null;
      }
      StringBuilder text =
          new StringBuilder("context: ")
              .append(beforeTokens)
              .append(" → ")
              .append(afterTokens)
              .append(" tokens");
      if (elidedResults > 0) {
        text.append(", ").append(elidedResults).append(" old tool result(s) elided");
      }
      if (droppedTurns > 0) {
        text.append(", ").append(droppedTurns).append(" earlier exchange(s) dropped");
      }
      if (truncated) {
        text.append(", the newest result was cut short");
      }
      if (overBudget) {
        // Saying so is the difference between "the model was given less than you think" and a
        // request that quietly exceeds the window it was budgeted for.
        text.append(", still over the budget: nothing else was left to cut");
      }
      return text.toString();
    }
  }

  private ContextBudget() {}

  /**
   * Projects {@code messages} onto at most {@code maxTokens} estimated tokens.
   *
   * @param maxTokens the budget, or anything &le; 0 for "no budget": the conversation is returned
   *     unchanged, which is what a run that never configured one expects
   */
  public static Result apply(List<Message> messages, int maxTokens) {
    List<Message> source = messages == null ? List.of() : List.copyOf(messages);
    int before = TokenEstimate.of(source);
    if (maxTokens <= 0 || before <= maxTokens) {
      return new Result(source, before, before, 0, 0, false, false);
    }

    // Measured once, then carried as arithmetic: every step below knows exactly what it changed, and
    // re-estimating the whole conversation inside a loop made one projection O(messages ×
    // characters) — seconds of pure CPU on a long session, on every model turn.
    Trimmed working = new Trimmed(new ArrayList<>(source), before);

    int elided = elideToolOutputs(working, maxTokens);

    int dropped = 0;
    while (working.tokens > maxTokens) {
      int end = firstExchangeEnd(working.messages);
      if (end <= 0) {
        break; // only the exchange being answered is left
      }
      working.dropPrefix(end);
      dropped++;
    }

    boolean truncated = truncateLargest(working, maxTokens);

    // The reported number is measured, not accumulated: the estimate is a heuristic, and what the
    // caller is told it sent should be what the estimator says it sent.
    int after = TokenEstimate.of(working.messages);
    return new Result(
        List.copyOf(working.messages), before, after, elided, dropped, truncated, after > maxTokens);
  }

  /**
   * A conversation being trimmed, with its token total carried along.
   *
   * <p>Every mutation here is paired with the arithmetic for it, which is what keeps one projection
   * linear in the size of the conversation instead of quadratic.
   */
  private static final class Trimmed {

    private final List<Message> messages;
    private int tokens;

    Trimmed(List<Message> messages, int tokens) {
      this.messages = messages;
      this.tokens = tokens;
    }

    /** Swaps one message for another and adjusts the total by what the swap actually saved. */
    void replace(int index, Message replacement) {
      tokens += TokenEstimate.of(replacement) - TokenEstimate.of(messages.get(index));
      messages.set(index, replacement);
    }

    /** Removes the first {@code end} messages. */
    void dropPrefix(int end) {
      for (int i = 0; i < end; i++) {
        tokens -= TokenEstimate.of(messages.get(i));
      }
      messages.subList(0, end).clear();
    }
  }

  /**
   * Replaces the contents of old tool results, oldest first, until the conversation fits or every
   * result outside the current exchange has been elided. A result in the current exchange is left
   * alone: it is the data the model is reasoning about right now, and eliding it to save room for
   * the sentence that asked for it would be exactly backwards.
   *
   * @return how many results were elided
   */
  private static int elideToolOutputs(Trimmed working, int maxTokens) {
    int currentExchange = lastExchangeStart(working.messages);
    int elided = 0;
    for (int i = 0; i < working.messages.size() && i < currentExchange; i++) {
      if (working.tokens <= maxTokens) {
        break;
      }
      if (!(working.messages.get(i) instanceof Message.ToolResult result)
          || result.content().isEmpty()) {
        continue;
      }
      int saved = TokenEstimate.of(result.content());
      working.replace(
          i,
          new Message.ToolResult(
              result.toolCallId(), result.toolName(), String.format(ELIDED, saved), result.error()));
      elided++;
    }
    return elided;
  }

  /** Index of the newest exchange's first message: from there on, the model is answering it. */
  private static int lastExchangeStart(List<Message> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof Message.User) {
        return i;
      }
    }
    return 0;
  }

  /**
   * One past the end of the oldest exchange: from its first user message up to the second one, which
   * starts the exchange after it. Leading messages that no user message owns (a system prompt) go
   * with it. Zero means there is only one exchange left — the one being answered — and it is never
   * dropped.
   */
  private static int firstExchangeEnd(List<Message> messages) {
    int seen = 0;
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i) instanceof Message.User) {
        seen++;
        if (seen == 2) {
          return i;
        }
      }
    }
    return 0;
  }

  /**
   * Cuts the largest tool result in what is left — necessarily in the current exchange — until the
   * request fits, or until cutting cannot buy anything more. The marker says so, because a model that
   * is shown half a build log without being told is a model that will reason about the missing half.
   *
   * <p>The loop stops on a pass that buys nothing rather than after a fixed number of them: the
   * estimate is a heuristic, so "exactly enough" is not something one pass can promise, and a cap
   * would leave an oversize request to be sent and rejected. A pass that shrinks nothing means the
   * remaining text is already at its floor, and the caller reports the miss instead of pretending.
   * The marker's own tokens count against the budget too, which is why they are subtracted rather
   * than discovered.
   *
   * @return true when something was cut
   */
  private static boolean truncateLargest(Trimmed working, int maxTokens) {
    boolean cut = false;
    while (working.tokens > maxTokens) {
      int largest = largestResult(working.messages);
      if (largest < 0) {
        return cut; // prose is all that is left, and cutting the question is not a repair
      }
      Message.ToolResult result = (Message.ToolResult) working.messages.get(largest);
      String content = result.content();
      int contentTokens = Math.max(1, TokenEstimate.of(content));
      int keepTokens = Math.max(16, contentTokens - (working.tokens - maxTokens) - MARKER_TOKENS);
      // Scaled by the rate this text actually measured at, so a CJK result is not kept four times
      // longer than an ASCII one would be.
      int keepChars =
          (int) Math.max(256L, (long) content.length() * keepTokens / contentTokens);
      keepChars = Math.min(keepChars, content.length());
      if (keepChars >= content.length()) {
        return cut; // nothing left to give
      }
      int was = working.tokens;
      working.replace(
          largest,
          new Message.ToolResult(
              result.toolCallId(),
              result.toolName(),
              content.substring(0, keepChars)
                  + "\n... (cut short: this result did not fit the context budget) ...",
              result.error()));
      if (working.tokens >= was) {
        return cut; // a pass that buys nothing is where this stops, not something to repeat
      }
      cut = true;
    }
    return cut;
  }

  /** Index of the biggest tool result left, or -1 when there is none. */
  private static int largestResult(List<Message> messages) {
    int largest = -1;
    int largestTokens = 0;
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i) instanceof Message.ToolResult result) {
        int tokens = TokenEstimate.of(result.content());
        if (tokens > largestTokens) {
          largest = i;
          largestTokens = tokens;
        }
      }
    }
    return largest;
  }
}
