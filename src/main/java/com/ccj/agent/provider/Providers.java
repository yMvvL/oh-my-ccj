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
   * True when the endpoint and credential a configuration stores are the ones {@code name} will
   * actually use.
   *
   * <p>A custom provider takes them from its own definition unless they were entered for it — a
   * stored pair is only this provider's to use when the configuration says so. A built-in has no
   * definition to fall back on, so whatever is stored is its own. This is the question a settings
   * form has to ask before showing them: displaying a value that will not be used offers to save it,
   * which is how the wrong endpoint gets written down again.
   */
  public static boolean usesStoredSettings(Config resolved, ProviderStore store, String name) {
    if (resolved == null || name == null || name.isBlank()) {
      return false;
    }
    ProviderDefinition definition = store == null ? null : store.find(name).orElse(null);
    return definition == null || resolved.settingsBelongTo(name);
  }

  /**
   * The endpoint {@code name} will actually be called at, which is not always the one the
   * configuration stores: a custom provider is served by its definition unless the stored URL was
   * entered for it, and a built-in by whatever the configuration says.
   *
   * <p>One place answers this so that what the status reports and what the request does cannot drift
   * apart — two answers to "where does this go" is how a session that reports one endpoint ends up
   * calling another.
   */
  public static String effectiveBaseUrl(Config resolved, ProviderStore store, String name) {
    if (resolved == null) {
      return null;
    }
    ProviderDefinition definition = store == null ? null : store.find(name).orElse(null);
    if (definition == null) {
      return resolved.baseUrl();
    }
    // "Stored" means this configuration really names an endpoint: resolved() fills the OpenAI
    // default for any name it does not recognise, and that filler is a placeholder rather than a
    // choice — taking it would point a custom provider at somebody else's address.
    boolean stored =
        resolved.baseUrl() != null
            && !resolved.baseUrl().isBlank()
            && !resolved.baseUrl().equals(Config.defaultBaseUrl(name));
    return usesStoredSettings(resolved, store, name) && stored
        ? resolved.baseUrl()
        : definition.baseUrl();
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
    // Whether the endpoint and the key in this configuration were entered for *this* provider. They
    // live in one flat file, so a value left behind by the provider used a minute ago is otherwise
    // indistinguishable from one meant for this provider — and using it sends this provider's traffic
    // to that address, with that provider's credential, which is a leak and a bill.
    boolean ownsSettings = resolved.settingsBelongTo(name);
    String apiKey = requireApiKey(resolved, environment, custom, name, ownsSettings);

    if (custom != null) {
      // The definition is where a custom provider's endpoint comes from: it is the only thing that
      // knows which address serves which provider. A stored base URL is honoured only when it was
      // entered for this provider (a --base-url flag, a CCJ_BASE_URL, or this very form).
      String baseUrl = effectiveBaseUrl(resolved, store, name);
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
      Config resolved,
      Map<String, String> env,
      ProviderDefinition custom,
      String name,
      boolean ownsSettings) {
    if (custom == null) {
      return requireApiKey(resolved, env, name);
    }
    // The variable the definition names is where its key lives. Otherwise the kind decides, and the
    // config's `apiKeyEnv` only counts when it is a deliberate choice: `resolved()` fills that field
    // with the default this provider's *name* would have produced, so a custom provider of the
    // Anthropic kind would otherwise be asked for OPENAI_API_KEY — and would send a globally
    // exported OpenAI key to a third-party relay.
    String configVariable = resolved.apiKeyEnv();
    String variable;
    if (custom.apiKeyEnv() != null && !custom.apiKeyEnv().isBlank()) {
      variable = custom.apiKeyEnv();
    } else if (configVariable != null
        && !configVariable.isBlank()
        && !configVariable.equals(Config.defaultKeyEnv(resolved.provider()))) {
      variable = configVariable;
    } else {
      variable = Config.defaultKeyEnv(custom.kind());
    }
    // A literal key is this provider's only when the configuration says so; an unowned one was
    // entered for somebody else, and handing it to this endpoint is the leak this rule prevents.
    if (ownsSettings) {
      String literal = resolved.apiKey();
      if (literal != null && !literal.isBlank()) {
        return literal.strip();
      }
    }
    String fromEnv = env.get(variable);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.strip();
    }
    if (looksLikeAKey(variable)) {
      // A very easy mistake to make, and the raw "no API key" message would not explain it. Checked
      // before anything that would name the variable, because this field holds a key rather than a
      // variable name and echoing it back would put a credential in a log.
      throw new IllegalArgumentException(
          "no API key for provider '"
              + name
              + "': the apiKeyEnv setting holds what looks like an API key itself ("
              + redact(variable)
              + ") — put the key in the API key field and the *name* of an environment variable here,"
              + " for example MY_RELAY_KEY");
    }
    if (resolved.apiKey() != null && !resolved.apiKey().isBlank()) {
      String owner =
          resolved.settingsFor() == null || resolved.settingsFor().isBlank()
              ? "another provider"
              : "provider '" + resolved.settingsFor() + "'";
      throw new IllegalArgumentException(
          "no API key for provider '"
              + name
              + "': the key stored in the config file was entered for "
              + owner
              + " and is not sent to '"
              + name
              + "' — paste it again with '"
              + name
              + "' selected, or set "
              + variable
              + " in the environment");
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

  private static boolean looksLikeAKey(String value) {
    if (value == null) {
      return false;
    }
    String trimmed = value.strip();
    return trimmed.startsWith("sk-") || (!trimmed.equals(trimmed.toUpperCase()) && trimmed.length() > 32);
  }

  private static String redact(String value) {
    String trimmed = value == null ? "" : value.strip();
    return trimmed.length() <= 6 ? "***" : trimmed.substring(0, 3) + "***" + trimmed.substring(trimmed.length() - 3);
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
