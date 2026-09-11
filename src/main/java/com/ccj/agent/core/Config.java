package com.ccj.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
 */
public record Config(
    String provider,
    String model,
    String baseUrl,
    String apiKey,
    String apiKeyEnv,
    Double temperature,
    Integer maxTokens,
    Integer maxSteps,
    Boolean autoApprove,
    Integer outputLimitBytes,
    String systemPrompt) {

  public static final String DEFAULT_PROVIDER = "openai";
  public static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
  public static final String ANTHROPIC_BASE_URL = "https://api.anthropic.com";
  public static final String OPENAI_KEY_ENV = "OPENAI_API_KEY";
  public static final String ANTHROPIC_KEY_ENV = "ANTHROPIC_API_KEY";
  public static final int DEFAULT_MAX_STEPS = 25;
  public static final int DEFAULT_OUTPUT_LIMIT_BYTES = 32 * 1024;

  public static Config empty() {
    return new Config(null, null, null, null, null, null, null, null, null, null, null);
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
        pick(maxSteps, higher.maxSteps),
        pick(autoApprove, higher.autoApprove),
        pick(outputLimitBytes, higher.outputLimitBytes),
        pick(systemPrompt, higher.systemPrompt));
  }

  /** Fills in defaults that depend on the chosen provider. */
  public Config resolved() {
    String resolvedProvider = provider == null || provider.isBlank() ? DEFAULT_PROVIDER : provider.strip().toLowerCase();
    String resolvedBaseUrl =
        baseUrl != null && !baseUrl.isBlank() ? stripTrailingSlash(baseUrl) : defaultBaseUrl(resolvedProvider);
    String resolvedKeyEnv =
        apiKeyEnv != null && !apiKeyEnv.isBlank() ? apiKeyEnv : defaultKeyEnv(resolvedProvider);
    return new Config(
        resolvedProvider,
        model,
        resolvedBaseUrl,
        apiKey,
        resolvedKeyEnv,
        temperature,
        maxTokens,
        maxSteps == null ? DEFAULT_MAX_STEPS : maxSteps,
        autoApprove != null && autoApprove,
        outputLimitBytes == null ? DEFAULT_OUTPUT_LIMIT_BYTES : outputLimitBytes,
        systemPrompt);
  }

  public static String defaultBaseUrl(String provider) {
    return "anthropic".equals(provider) ? ANTHROPIC_BASE_URL : OPENAI_BASE_URL;
  }

  public static String defaultKeyEnv(String provider) {
    return "anthropic".equals(provider) ? ANTHROPIC_KEY_ENV : OPENAI_KEY_ENV;
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
        integer(root, "maxSteps"),
        bool(root, "autoApprove"),
        integer(root, "outputLimitBytes"),
        text(root, "systemPrompt"));
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
        parseInteger(env.get("CCJ_MAX_STEPS")),
        parseBoolean(env.get("CCJ_AUTO_APPROVE")),
        parseInteger(env.get("CCJ_OUTPUT_LIMIT_BYTES")),
        env.get("CCJ_SYSTEM_PROMPT"));
  }

  /** Same as {@link #merge} with file, then environment, then the caller's overrides. */
  public static Config layered(Path configFile, Map<String, String> env, Config overrides) {
    return empty().merge(fromFile(configFile)).merge(fromEnv(env)).merge(overrides).resolved();
  }

  /** Redacted view, safe to print. */
  public Map<String, String> describe(Map<String, String> env) {
    Map<String, String> out = new LinkedHashMap<>();
    out.put("provider", provider);
    out.put("model", model == null ? "(provider default)" : model);
    out.put("baseUrl", baseUrl);
    out.put("apiKey", resolvedApiKey(env) == null ? "(unset)" : "***" + tail(resolvedApiKey(env)));
    out.put("maxSteps", String.valueOf(maxSteps));
    out.put("autoApprove", String.valueOf(autoApprove));
    out.put("outputLimitBytes", String.valueOf(outputLimitBytes));
    return out;
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
