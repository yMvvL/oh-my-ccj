package com.ccj.agent.core;

import java.util.Map;

/**
 * The endpoint, credential and model of the model that describes pictures, and the ceiling on the
 * reply it may write.
 *
 * <p>Deliberately not the main provider's. Those are two different choices: a cheap fast model is
 * right for reading a screenshot while an expensive one writes the code, and a local model is right
 * for a photo of something private. Reusing the main provider would force them to be one decision.
 *
 * <p>Every field is optional, and a block with nothing in it is not a configuration but the absence
 * of one: {@link Config} stores that as null, so "off" has exactly one representation and no two
 * code paths can disagree about whether it is on.
 *
 * @param maxTokens the completion budget for one description, or null for {@link
 *     VisionClient#DEFAULT_MAX_TOKENS}. It is a ceiling and not a spend — a description costs what
 *     the model writes, whatever room it was given — which is why naming it is only ever about
 *     giving a reasoning model enough room to finish thinking before it starts answering. Measured
 *     on a busy phone screenshot: 1500 tokens went entirely on reasoning and the description never
 *     started, 4096 finished the job using 2882.
 */
public record VisionConfig(
    String baseUrl, String apiKey, String apiKeyEnv, String model, Integer maxTokens) {

  public VisionConfig {
    baseUrl = blankToNull(baseUrl);
    apiKey = blankToNull(apiKey);
    apiKeyEnv = blankToNull(apiKeyEnv);
    model = blankToNull(model);
  }

  /** The three fields of the original block, with the budget left at its default. */
  public VisionConfig(String baseUrl, String apiKey, String apiKeyEnv, String model) {
    this(baseUrl, apiKey, apiKeyEnv, model, null);
  }

  /** True when nothing was entered at all. */
  public boolean isEmpty() {
    return baseUrl == null && apiKey == null && apiKeyEnv == null && model == null && maxTokens == null;
  }

  /**
   * True when this names the two fields a description cannot be asked for without.
   *
   * <p>A key is not one of them: it may come from the environment, which is not readable here.
   */
  public boolean isConfigured() {
    return baseUrl != null && model != null;
  }

  /** The key to send, or null when neither this record nor the environment provides one. */
  public String resolvedApiKey(Map<String, String> env) {
    if (apiKey != null) {
      return apiKey;
    }
    if (apiKeyEnv == null || env == null) {
      return null;
    }
    String fromEnv = env.get(apiKeyEnv);
    return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.strip();
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
