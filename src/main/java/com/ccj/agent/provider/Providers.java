package com.ccj.agent.provider;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.Provider;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Chooses a provider for a resolved {@link Config}.
 *
 * <p>Everything that can be wrong with the request is rejected here, before a socket is opened, so
 * the CLI can turn a misconfiguration into one actionable sentence instead of a vendor error page.
 */
public final class Providers {

  private static final List<String> OPENAI_NAMES =
      List.of("openai", "openai-compatible", "deepseek", "groq", "ollama", "custom");
  private static final List<String> ANTHROPIC_NAMES = List.of("anthropic");
  private static final List<String> SUPPORTED =
      Stream.concat(OPENAI_NAMES.stream(), ANTHROPIC_NAMES.stream()).toList();

  private Providers() {}

  /** Every provider name {@link #create} accepts, canonical names first. */
  public static List<String> supported() {
    return SUPPORTED;
  }

  /**
   * @param resolved configuration after {@link Config#resolved()}
   * @param env environment to read the API key from; may be null
   */
  public static Provider create(Config resolved, Map<String, String> env) {
    if (resolved == null) {
      throw new IllegalArgumentException("a resolved configuration is required");
    }
    Map<String, String> environment = env == null ? Map.of() : env;
    String name =
        resolved.provider() == null || resolved.provider().isBlank()
            ? Config.DEFAULT_PROVIDER
            : resolved.provider().strip().toLowerCase();
    if (!OPENAI_NAMES.contains(name) && !ANTHROPIC_NAMES.contains(name)) {
      throw new IllegalArgumentException(
          "unknown provider '" + name + "'; supported: " + String.join(", ", SUPPORTED));
    }
    requireModel(resolved);
    String apiKey = requireApiKey(resolved, environment, name);
    String baseUrl = resolveBaseUrl(resolved, name);
    return name.equals(AnthropicProvider.NAME)
        ? new AnthropicProvider(baseUrl, apiKey)
        : new OpenAiProvider(baseUrl, apiKey);
  }

  private static void requireModel(Config resolved) {
    if (resolved.model() != null && !resolved.model().isBlank()) {
      return;
    }
    String provider = resolved.provider();
    String fallback = Config.defaultModel(provider);
    String why =
        fallback == null
            ? "provider '" + provider + "' has no default model"
            : "a default (" + fallback + ") only applies to the provider's own endpoint, and '"
                + resolved.baseUrl()
                + "' is a custom one";
    throw new IllegalArgumentException(
        "no model configured: pass --model <name>, set CCJ_MODEL, or add \"model\" to the config file"
            + " — "
            + why);
  }

  private static String requireApiKey(Config resolved, Map<String, String> env, String provider) {
    String apiKey = resolved.resolvedApiKey(env);
    if (apiKey != null) {
      return apiKey;
    }
    String envVar =
        resolved.apiKeyEnv() == null || resolved.apiKeyEnv().isBlank()
            ? Config.defaultKeyEnv(provider)
            : resolved.apiKeyEnv();
    throw new IllegalArgumentException(
        "no API key for provider '"
            + provider
            + "': set "
            + envVar
            + " in the environment or `apiKey` in the config file");
  }

  private static String resolveBaseUrl(Config resolved, String provider) {
    String baseUrl = resolved.baseUrl();
    return baseUrl == null || baseUrl.isBlank() ? Config.defaultBaseUrl(provider) : baseUrl;
  }
}
