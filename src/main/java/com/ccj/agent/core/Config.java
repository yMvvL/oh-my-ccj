package com.ccj.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Effective configuration after layering file, environment and command line.
 *
 * <p>Every field is nullable and means "not specified here". {@link #merge} layers sources from
 * lowest to highest precedence and {@link #resolved} fills in the provider-dependent defaults, so
 * the rest of the code only ever sees a complete configuration.
 *
 * <p>Precedence, lowest first: built-in defaults, {@code ~/.oh-my-ccj/config.json}, {@code CCJ_*}
 * environment variables, command-line flags.
 *
 * <p>{@code baseUrl}, {@code apiKey} and {@code apiKeyEnv} are <em>provider-scoped</em>: they mean
 * something only next to the provider they were entered for, which is what {@code settingsFor}
 * records. See {@link #settingsBelongTo}.
 */
public record Config(
    String provider,
    String model,
    String baseUrl,
    String apiKey,
    String apiKeyEnv,
    Double temperature,
    Integer maxTokens,
    Boolean autoApprove,
    Integer outputLimitBytes,
    String systemPrompt,
    String language,
    String reasoning,
    Integer maxContextTokens,
    String settingsFor,
    Map<String, ProviderSettings> remembered,
    VisionConfig vision) {

  /**
   * The fields as they existed before context budgeting, so a caller that does not care about it does
   * not have to name it.
   */
  public Config(
      String provider,
      String model,
      String baseUrl,
      String apiKey,
      String apiKeyEnv,
      Double temperature,
      Integer maxTokens,
      Boolean autoApprove,
      Integer outputLimitBytes,
      String systemPrompt,
      String reasoning) {
    this(
        provider,
        model,
        baseUrl,
        apiKey,
        apiKeyEnv,
        temperature,
        maxTokens,
        autoApprove,
        outputLimitBytes,
        systemPrompt,
        null,
        reasoning,
        null,
        null,
        Map.of(),
        null);
  }

  /**
   * The fields as they existed before credentials were scoped to a provider, so a caller that does
   * not care about the scope does not have to name it.
   */
  public Config(
      String provider,
      String model,
      String baseUrl,
      String apiKey,
      String apiKeyEnv,
      Double temperature,
      Integer maxTokens,
      Boolean autoApprove,
      Integer outputLimitBytes,
      String systemPrompt,
      String reasoning,
      Integer maxContextTokens) {
    this(
        provider,
        model,
        baseUrl,
        apiKey,
        apiKeyEnv,
        temperature,
        maxTokens,
        autoApprove,
        outputLimitBytes,
        systemPrompt,
        null,
        reasoning,
        maxContextTokens,
        null,
        Map.of(),
        null);
  }

  /**
   * The map of remembered pairs is normalised here: keys are lower-cased because provider names are
   * matched case-insensitively everywhere else, and an entry with nothing in it is dropped rather
   * than kept as an empty promise. The vision block is normalised the same way: one that names
   * nothing is the absence of a configuration, and two representations of "off" is one too many.
   */
  public Config {
    Map<String, ProviderSettings> clean = new LinkedHashMap<>();
    if (remembered != null) {
      remembered.forEach(
          (name, settings) -> {
            if (name != null && !name.isBlank() && settings != null && !settings.isEmpty()) {
              clean.put(name.strip().toLowerCase(), settings);
            }
          });
    }
    remembered = Map.copyOf(clean);
    if (vision != null && vision.isEmpty()) {
      vision = null;
    }
  }

  public static final String DEFAULT_PROVIDER = "openai";
  public static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
  public static final String ANTHROPIC_BASE_URL = "https://api.anthropic.com";
  public static final String OPENAI_KEY_ENV = "OPENAI_API_KEY";
  public static final String ANTHROPIC_KEY_ENV = "ANTHROPIC_API_KEY";
  public static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
  public static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-5";
  public static final int DEFAULT_OUTPUT_LIMIT_BYTES = 32 * 1024;

  /** Reasoning effort tiers, in ascending order; null means "say nothing, let the model decide". */
  public static final List<String> REASONING_LEVELS = List.of("low", "high", "max");

  public static Config empty() {
    return new Config(null, null, null, null, null, null, null, null, null, null, null, null);
  }

  /** Returns this configuration with every field {@code higher} specifies taking over. */
  public Config merge(Config higher) {
    if (higher == null) {
      return this;
    }
    return new Config(
        pick(provider, higher.provider),
        pick(model, higher.model),
        pick(baseUrl, higher.baseUrl),
        pick(apiKey, higher.apiKey),
        pick(apiKeyEnv, higher.apiKeyEnv),
        pick(temperature, higher.temperature),
        pick(maxTokens, higher.maxTokens),
        pick(autoApprove, higher.autoApprove),
        pick(outputLimitBytes, higher.outputLimitBytes),
        pick(systemPrompt, higher.systemPrompt),
        pick(language, higher.language),
        pick(reasoning, higher.reasoning),
        pick(maxContextTokens, higher.maxContextTokens),
        pick(settingsFor, higher.settingsFor),
        mergeRemembered(remembered, higher.remembered),
        mergeVision(vision, higher.vision));
  }

  /**
   * The vision block from both layers, field by field.
   *
   * <p>Field by field rather than whole-block, for the same reason every other field is picked
   * individually: a layer that names only the model — {@code CCJ_VISION_MODEL}, a
   * {@code --vision-model} flag — must not erase the endpoint underneath it. Replacing the block
   * wholesale would turn "use this model instead" into "forget the endpoint and the key".
   */
  private static VisionConfig mergeVision(VisionConfig lower, VisionConfig higher) {
    if (higher == null || higher.isEmpty()) {
      return lower;
    }
    if (lower == null || lower.isEmpty()) {
      return higher;
    }
    return new VisionConfig(
        pick(lower.baseUrl(), higher.baseUrl()),
        pick(lower.apiKey(), higher.apiKey()),
        pick(lower.apiKeyEnv(), higher.apiKeyEnv()),
        pick(lower.model(), higher.model()),
        pick(lower.maxTokens(), higher.maxTokens()));
  }

  /**
   * Remembered pairs from both layers, the higher one winning per provider.
   *
   * <p>Layered like every other field, but a map cannot be "picked": a layer that says nothing about
   * a provider must not erase what a lower layer knows about it. That is the whole point of the map —
   * a key entered for the provider you are not using has to survive the saves that happen while you
   * are using another one.
   */
  private static Map<String, ProviderSettings> mergeRemembered(
      Map<String, ProviderSettings> lower, Map<String, ProviderSettings> higher) {
    if (higher == null || higher.isEmpty()) {
      return lower;
    }
    if (lower == null || lower.isEmpty()) {
      return higher;
    }
    Map<String, ProviderSettings> merged = new LinkedHashMap<>(lower);
    merged.putAll(higher);
    return merged;
  }

  /**
   * True when the endpoint and credential fields in this configuration were entered for {@code
   * provider}.
   *
   * <p>Those three fields are provider-scoped but live in one flat file, so without this mark a base
   * URL and a key left behind by the provider used a minute ago look exactly like ones meant for the
   * provider active now. Reading "unknown" as "yes, this key is for that endpoint" is how one
   * vendor's traffic — and one vendor's credential — ends up at another vendor's address. A null
   * mark therefore means <em>not this provider's</em>, never "assume yes".
   */
  public boolean settingsBelongTo(String provider) {
    if (settingsFor == null || settingsFor.isBlank() || provider == null || provider.isBlank()) {
      return false;
    }
    return settingsFor.strip().equalsIgnoreCase(provider.strip());
  }

  /** True when this configuration sets any of the provider-scoped fields. */
  public boolean setsProviderSettings() {
    return baseUrl != null || apiKey != null || apiKeyEnv != null;
  }

  /**
   * True when the flat pair carries a mark naming a provider other than {@code provider}.
   *
   * <p>{@link #settingsBelongTo} answers "may this pair be used here?", which is false both for
   * another provider's pair and for one nobody claimed. This answers the narrower question a switch
   * has to ask: the pair is deliberately somebody else's, so it is dropped rather than inherited.
   */
  private boolean markedForAnotherProvider(String provider) {
    return settingsFor != null
        && !settingsFor.isBlank()
        && provider != null
        && !provider.isBlank()
        && !settingsFor.strip().equalsIgnoreCase(provider.strip());
  }

  /** The same configuration with a different thinking language; null means "let the model decide". */
  public Config language(String value) {
    return new Config(
        provider,
        model,
        baseUrl,
        apiKey,
        apiKeyEnv,
        temperature,
        maxTokens,
        autoApprove,
        outputLimitBytes,
        systemPrompt,
        value,
        reasoning,
        maxContextTokens,
        settingsFor,
        remembered,
        vision);
  }

  /**
   * The pair this configuration holds in its flat fields, or null when it holds none.
   *
   * <p>An endpoint or a key variable that is only this provider's default is left out: the file
   * records what was chosen, and repeating a default that {@link #resolved()} re-derives anyway is
   * how a meaningless address gets written down and later mistaken for a deliberate one.
   */
  public ProviderSettings settings() {
    String name = provider == null ? DEFAULT_PROVIDER : provider.strip().toLowerCase();
    String keptBaseUrl = defaultBaseUrl(name).equals(baseUrl) ? null : baseUrl;
    String keptKeyEnv = defaultKeyEnv(name).equals(apiKeyEnv) ? null : apiKeyEnv;
    ProviderSettings settings = new ProviderSettings(keptBaseUrl, apiKey, keptKeyEnv);
    return settings.isEmpty() ? null : settings;
  }

  /**
   * This configuration without the provider-scoped fields, which is what a provider change has to
   * start from: the new provider inherits neither the old endpoint nor the old credential.
   */
  public Config forgetProviderSettings() {
    return new Config(
        provider, model, null, null, null, temperature, maxTokens, autoApprove, outputLimitBytes,
        systemPrompt, language, reasoning, maxContextTokens, null, remembered, vision);
  }

  /** The same configuration, with its endpoint and credential fields marked as {@code provider}'s. */
  public Config scopedTo(String provider) {
    return new Config(
        this.provider,
        model,
        baseUrl,
        apiKey,
        apiKeyEnv,
        temperature,
        maxTokens,
        autoApprove,
        outputLimitBytes,
        systemPrompt,
        language,
        reasoning,
        maxContextTokens,
        provider,
        remembered,
        vision);
  }

  /** The pair remembered for {@code provider}, if any. */
  public ProviderSettings rememberedFor(String provider) {
    return provider == null ? null : remembered.get(provider.strip().toLowerCase());
  }

  /** The names of the providers whose endpoint and key are remembered here. */
  public java.util.Set<String> rememberedNames() {
    return remembered.keySet();
  }

  /**
   * Keeps the pair this configuration holds under {@code provider}, so switching back restores it.
   *
   * <p>Only the pair that belongs to that provider is worth keeping: anything else was entered
   * somewhere else, and filing it under this name is the mistake this map exists to prevent.
   */
  public Config remembering(String provider, ProviderSettings settings) {
    if (provider == null || provider.isBlank() || settings == null || settings.isEmpty()) {
      return this;
    }
    Map<String, ProviderSettings> next = new LinkedHashMap<>(remembered);
    next.put(provider.strip().toLowerCase(), settings);
    return withRemembered(next);
  }

  /** The same configuration, named after {@code other}'s provider when it does not name one itself. */
  public Config namedBy(Config other) {
    if (provider != null || other == null || other.provider() == null) {
      return this;
    }
    return new Config(
        other.provider(),
        model,
        baseUrl,
        apiKey,
        apiKeyEnv,
        temperature,
        maxTokens,
        autoApprove,
        outputLimitBytes,
        systemPrompt,
        language,
        reasoning,
        maxContextTokens,
        settingsFor,
        remembered,
        vision);
  }

  /** Forgets what is remembered for {@code provider}. */
  public Config forgetting(String provider) {
    if (provider == null || !remembered.containsKey(provider.strip().toLowerCase())) {
      return this;
    }
    Map<String, ProviderSettings> next = new LinkedHashMap<>(remembered);
    next.remove(provider.strip().toLowerCase());
    return withRemembered(next);
  }

  /**
   * Takes this provider's remembered pair into the flat fields, when the ones there are not its own.
   *
   * <p>The map holds what the providers you are not using brought with them; the flat fields hold the
   * pair in effect now. Moving the entry out of the map keeps one copy of it: the active pair lives
   * in the flat fields, everyone else's in the map.
   *
   * <p>When nothing was ever entered for the provider being switched to and the pair in the flat
   * fields is marked as another provider's, the pair is dropped rather than inherited: it is the
   * address and the credential of the provider used a minute ago, and a run that just named a
   * different provider must not send one vendor's key to the other's endpoint. A file with no mark
   * at all is a hand-written one, and there the pair is left alone — "no mark" is not "someone
   * else's", only "nobody recorded who entered it".
   */
  public Config recalling(String provider) {
    if (provider == null || provider.isBlank() || settingsBelongTo(provider)) {
      return this;
    }
    ProviderSettings settings = rememberedFor(provider);
    if (settings == null) {
      return markedForAnotherProvider(provider) ? forgetProviderSettings().resolved() : this;
    }
    Map<String, ProviderSettings> next = new LinkedHashMap<>(remembered);
    next.remove(provider.strip().toLowerCase());
    // Resolved afterwards, so a pair that only names a key still gets this provider's own endpoint
    // and key variable: what is being replaced is another provider's, and carrying its endpoint over
    // is the mistake the mark exists to prevent.
    return new Config(
            this.provider,
            model,
            settings.baseUrl(),
            settings.apiKey(),
            settings.apiKeyEnv(),
            temperature,
            maxTokens,
            autoApprove,
            outputLimitBytes,
            systemPrompt,
            language,
            reasoning,
            maxContextTokens,
            provider,
            next,
            vision)
        .resolved();
  }

  /**
   * The configuration after a settings change: what is being left is remembered, what is being
   * switched to is recalled, and a change that names an endpoint or a key writes it as the active
   * provider's.
   *
   * <p>This is the rule that keeps a stored pair and the provider using it in step. Drop either half
   * and a session ends up calling one vendor's address with another vendor's — or with nobody's —
   * credential, which is what the map and {@code settingsFor} are for.
   */
  public Config changedBy(Config changes) {
    if (changes == null) {
      return this;
    }
    String target = changes.provider() != null ? changes.provider() : provider;
    boolean switching =
        target != null && (provider == null || !target.strip().equalsIgnoreCase(provider.strip()));
    Config base = this;
    if (switching && settingsBelongTo(provider)) {
      // Leaving: the pair in effect belongs to the provider being left, so it stays available under
      // its name instead of being thrown away for being in the way. Whatever this request carries is
      // for the provider it is switching *to* — that is what naming a provider means.
      base = base.remembering(provider, base.settings());
    }
    boolean replaces = changes.setsProviderSettings();
    if (switching || (replaces && !base.settingsBelongTo(target))) {
      base = base.forgetProviderSettings();
    }
    // Marked before `resolved()` fills in the provider-dependent defaults: a defaulted endpoint is
    // not something the user entered for this provider, and marking it would tell `recalling` that
    // the active pair is already this provider's — which is how a remembered key stops being used.
    Config merged = base.merge(changes);
    if ((switching || replaces) && merged.setsProviderSettings()) {
      merged = merged.scopedTo(merged.provider());
    }
    merged = merged.resolved();
    if (changes.apiKey() != null && changes.apiKey().isBlank()) {
      // Clearing a key means clearing it: the flat field goes, and a remembered copy would otherwise
      // come back on the next switch. A key the user asked to forget is not a key to keep.
      merged = merged.withApiKey(null).forgetting(merged.provider());
    }
    return merged.recalling(merged.provider());
  }

  private Config withApiKey(String key) {
    return new Config(
        provider, model, baseUrl, key, apiKeyEnv, temperature, maxTokens, autoApprove,
        outputLimitBytes, systemPrompt, language, reasoning, maxContextTokens, settingsFor,
        remembered, vision);
  }

  private Config withRemembered(Map<String, ProviderSettings> next) {
    return new Config(
        provider, model, baseUrl, apiKey, apiKeyEnv, temperature, maxTokens, autoApprove,
        outputLimitBytes, systemPrompt, language, reasoning, maxContextTokens, settingsFor, next,
        vision);
  }

  /** Fills in defaults that depend on the chosen provider. */
  public Config resolved() {
    String resolvedProvider = provider == null || provider.isBlank() ? DEFAULT_PROVIDER : provider.strip().toLowerCase();
    String resolvedBaseUrl =
        baseUrl != null && !baseUrl.isBlank()
            ? ProviderDefinition.normaliseBaseUrl(stripTrailingSlash(baseUrl))
            : defaultBaseUrl(resolvedProvider);
    String resolvedKeyEnv =
        apiKeyEnv != null && !apiKeyEnv.isBlank() ? apiKeyEnv : defaultKeyEnv(resolvedProvider);
    String resolvedModel = model != null && !model.isBlank() ? model : defaultModelFor(resolvedProvider, resolvedBaseUrl);
    return new Config(
        resolvedProvider,
        resolvedModel,
        resolvedBaseUrl,
        apiKey,
        resolvedKeyEnv,
        temperature,
        maxTokens,
        autoApprove != null && autoApprove,
        outputLimitBytes == null ? DEFAULT_OUTPUT_LIMIT_BYTES : outputLimitBytes,
        systemPrompt,
        normalizeLanguage(language),
        normaliseReasoning(reasoning),
        maxContextTokens,
        settingsFor,
        remembered,
        vision);
  }

  /**
   * The language the prompt asks for. Blank and {@code auto} both mean "say nothing"; anything else is
   * kept as written, because the list of languages is the prompt's to offer and a language this build
   * has never heard of is still a name a model can follow.
   */
  public static String normalizeLanguage(String language) {
    if (language == null || language.isBlank() || Prompts.AUTO.equalsIgnoreCase(language.strip())) {
      return null;
    }
    return language.strip();
  }

  /**
   * The effort tier, lower-cased and checked. An unknown value is a typo worth reporting rather than
   * a setting to silently ignore: it changes what the model spends tokens on.
   */
  public static String normaliseReasoning(String reasoning) {
    if (reasoning == null || reasoning.isBlank()) {
      return null;
    }
    String value = reasoning.strip().toLowerCase();
    if (!REASONING_LEVELS.contains(value)) {
      throw new IllegalArgumentException(
          "unknown reasoning level '" + reasoning + "'; use " + String.join(", ", REASONING_LEVELS));
    }
    return value;
  }

  public static String defaultBaseUrl(String provider) {
    return "anthropic".equals(provider) ? ANTHROPIC_BASE_URL : OPENAI_BASE_URL;
  }

  public static String defaultKeyEnv(String provider) {
    return "anthropic".equals(provider) ? ANTHROPIC_KEY_ENV : OPENAI_KEY_ENV;
  }

  /** The model to use when none is configured, or null when the provider has no obvious one. */
  public static String defaultModel(String provider) {
    if (provider == null) {
      return null;
    }
    return switch (provider.strip().toLowerCase()) {
      case "openai" -> DEFAULT_OPENAI_MODEL;
      case "anthropic" -> DEFAULT_ANTHROPIC_MODEL;
      default -> null;
    };
  }

  /**
   * A default model only makes sense against the provider's own endpoint. Relays name models
   * freely, so guessing there would turn a clear configuration error into an obscure 404.
   */
  private static String defaultModelFor(String provider, String baseUrl) {
    return defaultBaseUrl(provider).equals(baseUrl) ? defaultModel(provider) : null;
  }

  /** The API key to send, or null when neither the config nor the environment provides one. */
  public String resolvedApiKey(Map<String, String> env) {
    if (apiKey != null && !apiKey.isBlank()) {
      return apiKey.strip();
    }
    if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
      return null;
    }
    String fromEnv = env.get(apiKeyEnv);
    return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.strip();
  }

  /** Reads {@code config.json}; a missing file yields {@link #empty()}. */
  public static Config fromFile(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return empty();
    }
    JsonNode root;
    try {
      root = Json.parse(Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("invalid config file " + file + ": " + e.getMessage(), e);
    }
    if (!root.isObject()) {
      throw new IllegalArgumentException("invalid config file " + file + ": expected a JSON object");
    }
    return new Config(
        text(root, "provider"),
        text(root, "model"),
        text(root, "baseUrl"),
        text(root, "apiKey"),
        text(root, "apiKeyEnv"),
        number(root, "temperature"),
        integer(root, "maxTokens"),
        bool(root, "autoApprove"),
        integer(root, "outputLimitBytes"),
        text(root, "systemPrompt"),
        text(root, "language"),
        text(root, "reasoning"),
        integer(root, "maxContextTokens"),
        text(root, "settingsFor"),
        readRemembered(root),
        readVision(root));
  }

  /**
   * The {@code vision} block: the endpoint, credential, model and reply ceiling of the model that
   * describes pictures. Absent, null or empty all mean the feature is off, and a block that is not an
   * object is a typo worth reporting rather than a setting to ignore silently.
   */
  private static VisionConfig readVision(JsonNode root) {
    JsonNode node = root.get("vision");
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException("config field 'vision' must be an object");
    }
    VisionConfig vision =
        new VisionConfig(
            text(node, "baseUrl"),
            text(node, "apiKey"),
            text(node, "apiKeyEnv"),
            text(node, "model"),
            integer(node, "maxTokens"));
    return vision.isEmpty() ? null : vision;
  }

  /** The {@code remembered} map: provider name to the endpoint and credential entered for it. */
  private static Map<String, ProviderSettings> readRemembered(JsonNode root) {
    JsonNode entries = root.get("remembered");
    if (entries == null || !entries.isObject()) {
      return Map.of();
    }
    Map<String, ProviderSettings> out = new LinkedHashMap<>();
    entries
        .fields()
        .forEachRemaining(
            entry -> {
              JsonNode body = entry.getValue();
              if (body == null || !body.isObject()) {
                return;
              }
              ProviderSettings settings =
                  new ProviderSettings(
                      text(body, "baseUrl"), text(body, "apiKey"), text(body, "apiKeyEnv"));
              if (!settings.isEmpty()) {
                out.put(entry.getKey().strip().toLowerCase(), settings);
              }
            });
    return out;
  }

  /** Reads {@code CCJ_*} variables. */
  public static Config fromEnv(Map<String, String> env) {
    return new Config(
        env.get("CCJ_PROVIDER"),
        env.get("CCJ_MODEL"),
        env.get("CCJ_BASE_URL"),
        env.get("CCJ_API_KEY"),
        env.get("CCJ_API_KEY_ENV"),
        parseDouble(env.get("CCJ_TEMPERATURE")),
        parseInteger(env.get("CCJ_MAX_TOKENS")),
        parseBoolean(env.get("CCJ_AUTO_APPROVE")),
        parseInteger(env.get("CCJ_OUTPUT_LIMIT_BYTES")),
        env.get("CCJ_SYSTEM_PROMPT"),
        env.get("CCJ_LANGUAGE"),
        env.get("CCJ_REASONING"),
        parseInteger(env.get("CCJ_MAX_CONTEXT_TOKENS")),
        null,
        Map.of(),
        readEnvVision(env));
  }

  /**
   * The {@code CCJ_VISION_*} variables, as one block or null.
   *
   * <p>Named after the block they fill rather than after the main provider's variables, because the
   * model they configure is a different one: {@code CCJ_API_KEY} must not double as the key for a
   * picture-describer, which is the whole reason vision is configured separately.
   */
  private static VisionConfig readEnvVision(Map<String, String> env) {
    VisionConfig vision =
        new VisionConfig(
            env.get("CCJ_VISION_BASE_URL"),
            env.get("CCJ_VISION_API_KEY"),
            env.get("CCJ_VISION_API_KEY_ENV"),
            env.get("CCJ_VISION_MODEL"),
            parseInteger(env.get("CCJ_VISION_MAX_TOKENS")));
    return vision.isEmpty() ? null : vision;
  }

  /**
   * Same as {@link #merge} with file, then environment, then the caller's overrides.
   *
   * <p>A flag or a {@code CCJ_*} variable naming an endpoint or a key is an explicit act for whatever
   * provider this run ends up using, so it is marked as that provider's and wins outright. A value
   * read from the file is not: it may have been written for a provider the run is not using, so when
   * it does not belong to the active provider the pair remembered for that provider is used instead.
   */
  public static Config layered(Path configFile, Map<String, String> env, Config overrides) {
    Config fromEnvironment = fromEnv(env);
    Config merged =
        empty().merge(fromFile(configFile)).merge(fromEnvironment).merge(overrides).resolved();
    boolean namedHere =
        (overrides != null && overrides.setsProviderSettings())
            || fromEnvironment.setsProviderSettings();
    if (namedHere) {
      return merged.scopedTo(merged.provider());
    }
    return merged.recalling(merged.provider());
  }

  /**
   * Writes the settings the UI manages into {@code file}, keeping every other key that is already
   * there — a hand-written system prompt or output cap must survive a visit to the settings form.
   *
   * <p>Fields that are null are removed, and a blank {@code apiKey} is removed rather than stored as
   * an empty string. The file is created owner-only because it may hold a key.
   *
   * <p>{@code settingsFor} travels with them: it is what says whose {@code baseUrl} and {@code
   * apiKey} these are, so removing the fields removes the mark too. The {@code remembered} map holds
   * the same pair for every other provider you have entered one for, which is what lets a provider be
   * switched back to without pasting its key again.
   */
  public static void writeInto(Path file, Config managed) {
    ObjectNode root;
    if (Files.isRegularFile(file)) {
      JsonNode existing = readTree(file);
      if (!existing.isObject()) {
        throw new IllegalArgumentException("config file " + file + " must contain a JSON object");
      }
      root = (ObjectNode) existing;
    } else {
      root = Json.object();
    }

    putText(root, "provider", managed.provider());
    putText(root, "model", managed.model());
    // Same rule as Config.settings(): a value that is only the provider's default is not written, so
    // the file never accumulates endpoints and key variables nobody chose.
    putText(root, "baseUrl", chosenBaseUrl(managed));
    putText(root, "apiKey", managed.apiKey() == null || managed.apiKey().isBlank() ? null : managed.apiKey());
    putText(root, "apiKeyEnv", chosenKeyEnv(managed.provider(), managed.apiKeyEnv()));
    // The mark means "these fields were entered for that provider": with no fields there is nothing
    // for it to be about, and a stale name would only invite the next reader to file them wrongly.
    putText(root, "settingsFor", managed.setsProviderSettings() ? managed.settingsFor() : null);
    writeRemembered(root, managed.remembered());
    writeVision(root, managed.vision());
    // The step ceiling is gone, and a key ccj no longer reads would advertise a
    // setting that does nothing, so an old file is cleaned up as it is rewritten.
    root.remove("maxSteps");
    putNumber(root, "temperature", managed.temperature());
    putNumber(root, "maxTokens", managed.maxTokens());
    putText(root, "language", managed.language());
    putText(root, "reasoning", managed.reasoning());
    putNumber(root, "maxContextTokens", managed.maxContextTokens());

    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(
          file,
          Json.writePretty(root) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      restrictToOwner(file);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }

  /** Writes the remembered map, dropping nothing an existing file holds for other providers. */
  private static void writeRemembered(ObjectNode root, Map<String, ProviderSettings> remembered) {
    if (remembered == null || remembered.isEmpty()) {
      root.remove("remembered");
      return;
    }
    ObjectNode entries = root.putObject("remembered");
    remembered.forEach(
        (name, settings) -> {
          ObjectNode body = entries.putObject(name);
          putText(body, "baseUrl", chosenBaseUrl(name, settings.baseUrl()));
          putText(body, "apiKey", settings.apiKey());
          putText(body, "apiKeyEnv", chosenKeyEnv(name, settings.apiKeyEnv()));
        });
  }

  /**
   * Writes the vision block as it stands, or removes it when there is none.
   *
   * <p>Unlike the provider fields there is no default to compare against: the endpoint is whatever
   * the user named, so every field that is set is recorded and nothing is left out for looking
   * ordinary. Fields are written individually, because a block that names only a model is a real
   * configuration — of the endpoint it inherits from the file.
   */
  private static void writeVision(ObjectNode root, VisionConfig vision) {
    if (vision == null || vision.isEmpty()) {
      root.remove("vision");
      return;
    }
    ObjectNode block = root.putObject("vision");
    putText(block, "baseUrl", vision.baseUrl());
    putText(block, "apiKey", vision.apiKey());
    putText(block, "apiKeyEnv", vision.apiKeyEnv());
    putText(block, "model", vision.model());
    putNumber(block, "maxTokens", vision.maxTokens());
  }

  private static String chosenBaseUrl(Config config) {
    return chosenBaseUrl(config.provider(), config.baseUrl());
  }

  private static String chosenBaseUrl(String provider, String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return null;
    }
    String name = provider == null ? DEFAULT_PROVIDER : provider.strip().toLowerCase();
    return defaultBaseUrl(name).equals(baseUrl) ? null : baseUrl;
  }

  private static String chosenKeyEnv(String provider, String apiKeyEnv) {
    if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
      return null;
    }
    String name = provider == null ? DEFAULT_PROVIDER : provider.strip().toLowerCase();
    return defaultKeyEnv(name).equals(apiKeyEnv) ? null : apiKeyEnv;
  }

  private static JsonNode readTree(Path file) {    try {
      return Json.parse(Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  /** Best effort: non-POSIX filesystems keep their default rather than failing the save. */
  private static void restrictToOwner(Path file) {
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException | IOException ignored) {
      // Nothing to do; the save itself already succeeded.
    }
  }

  private static void putText(ObjectNode root, String field, String value) {
    if (value == null || value.isBlank()) {
      root.remove(field);
    } else {
      root.put(field, value);
    }
  }

  private static void putNumber(ObjectNode root, String field, Number value) {
    if (value == null) {
      root.remove(field);
    } else if (value instanceof Integer integer) {
      root.put(field, integer.intValue());
    } else {
      root.put(field, value.doubleValue());
    }
  }

  /** Redacted view, safe to print. */
  public Map<String, String> describe(Map<String, String> env) {
    Map<String, String> out = new LinkedHashMap<>();
    out.put("provider", provider);
    out.put("model", model == null ? "(unset)" : model);
    out.put("baseUrl", baseUrl);
    out.put("apiKey", resolvedApiKey(env) == null ? "(unset)" : "***" + tail(resolvedApiKey(env)));
    // Which provider the endpoint and the key above belong to; "(unknown)" is a file written by
    // hand or by an older build, and a custom provider will not use an unowned pair.
    out.put("settingsFor", settingsFor == null || settingsFor.isBlank() ? "(unknown)" : settingsFor);
    out.put("autoApprove", String.valueOf(autoApprove));
    out.put("outputLimitBytes", String.valueOf(outputLimitBytes));
    out.put("reasoning", reasoning == null ? "(provider default)" : reasoning);
    out.put(
        "maxContextTokens",
        maxContextTokens == null ? "(no budget)" : String.valueOf(maxContextTokens));
    out.put("vision", describeVision(env));
    return out;
  }

  /**
   * The vision model as one line: off, or where it is, which model, and whether a key was found —
   * never the key itself, which is the rule for every key this class reports.
   */
  private String describeVision(Map<String, String> env) {
    if (vision == null || !vision.isConfigured()) {
      return "(off)";
    }
    String key = vision.resolvedApiKey(env);
    return vision.baseUrl()
        + " · "
        + vision.model()
        + " · "
        + (key == null ? "(no key)" : "***" + tail(key));
  }

  private static String tail(String key) {
    return key.length() <= 4 ? "" : key.substring(key.length() - 4);
  }

  private static String stripTrailingSlash(String url) {
    String stripped = url.strip();
    return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
  }

  private static String text(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException("config field '" + field + "' must be a string");
    }
    return node.asText();
  }

  private static Integer integer(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isInt()) {
      throw new IllegalArgumentException("config field '" + field + "' must be an integer");
    }
    return node.asInt();
  }

  private static Double number(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isNumber()) {
      throw new IllegalArgumentException("config field '" + field + "' must be a number");
    }
    return node.asDouble();
  }

  private static Boolean bool(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isBoolean()) {
      throw new IllegalArgumentException("config field '" + field + "' must be a boolean");
    }
    return node.asBoolean();
  }

  private static Integer parseInteger(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(raw.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("not an integer: " + raw, e);
    }
  }

  private static Double parseDouble(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Double.valueOf(raw.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("not a number: " + raw, e);
    }
  }

  private static Boolean parseBoolean(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.strip().toLowerCase();
    return switch (value) {
      case "1", "true", "yes", "on" -> true;
      case "0", "false", "no", "off" -> false;
      default -> throw new IllegalArgumentException("not a boolean: " + raw);
    };
  }

  private static <T> T pick(T lower, T higher) {
    return higher != null ? higher : lower;
  }
}
