package com.ccj.agent.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.ProviderDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The registry for user-defined providers: a name, a protocol, an endpoint, and the models to offer.
 * Built-ins are never stored here, so a bad definition cannot take {@code openai} down with it.
 */
class ProviderStoreTest {

  @TempDir Path tmp;

  private ProviderDefinition definition(String name, String kind, String url) {
    return new ProviderDefinition(name, kind, url, "MY_KEY", List.of("model-a", "model-b"));
  }

  @Test
  void savesAndReloadsDefinitions() {
    ProviderStore store = ProviderStore.open(tmp);
    store.save(definition("relay", ProviderDefinition.OPENAI, "https://relay.example.com/v1"));

    ProviderStore reopened = ProviderStore.open(tmp);

    Optional<ProviderDefinition> found = reopened.find("relay");
    assertTrue(found.isPresent());
    assertEquals("https://relay.example.com/v1", found.get().baseUrl());
    assertEquals(ProviderDefinition.OPENAI, found.get().kind());
    assertEquals("MY_KEY", found.get().apiKeyEnv());
    assertEquals(List.of("model-a", "model-b"), found.get().models());
    assertTrue(Files.isRegularFile(tmp.resolve("providers.json")));
  }

  @Test
  void savingAgainReplacesAndRemovingIsReported() {
    ProviderStore store = ProviderStore.open(tmp);
    store.save(definition("relay", ProviderDefinition.OPENAI, "https://one/v1"));
    store.save(definition("relay", ProviderDefinition.ANTHROPIC, "https://two"));

    assertEquals(1, store.list().size());
    assertEquals("https://two", store.find("relay").orElseThrow().baseUrl());
    assertEquals(ProviderDefinition.ANTHROPIC, store.find("relay").orElseThrow().kind());

    store.remove("relay");
    assertTrue(store.list().isEmpty());
    IllegalArgumentException missing =
        assertThrows(IllegalArgumentException.class, () -> store.remove("relay"));
    assertTrue(missing.getMessage().contains("no custom provider"), missing.getMessage());
  }

  @Test
  void rejectsDefinitionsThatCouldNotWork() {
    ProviderStore store = ProviderStore.open(tmp);

    assertThrows(IllegalArgumentException.class, () -> store.save(definition("", "openai", "https://x")));
    assertThrows(IllegalArgumentException.class, () -> store.save(definition("bad name", "openai", "https://x")));
    assertThrows(IllegalArgumentException.class, () -> store.save(definition("relay", "grpc", "https://x")));
    assertThrows(IllegalArgumentException.class, () -> store.save(definition("relay", "openai", "")));
    assertTrue(store.list().isEmpty(), "a rejected definition must not be stored");
  }

  @Test
  void oneBrokenEntryDoesNotHideTheOthers() throws IOException {
    Files.writeString(
        tmp.resolve("providers.json"),
        """
        {"providers": {
           "good": {"kind": "openai", "baseUrl": "https://good/v1", "models": ["m"]},
           "broken": {"kind": "carrier-pigeon", "baseUrl": "https://broken/v1"}
         }}
        """);

    ProviderStore store = ProviderStore.open(tmp);

    assertEquals(List.of("good"), store.list().stream().map(ProviderDefinition::name).toList());
  }

  @Test
  void namesAreCaseInsensitiveOnLookup() {
    ProviderStore store = ProviderStore.open(tmp);
    store.save(definition("Relay", ProviderDefinition.OPENAI, "https://x/v1"));

    assertTrue(store.find("relay").isPresent());
    assertTrue(store.find("RELAY").isPresent());
    assertFalse(store.find("other").isPresent());
    assertFalse(store.find(null).isPresent());
  }

  @Test
  void aDefinitionMissingFromAnExplicitListIsFoldedBackIn() throws IOException {
    // What the buggy build left behind: a definition on disk that the list never mentions.
    Files.writeString(
        tmp.resolve("providers.json"),
        """
        {"shown": ["deepseek"],
         "providers": {"OpenCode": {"kind": "openai", "baseUrl": "https://x/v1", "models": ["m"]}}}
        """);

    ProviderStore store = ProviderStore.open(tmp);

    assertEquals(List.of("deepseek", "OpenCode"), store.shown(), "a definition is intent to have it");
    assertTrue(
        Files.readString(tmp.resolve("providers.json")).contains("OpenCode"),
        "and the repaired list is written down");
  }
}
