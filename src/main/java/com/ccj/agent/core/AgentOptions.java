package com.ccj.agent.core;

/**
 * Knobs the agent loop itself cares about.
 *
 * @param model provider-specific model identifier
 * @param system system prompt, or null for {@link Prompts#DEFAULT_SYSTEM}
 * @param maxSteps hard ceiling on model turns per user input; keeps a looping model from burning
 *     tokens forever
 */
public record AgentOptions(
    String model,
    String system,
    Double temperature,
    Integer maxTokens,
    int maxSteps,
    String reasoning) {

  public AgentOptions {
    if (maxSteps <= 0) {
      maxSteps = 25;
    }
  }

  public static AgentOptions defaults() {
    return new AgentOptions(null, null, null, null, 25, null);
  }

  /** The effort tier the model should spend, or null to leave it to the provider. */
  public String reasoning() {
    return reasoning;
  }

  public String systemPrompt() {
    return system == null || system.isBlank() ? Prompts.DEFAULT_SYSTEM : system;
  }
}
