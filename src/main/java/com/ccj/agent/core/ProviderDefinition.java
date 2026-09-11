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

  public ProviderDefinition {
    name = name == null ? "" : name.strip();
    kind = kind == null || kind.isBlank() ? OPENAI : kind.strip().toLowerCase();
    baseUrl = baseUrl == null ? "" : baseUrl.strip();
    apiKeyEnv = apiKeyEnv == null || apiKeyEnv.isBlank() ? null : apiKeyEnv.strip();
    models = models == null ? List.of() : List.copyOf(models);
  }

  /** Only two protocols exist, so anything else is a typo worth reporting early. */
  public static boolean validKind(String kind) {
    return OPENAI.equals(kind) || ANTHROPIC.equals(kind);
  }

  public ProviderDefinition requireValid() {
    if (name.isBlank()) {
      throw new IllegalArgumentException("a provider definition needs a name");
    }
    if (!Workspace.validName(name)) {
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
