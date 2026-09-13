package com.ccj.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Provider test double: replays a fixed script of assistant turns and records every request. */
final class ScriptedProvider implements Provider {

  record Reply(String text, List<Message.ToolCall> toolCalls) {

    static Reply text(String text) {
      return new Reply(text, List.of());
    }

    static Reply calls(Message.ToolCall... calls) {
      return new Reply("", List.of(calls));
    }

    static Reply textAndCalls(String text, Message.ToolCall... calls) {
      return new Reply(text, List.of(calls));
    }
  }

  private final String name;
  private final List<Reply> script;
  private final List<Provider.Request> requests = new ArrayList<>();
  private int cursor;
  private Exception failure;
  private int[] usage;
  /** How long each streamed delta takes, for tests about stopping a call that is still running. */
  private long deltaMillis;
  private int deltaCount;

  ScriptedProvider(Reply... replies) {
    this("scripted", List.of(replies));
  }

  ScriptedProvider(String name, List<Reply> replies) {
    this.name = name;
    this.script = replies;
  }

  ScriptedProvider failingWith(Exception failure) {
    this.failure = failure;
    return this;
  }

  /**
   * Makes every delta take this long, standing in for a model that is still writing.
   *
   * <p>A real turn streams for seconds or minutes, which is exactly when a user presses stop; without
   * this a scripted provider answers instantly and there is nothing to interrupt.
   */
  ScriptedProvider streaming(long deltaMillis, int deltas) {
    this.deltaMillis = deltaMillis;
    this.deltaCount = deltas;
    return this;
  }

  /** Reports token accounting after each turn: input, output, cached (cached may be null). */
  ScriptedProvider usage(int inputTokens, int outputTokens, Integer cachedInputTokens) {
    this.usage = new int[] {inputTokens, outputTokens, cachedInputTokens == null ? -1 : cachedInputTokens};
    return this;
  }

  List<Provider.Request> requests() {
    return List.copyOf(requests);
  }

  int callCount() {
    return requests.size();
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Message.Assistant complete(Request request, Consumer<Event> listener) throws Exception {
    requests.add(request);
    if (failure != null) {
      throw failure;
    }
    Reply reply = script.get(Math.min(cursor, script.size() - 1));
    cursor++;
    if (deltaMillis > 0) {
      // Fragment the reply the way the wire does, one piece per interval, so there is a call in
      // progress for an abort to interrupt.
      int pieces = Math.max(1, deltaCount);
      for (int i = 0; i < pieces; i++) {
        Thread.sleep(deltaMillis);
        listener.accept(new Event.TextDelta("piece" + i + " "));
      }
    }
    if (!reply.text().isEmpty()) {
      listener.accept(new Event.TextDelta(reply.text()));
    }
    for (Message.ToolCall call : reply.toolCalls()) {
      listener.accept(new Event.ToolCallStart(call.id(), call.name()));
    }
    if (usage != null) {
      listener.accept(
          new Event.Usage(usage[0], usage[1], usage[2] < 0 ? null : usage[2]));
    }
    return new Message.Assistant(reply.text(), reply.toolCalls());
  }
}
