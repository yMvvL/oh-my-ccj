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
 * 用户自定义提供方的注册表：一个名字、一种协议、一个端点，以及要提供的模型。内置项从不存这里，所以一个坏
 * 定义无法把 {@code openai} 一起拖下水。
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
    assertTrue(
        missing.getMessage().contains("没有名为 'relay' 的自定义提供方"), missing.getMessage());
  }

  @Test
  void rejectsDefinitionsThatCouldNotWork() {
    ProviderStore store = ProviderStore.open(tmp);

    assertThrows(IllegalArgumentException.class, () -> store.save(definition("", "openai", "https://x")));
    assertThrows(IllegalArgumentException.class, () -> store.save(definition("bad name", "openai", "https://x")));
    assertThrows(IllegalArgumentException.class, () -> store.save(definition("relay", "grpc", "https://x")));
    assertThrows(IllegalArgumentException.class, () -> store.save(definition("relay", "openai", "")));
    assertTrue(store.list().isEmpty(), "被拒绝的定义不能存下来");
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
    // 那个有 bug 的版本留下的东西：磁盘上有一个列表从未提到的定义。
    Files.writeString(
        tmp.resolve("providers.json"),
        """
        {"shown": ["deepseek"],
         "providers": {"OpenCode": {"kind": "openai", "baseUrl": "https://x/v1", "models": ["m"]}}}
        """);

    ProviderStore store = ProviderStore.open(tmp);

    assertEquals(List.of("deepseek", "OpenCode"), store.shown(), "有定义就说明想留着它");
    assertTrue(
        Files.readString(tmp.resolve("providers.json")).contains("OpenCode"),
        "修好的列表被写了下来");
  }
}
