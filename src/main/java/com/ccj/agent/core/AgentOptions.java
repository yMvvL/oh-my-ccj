package com.ccj.agent.core;

/**
 * Knobs the agent loop itself cares about.
 *
 * <p>There is deliberately no ceiling on model turns per user input. A turn ends when the model
 * stops asking for tools, or when someone aborts it — nothing else. A cap cannot tell a model stuck
 * in a rut from one working through a long task, and the number that cuts the second one off to
 * punish the first is worse than no number: it fails a run that was going to succeed, and it does so
 * at an arbitrary point that has nothing to do with the work. Stopping is {@link AgentLoop#abort()}.
 *
 * @param model provider-specific model identifier
 * @param system system prompt, or null for {@link Prompts#DEFAULT_SYSTEM}
 */
public record AgentOptions(
    String model,
    String system,
    Double temperature,
    Integer maxTokens,
    String reasoning,
    Integer maxContextTokens) {

  /** The knobs as they were before a prompt budget existed: no budget. */
  public AgentOptions(
      String model, String system, Double temperature, Integer maxTokens, String reasoning) {
    this(model, system, temperature, maxTokens, reasoning, null);
  }

  public static AgentOptions defaults() {
    return new AgentOptions(null, null, null, null, null, null);
  }

  /** The effort tier the model should spend, or null to leave it to the provider. */
  public String reasoning() {
    return reasoning;
  }

  /**
   * The prompt budget in estimated tokens, or 0 for "send the whole conversation".
   *
   * <p>Unset by default on purpose: a wrong guess about a model's context window is worse than no
   * guess, and the number that matters is the one the user paid for.
   */
  public int contextBudget() {
    return maxContextTokens == null || maxContextTokens <= 0 ? 0 : maxContextTokens;
  }

  public String systemPrompt() {
    return system == null || system.isBlank() ? Prompts.DEFAULT_SYSTEM : system;
  }
}
