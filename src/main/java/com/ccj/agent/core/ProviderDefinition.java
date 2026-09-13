package com.ccj.agent.core;

import java.util.List;

/**
 * A provider the user defined themselves: a name they chose, a protocol, an endpoint and the models
 * it serves.
 *
 * <p>This is what makes a relay, a gateway, a self-hosted vLLM or a personal API router usable
 * without waiting for a release: it is only ever a name, a URL and a protocol.
 *
 * @param kind which wire protocol to speak; {@code openai} covers everything that speaks
 *     {@code /chat/completions}, {@code anthropic} the messages API
 * @param models what the settings form offers; an empty list simply means "type the model yourself"
 */
public record ProviderDefinition(
    String name, String kind, String baseUrl, String apiKeyEnv, List<String> models) {

  public static final String OPENAI = "openai";
  public static final String ANTHROPIC = "anthropic";

  /** Endpoint paths the providers append themselves, which a base URL must not repeat. */
  private static final List<String> ENDPOINT_SUFFIXES =
      List.of("/chat/completions", "/v1/messages", "/messages");

  /**
   * A provider name is an identifier and nothing else — it is never a directory — so it keeps the
   * strict character set, unlike a workspace name, which is a folder's own.
   */
  private static final java.util.regex.Pattern NAME =
      java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,39}");

  public ProviderDefinition {
    name = name == null ? "" : name.strip();
    kind = kind == null || kind.isBlank() ? OPENAI : kind.strip().toLowerCase();
    baseUrl = normaliseBaseUrl(baseUrl == null ? "" : baseUrl.strip());
    apiKeyEnv = apiKeyEnv == null || apiKeyEnv.isBlank() ? null : apiKeyEnv.strip();
    models = models == null ? List.of() : List.copyOf(models);
  }

  /**
   * Removes a path the providers add themselves — the one rule for every base URL that enters the
   * system, whether typed into a provider definition or into the settings form. A base URL of
   * {@code https://host/v1} plus
   * {@code /chat/completions} is the request; a base URL that already ends in
   * {@code /v1/chat/completions} would send the path twice and 404, which is a confusing way to
   * learn the rule — so the prefix is what is kept.
   */
  public static String normaliseBaseUrl(String baseUrl) {
    String value = baseUrl == null ? "" : baseUrl.strip();
    boolean changed = true;
    while (changed) {
      changed = false;
      // Trailing slashes go first: ".../chat/completions/" does not end with "/chat/completions",
      // so stripping the suffix first would leave the endpoint path behind and the provider would
      // append its own to it, twice over.
      while (value.endsWith("/")) {
        value = value.substring(0, value.length() - 1);
        changed = true;
      }
      for (String suffix : ENDPOINT_SUFFIXES) {
        if (value.toLowerCase().endsWith(suffix)) {
          value = value.substring(0, value.length() - suffix.length());
          changed = true;
        }
      }
    }
    return value;
  }

  /** Only two protocols exist, so anything else is a typo worth reporting early. */
  public static boolean validKind(String kind) {
    return OPENAI.equals(kind) || ANTHROPIC.equals(kind);
  }

  public ProviderDefinition requireValid() {
    if (name.isBlank()) {
      throw new IllegalArgumentException("a provider definition needs a name");
    }
    if (!NAME.matcher(name.strip()).matches()) {
      throw new IllegalArgumentException(
          "a provider name must be 1-40 characters of letters, digits, dot, dash or underscore");
    }
    if (!validKind(kind)) {
      throw new IllegalArgumentException("unknown provider kind '" + kind + "'; use openai or anthropic");
    }
    if (baseUrl.isBlank()) {
      throw new IllegalArgumentException("provider '" + name + "' needs a base URL");
    }
    return this;
  }
}
