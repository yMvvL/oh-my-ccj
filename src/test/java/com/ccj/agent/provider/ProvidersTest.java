package com.ccj.agent.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.Provider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProvidersTest {

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
                "openai", null, "https://relay.example.com/v1", "k", null, null, null, null, null,
                null, null)
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
}
