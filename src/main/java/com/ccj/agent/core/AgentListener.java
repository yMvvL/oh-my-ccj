package com.ccj.agent.core;

/**
 * 代理循环的观察点。
 *
 * <p>每个方法都是可选的：循环必须能在没有渲染器的情况下无头使用（测试、单次运行），所以默认实现是空操作。
 */
public interface AgentListener {

  AgentListener NOOP = new AgentListener() {};

  /** 每个模型回合开始前调用，从第 0 步算起。 */
  default void onTurnStart(int step) {}

  default void onText(String delta) {}

  default void onReasoning(String delta) {}

  /** 每回合调用一次，传入组装完成的助手消息。 */
  default void onAssistant(Message.Assistant message) {}

  default void onToolStart(Message.ToolCall call) {}

  default void onToolEnd(Message.ToolCall call, ToolResult result, long elapsedMillis) {}

  /**
   * 一个回合的 token 记账。与给人看的通知分开，因为前端要的是数字：用量面板没法把散文加起来。
   */
  default void onUsage(int inputTokens, int outputTokens, Integer cachedInputTokens) {}

  /** 带外提示，例如上下文预算被截断或提供方重试。 */
  default void onNotice(String text) {}
}
