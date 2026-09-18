package com.ccj.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 提供方测试替身：重放一份固定的 assistant 回合脚本，并记录每一个请求。 */
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
  /** 每个流式增量要花的时间，供那些「停掉一个仍在运行的调用」的测试使用。 */
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
   * 让每个增量都花这么久，以此代替一个仍在书写的模型。
   *
   * <p>真实的回合会流式输出数秒乃至数分钟，而那正是用户按下停止的时候；没有这个，脚本化提供方
   * 会瞬间作答，也就没有任何东西可供打断。
   */
  ScriptedProvider streaming(long deltaMillis, int deltas) {
    this.deltaMillis = deltaMillis;
    this.deltaCount = deltas;
    return this;
  }

  /** 在每个回合之后报告 token 计数：输入、输出、缓存（缓存可能为 null）。 */
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
      // 像线上协议那样把回复切碎，每个间隔一段，这样中止才有一次进行中的调用可供打断。
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
