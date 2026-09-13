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
        failure.getMessage().contains("custom"),
        "the message must explain why no default applies: " + failure.getMessage());
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

      assertEquals("/v1/messages", server.path(0), "an anthropic-kind definition speaks that API");
      assertEquals("sk-from-env", server.header(0, "x-api-key"));
      assertEquals("myrelay", config.provider(), "and the name stays the user's");
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
      // `settingsFor` is what says the URL was entered for myrelay — a --base-url flag, a
      // CCJ_BASE_URL, or the settings form while myrelay was selected.
      Config config =
          new Config("myrelay", "m", override.url(), null, null, null, null, null, null, null, null)
              .resolved()
              .scopedTo("myrelay");

      Provider provider = Providers.create(config, Map.of("OPENAI_API_KEY", "sk"), store);
      provider.complete(ping("m"), event -> {});

      assertEquals(1, override.count(), "the explicitly configured URL must be the one used");
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
        "the key must not be echoed back: " + failure.getMessage());
    assertTrue(failure.getMessage().contains("another provider"), failure.getMessage());
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
        "a custom provider uses its definition, so a stored pair entered elsewhere is not its own");
    assertTrue(Providers.usesStoredSettings(unowned.scopedTo("myrelay"), store, "myrelay"));
    assertTrue(
        Providers.usesStoredSettings(unowned, store, "openai"),
        "a built-in has no definition to fall back on, so whatever is stored is its own");
  }

  @Test
  void aBaseUrlLeftBehindByAnotherProviderIsNotUsed() throws Exception {
    // The bug this rule exists for: the provider was switched, the stored endpoint was not, and the
    // traffic went to the previous provider — with the previous provider's key, on its bill.
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

      assertEquals(1, relay.count(), "the definition's endpoint must be the one used");
      assertEquals(
          "Bearer sk-mine",
          relay.header(0, "authorization"),
          "the definition's key variable is the one this provider reads");
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
    assertTrue(Providers.supported(store).contains("myrelay"), "the picker must offer it");
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
          "the endpoint must appear once, not twice: " + server.path(0));
      provider.close();
    }
  }

  @Test
  void aCustomProvidersOwnKeyVariableBeatsTheConfigsDefault() throws Exception {
    // The bug: the key *value* was resolved against the config's apiKeyEnv, which `resolved()` fills
    // with the provider-kind default — OPENAI_API_KEY for any name that is not "anthropic". A
    // globally exported OpenAI key was therefore sent to somebody else's endpoint, and the variable
    // the user tied to that provider was never consulted.
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
        "the message must name the definition's variable: " + failure.getMessage());
  }

  @Test
  void anAnthropicKindRelayIsNotAskedForOpenAIsKeyVariable() {
    // `resolved()` fills apiKeyEnv with the default the provider's *name* gives, and only the literal
    // name "anthropic" maps to ANTHROPIC_API_KEY — so a relay with the Anthropic kind called anything
    // else was asked for OPENAI_API_KEY, and a globally exported OpenAI key was sent to it.
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
        "the variable must match the kind: " + failure.getMessage());
    assertFalse(
        failure.getMessage().contains("OPENAI_API_KEY"),
        "an Anthropic-kind relay must not be told to set OpenAI's variable: " + failure.getMessage());

    // And the exported OpenAI key is not quietly used instead.
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
        "a credential must never be echoed: " + withOpenAiExported.getMessage());
  }

  @Test
  void aBaseUrlWithATrailingSlashAfterTheEndpointIsStillNormalised() throws Exception {
    // The bug: the endpoint suffix was stripped before the trailing slash, so ".../chat/completions/"
    // kept the path and the provider appended its own to it — a 404 that looked random.
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

    assertTrue(failure.getMessage().contains("looks like an API key"), failure.getMessage());
    assertTrue(failure.getMessage().contains("API key field"), failure.getMessage());
    assertFalse(failure.getMessage().contains("abcdefghij"), "the key itself must not be echoed");
  }
}
