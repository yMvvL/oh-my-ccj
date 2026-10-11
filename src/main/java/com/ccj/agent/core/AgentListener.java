package com.ccj.agent.core;

/**
 * 代理循环的观察点。
 *
 * <p>每个方法都是可选的：循环必须能在没有渲染器的情况下无头使用（测试、单次运行），所以默认实现是空操作。
 */
public interface AgentListener {

  AgentListener NOOP = new AgentListener() {};

  /**
   * 一次运行开始之前调用一次：一次<em>运行</em>，而不是其中的一个模型回合——后者是
   * {@link #onTurnStart(int)}，在一次运行里会来很多次。
   *
   * <p>它与 {@link #onRunEnd} 成对，存在的理由是让观察者能画出一次运行的边界：一次运行的耗时、它的结局，
   * 都是把这两头的时间相减得来的，而只靠回合边界拼不出它——最后一个回合结束到运行真正结束之间，还隔着
   * 收尾那一段（落盘、清空队列、自动压缩）。
   */
  default void onRunStart() {}

  /**
   * 一次运行结束时调用一次，正常收尾和失败收尾都走这里。
   *
   * <p>两者都有 {@code failure} 或都没有，是刻意的：一次因为模型不可达而结束的运行，既不是「完成」也不是
   * 「用户中止」，而用其中任何一个去表示它，都会让读这个数字的人算错——所以它就是它自己。
   *
   * @param result 运行的结果；运行以异常结束时为 null
   * @param failure 结束这次运行的异常；正常结束（含被中止）时为 null
   */
  default void onRunEnd(AgentLoop.Result result, Throwable failure) {}

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
