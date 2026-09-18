package com.ccj.agent.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.ProviderDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProvidersTest {

  @TempDir Path tmp;

  private static Provider.Request ping(String model) {
    return new Provider.Request(model, null, List.of(new Message.User("hi")), List.of(), null, 16, null);
  }

  @Test
  void mapsEveryAcceptedNameToAnImplementation() {
    for (String name :
        List.of("openai", "openai-compatible", "deepseek", "groq", "ollama", "custom")) {
      assertEquals("openai", Providers.create(config(name, "m", "k"), Map.of()).name(), name);
    }
    Provider anthropic = Providers.create(config("anthropic", "claude-x", "k"), Map.of());
    assertEquals("anthropic", anthropic.name());
    assertTrue(Providers.supported().containsAll(List.of("openai", "anthropic")));
  }

  @Test
  void fallsBackToDefaultsForUnsetProviderAndBaseUrl() {
    Config partial =
        new Config(null, "gpt-x", null, "k", null, null, null, null, null, null, null).resolved();
    Provider provider = Providers.create(partial, Map.of());

    assertEquals("openai", provider.name());
  }

  @Test
  void readsTheApiKeyFromTheEnvironment() {
    Provider provider =
        Providers.create(
            config("anthropic", "claude-x", null), Map.of("ANTHROPIC_API_KEY", "from-env"));

    assertEquals("anthropic", provider.name());
  }

  @Test
  void rejectsUnknownProviderNamesWithTheSupportedList() {
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> Providers.create(config("gemini", "g", "k"), Map.of()));

    assertTrue(failure.getMessage().contains("gemini"), failure.getMessage());
    assertTrue(failure.getMessage().contains("anthropic"), failure.getMessage());
  }

  @Test
  void usesTheProviderDefaultModelOnItsOwnEndpoint() {
    Config config =
        new Config("openai", null, null, "k", null, null, null, null, null, null, null).resolved();

    assertEquals(Config.DEFAULT_OPENAI_MODEL, config.model());
    assertEquals("openai", Providers.create(config, Map.of()).name());
  }

  @Test
  void rejectsAMissingModelWhenTheEndpointIsCustom() {
    Config relay =
        new Config(
                "openai", null, "https://relay.example.com/v1", "k", null, null, null, null,
                null, null, null)
            .resolved();

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> Providers.create(relay, Map.of()));

    assertTrue(failure.getMessage().contains("--model"), failure.getMessage());
    assertTrue(failure.getMessage().contains("CCJ_MODEL"), failure.getMessage());
    assertTrue(
        failure.getMessage().contains("自定义"),
        "消息必须解释为什么没有默认值可用：" + failure.getMessage());
  }

  @Test
  void rejectsAMissingKeyWithTheExpectedEnvironmentVariable() {
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> Providers.create(config("openai", "gpt-x", null), Map.of()));

    assertTrue(failure.getMessage().contains("OPENAI_API_KEY"), failure.getMessage());
    assertTrue(failure.getMessage().contains("apiKey"), failure.getMessage());
  }

  private static Config config(String provider, String model, String apiKey) {
    return new Config(provider, model, null, apiKey, null, null, null, null, null, null, null)
        .resolved();
  }

  @Test
  void aCustomDefinitionDecidesTheProtocolTheEndpointAndTheKeyVariable() throws Exception {
    ProviderStore store = ProviderStore.open(tmp);
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      store.save(
          new ProviderDefinition(
              "myrelay", ProviderDefinition.ANTHROPIC, server.url(), "MY_KEY", List.of("claude-x")));
      Config config =
          new Config("myrelay", "claude-x", null, null, null, null, null, null, null, null, null)
              .resolved();

      Provider provider = Providers.create(config, Map.of("MY_KEY", "sk-from-env"), store);
      provider.complete(ping("claude-x"), event -> {});

      assertEquals("/v1/messages", server.path(0), "anthropic 类型的定义说的就是这个 API");
      assertEquals("sk-from-env", server.header(0, "x-api-key"));
      assertEquals("myrelay", config.provider(), "而名字仍归用户所有");
      provider.close();
    }
  }

  @Test
  void aBaseUrlEnteredForThisProviderStillWinsOverTheDefinition() throws Exception {
    ProviderStore store = ProviderStore.open(tmp);
    try (FakeServer override = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      store.save(
          new ProviderDefinition(
              "myrelay", ProviderDefinition.OPENAI, "https://ignored.invalid/v1", null, List.of("m")));
      // `settingsFor` 才是说明这个 URL 是为 myrelay 填的东西——一个 --base-url 参数、一个
      // CCJ_BASE_URL，或者在选中 myrelay 时的设置表单。
      Config config =
          new Config("myrelay", "m", override.url(), null, null, null, null, null, null, null, null)
              .resolved()
              .scopedTo("myrelay");

      Provider provider = Providers.create(config, Map.of("OPENAI_API_KEY", "sk"), store);
      provider.complete(ping("m"), event -> {});

      assertEquals(1, override.count(), "显式配置的 URL 必须就是被用到的那个");
      provider.close();
    }
  }

  @Test
  void aKeyEnteredForAnotherProviderIsRefusedRatherThanSent() {
    ProviderStore store = ProviderStore.open(tmp);
    store.save(
        new ProviderDefinition(
            "myrelay", ProviderDefinition.OPENAI, "https://relay.invalid/v1", "MY_KEY", List.of("m")));
    Config config =
        new Config("myrelay", "m", null, "sk-previous-provider", null, null, null, null, null, null, null)
            .resolved();

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> Providers.create(config, Map.of(), store));

    assertFalse(
        failure.getMessage().contains("sk-previous-provider"),
        "密钥绝不能回显出来：" + failure.getMessage());
    assertTrue(failure.getMessage().contains("另一个提供方"), failure.getMessage());
    assertTrue(failure.getMessage().contains("MY_KEY"), failure.getMessage());
  }

  @Test
  void saysWhetherStoredSettingsAreTheOnesAProviderWillUse() {
    ProviderStore store = ProviderStore.open(tmp);
    store.save(
        new ProviderDefinition(
            "myrelay", ProviderDefinition.OPENAI, "https://relay.invalid/v1", null, List.of("m")));
    Config unowned =
        new Config("myrelay", "m", "https://left.behind/v1", "sk-x", null, null, null, null, null, null, null)
            .resolved();

    assertFalse(
        Providers.usesStoredSettings(unowned, store, "myrelay"),
        "自定义提供方用自己的定义，所以在别处填的那一对不是它的");
    assertTrue(Providers.usesStoredSettings(unowned.scopedTo("myrelay"), store, "myrelay"));
    assertTrue(
        Providers.usesStoredSettings(unowned, store, "openai"),
        "内置项没有定义可以回退，所以存下的是什么就是它的");
  }

  @Test
  void aBaseUrlLeftBehindByAnotherProviderIsNotUsed() throws Exception {
    // 这条规则之所以存在，是为了这个 bug：提供方换了，存下的端点没换，流量于是去了上一个提供方——用着上一
    // 个提供方的密钥，记在它的账上。
    ProviderStore store = ProviderStore.open(tmp);
    try (FakeServer relay = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      store.save(
          new ProviderDefinition("myrelay", ProviderDefinition.OPENAI, relay.url(), "MY_KEY", List.of("m")));
      Config config =
          new Config(
                  "myrelay",
                  "m",
                  "https://api.deepseek.com",
                  "sk-previous-provider",
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null)
              .resolved();

      Provider provider = Providers.create(config, Map.of("MY_KEY", "sk-mine"), store);
      provider.complete(ping("m"), event -> {});

      assertEquals(1, relay.count(), "被用到的必须是定义里的端点");
      assertEquals(
          "Bearer sk-mine",
          relay.header(0, "authorization"),
          "这个提供方读的就是定义里的那个密钥变量");
      provider.close();
    }
  }

  @Test
  void anUnknownNameListsWhatIsDefined() {
    ProviderStore store = ProviderStore.open(tmp);
    store.save(
        new ProviderDefinition("myrelay", ProviderDefinition.OPENAI, "https://x/v1", null, List.of("m")));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> Providers.create(config("nope", "m", "sk"), Map.of(), store));

    assertTrue(failure.getMessage().contains("myrelay"), failure.getMessage());
    assertTrue(Providers.supported(store).contains("myrelay"), "选择器必须给出它");
  }

  @Test
  void aCustomProviderWithoutAModelSaysWhatItOffers() {
    ProviderStore store = ProviderStore.open(tmp);
    store.save(
        new ProviderDefinition(
            "myrelay", ProviderDefinition.OPENAI, "https://x/v1", null, List.of("model-a", "model-b")));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> Providers.create(config("myrelay", null, "sk"), Map.of(), store));

    assertTrue(failure.getMessage().contains("model-a"), failure.getMessage());
  }

  @Test
  void aBaseUrlThatRepeatsTheEndpointIsNormalised() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      Config config =
          new Config("openai", "gpt-test", server.url() + "/v1/chat/completions", "sk-k", null, null,
                  null, null, null, null, null)
              .resolved();

      Provider provider = Providers.create(config, Map.of());
      provider.complete(ping("gpt-test"), event -> {});

      assertEquals(
          "/v1/chat/completions",
          server.path(0),
          "端点必须只出现一次，而不是两次：" + server.path(0));
      provider.close();
    }
  }

  @Test
  void aCustomProvidersOwnKeyVariableBeatsTheConfigsDefault() throws Exception {
    // 那个 bug：密钥的*值*是照着配置里的 apiKeyEnv 解析的，而 `resolved()` 会用提供方类型的默认值填上
    // 它——任何不叫 "anthropic" 的名字都得到 OPENAI_API_KEY。于是全局导出的 OpenAI 密钥被发去了别人的
    // 端点，而用户绑给那个提供方的变量从未被看过。
    ProviderStore store = ProviderStore.open(tmp);
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      store.save(
          new ProviderDefinition(
              "myrelay", ProviderDefinition.OPENAI, server.url() + "/v1", "MY_KEY", List.of("m")));
      Config config =
          new Config("myrelay", "m", null, null, null, null, null, null, null, null, null)
              .resolved();

      Provider provider =
          Providers.create(
              config, Map.of("OPENAI_API_KEY", "sk-openai-secret", "MY_KEY", "sk-relay"), store);
      provider.complete(ping("m"), event -> {});

      assertEquals("Bearer sk-relay", server.header(0, "authorization"));
      provider.close();
    }
  }

  @Test
  void aCustomProviderWithNoKeyOfItsOwnNamesItsOwnVariable() {
    ProviderStore store = ProviderStore.open(tmp);
    store.save(
        new ProviderDefinition(
            "myrelay", ProviderDefinition.OPENAI, "https://relay.example.com/v1", "MY_KEY", List.of("m")));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> Providers.create(config("myrelay", "m", null), Map.of("OPENAI_API_KEY", "sk-openai"), store));

    assertTrue(
        failure.getMessage().contains("MY_KEY"),
        "消息必须点名定义里的那个变量：" + failure.getMessage());
  }

  @Test
  void anAnthropicKindRelayIsNotAskedForOpenAIsKeyVariable() {
    // `resolved()` 会用提供方*名字*给出的默认值填上 apiKeyEnv，而只有字面上的名字 "anthropic" 才映射
    // 到 ANTHROPIC_API_KEY——于是一个叫别的名字、类型却是 Anthropic 的中继被要求提供 OPENAI_API_KEY，而
    // 全局导出的 OpenAI 密钥被打发给了它。
    ProviderStore store = ProviderStore.open(tmp);
    store.save(
        new ProviderDefinition(
            "myrelay-anthropic",
            ProviderDefinition.ANTHROPIC,
            "https://relay.example.com/v1",
            null,
            List.of("m")));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> Providers.create(config("myrelay-anthropic", "m", null), Map.of(), store));

    assertTrue(
        failure.getMessage().contains("ANTHROPIC_API_KEY"),
        "变量必须与类型相配：" + failure.getMessage());
    assertFalse(
        failure.getMessage().contains("OPENAI_API_KEY"),
        "不能叫一个 Anthropic 类型的中继去设置 OpenAI 的变量：" + failure.getMessage());

    // 而导出的 OpenAI 密钥也不会被悄悄拿去顶替。
    IllegalArgumentException withOpenAiExported =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Providers.create(
                    config("myrelay-anthropic", "m", null),
                    Map.of("OPENAI_API_KEY", "sk-openai-secret"),
                    store));
    assertFalse(
        withOpenAiExported.getMessage().contains("sk-openai-secret"),
        "凭据绝不能回显：" + withOpenAiExported.getMessage());
  }

  @Test
  void aBaseUrlWithATrailingSlashAfterTheEndpointIsStillNormalised() throws Exception {
    // 那个 bug：端点后缀在结尾斜杠之前就被剥掉，于是 ".../chat/completions/" 把路径留了下来，提供方又
    // 往后面追加自己的路径——一个看起来毫无规律的 404。
    ProviderStore store = ProviderStore.open(tmp);
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      store.save(
          new ProviderDefinition(
              "myrelay",
              ProviderDefinition.OPENAI,
              server.url() + "/v1/chat/completions/",
              null,
              List.of("m")));
      Config config =
          new Config("myrelay", "m", null, null, null, null, null, null, null, null, null)
              .resolved();

      Provider provider = Providers.create(config, Map.of("OPENAI_API_KEY", "sk"), store);
      provider.complete(ping("m"), event -> {});

      assertEquals("/v1/chat/completions", server.path(0), server.path(0));
      provider.close();
    }
  }

  @Test
  void anApiKeyEnvThatHoldsAKeyExplainsTheMistake() {
    ProviderStore store = ProviderStore.open(tmp);
    store.save(
        new ProviderDefinition(
            "myrelay",
            ProviderDefinition.OPENAI,
            "https://relay.example.com/v1",
            "sk-abcdefghijklmnopqrstuvwxyz012345",
            List.of("m")));
    Config config =
        new Config("myrelay", "m", null, null, null, null, null, null, null, null, null)
            .resolved();

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> Providers.create(config, Map.of(), store));

    assertTrue(
        failure.getMessage().contains("看起来就是 API 密钥本身"), failure.getMessage());
    assertTrue(failure.getMessage().contains("API key 字段"), failure.getMessage());
    assertFalse(failure.getMessage().contains("abcdefghij"), "密钥本身绝不能回显");
  }
}
