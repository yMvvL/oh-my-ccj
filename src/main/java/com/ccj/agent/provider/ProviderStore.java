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

  /**
   * Model lists the user edited, per provider name — including built-ins, which have no definition
   * of their own. Once a list is recorded here it is authoritative, so removing a model sticks;
   * before that the catalogue's own list applies.
   */
  private final Map<String, List<String>> modelLists = new LinkedHashMap<>();

  /**
   * The providers the user actually has, once they have narrowed the list.
   *
   * <p>{@code null} means "no opinion: everything the agent ships plus your definitions"; a list —
   * including an empty one — is the whole answer. Once anything is removed the list becomes
   * explicit, so a deleted provider is simply absent rather than remembered as "hidden" — the UI
   * has nothing to explain and nothing to restore, and adding one back is an ordinary add. The
   * difference between {@code null} and empty is what {@link #narrowed()} exists to report.
   */
  private java.util.List<String> shown;

  private ProviderStore(Path home) {
    this.home = home.toAbsolutePath().normalize();
    this.file = this.home.resolve(FILE_NAME);
  }

  public static ProviderStore open(Path home) {
    ProviderStore store = new ProviderStore(home);
    if (Files.isRegularFile(store.file)) {
      store.load();
      if (store.healList()) {
        store.write();
      }
    }
    return store;
  }

  public Path file() {
    return file;
  }

  /** The explicit list, or empty when the user has never removed a provider. */
  public synchronized List<String> shown() {
    return shown == null ? List.of() : List.copyOf(shown);
  }

  /**
   * True once the user has narrowed the list, which {@link #shown()} cannot say: it returns an
   * empty list both for "no opinion" and for "I removed the last one". Callers that decide what to
   * offer have to tell those apart — otherwise removing the last provider resurrects every
   * built-in, and adding one to an emptied list is never recorded.
   */
  public synchronized boolean narrowed() {
    return shown != null;
  }

  public synchronized boolean isShown(String provider) {
    if (shown == null || provider == null) {
      return true;
    }
    String key = provider.strip().toLowerCase();
    return shown.stream().anyMatch(name -> name.equalsIgnoreCase(key));
  }

  /**
   * Records the whole list. Called with what should remain, because only the caller knows the
   * built-ins the store never stored.
   */
  public synchronized void setShown(List<String> providers) {
    java.util.List<String> clean = new java.util.ArrayList<>();
    for (String provider : providers == null ? List.<String>of() : providers) {
      String value = provider == null ? "" : provider.strip();
      if (!value.isEmpty() && clean.stream().noneMatch(name -> name.equalsIgnoreCase(value))) {
        clean.add(value);
      }
    }
    shown = clean;
    write();
  }

  /**
   * The list the user recorded for a provider.
   *
   * <p>Empty means "never recorded, use the catalogue's own list"; a present-but-empty list means
   * "recorded as empty" — the difference is what lets a user remove the last model and have it stay
   * removed instead of the provider's default list reappearing.
   */
  public synchronized java.util.Optional<List<String>> modelsFor(String provider) {
    if (provider == null) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.ofNullable(modelLists.get(provider.strip().toLowerCase()));
  }

  /**
   * Records the model list for a provider. The caller passes the list it wants to end up with,
   * because only the catalogue knows what "the list without this model" means.
   */
  public synchronized void setModels(String provider, List<String> models) {
    String key = provider == null ? "" : provider.strip().toLowerCase();
    if (key.isEmpty()) {
      throw new IllegalArgumentException("a provider name is required");
    }
    List<String> clean = new java.util.ArrayList<>();
    for (String model : models == null ? List.<String>of() : models) {
      String value = model == null ? "" : model.strip();
      if (!value.isEmpty() && !clean.contains(value)) {
        clean.add(value);
      }
    }
    modelLists.put(key, List.copyOf(clean));
    write();
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
    JsonNode shownNames = root.path("shown");
    if (shownNames.isArray()) {
      shown = new java.util.ArrayList<>();
      shownNames.forEach(name -> shown.add(name.asText()));
    }
    JsonNode models = root.path("models");
    if (models.isObject()) {
      models
          .fields()
          .forEachRemaining(
              entry -> {
                if (!entry.getValue().isArray()) {
                  return;
                }
                List<String> recorded = new ArrayList<>();
                entry.getValue().forEach(model -> recorded.add(model.asText()));
                modelLists.put(entry.getKey().toLowerCase(), List.copyOf(recorded));
              });
    }
    JsonNode entries = root.path("providers");
    if (!entries.isObject()) {
      return;
    }
    boolean healed = false;
    entries
        .fields()
        .forEachRemaining(
            entry -> {
              String name = entry.getKey();
              JsonNode body = entry.getValue();
              String kind = body.path("kind").asText(ProviderDefinition.OPENAI);
              String baseUrl = body.path("baseUrl").asText("");
              String apiKeyEnv = body.path("apiKeyEnv").asText("");
              List<String> modelNames = new ArrayList<>();
              JsonNode list = body.path("models");
              if (list.isArray()) {
                list.forEach(model -> modelNames.add(model.asText()));
              }
              try {
                ProviderDefinition definition =
                    new ProviderDefinition(name, kind, baseUrl, apiKeyEnv, modelNames).requireValid();
                definitions.put(definition.name().toLowerCase(), definition);
              } catch (IllegalArgumentException ignored) {
                // An entry we cannot use is skipped: one broken definition must not hide the rest.
              }
            });
  }

  /**
   * A definition the list does not mention is invisible, which no user wants: definitions written
   * by an older build (or by a bug) are folded back into the explicit list instead of being lost.
   */
  private boolean healList() {
    if (shown == null) {
      return false;
    }
    boolean changed = false;
    // The definition's own spelling, not the lower-cased key it is stored under: the list is shown
    // to the user, and silently renaming their provider would be a surprise.
    for (ProviderDefinition definition : definitions.values()) {
      String name = definition.name();
      if (shown.stream().noneMatch(known -> known.equalsIgnoreCase(name))) {
        shown.add(name);
        changed = true;
      }
    }
    return changed;
  }

  private void write() {
    ObjectNode root = Json.object();
    if (shown != null) {
      ArrayNode shownNames = root.putArray("shown");
      shown.forEach(shownNames::add);
    }
    ObjectNode models = root.putObject("models");
    modelLists.forEach(
        (provider, list) -> {
          ArrayNode array = models.putArray(provider);
          list.forEach(array::add);
        });
    ObjectNode entries = root.putObject("providers");
    for (ProviderDefinition definition : definitions.values()) {
      ObjectNode body = entries.putObject(definition.name());
      body.put("kind", definition.kind());
      body.put("baseUrl", definition.baseUrl());
      if (definition.apiKeyEnv() != null) {
        body.put("apiKeyEnv", definition.apiKeyEnv());
      }
      if (!definition.models().isEmpty()) {
        ArrayNode declared = body.putArray("models");
        definition.models().forEach(declared::add);
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
