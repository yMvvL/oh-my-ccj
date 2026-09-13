package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.ProviderSettings;
import com.fasterxml.jackson.databind.JsonNode;
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
    assertTrue(config.autoApprove());
    assertEquals(4096, config.outputLimitBytes().intValue());
    assertEquals("be terse", config.systemPrompt());
    assertEquals(Config.ANTHROPIC_KEY_ENV, config.apiKeyEnv());
  }

  @Test
  void aConfigFileFromBeforeTheStepCapWasRemovedStillLoads() throws IOException {
    Path file = tmp.resolve("legacy.json");
    Files.writeString(
        file,
        "{\"provider\":\"openai\",\"model\":\"gpt-4o-mini\",\"maxSteps\":25,\"temperature\":0.1}");

    Config config = Config.fromFile(file).resolved();

    assertEquals("gpt-4o-mini", config.model());
    assertEquals(0.1, config.temperature().doubleValue(), "the fields around it are still read");
  }

  @Test
  void aRemovedFieldIsNotBroughtBackBySaving() throws IOException {
    Path file = tmp.resolve("config.json");
    Files.writeString(file, "{\"provider\":\"openai\",\"maxSteps\":25}");

    Config.writeInto(file, Config.empty().merge(new Config(null, "gpt-4o-mini", null, null, null,
        null, null, null, null, null, null)));

    String written = Files.readString(file);
    assertFalse(written.contains("maxSteps"), written);
    assertEquals("gpt-4o-mini", Config.fromFile(file).model());
  }

  @Test
  void rejectsWrongFieldTypeAndMalformedJson() throws IOException {
    Path wrongType = tmp.resolve("wrong.json");
    Files.writeString(wrongType, "{\"maxTokens\": \"many\"}");
    IllegalArgumentException typeError =
        assertThrows(IllegalArgumentException.class, () -> Config.fromFile(wrongType));
    assertTrue(typeError.getMessage().contains("maxTokens"), typeError.getMessage());

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
                "CCJ_AUTO_APPROVE", "yes",
                "CCJ_TEMPERATURE", "1.5"));

    assertEquals("openai", env.provider());
    assertEquals("gpt-4o-mini", env.model());
    assertTrue(env.autoApprove());
    assertEquals(1.5, env.temperature().doubleValue());
  }

  @Test
  void rejectsGarbageInEnvironment() {
    assertThrows(
        IllegalArgumentException.class, () -> Config.fromEnv(Map.of("CCJ_MAX_TOKENS", "soon")));
    assertThrows(
        IllegalArgumentException.class, () -> Config.fromEnv(Map.of("CCJ_AUTO_APPROVE", "maybe")));
  }

  @Test
  void laterSourcesWinOverEarlierOnes() throws IOException {
    Path file = tmp.resolve("config.json");
    Files.writeString(file, "{\"provider\":\"openai\",\"model\":\"file-model\",\"maxTokens\":9}");

    Config layered =
        Config.layered(
            file,
            Map.of("CCJ_MODEL", "env-model"),
            new Config(null, null, null, null, null, null, 4, null, null, null, null));

    assertEquals("openai", layered.provider());
    assertEquals("env-model", layered.model());
    assertEquals(4, layered.maxTokens().intValue(), "the caller's override wins over the file");
  }

  @Test
  void fillsProviderDependentDefaults() {
    Config openai = Config.empty().resolved();
    assertEquals(Config.DEFAULT_PROVIDER, openai.provider());
    assertEquals(Config.OPENAI_BASE_URL, openai.baseUrl());
    assertEquals(Config.OPENAI_KEY_ENV, openai.apiKeyEnv());
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
                null, null, "https://relay.example.com/v1", null, null, null, null, null, null,
                null, null)
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
            "anthropic", "claude-x", "https://relay.example.com/", "sk-abc", "MY_KEY", 0.5, 2048,
            null, null, null, null));

    Config reread = Config.fromFile(file);
    assertEquals("anthropic", reread.provider());
    assertEquals("claude-x", reread.model());
    assertEquals("https://relay.example.com/", reread.baseUrl());
    assertEquals("sk-abc", reread.apiKey());
    assertEquals("MY_KEY", reread.apiKeyEnv());
    assertEquals(0.5, reread.temperature().doubleValue());
    assertEquals(2048, reread.maxTokens().intValue());
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
  void theProviderAnEndpointAndKeyWereEnteredForSurvivesTheFile() throws IOException {
    Path file = tmp.resolve("config.json");

    Config.writeInto(
        file,
        new Config("myrelay", "m", "https://relay.example.com/v1", "sk-abc", "MY_KEY", null, null,
                null, null, null, null)
            .scopedTo("myrelay"));

    assertEquals("myrelay", Config.fromFile(file).settingsFor());
    assertTrue(Config.fromFile(file).settingsBelongTo("MyRelay"), "names are matched case-insensitively");
    assertFalse(
        Config.fromFile(file).settingsBelongTo("other"),
        "a mark for another provider is not a yes");
  }

  @Test
  void anUnownedEndpointBelongsToNobody() {
    // A file written by hand, or by a build that did not record the owner: unknown must never read
    // as "yes, this key is for that endpoint".
    Config unowned =
        new Config("myrelay", "m", "https://left.behind/v1", "sk-x", null, null, null, null, null,
                null, null)
            .resolved();

    assertNull(unowned.settingsFor());
    assertFalse(unowned.settingsBelongTo("myrelay"));
    assertFalse(unowned.forgetProviderSettings().settingsBelongTo("myrelay"));
    assertNull(unowned.forgetProviderSettings().baseUrl(), "and it drops what it cannot attribute");
    assertNull(unowned.forgetProviderSettings().apiKey());
    assertEquals("myrelay", unowned.forgetProviderSettings().provider(), "the provider stays");
    assertEquals("m", unowned.forgetProviderSettings().model());
  }

  @Test
  void aProviderSwitchKeepsThePairUnderItsOwnName() {
    Config openai =
        new Config("openai", "gpt-x", "https://api.openai.com/v1", "sk-openai", "OPENAI_API_KEY",
                null, null, null, null, null, null)
            .scopedTo("openai");

    Config switched = openai.changedBy(new Config("myrelay", "m", null, null, null, null, null, null, null, null, null));

    assertEquals("myrelay", switched.provider());
    assertNull(switched.apiKey(), "the pair being left is not the new provider's: " + switched);
    assertEquals("sk-openai", switched.rememberedFor("openai").apiKey(), switched.toString());
    assertEquals(
        "sk-openai", switched.rememberedFor("OPENAI").apiKey(), "names match case-insensitively");

    Config back = switched.changedBy(new Config("openai", "gpt-x", null, null, null, null, null, null, null, null, null));

    assertEquals("sk-openai", back.apiKey(), "switching back restores it without pasting again");
    assertEquals("https://api.openai.com/v1", back.baseUrl());
    assertTrue(back.settingsBelongTo("openai"));
    assertFalse(
        back.rememberedNames().contains("openai"),
        "the active pair is kept in the flat fields, not in two places: " + back.rememberedNames());
    assertEquals(
        "sk-openai", switched.rememberedFor("openai").apiKey(), "and it was kept on the way out");
  }

  @Test
  void aKeyPostedForTheNewProviderReplacesTheSwitch() {
    Config openai =
        new Config("openai", "gpt-x", "https://api.openai.com/v1", "sk-openai", null, null, null,
                null, null, null, null)
            .scopedTo("openai");

    // Naming a provider *and* a key means the key is the new provider's — and the old pair is still
    // remembered, because switching away is not the same as throwing the old key away.
    Config switched =
        openai.changedBy(
            new Config("myrelay", "m", "https://relay.invalid/v1", "sk-relay", null, null, null,
                null, null, null, null));

    assertEquals("sk-relay", switched.apiKey());
    assertEquals("https://relay.invalid/v1", switched.baseUrl());
    assertTrue(switched.settingsBelongTo("myrelay"));
    assertEquals("sk-openai", switched.rememberedFor("openai").apiKey(), switched.toString());
  }

  @Test
  void clearingAKeyForgetsTheRememberedOneToo() {
    Config openai =
        new Config("openai", "gpt-x", null, "sk-openai", null, null, null, null, null, null, null)
            .scopedTo("openai");
    Config relay =
        openai.changedBy(
            new Config("myrelay", "m", "https://relay.invalid/v1", "sk-relay", null, null, null,
                null, null, null, null));

    Config cleared = relay.changedBy(new Config(null, null, null, "", null, null, null, null, null, null, null));

    assertNull(cleared.apiKey());
    assertTrue(
        cleared.rememberedFor("myrelay") == null,
        "a key the user asked to forget must not come back on the next switch: " + cleared);
    assertEquals("sk-openai", cleared.rememberedFor("openai").apiKey(), "the other one is untouched");
  }

  @Test
  void anUnrelatedSaveKeepsTheRememberedPairs() {
    Config config =
        new Config("openai", "gpt-x", null, "sk-openai", null, null, null, null, null, null, null)
            .scopedTo("openai")
            .remembering("myrelay", new ProviderSettings("https://relay.invalid/v1", "sk-relay", null));

    Config saved = config.changedBy(new Config(null, null, null, null, null, null, null, null, null, null, "high"));

    assertEquals("high", saved.reasoning());
    assertEquals("sk-openai", saved.apiKey(), "a tier change must not touch a credential");
    assertEquals("sk-relay", saved.rememberedFor("myrelay").apiKey());
  }

  @Test
  void layeredUsesThePairRememberedForTheProviderTheRunIsOn() {
    Path file = tmp.resolve("config.json");
    // The active pair in the file belongs to openai; myrelay's is the one remembered.
    Config.writeInto(
        file,
        new Config("openai", "gpt-x", null, "sk-openai", null, null, null, null, null, null, null)
            .scopedTo("openai")
            .remembering("myrelay", new ProviderSettings("https://relay.invalid/v1", "sk-relay", null)));

    Config onRelay =
        Config.layered(file, Map.of(), new Config("myrelay", "m", null, null, null, null, null, null, null, null, null));

    assertEquals("myrelay", onRelay.provider());
    assertEquals("sk-relay", onRelay.apiKey(), "recalled, not pasted again: " + onRelay);
    assertEquals("https://relay.invalid/v1", onRelay.baseUrl());
    assertTrue(onRelay.settingsBelongTo("myrelay"));

    // A flag naming an endpoint still wins outright: it is an act for this run's provider.
    Config onFlag =
        Config.layered(
            file,
            Map.of(),
            new Config("myrelay", "m", "https://flag.invalid/v1", "sk-flag", null, null, null, null,
                null, null, null));
    assertEquals("sk-flag", onFlag.apiKey());
    assertEquals("https://flag.invalid/v1", onFlag.baseUrl());
  }

  @Test
  void theFileRecordsChoicesNotDefaults() throws IOException {
    // resolved() fills in the provider's endpoint and key variable for whoever needs a complete
    // configuration. Writing that back would put an address nobody chose into the file, where the
    // next reader would treat it as a deliberate one.
    Path file = tmp.resolve("config.json");

    Config.writeInto(
        file,
        new Config("myrelay", "m", null, "sk-relay", null, null, null, null, null, null, null)
            .scopedTo("myrelay")
            .resolved()
            .remembering(
                "myrelay", new ProviderSettings(Config.defaultBaseUrl("myrelay"), "sk-relay", null)));

    JsonNode written = Json.parse(Files.readString(file));
    assertFalse(written.has("baseUrl"), "a defaulted endpoint is not a choice: " + written);
    assertFalse(
        written.has("apiKeyEnv"),
        "nor is the protocol's default variable: " + written);
    assertEquals("sk-relay", written.path("apiKey").asText());

    Config reread = Config.fromFile(file);
    assertNull(reread.baseUrl(), "still nothing stored");
    assertEquals(Config.defaultBaseUrl("myrelay"), reread.resolved().baseUrl(), "re-derived on load");
    assertEquals("OPENAI_API_KEY", reread.resolved().apiKeyEnv());
  }

  @Test
  void theRememberedMapSurvivesTheFile() throws IOException {
    Path file = tmp.resolve("config.json");

    Config.writeInto(
        file,
        new Config("myrelay", "m", null, "sk-relay", null, null, null, null, null, null, null)
            .scopedTo("myrelay")
            .remembering("openai", new ProviderSettings("https://openai-relay.invalid/v1", "sk-openai", null)));

    Config reread = Config.fromFile(file);
    assertEquals("sk-relay", reread.apiKey());
    assertEquals("myrelay", reread.settingsFor());
    assertEquals("sk-openai", reread.rememberedFor("openai").apiKey());
    assertEquals(
        "https://openai-relay.invalid/v1",
        reread.rememberedFor("openai").baseUrl(),
        "endpoint and key travel together, or neither of them means anything");
    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
        "a file that may hold several keys is still owner-only");

    Config empty = Config.fromFile(tmp.resolve("nothing.json"));
    assertTrue(empty.rememberedNames().isEmpty());
  }

  @Test
  void layeredMarksSettingsThatCameFromAFlagOrTheEnvironment() throws IOException {
    Path file = tmp.resolve("config.json");

    // From the command line: an explicit act for whatever provider this run ends up using.
    Config fromFlag =
        Config.layered(
            file,
            Map.of(),
            new Config(null, null, "https://flag.example.com/v1", null, null, null, null, null, null,
                null, null));
    assertTrue(fromFlag.settingsBelongTo("openai"), "the default provider owns what the flag named");

    // From the environment: the same kind of act.
    Config fromEnvironment =
        Config.layered(file, Map.of("CCJ_BASE_URL", "https://env.example.com/v1"), null);
    assertTrue(fromEnvironment.settingsBelongTo("openai"));

    // From the file: unmarked, because it may have been written for a provider this run is not
    // using — which is the whole reason the mark exists.
    Config.writeInto(
        file,
        new Config(null, null, "https://file.example.com/v1", null, null, null, null, null, null,
            null, null));
    assertFalse(Config.layered(file, Map.of(), null).settingsBelongTo("openai"));
  }

  @Test
  void aSwitchDoesNotInheritAPairMarkedForAnotherProvider() throws IOException {
    // The mark in the file says whose endpoint and key these are. Naming a different provider is not
    // a licence to send the previous vendor's credential to a new address, so a marked pair is
    // dropped rather than inherited — while a hand-written file with no mark keeps its pair, because
    // "nobody recorded who entered it" is not "somebody else's".
    Path file = tmp.resolve("config.json");
    Config.writeInto(
        file,
        new Config(
                "openai",
                "gpt-x",
                "https://openai-relay.invalid/v1",
                "sk-openai-secret",
                null,
                null,
                null,
                null,
                null,
                null,
                null)
            .scopedTo("openai"));

    Config switched =
        Config.layered(
            file,
            Map.of(),
            new Config("anthropic", "claude-x", null, null, null, null, null, null, null, null, null));

    assertEquals("anthropic", switched.provider());
    assertNull(switched.apiKey(), "one vendor's key must not travel to another: " + switched);
    assertEquals(
        Config.defaultBaseUrl("anthropic"),
        switched.baseUrl(),
        "nor its endpoint: " + switched);
    assertFalse(switched.settingsBelongTo("openai"));

    // A pair nobody claimed is still used: there is nothing to say it belongs elsewhere.
    Path handWritten = tmp.resolve("hand-written.json");
    Files.writeString(
        handWritten,
        """
        {"provider":"openai","model":"gpt-x","baseUrl":"https://mine.invalid/v1","apiKey":"sk-mine"}
        """);
    Config kept = Config.layered(handWritten, Map.of(), null);
    assertEquals("sk-mine", kept.apiKey(), "a hand-written file keeps what it says: " + kept);
    assertEquals("https://mine.invalid/v1", kept.baseUrl());
  }

  @Test
  void writeIntoRejectsAFileThatIsNotAnObject() throws IOException {
    Path file = tmp.resolve("config.json");
    Files.writeString(file, "[1, 2, 3]");

    assertThrows(
        IllegalArgumentException.class,
        () -> Config.writeInto(file, Config.empty().merge(new Config(null, "m", null, null, null, null, null, null, null, null, null))));
  }

  @Test
  void reasoningTiersRoundTripAndAreValidated() throws IOException {
    Path file = tmp.resolve("config.json");
    Config.writeInto(file, Config.empty().merge(reasoning("high")));
    assertEquals("high", Config.fromFile(file).reasoning());

    assertEquals("low", Config.empty().merge(reasoning("LOW")).resolved().reasoning());
    assertNull(Config.empty().resolved().reasoning(), "absent means the provider decides");
    assertEquals("max", Config.fromEnv(Map.of("CCJ_REASONING", "max")).reasoning());

    IllegalArgumentException bad =
        assertThrows(
            IllegalArgumentException.class,
            () -> Config.empty().merge(reasoning("turbo")).resolved());
    assertTrue(bad.getMessage().contains("low, high, max"), bad.getMessage());
  }

  @Test
  void aLaterSourceOverridesTheReasoningTier() {
    Config merged = Config.empty().merge(reasoning("low")).merge(reasoning("max"));

    assertEquals("max", merged.resolved().reasoning());
  }

  private static Config reasoning(String level) {
    return new Config(null, null, null, null, null, null, null, null, null, null, level);
  }
}
