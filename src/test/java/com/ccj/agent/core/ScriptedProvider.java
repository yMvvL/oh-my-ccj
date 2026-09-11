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
    if (!reply.text().isEmpty()) {
      listener.accept(new Event.TextDelta(reply.text()));
    }
    for (Message.ToolCall call : reply.toolCalls()) {
      listener.accept(new Event.ToolCallStart(call.id(), call.name()));
    }
    return new Message.Assistant(reply.text(), reply.toolCalls());
  }
}
