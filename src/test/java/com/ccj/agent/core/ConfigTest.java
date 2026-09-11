package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

  @TempDir Path tmp;

  @Test
  void missingFileIsAnEmptyConfig() {
    assertEquals(Config.empty(), Config.fromFile(tmp.resolve("nope.json")));
    assertNull(Config.fromFile(null).provider());
  }

  @Test
  void readsEveryFieldFromFile() throws IOException {
    Path file = tmp.resolve("config.json");
    Files.writeString(
        file,
        """
        {
          "provider": "anthropic",
          "model": "claude-sonnet-4",
          "baseUrl": "https://relay.example.com/",
          "apiKey": "sk-file",
          "temperature": 0.2,
          "maxTokens": 1024,
          "maxSteps": 7,
          "autoApprove": true,
          "outputLimitBytes": 4096,
          "systemPrompt": "be terse"
        }
        """);

    Config config = Config.fromFile(file).resolved();

    assertEquals("anthropic", config.provider());
    assertEquals("claude-sonnet-4", config.model());
    assertEquals("https://relay.example.com", config.baseUrl());
    assertEquals("sk-file", config.apiKey());
    assertEquals(0.2, config.temperature().doubleValue());
    assertEquals(1024, config.maxTokens().intValue());
    assertEquals(7, config.maxSteps().intValue());
    assertTrue(config.autoApprove());
    assertEquals(4096, config.outputLimitBytes().intValue());
    assertEquals("be terse", config.systemPrompt());
    assertEquals(Config.ANTHROPIC_KEY_ENV, config.apiKeyEnv());
  }

  @Test
  void rejectsWrongFieldTypeAndMalformedJson() throws IOException {
    Path wrongType = tmp.resolve("wrong.json");
    Files.writeString(wrongType, "{\"maxSteps\": \"many\"}");
    IllegalArgumentException typeError =
        assertThrows(IllegalArgumentException.class, () -> Config.fromFile(wrongType));
    assertTrue(typeError.getMessage().contains("maxSteps"), typeError.getMessage());

    Path broken = tmp.resolve("broken.json");
    Files.writeString(broken, "{oops");
    assertThrows(IllegalArgumentException.class, () -> Config.fromFile(broken));
  }

  @Test
  void parsesEnvironmentVariables() {
    Config env =
        Config.fromEnv(
            Map.of(
                "CCJ_PROVIDER", "openai",
                "CCJ_MODEL", "gpt-4o-mini",
                "CCJ_MAX_STEPS", "3",
                "CCJ_AUTO_APPROVE", "yes",
                "CCJ_TEMPERATURE", "1.5"));

    assertEquals("openai", env.provider());
    assertEquals("gpt-4o-mini", env.model());
    assertEquals(3, env.maxSteps().intValue());
    assertTrue(env.autoApprove());
    assertEquals(1.5, env.temperature().doubleValue());
  }

  @Test
  void rejectsGarbageInEnvironment() {
    assertThrows(
        IllegalArgumentException.class, () -> Config.fromEnv(Map.of("CCJ_MAX_STEPS", "soon")));
    assertThrows(
        IllegalArgumentException.class, () -> Config.fromEnv(Map.of("CCJ_AUTO_APPROVE", "maybe")));
  }

  @Test
  void laterSourcesWinOverEarlierOnes() throws IOException {
    Path file = tmp.resolve("config.json");
    Files.writeString(file, "{\"provider\":\"openai\",\"model\":\"file-model\",\"maxSteps\":9}");

    Config layered =
        Config.layered(
            file,
            Map.of("CCJ_MODEL", "env-model"),
            new Config(null, null, null, null, null, null, null, 4, null, null, null));

    assertEquals("openai", layered.provider());
    assertEquals("env-model", layered.model());
    assertEquals(4, layered.maxSteps().intValue());
  }

  @Test
  void fillsProviderDependentDefaults() {
    Config openai = Config.empty().resolved();
    assertEquals(Config.DEFAULT_PROVIDER, openai.provider());
    assertEquals(Config.OPENAI_BASE_URL, openai.baseUrl());
    assertEquals(Config.OPENAI_KEY_ENV, openai.apiKeyEnv());
    assertEquals(Config.DEFAULT_MAX_STEPS, openai.maxSteps().intValue());
    assertFalse(openai.autoApprove());

    Config anthropic = new Config("Anthropic", null, null, null, null, null, null, null, null, null, null).resolved();
    assertEquals("anthropic", anthropic.provider());
    assertEquals(Config.ANTHROPIC_BASE_URL, anthropic.baseUrl());
    assertEquals(Config.ANTHROPIC_KEY_ENV, anthropic.apiKeyEnv());
  }

  @Test
  void appliesAProviderDefaultModelOnlyAgainstTheOfficialEndpoint() {
    assertEquals(Config.DEFAULT_OPENAI_MODEL, Config.empty().resolved().model());
    assertEquals(
        Config.DEFAULT_ANTHROPIC_MODEL,
        new Config("anthropic", null, null, null, null, null, null, null, null, null, null)
            .resolved()
            .model());
    assertEquals(
        Config.DEFAULT_OPENAI_MODEL,
        new Config(null, null, Config.OPENAI_BASE_URL, null, null, null, null, null, null, null, null)
            .resolved()
            .model());

    Config relay =
        new Config(
                null, null, "https://relay.example.com/v1", null, null, null, null, null, null, null,
                null)
            .resolved();
    assertNull(relay.model(), "relays name models freely; guessing one would hide the real error");

    assertEquals(
        "my-model",
        Config.empty()
            .merge(new Config(null, "my-model", null, null, null, null, null, null, null, null, null))
            .resolved()
            .model());
  }

  @Test
  void apiKeyComesFromConfigThenEnvironment() {
    Config fromConfig =
        Config.empty().merge(new Config(null, null, null, "sk-literal", null, null, null, null, null, null, null));
    assertEquals("sk-literal", fromConfig.resolvedApiKey(Map.of("OPENAI_API_KEY", "sk-env")));

    Config fromEnv = Config.empty().resolved();
    assertEquals("sk-env", fromEnv.resolvedApiKey(Map.of("OPENAI_API_KEY", "sk-env")));
    assertNull(fromEnv.resolvedApiKey(Map.of()));
  }

  @Test
  void describeRedactsTheKey() {
    Config config =
        Config.empty()
            .merge(new Config(null, null, null, "sk-supersecret1234", null, null, null, null, null, null, null))
            .resolved();

    String shown = config.describe(Map.of()).get("apiKey");
    assertEquals("***1234", shown);
    assertEquals("(unset)", Config.empty().resolved().describe(Map.of()).get("apiKey"));
  }

  @Test
  void appPathsHonourCcjHome() {
    assertEquals(
        Path.of("/tmp/ccj-home"),
        AppPaths.fromEnv(Map.of("CCJ_HOME", "/tmp/ccj-home")).home());
    assertEquals(
        Path.of("/home/someone/.oh-my-ccj"),
        AppPaths.fromEnv(Map.of("HOME", "/home/someone")).home());
  }

  @Test
  void writeIntoPersistsManagedFieldsAndKeepsTheRest() throws IOException {
    Path file = tmp.resolve("nested/config.json");

    Config.writeInto(
        file,
        new Config(
            "anthropic", "claude-x", "https://relay.example.com/", "sk-abc", "MY_KEY", 0.5, 2048, 12,
            null, null, null));

    Config reread = Config.fromFile(file);
    assertEquals("anthropic", reread.provider());
    assertEquals("claude-x", reread.model());
    assertEquals("https://relay.example.com/", reread.baseUrl());
    assertEquals("sk-abc", reread.apiKey());
    assertEquals("MY_KEY", reread.apiKeyEnv());
    assertEquals(0.5, reread.temperature().doubleValue());
    assertEquals(2048, reread.maxTokens().intValue());
    assertEquals(12, reread.maxSteps().intValue());
    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
        "the file may hold a key");
  }

  @Test
  void writeIntoLeavesFieldsItDoesNotManageAlone() throws IOException {
    Path file = tmp.resolve("config.json");
    Files.writeString(
        file, "{\"systemPrompt\": \"keep me\", \"outputLimitBytes\": 4096, \"model\": \"old\"}");

    Config.writeInto(file, Config.empty().merge(new Config(null, "new-model", null, null, null, null, null, null, null, null, null)));

    Config merged = Config.fromFile(file);
    assertEquals("keep me", merged.systemPrompt());
    assertEquals(4096, merged.outputLimitBytes().intValue());
    assertEquals("new-model", merged.model());
    assertNull(merged.provider(), "provider was not managed by this call, so nothing was added");
  }

  @Test
  void aBlankApiKeyIsRemovedRatherThanStored() throws IOException {
    Path file = tmp.resolve("config.json");
    Config.writeInto(file, Config.empty().merge(new Config(null, "m", null, "sk-secret", null, null, null, null, null, null, null)));
    assertTrue(Files.readString(file).contains("sk-secret"));

    Config.writeInto(file, Config.fromFile(file).merge(new Config(null, null, null, "", null, null, null, null, null, null, null)));

    assertFalse(Files.readString(file).contains("sk-secret"));
    assertNull(Config.fromFile(file).apiKey());
  }

  @Test
  void writeIntoRejectsAFileThatIsNotAnObject() throws IOException {
    Path file = tmp.resolve("config.json");
    Files.writeString(file, "[1, 2, 3]");

    assertThrows(
        IllegalArgumentException.class,
        () -> Config.writeInto(file, Config.empty().merge(new Config(null, "m", null, null, null, null, null, null, null, null, null))));
  }
}
