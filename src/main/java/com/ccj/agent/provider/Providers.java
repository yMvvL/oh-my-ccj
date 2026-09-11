package com.ccj.agent.provider;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.ProviderDefinition;
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

  /** Every built-in provider name {@link #create} accepts, canonical names first. */
  public static List<String> supported() {
    return SUPPORTED;
  }

  /** The built-ins plus whatever the user defined, which is what a picker should offer. */
  public static List<String> supported(ProviderStore store) {
    List<String> names = new java.util.ArrayList<>(SUPPORTED);
    if (store != null) {
      for (ProviderDefinition definition : store.list()) {
        if (!names.contains(definition.name())) {
          names.add(definition.name());
        }
      }
    }
    return List.copyOf(names);
  }

  /**
   * @param resolved configuration after {@link Config#resolved()}
   * @param env environment to read the API key from; may be null
   */
  public static Provider create(Config resolved, Map<String, String> env) {
    return create(resolved, env, null);
  }

  /**
   * @param store custom provider definitions; may be null when none exist
   */
  public static Provider create(Config resolved, Map<String, String> env, ProviderStore store) {
    if (resolved == null) {
      throw new IllegalArgumentException("a resolved configuration is required");
    }
    Map<String, String> environment = env == null ? Map.of() : env;
    String name =
        resolved.provider() == null || resolved.provider().isBlank()
            ? Config.DEFAULT_PROVIDER
            : resolved.provider().strip().toLowerCase();

    // A custom definition wins over the built-in alias table: the user's own "custom" entry must
    // mean what they wrote, not the generic OpenAI-compatible default.
    ProviderDefinition custom = store == null ? null : store.find(name).orElse(null);
    requireModel(resolved, custom);
    String apiKey = requireApiKey(resolved, environment, custom, name);

    if (custom != null) {
      // Precedence: the definition's endpoint wins over the URL that Config#resolved fills in by
      // default (it fills the OpenAI one for any unrecognised name, which would silently override
      // the very definition being used). An explicitly different base URL still wins over both.
      String configured = resolved.baseUrl();
      boolean explicitOverride =
          configured != null
              && !configured.isBlank()
              && !configured.equals(Config.defaultBaseUrl(name));
      String baseUrl = explicitOverride ? configured : custom.baseUrl();
      return ProviderDefinition.ANTHROPIC.equals(custom.kind())
          ? new AnthropicProvider(baseUrl, apiKey)
          : new OpenAiProvider(baseUrl, apiKey);
    }

    if (!OPENAI_NAMES.contains(name) && !ANTHROPIC_NAMES.contains(name)) {
      throw new IllegalArgumentException(
          "unknown provider '"
              + name
              + "'; built-in: "
              + String.join(", ", SUPPORTED)
              + (store == null || store.list().isEmpty()
                  ? " (define your own in the settings panel)"
                  : "; defined: "
                      + String.join(
                          ", ",
                          store.list().stream().map(ProviderDefinition::name).toList())));
    }
    String baseUrl = resolveBaseUrl(resolved, name);
    return name.equals(AnthropicProvider.NAME)
        ? new AnthropicProvider(baseUrl, apiKey)
        : new OpenAiProvider(baseUrl, apiKey);
  }

  /** A custom provider supplies its own key variable; the built-ins have theirs. */
  private static String requireApiKey(
      Config resolved, Map<String, String> env, ProviderDefinition custom, String name) {
    if (custom == null) {
      return requireApiKey(resolved, env, name);
    }
    String variable =
        custom.apiKeyEnv() != null
            ? custom.apiKeyEnv()
            : resolved.apiKeyEnv() == null || resolved.apiKeyEnv().isBlank()
                ? Config.defaultKeyEnv(custom.kind())
                : resolved.apiKeyEnv();
    String apiKey = resolved.resolvedApiKey(env);
    if (apiKey != null) {
      return apiKey;
    }
    String fromEnv = env.get(variable);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.strip();
    }
    throw new IllegalArgumentException(
        "no API key for provider '"
            + name
            + "': set "
            + variable
            + " in the environment or \"apiKey\" in the config file");
  }

  private static void requireModel(Config resolved) {
    requireModel(resolved, null);
  }

  private static void requireModel(Config resolved, ProviderDefinition custom) {
    if (resolved.model() != null && !resolved.model().isBlank()) {
      return;
    }
    String provider = resolved.provider();
    String fallback = custom == null ? Config.defaultModel(provider) : null;
    String why =
        custom != null
            ? custom.models().isEmpty()
                ? "provider '" + provider + "' is a custom provider with no model list"
                : "provider '" + provider + "' offers: " + String.join(", ", custom.models())
            : fallback == null
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
