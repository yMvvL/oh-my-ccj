package com.ccj.agent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes a conversation sendable again after an interruption.
 *
 * <p>Both wire formats validate the same shape: an assistant turn that asked for a tool call must be
 * followed by one result per call, in the run of messages immediately after it. The session, however,
 * is append-only and the loop appends the assistant <em>before</em> the calls run — so an abort
 * between the two, or a process killed mid-call, leaves a history that no provider will accept. The
 * failure is permanent and total: every later turn re-sends the same broken history and is refused,
 * so the conversation can never be continued.
 *
 * <p>A repair cannot rewrite the file (append-only is the property that makes resuming trustworthy),
 * so it is applied to the projection that goes on the wire. The recorded truth is preserved and the
 * missing results are supplied: "not run" is what actually happened to those calls, and it is a
 * result the model can read.
 *
 * <p>The answers are collected <em>by call</em> rather than by adjacency. A conversation can gain a
 * message in the middle of a turn's answers — a second front end on the same session, a user typing
 * while the turn runs, a stray write into the file — and the result then is not where the wire format
 * wants it. Such a result is carried back into its turn, where it belongs and where the API will
 * accept it, instead of being sent as an orphan that no provider can place. An answer that no
 * recorded call is waiting for has nowhere to go at all: it is left out of the projection, and the
 * notice says so, because a request is only sendable if every result answers a call.
 */
public final class SessionRepair {

  /** What a missing result says, which is the one thing that is certainly true about it. */
  public static final String NOT_RUN =
      "not run: the turn was interrupted before this call finished";

  /**
   * A repaired conversation.
   *
   * @param messages the same messages, with one synthetic result per unanswered call placed right
   *     after the assistant that asked for it
   * @param filled how many results were supplied
   * @param moved how many answers had to be carried back to the turn that asked for them
   * @param dropped how many results are not in the projection because they answer no call
   */
  public record Result(List<Message> messages, int filled, int moved, int dropped) {

    public boolean repaired() {
      return filled > 0 || moved > 0 || dropped > 0;
    }

    /** One line for the transcript, or null when there was nothing to repair. */
    public String notice() {
      if (!repaired()) {
        return null;
      }
      List<String> parts = new ArrayList<>(3);
      if (filled > 0) {
        parts.add(
            filled
                + (filled == 1 ? " interrupted tool call" : " interrupted tool calls")
                + " marked as not run");
      }
      if (moved > 0) {
        parts.add(
            moved
                + (moved == 1 ? " tool result" : " tool results")
                + " moved back to the turn that asked for it");
      }
      if (dropped > 0) {
        // The result is not sent at all, and saying nothing about it would hide a real answer from
        // the person who can go and look at the file it is still in.
        parts.add(
            dropped
                + (dropped == 1 ? " tool result" : " tool results")
                + " that answer no call left out of the request");
      }
      return "history repaired: " + String.join(", ", parts);
    }
  }

  private SessionRepair() {}

  /**
   * Returns {@code messages} with every tool call and every tool result properly paired: the answers
   * gathered into the turn that asked for the calls, in the call order the wire format expects, and a
   * "not run" result for any call that has none.
   */
  public static Result apply(List<Message> messages) {
    if (messages == null || messages.isEmpty()) {
      return new Result(List.of(), 0, 0, 0);
    }
    List<Message> out = new ArrayList<>(messages.size() + 4);
    // The turn whose answers are still being collected, and whatever arrived while it waited: those
    // messages cannot be written out before the answers, because that is exactly the shape both wire
    // formats reject, so they wait here and leave once the turn is whole.
    Open open = null;
    List<Message> deferred = new ArrayList<>();
    int filled = 0;
    int moved = 0;
    int dropped = 0;

    for (Message message : messages) {
      if (message instanceof Message.ToolResult result) {
        int slot = open == null ? -1 : open.slotFor(result);
        if (slot < 0) {
          // Nothing is waiting for it: either its call was never recorded, or it arrives twice. The
          // wire format has no place for such a result, so sending it is the rejected request this
          // projection exists to prevent.
          dropped++;
          continue;
        }
        if (slot < open.arrived || !deferred.isEmpty()) {
          // It had to be carried back to its turn: something arrived between the two, or the answers
          // came in an order of their own. A slot *past* the next free one is not a move — it is an
          // earlier call that has no answer yet, which the flush below supplies.
          moved++;
        }
        open.answer(slot, result);
        if (open.complete()) {
          filled += flush(out, open, deferred);
          open = null;
        }
        continue;
      }
      if (message instanceof Message.Assistant assistant && assistant.hasToolCalls()) {
        if (open != null) {
          filled += flush(out, open, deferred);
        }
        open = new Open(assistant);
        continue;
      }
      if (open == null) {
        out.add(message);
      } else {
        deferred.add(message);
      }
    }
    if (open != null) {
      filled += flush(out, open, deferred);
    }
    return new Result(List.copyOf(out), filled, moved, dropped);
  }

  /**
   * Writes one turn out whole: the assistant, one result per call in call order — the real one where
   * there is one, "not run" where there is not — and then whatever waited behind it.
   *
   * @return how many results had to be supplied
   */
  private static int flush(List<Message> out, Open open, List<Message> deferred) {
    List<Message.ToolCall> calls = open.assistant.toolCalls();
    out.add(open.assistant);
    int filled = 0;
    for (int i = 0; i < calls.size(); i++) {
      Message.ToolResult answer = open.answers.get(i);
      if (answer != null) {
        out.add(answer);
        continue;
      }
      Message.ToolCall call = calls.get(i);
      out.add(new Message.ToolResult(call.id(), call.name(), NOT_RUN, true));
      filled++;
    }
    out.addAll(deferred);
    deferred.clear();
    return filled;
  }

  /**
   * One assistant turn whose answers are being collected.
   *
   * <p>Answers are matched to calls positionally, not by id alone: servers exist that omit call ids
   * entirely, and two calls sharing one empty id are still two calls. The first unanswered call with a
   * matching id takes the result, which is what keeps such a conversation sendable.
   */
  private static final class Open {

    private final Message.Assistant assistant;
    private final List<Message.ToolResult> answers;
    /** How many answers have arrived, which is the slot the next one belongs in if nothing moved. */
    private int arrived;

    Open(Message.Assistant assistant) {
      this.assistant = assistant;
      List<Message.ToolResult> slots = new ArrayList<>(assistant.toolCalls().size());
      for (int i = 0; i < assistant.toolCalls().size(); i++) {
        slots.add(null);
      }
      this.answers = slots;
    }

    /** The slot this result answers, or -1 when it answers none of this turn's calls. */
    int slotFor(Message.ToolResult result) {
      List<Message.ToolCall> calls = assistant.toolCalls();
      for (int i = 0; i < answers.size(); i++) {
        if (answers.get(i) == null && calls.get(i).id().equals(result.toolCallId())) {
          return i;
        }
      }
      return -1;
    }

    void answer(int slot, Message.ToolResult result) {
      answers.set(slot, result);
      arrived++;
    }

    boolean complete() {
      return arrived == answers.size();
    }
  }
}
