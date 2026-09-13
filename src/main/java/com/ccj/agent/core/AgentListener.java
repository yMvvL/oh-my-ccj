package com.ccj.agent.core;

/**
 * Observation points for the agent loop.
 *
 * <p>Every method is optional: the loop must be usable headless (tests, one-shot runs) without a
 * renderer, so the default implementation is a no-op.
 */
public interface AgentListener {

  AgentListener NOOP = new AgentListener() {};

  /** Called before each model turn, starting at step 0. */
  default void onTurnStart(int step) {}

  default void onText(String delta) {}

  default void onReasoning(String delta) {}

  /** Called once per turn with the fully assembled assistant message. */
  default void onAssistant(Message.Assistant message) {}

  default void onToolStart(Message.ToolCall call) {}

  default void onToolEnd(Message.ToolCall call, ToolResult result, long elapsedMillis) {}

  /**
   * Token accounting for one turn. Separate from the human-readable notice because front ends want
   * the numbers: a usage panel cannot add up prose.
   */
  default void onUsage(int inputTokens, int outputTokens, Integer cachedInputTokens) {}

  /** Out-of-band remark, e.g. a truncated context budget or a provider retry. */
  default void onNotice(String text) {}
}
