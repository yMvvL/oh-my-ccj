package com.ccj.agent.provider;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.ProviderDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The providers the user defined, kept in {@code <home>/providers.json}.
 *
 * <p>A separate file from {@code config.json} because they answer different questions: the config
 * says *which* provider is active and holds a key, this says *what* providers exist. Adding one must
 * not disturb the model you are currently using, and a definition survives switching away from it.
 *
 * <p>Built-in providers are not stored here — they are code — so a deleted entry can never take
 * {@code openai} or {@code anthropic} with it.
 */
public final class ProviderStore {

  private static final String FILE_NAME = "providers.json";

  private final Path home;
  private final Path file;
  private final Map<String, ProviderDefinition> definitions = new LinkedHashMap<>();

  private ProviderStore(Path home) {
    this.home = home.toAbsolutePath().normalize();
    this.file = this.home.resolve(FILE_NAME);
  }

  public static ProviderStore open(Path home) {
    ProviderStore store = new ProviderStore(home);
    if (Files.isRegularFile(store.file)) {
      store.load();
    }
    return store;
  }

  public Path file() {
    return file;
  }

  public synchronized List<ProviderDefinition> list() {
    return List.copyOf(definitions.values());
  }

  public synchronized Optional<ProviderDefinition> find(String name) {
    return name == null
        ? Optional.empty()
        : Optional.ofNullable(definitions.get(name.strip().toLowerCase()));
  }

  /** Adds or replaces a definition. */
  public synchronized ProviderDefinition save(ProviderDefinition definition) {
    ProviderDefinition valid = definition.requireValid();
    definitions.put(valid.name().toLowerCase(), valid);
    write();
    return valid;
  }

  public synchronized void remove(String name) {
    String key = name == null ? "" : name.strip().toLowerCase();
    if (definitions.remove(key) == null) {
      throw new IllegalArgumentException(
          "no custom provider named '"
              + key
              + "'; defined: "
              + (definitions.isEmpty() ? "(none)" : String.join(", ", definitions.keySet())));
    }
    write();
  }

  private void load() {
    JsonNode root;
    try {
      root = Json.parse(Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
    JsonNode entries = root.path("providers");
    if (!entries.isObject()) {
      return;
    }
    entries
        .fields()
        .forEachRemaining(
            entry -> {
              String name = entry.getKey();
              JsonNode body = entry.getValue();
              String kind = body.path("kind").asText(ProviderDefinition.OPENAI);
              String baseUrl = body.path("baseUrl").asText("");
              String apiKeyEnv = body.path("apiKeyEnv").asText("");
              List<String> models = new ArrayList<>();
              JsonNode list = body.path("models");
              if (list.isArray()) {
                list.forEach(model -> models.add(model.asText()));
              }
              try {
                ProviderDefinition definition =
                    new ProviderDefinition(name, kind, baseUrl, apiKeyEnv, models).requireValid();
                definitions.put(definition.name().toLowerCase(), definition);
              } catch (IllegalArgumentException ignored) {
                // An entry we cannot use is skipped: one broken definition must not hide the rest.
              }
            });
  }

  private void write() {
    ObjectNode root = Json.object();
    ObjectNode entries = root.putObject("providers");
    for (ProviderDefinition definition : definitions.values()) {
      ObjectNode body = entries.putObject(definition.name());
      body.put("kind", definition.kind());
      body.put("baseUrl", definition.baseUrl());
      if (definition.apiKeyEnv() != null) {
        body.put("apiKeyEnv", definition.apiKeyEnv());
      }
      if (!definition.models().isEmpty()) {
        ArrayNode models = body.putArray("models");
        definition.models().forEach(models::add);
      }
    }
    try {
      Files.createDirectories(home);
      Files.writeString(
          file,
          Json.writePretty(root) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }
}
