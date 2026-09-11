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
        new Config(null, "gpt-x", null, "k", null, null, null, null, null, null, null, null).resolved();
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
        new Config("openai", null, null, "k", null, null, null, null, null, null, null, null).resolved();

    assertEquals(Config.DEFAULT_OPENAI_MODEL, config.model());
    assertEquals("openai", Providers.create(config, Map.of()).name());
  }

  @Test
  void rejectsAMissingModelWhenTheEndpointIsCustom() {
    Config relay =
        new Config(
                "openai", null, "https://relay.example.com/v1", "k", null, null, null, null, null,
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
    return new Config(provider, model, null, apiKey, null, null, null, null, null, null, null, null)
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
          new Config("myrelay", "claude-x", null, null, null, null, null, null, null, null, null, null)
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
  void anExplicitBaseUrlStillWinsOverTheDefinition() throws Exception {
    ProviderStore store = ProviderStore.open(tmp);
    try (FakeServer override = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      store.save(
          new ProviderDefinition(
              "myrelay", ProviderDefinition.OPENAI, "https://ignored.invalid/v1", null, List.of("m")));
      Config config =
          new Config("myrelay", "m", override.url(), null, null, null, null, null, null, null, null, null)
              .resolved();

      Provider provider = Providers.create(config, Map.of("OPENAI_API_KEY", "sk"), store);
      provider.complete(ping("m"), event -> {});

      assertEquals(1, override.count(), "the explicitly configured URL must be the one used");
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
                  null, null, null, null, null, null)
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
        new Config("myrelay", "m", null, null, null, null, null, null, null, null, null, null)
            .resolved();

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> Providers.create(config, Map.of(), store));

    assertTrue(failure.getMessage().contains("looks like an API key"), failure.getMessage());
    assertTrue(failure.getMessage().contains("API key field"), failure.getMessage());
    assertFalse(failure.getMessage().contains("abcdefghij"), "the key itself must not be echoed");
  }
}
