package com.ccj.agent.core;

/**
 * The endpoint and credential entered for one provider: what {@link Config} keeps in its flat
 * {@code baseUrl}/{@code apiKey}/{@code apiKeyEnv} fields while that provider is the active one, and
 * what it remembers for the others.
 *
 * <p>Provider-scoped by nature, so it travels as a unit and is stored next to the name it belongs to:
 * a key without its endpoint, or an endpoint without its key, is a pair that never worked together.
 * Every field may be null or blank, which means "nothing was entered for this one" — the provider's
 * definition or the built-in defaults then apply.
 */
public record ProviderSettings(String baseUrl, String apiKey, String apiKeyEnv) {

  public ProviderSettings {
    baseUrl = blankToNull(baseUrl);
    apiKey = blankToNull(apiKey);
    apiKeyEnv = blankToNull(apiKeyEnv);
  }

  /** True when nothing was entered at all, which is a pair not worth remembering. */
  public boolean isEmpty() {
    return baseUrl == null && apiKey == null && apiKeyEnv == null;
  }

  /** True when this pair holds a key literal, the only field worth redacting in a report. */
  public boolean hasKey() {
    return apiKey != null;
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
