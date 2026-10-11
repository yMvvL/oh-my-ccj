package com.ccj.agent.web;

import com.ccj.agent.core.AgentListener;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.ToolResult;
import com.ccj.agent.core.Json;
import com.ccj.agent.ui.ToolSummary;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 把循环的回调翻译成线上事件；这里没有任何东西做策略决定。
 *
 * <p>它需要的两个出口——往事件流上发布、让用量面板立刻看到新数字——是注入进来的，而不是抓着一个
 * {@link AgentHub} 的引用：这一层要做的翻译（一个回调变成哪一种事件、带上哪些字段）与「事件往哪去」是两件
 * 事，而后面那件属于那个握着事件流的人。
 */
final class WebListener implements AgentListener {

  /** 翻译好的事件往哪去。由 hub 提供，因为它握着事件流与用量面板。 */
  interface Events {

    void publish(String sessionId, String type, ObjectNode payload);

    void publishUsage(String sessionId);
  }

  private final Conversation conversation;
  private final Events events;

  /**
   * @param conversation 这条监听器所属的对话——由调用方直接给，而不是按 id 再查一遍：调用方手里本来就拿着它，
   *     而按 id 查会多出一种「查不到就造一个」的分支，那正是两个真相来源的开始
   */
  WebListener(Conversation conversation, Events events) {
    this.conversation = conversation;
    this.events = events;
  }

  @Override
  public void onText(String delta) {
    events.publish(conversation.id(), "text", Json.object().put("delta", delta));
  }

  @Override
  public void onReasoning(String delta) {
    events.publish(conversation.id(), "reasoning", Json.object().put("delta", delta));
  }

  @Override
  public void onToolStart(Message.ToolCall call) {
    conversation.add(0, 0, null, 0, 0, 1, 0, 0);
    events.publish(conversation.id(), "tool", toolPayload(call, "start"));
    // 面板数的是屏幕上的东西：没有这一句，卡片说某次调用正在跑，计数器却还停在上一回合的总数上。
    events.publishUsage(conversation.id());
  }

  @Override
  public void onToolEnd(Message.ToolCall call, ToolResult result, long elapsedMillis) {
    if (result.error()) {
      conversation.add(0, 0, null, 0, 0, 0, 1, 0);
    }
    events.publish(
        conversation.id(),
        "tool",
        toolPayload(call, "end")
            .put("ok", !result.error())
            .put("elapsedMs", elapsedMillis)
            .put("output", result.content()));
    events.publishUsage(conversation.id());
  }

  /** 模型回合在这里计数，而不是来自用量：一个不报用量的提供方照样跑过。 */
  @Override
  public void onTurnStart(int step) {
    conversation.add(0, 0, null, 0, 1, 0, 0, 0);
  }

  @Override
  public void onUsage(int inputTokens, int outputTokens, Integer cachedInputTokens) {
    // token 由循环本身记进会话账本（它是唯一知道会话的那个东西，也是终端唯一有的那个东西）；这里只负责让
    // 页面立刻看到新数字。再加一次就是同一笔钱记两遍。
    events.publishUsage(conversation.id());
  }

  @Override
  public void onNotice(String text) {
    events.publish(conversation.id(), "notice", Json.object().put("text", text));
  }

  private ObjectNode toolPayload(Message.ToolCall call, String state) {
    return Json.object()
        .put("id", call.id())
        .put("name", call.name())
        .put("state", state)
        .put("summary", ToolSummary.summarise(call));
  }
}
