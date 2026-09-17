package com.ccj.agent.core;

import java.util.Map;

/**
 * The endpoint, credential and model of the model that describes pictures.
 *
 * <p>Deliberately not the main provider's. Those are two different choices: a cheap fast model is
 * right for reading a screenshot while an expensive one writes the code, and a local model is right
 * for a photo of something private. Reusing the main provider would force them to be one decision.
 *
 * <p>Every field is optional, and a block with nothing in it is not a configuration but the absence
 * of one: {@link Config} stores that as null, so "off" has exactly one representation and no two
 * code paths can disagree about whether it is on.
 */
public record VisionConfig(String baseUrl, String apiKey, String apiKeyEnv, String model) {

  public VisionConfig {
    baseUrl = blankToNull(baseUrl);
    apiKey = blankToNull(apiKey);
    apiKeyEnv = blankToNull(apiKeyEnv);
    model = blankToNull(model);
  }

  /** True when nothing was entered at all. */
  public boolean isEmpty() {
    return baseUrl == null && apiKey == null && apiKeyEnv == null && model == null;
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
