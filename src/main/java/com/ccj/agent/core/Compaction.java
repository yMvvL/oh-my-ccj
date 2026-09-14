package com.ccj.agent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * What older turns of a conversation come to, in the model's own words.
 *
 * <p>This is the one thing in the project that replaces conversation content rather than projecting
 * it, so the rules it follows are deliberately conservative:
 *
 * <ul>
 *   <li><b>The newest exchanges stay verbatim.</b> A summary of the last thing that happened is worse
 *       than no summary, because the model is about to act on it. Cutting happens at an exchange
 *       boundary — a {@link Message.User} — so a tool result never loses the assistant turn that asked
 *       for it, which is the same invariant {@link ContextBudget} and {@link SessionRepair} protect
 *       for their own reasons.
 *   <li><b>What is kept is quoted, not compressed.</b> The tail is copied as-is; only the older part
 *       is summarised. Nothing here paraphrases something the model can still read for itself.
 *   <li><b>The summary is asked for as a set of facts.</b> {@link #INSTRUCTIONS} names the categories
 *       that a model resuming the work actually needs — including the exact error text and paths it
 *       would otherwise have to rediscover — because "summarise this" produces a narrative, and a
 *       narrative is exactly what loses the detail the next step depends on.
 * </ul>
 */
public final class Compaction {

  /**
   * How many of the most recent exchanges stay verbatim.
   *
   * <p>Five is a judgement, not a constant of nature: it is about as far back as a person scrolls when
   * they are picking up work, and it is the window the model needs when the user's next message refers
   * to "that error" or "the file you just changed". The cost of a larger tail is a smaller saving; the
   * cost of a smaller one is a model that has to be told things it should already know.
   */
  public static final int KEEP_EXCHANGES = 5;

  /**
   * The instruction that turns a conversation into a summary.
   *
   * <p>Written as a list because the failure mode of prose summarisation is well known and specific:
   * the model produces a fluent account of what happened and drops every identifier it needs. Naming
   * the categories is what keeps the file paths, the commands that worked and the error text that did
   * not.
   */
  public static final String INSTRUCTIONS =
      """
      You are compacting a coding session so it can continue with less context. Below is the earlier \
      part of the conversation. Write a summary that a model resuming this work can act on.

      Cover these, in this order, keeping concrete facts rather than describing the process:

      1. Goal — what was asked for, and any constraint stated up front.
      2. Decisions — what was chosen, and why, including approaches that were tried and rejected.
      3. Files — every file created, modified or read for a reason, with its path, and what changed.
      4. Verification — what was run or checked, and its outcome. Quote exit codes and test results.
      5. Open — what is unfinished, broken, or still uncertain.
      6. Details worth not losing — exact error messages, command lines, API shapes, versions.

      Use the paths, identifiers and commands as they were written: they are the part that cannot be \
      guessed later. Be specific and complete rather than brief. Do not add anything that is not in \
      the conversation, and do not claim work was finished when it was not. Write the summary itself; \
      no preamble, no headings other than the numbered ones above.""".strip();

  /** The marker put in front of a summary wherever a model reads it. */
  private static final String PREAMBLE =
      "[Summary of earlier work in this session, written by the model when the conversation was "
          + "compacted]";

  /** What a summary says about where the real conversation still is. */
  private static final String SOURCE_NOTE =
      "[The full conversation, verbatim, is in %s — read it if you need a detail this summary does "
          + "not have.]";

  private Compaction() {}

  /**
   * The conversation an older part was replaced with.
   *
   * @param messages the summary followed by the exchanges that were kept
   * @param summarised how many messages were replaced by the summary
   * @param kept how many messages were kept verbatim
   * @param summaryTokens estimate of the summary that replaced them
   * @param replacedTokens estimate of the messages it replaced, which it is smaller than by definition
   */
  public record Result(
      List<Message> messages, int summarised, int kept, int summaryTokens, int replacedTokens) {

    /** How much of the replaced part the summary gave back, as a percentage. */
    public int savedPercent() {
      return replacedTokens <= 0 ? 0 : 100 - Math.round(summaryTokens * 100f / replacedTokens);
    }
  }

  /** True when there is an older part worth compacting: something to summarise, and a tail to keep. */
  public static boolean possible(List<Message> messages) {
    return cutPoint(messages) > 0;
  }

  /**
   * The index the summary replaces everything before.
   *
   * <p>The tail must begin at the start of an exchange, so this returns the position of the
   * {@code KEEP_EXCHANGES}-th exchange from the end — counting from the newest, not stopping after
   * passing that many, which would land one message late and split an exchange. Zero means there is
   * nothing to do: fewer exchanges than the tail keeps, so compacting would replace nothing and lose
   * the detail for no saving at all.
   */
  public static int cutPoint(List<Message> messages) {
    if (messages == null || messages.isEmpty()) {
      return 0;
    }
    List<Integer> starts = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      // A summary counts as the start of an exchange: compacting twice must not summarise a summary
      // as if it were raw history, or the second pass would compress the first pass's compression.
      if (messages.get(i) instanceof Message.User || messages.get(i) instanceof Message.Summary) {
        starts.add(i);
      }
    }
    if (starts.size() <= KEEP_EXCHANGES) {
      return 0;
    }
    return starts.get(starts.size() - KEEP_EXCHANGES);
  }

  /**
   * Compacts a conversation, or reports that compacting it would not be worth it.
   *
   * <p>Compaction is only a saving when the summary is smaller than what it replaces, and that is not
   * automatic: a summary has a preamble, names the file it came from, and is written to be complete —
   * so a conversation whose early exchanges were mostly tool-call plumbing can summarise into
   * something <em>longer</em> than the text it removed. The check is made against the messages the cut
   * would actually consume, not against the whole conversation, because that is the part being traded.
   *
   * @param messages the conversation as it stands
   * @param summary what the model wrote
   * @param source the file the summarised messages are still in
   * @param cwd used to show the source as a readable relative path
   * @throws NotWorthIt when the result would not be smaller than the conversation it replaces
   */
  public static Result apply(
      List<Message> messages, String summary, String source, java.nio.file.Path cwd) {
    int cut = cutPoint(messages);
    if (cut <= 0) {
      throw new IllegalArgumentException("this conversation has nothing to compact");
    }
    List<Message> replaced = messages.subList(0, cut);
    List<Message> kept = new ArrayList<>(messages.subList(cut, messages.size()));
    String label = display(source, cwd);
    StringBuilder text = new StringBuilder(PREAMBLE).append(' ');
    if (!label.isEmpty()) {
      text.append(String.format(SOURCE_NOTE, label)).append(' ');
    }
    text.append('\n').append(summary == null ? "" : summary.strip());
    Message.Summary head = new Message.Summary(text.toString(), cut, label);

    // Measured, not assumed: an estimate of the summary is not available before it is written, so the
    // comparison happens now and a losing trade is refused rather than written down.
    int replacedTokens = TokenEstimate.of(replaced);
    int summaryTokens = TokenEstimate.of(head);
    if (summaryTokens >= replacedTokens) {
      throw new NotWorthIt(replacedTokens, summaryTokens);
    }

    List<Message> out = new ArrayList<>(kept.size() + 1);
    out.add(head);
    out.addAll(kept);
    return new Result(List.copyOf(out), cut, kept.size(), summaryTokens, replacedTokens);
  }

  /**
   * Refused because the summary did not come out smaller than what it would replace.
   *
   * <p>Carries both numbers, because the honest answer to "why not" is the comparison itself: the
   * caller can say the conversation is not long enough yet rather than reporting a failure.
   */
  public static final class NotWorthIt extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int replacedTokens;
    private final int summaryTokens;

    NotWorthIt(int replacedTokens, int summaryTokens) {
      super(
          "the summary is "
              + summaryTokens
              + " tokens against the "
              + replacedTokens
              + " it would replace, so compacting would not free anything");
      this.replacedTokens = replacedTokens;
      this.summaryTokens = summaryTokens;
    }

    public int replacedTokens() {
      return replacedTokens;
    }

    public int summaryTokens() {
      return summaryTokens;
    }
  }

  /**
   * The summarised part, as plain text, in the shape the request that summarises it should carry.
   *
   * <p>Deliberately rendered from the same messages the wire formats would send — roles named, tool
   * calls and their results shown together — rather than the raw JSONL, because the summary has to be
   * readable by the model that has to write it.
   */
  public static String transcript(List<Message> messages) {
    int cut = cutPoint(messages);
    List<Message> part = cut <= 0 ? List.of() : messages.subList(0, cut);
    StringBuilder text = new StringBuilder();
    for (Message message : part) {
      switch (message) {
        case Message.System system -> append(text, "system", system.text());
        case Message.User user -> append(text, "user", user.text());
        case Message.Summary summary -> append(text, "summary of still earlier work", summary.text());
        case Message.Assistant assistant -> {
          StringBuilder body = new StringBuilder(assistant.text());
          for (Message.ToolCall call : assistant.toolCalls()) {
            if (!body.isEmpty()) {
              body.append('\n');
            }
            body.append("called ").append(call.name()).append(' ').append(call.arguments());
          }
          append(text, "assistant", body.toString());
        }
        case Message.ToolResult result ->
            append(
                text,
                result.error() ? "tool result (error)" : "tool result",
                result.toolName() + ": " + result.content());
      }
    }
    return text.toString();
  }

  private static void append(StringBuilder text, String role, String body) {
    text.append("\n### ").append(role).append('\n').append(body == null ? "" : body.strip()).append('\n');
  }

  /** The source path as it should appear in the prompt: relative to the cwd when it is inside it. */
  private static String display(String source, java.nio.file.Path cwd) {
    if (source == null || source.isBlank()) {
      return "";
    }
    try {
      java.nio.file.Path path = java.nio.file.Path.of(source).toAbsolutePath().normalize();
      if (cwd != null) {
        java.nio.file.Path base = cwd.toAbsolutePath().normalize();
        if (path.startsWith(base)) {
          java.nio.file.Path relative = base.relativize(path);
          return relative.toString().isEmpty() ? "." : relative.toString();
        }
      }
      return path.toString();
    } catch (RuntimeException e) {
      // Not a usable path: better to leave the note out than to print something a model would try to
      // open and fail on. The summary itself is unaffected.
      return "";
    }
  }
}
