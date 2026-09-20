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
    assertEquals(0.1, config.temperature().doubleValue(), "它周围的字段仍被读取");
  }

  @Test
  void aRemovedFieldIsNotBroughtBackBySaving() throws IOException {
    Path file = tmp.resolve("config.json");
    Files.writeString(file, "{\"provider\":\"openai\",\"maxSteps\":25,\"language\":\"English\"}");

    Config.writeInto(file, Config.empty().merge(new Config(null, "gpt-4o-mini", null, null, null,
        null, null, null, null, null, null)));

    String written = Files.readString(file);
    assertFalse(written.contains("maxSteps"), written);
    assertFalse(written.contains("language"), written);
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

    Path wrongVision = tmp.resolve("vision.json");
    Files.writeString(wrongVision, "{\"vision\": \"llava\"}");
    IllegalArgumentException visionError =
        assertThrows(IllegalArgumentException.class, () -> Config.fromFile(wrongVision));
    assertTrue(visionError.getMessage().contains("vision"), visionError.getMessage());
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
    assertEquals(4, layered.maxTokens().intValue(), "调用方的覆盖胜过文件");
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
    assertNull(relay.model(), "中转服务可以自由命名模型；替它猜一个只会掩盖真正的错误");

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
    assertEquals("（未设置）", Config.empty().resolved().describe(Map.of()).get("apiKey"));
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
        "这个文件里可能有密钥");
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
    assertNull(merged.provider(), "这次调用不管理 provider，所以什么都没加上");
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
    assertTrue(Config.fromFile(file).settingsBelongTo("MyRelay"), "名字匹配不区分大小写");
    assertFalse(
        Config.fromFile(file).settingsBelongTo("other"),
        "给另一个提供方做的标记不是肯定答复");
  }

  @Test
  void anUnownedEndpointBelongsToNobody() {
    // 一个手写的文件，或由一次没有记录归属者的构建写出的文件：未知绝不能读成「是的，这个密钥就是
    // 给那个端点的」。
    Config unowned =
        new Config("myrelay", "m", "https://left.behind/v1", "sk-x", null, null, null, null, null,
                null, null)
            .resolved();

    assertNull(unowned.settingsFor());
    assertFalse(unowned.settingsBelongTo("myrelay"));
    assertFalse(unowned.forgetProviderSettings().settingsBelongTo("myrelay"));
    assertNull(unowned.forgetProviderSettings().baseUrl(), "并且它会丢弃自己无法归属的东西");
    assertNull(unowned.forgetProviderSettings().apiKey());
    assertEquals("myrelay", unowned.forgetProviderSettings().provider(), "提供方本身留下");
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
    assertNull(switched.apiKey(), "被离开的那一对不是新提供方的：" + switched);
    assertEquals("sk-openai", switched.rememberedFor("openai").apiKey(), switched.toString());
    assertEquals(
        "sk-openai", switched.rememberedFor("OPENAI").apiKey(), "名字匹配不区分大小写");

    Config back = switched.changedBy(new Config("openai", "gpt-x", null, null, null, null, null, null, null, null, null));

    assertEquals("sk-openai", back.apiKey(), "切回来即可恢复，无需重新粘贴");
    assertEquals("https://api.openai.com/v1", back.baseUrl());
    assertTrue(back.settingsBelongTo("openai"));
    assertFalse(
        back.rememberedNames().contains("openai"),
        "当前生效的那一对保存在扁平字段里，而不是两处都有：" + back.rememberedNames());
    assertEquals(
        "sk-openai", switched.rememberedFor("openai").apiKey(), "而且它在离开时就被留下来了");
  }

  @Test
  void aKeyPostedForTheNewProviderReplacesTheSwitch() {
    Config openai =
        new Config("openai", "gpt-x", "https://api.openai.com/v1", "sk-openai", null, null, null,
                null, null, null, null)
            .scopedTo("openai");

    // 同时给出提供方*和*密钥，意味着这个密钥属于新提供方 —— 而旧的那一对仍被记着，因为切换走
    // 并不等于把旧密钥扔掉。
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
        "用户要求忘掉的密钥，不得在下次切换时冒出来：" + cleared);
    assertEquals("sk-openai", cleared.rememberedFor("openai").apiKey(), "另一个没有被动过");
  }

  @Test
  void anUnrelatedSaveKeepsTheRememberedPairs() {
    Config config =
        new Config("openai", "gpt-x", null, "sk-openai", null, null, null, null, null, null, null)
            .scopedTo("openai")
            .remembering("myrelay", new ProviderSettings("https://relay.invalid/v1", "sk-relay", null));

    Config saved = config.changedBy(new Config(null, null, null, null, null, null, null, null, null, null, "high"));

    assertEquals("high", saved.reasoning());
    assertEquals("sk-openai", saved.apiKey(), "改动推理档位绝不能碰凭据");
    assertEquals("sk-relay", saved.rememberedFor("myrelay").apiKey());
  }

  @Test
  void layeredUsesThePairRememberedForTheProviderTheRunIsOn() {
    Path file = tmp.resolve("config.json");
    // 文件里当前生效的那一对属于 openai；myrelay 的那一对是被记住的。
    Config.writeInto(
        file,
        new Config("openai", "gpt-x", null, "sk-openai", null, null, null, null, null, null, null)
            .scopedTo("openai")
            .remembering("myrelay", new ProviderSettings("https://relay.invalid/v1", "sk-relay", null)));

    Config onRelay =
        Config.layered(file, Map.of(), new Config("myrelay", "m", null, null, null, null, null, null, null, null, null));

    assertEquals("myrelay", onRelay.provider());
    assertEquals("sk-relay", onRelay.apiKey(), "被回忆起来，而不是重新粘贴：" + onRelay);
    assertEquals("https://relay.invalid/v1", onRelay.baseUrl());
    assertTrue(onRelay.settingsBelongTo("myrelay"));

    // 点名了某个端点的命令行标志仍然直接胜出：它是为本次运行的提供方而做的动作。
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
    // resolved() 会为需要完整配置的人补上提供方的端点和密钥变量名。把它写回去，就会把一个没人
    // 选过的地址放进文件，而下一个读者会把它当作有意为之。
    Path file = tmp.resolve("config.json");

    Config.writeInto(
        file,
        new Config("myrelay", "m", null, "sk-relay", null, null, null, null, null, null, null)
            .scopedTo("myrelay")
            .resolved()
            .remembering(
                "myrelay", new ProviderSettings(Config.defaultBaseUrl("myrelay"), "sk-relay", null)));

    JsonNode written = Json.parse(Files.readString(file));
    assertFalse(written.has("baseUrl"), "被套用默认值的端点不是一个选择：" + written);
    assertFalse(
        written.has("apiKeyEnv"),
        "协议默认的变量名也不是：" + written);
    assertEquals("sk-relay", written.path("apiKey").asText());

    Config reread = Config.fromFile(file);
    assertNull(reread.baseUrl(), "仍然什么都没存");
    assertEquals(Config.defaultBaseUrl("myrelay"), reread.resolved().baseUrl(), "加载时重新推导");
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
        "端点和密钥要么一起走，要么两个都没有意义");
    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
        "一个可能存着多个密钥的文件，仍然只有属主可读");

    Config empty = Config.fromFile(tmp.resolve("nothing.json"));
    assertTrue(empty.rememberedNames().isEmpty());
  }

  @Test
  void layeredMarksSettingsThatCameFromAFlagOrTheEnvironment() throws IOException {
    Path file = tmp.resolve("config.json");

    // 来自命令行：无论本次运行最终用哪个提供方，这都是一个明确的动作。
    Config fromFlag =
        Config.layered(
            file,
            Map.of(),
            new Config(null, null, "https://flag.example.com/v1", null, null, null, null, null, null,
                null, null));
    assertTrue(fromFlag.settingsBelongTo("openai"), "默认提供方拥有那个标志所点名的东西");

    // 来自环境变量：同一类动作。
    Config fromEnvironment =
        Config.layered(file, Map.of("CCJ_BASE_URL", "https://env.example.com/v1"), null);
    assertTrue(fromEnvironment.settingsBelongTo("openai"));

    // 来自文件：没有标记，因为它是为某个本次运行并未使用的提供方写的也说不定 —— 这正是标记存在的
    // 全部理由。
    Config.writeInto(
        file,
        new Config(null, null, "https://file.example.com/v1", null, null, null, null, null, null,
            null, null));
    assertFalse(Config.layered(file, Map.of(), null).settingsBelongTo("openai"));
  }

  @Test
  void aSwitchDoesNotInheritAPairMarkedForAnotherProvider() throws IOException {
    // 文件里的标记说明这些端点和密钥是谁的。点名另一个提供方，并不是可以把上一个厂商的凭据发往
    // 新地址的许可，所以带标记的那一对会被丢弃而不是继承 —— 而一个没有标记的手写文件仍保留它的
    // 那一对，因为「没人记录是谁输入的」不等于「这是别人的」。
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
    assertNull(switched.apiKey(), "一个厂商的密钥绝不能跑到另一个厂商那里去：" + switched);
    assertEquals(
        Config.defaultBaseUrl("anthropic"),
        switched.baseUrl(),
        "它的端点也不行：" + switched);
    assertFalse(switched.settingsBelongTo("openai"));

    // 一对没人认领的仍会被使用：没有任何东西表明它属于别处。
    Path handWritten = tmp.resolve("hand-written.json");
    Files.writeString(
        handWritten,
        """
        {"provider":"openai","model":"gpt-x","baseUrl":"https://mine.invalid/v1","apiKey":"sk-mine"}
        """);
    Config kept = Config.layered(handWritten, Map.of(), null);
    assertEquals("sk-mine", kept.apiKey(), "手写的文件保留它写的内容：" + kept);
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
    assertNull(Config.empty().resolved().reasoning(), "缺省意味着由提供方决定");
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

  @Test
  void aVisionBlockRoundTripsAndIsOffWithoutOne() throws IOException {
    Path file = tmp.resolve("config.json");
    Files.writeString(
        file,
        """
        {"provider":"openai",
         "vision":{"baseUrl":"http://127.0.0.1:8080/v1","apiKey":"sk-vision","model":"llava",
                   "maxTokens":4096}}
        """);

    Config read = Config.fromFile(file);
    assertEquals("http://127.0.0.1:8080/v1", read.vision().baseUrl());
    assertEquals("sk-vision", read.vision().apiKey());
    assertEquals("llava", read.vision().model());
    assertTrue(read.vision().isConfigured());

    assertEquals(4096, read.vision().maxTokens().intValue(), "预算也是这个块的一部分");
    Config.writeInto(file, read);
    assertEquals("llava", Config.fromFile(file).vision().model(), "一次保存会保留它收到的块");
    assertEquals(
        4096,
        Config.fromFile(file).vision().maxTokens().intValue(),
        "预算也随之保留：1500 在真实截图上曾经返回空值");

    // 没有块，以及一个什么都没写的块，都只意味着同一件事：这个特性关着。
    Path none = tmp.resolve("none.json");
    assertNull(Config.fromFile(none).vision());
    assertEquals("（关闭）", Config.fromFile(none).resolved().describe(Map.of()).get("vision"));
    Path emptyBlock = tmp.resolve("empty-vision.json");
    Files.writeString(emptyBlock, "{\"vision\": {}}");
    assertNull(Config.fromFile(emptyBlock).vision());

    // 图片描述器的密钥按其他每个密钥的方式报告：从不给出完整内容。
    String reported = read.describe(Map.of()).get("vision");
    assertTrue(reported.contains("***sion"), reported);
    assertFalse(reported.contains("sk-vision"), reported);
  }

  @Test
  void theVisionBlockIsReadFromTheEnvironmentToo() {
    Config env =
        Config.fromEnv(
            Map.of(
                "CCJ_VISION_BASE_URL", "http://127.0.0.1:9000/v1",
                "CCJ_VISION_MODEL", "llava",
                "CCJ_VISION_API_KEY_ENV", "MY_VISION_KEY"));

    assertEquals("http://127.0.0.1:9000/v1", env.vision().baseUrl());
    assertEquals("llava", env.vision().model());
    assertEquals("k-1234", env.vision().resolvedApiKey(Map.of("MY_VISION_KEY", "k-1234")));
    assertNull(env.vision().maxTokens(), "缺省意味着采用客户端套用的默认值");
    assertEquals(
        6000,
        Config.fromEnv(Map.of("CCJ_VISION_MAX_TOKENS", "6000")).vision().maxTokens().intValue());
    assertNull(env.vision().resolvedApiKey(Map.of()), "未设置的变量不是密钥");
    assertNull(Config.fromEnv(Map.of()).vision(), "没有变量，就没有视觉能力");
  }

  @Test
  void aLaterSourceOverridesOneVisionFieldWithoutLosingTheRest() throws IOException {
    // 一个只点名模型的分层绝不能抹掉它下面的端点和密钥：「改用这个模型」不是「忘了端点在哪」。
    Path file = tmp.resolve("config.json");
    Files.writeString(
        file,
        """
        {"vision":{"baseUrl":"http://127.0.0.1:8080/v1","apiKey":"sk-vision","model":"llava"}}
        """);

    Config layered = Config.layered(file, Map.of("CCJ_VISION_MODEL", "internvl"), null);

    assertEquals("internvl", layered.vision().model());
    assertEquals("http://127.0.0.1:8080/v1", layered.vision().baseUrl());
    assertEquals("sk-vision", layered.vision().apiKey());

    // 而一个只点名预算的分层也照样不动其余部分。
    Config budgeted =
        Config.layered(file, Map.of("CCJ_VISION_MAX_TOKENS", "16384", "CCJ_VISION_MODEL", "internvl"), null);
    assertEquals(16384, budgeted.vision().maxTokens().intValue());
    assertEquals("http://127.0.0.1:8080/v1", budgeted.vision().baseUrl());
    assertEquals("sk-vision", budgeted.vision().apiKey());
  }

  @Test
  void theSpendLimitIsUnsetByDefault() {
    // null 是「不设上限」，与「上限是 0」不是一回事：默认必须是不设，否则每个回合都会被挡下。
    assertNull(Config.empty().maxTotalTokens());
    assertNull(Config.empty().resolved().maxTotalTokens());
    assertEquals("（无上限）", Config.empty().resolved().describe(Map.of()).get("maxTotalTokens"));
    assertEquals(
        "10000",
        Config.empty().merge(spendLimit(10000)).resolved().describe(Map.of()).get("maxTotalTokens"));
  }

  @Test
  void theSpendLimitComesFromFileAndEnvironment() throws IOException {
    Path file = tmp.resolve("config.json");
    Files.writeString(file, "{\"maxTotalTokens\": 250000}");

    assertEquals(250000, Config.fromFile(file).maxTotalTokens().intValue());
    assertEquals(
        300000,
        Config.fromEnv(Map.of("CCJ_MAX_TOTAL_TOKENS", "300000")).maxTotalTokens().intValue());
    assertNull(Config.fromEnv(Map.of()).maxTotalTokens(), "没有变量就不设上限");
  }

  @Test
  void theSpendLimitIsLayeredLikeEveryOtherLimit() throws IOException {
    Path file = tmp.resolve("config.json");
    Files.writeString(file, "{\"maxTotalTokens\": 100000}");

    assertEquals(
        200000,
        Config.layered(file, Map.of("CCJ_MAX_TOTAL_TOKENS", "200000"), null).maxTotalTokens().intValue(),
        "环境变量胜过文件");
    assertEquals(
        400000,
        Config.layered(file, Map.of("CCJ_MAX_TOTAL_TOKENS", "200000"), spendLimit(400000))
            .maxTotalTokens()
            .intValue(),
        "调用方的 flag 又胜过环境变量");
    assertEquals(
        100000,
        Config.empty().merge(spendLimit(100000)).merge(Config.empty()).maxTotalTokens().intValue(),
        "上层没提到它，就留着下层：合并不把「没说」读成「取消」");
  }

  @Test
  void writeIntoPersistsTheSpendLimit() throws IOException {
    Path file = tmp.resolve("config.json");
    Config.writeInto(file, Config.empty().merge(spendLimit(123456)));

    assertTrue(Files.readString(file).contains("maxTotalTokens"));
    assertEquals(123456, Config.fromFile(file).maxTotalTokens().intValue());

    // 清空就是清空：一个 null 上限让这个键从文件里消失，而不是留个空位等人猜。
    Config.writeInto(
        file,
        Config.empty()
            .merge(new Config(null, "m", null, null, null, null, null, null, null, null, null)));
    assertFalse(Files.readString(file).contains("maxTotalTokens"));
    assertNull(Config.fromFile(file).maxTotalTokens());
  }

  private static Config spendLimit(Integer maxTotalTokens) {
    return new Config(null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, maxTotalTokens);
  }

  private static Config reasoning(String level) {
    return new Config(null, null, null, null, null, null, null, null, null, null, level);
  }
}
