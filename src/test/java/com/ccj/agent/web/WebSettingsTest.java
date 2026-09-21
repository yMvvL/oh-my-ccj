package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * 设置表单：保存、拒绝、探测、以及未配置时服务器怎样解释自己。
 *
 * <p>单独成类，是因为这一组都在同一个端点上验证「写进文件的就是屏幕上看到的」。
 */
class WebSettingsTest extends WebHarness {

  @Test
  void anUnconfiguredServerStillServesAndExplainsItself() throws Exception {
    api.close();
    hub.close();
    hub = hub(null, Config.empty().resolved());
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();

    JsonNode status = json("/api/status");
    assertFalse(status.path("configured").asBoolean());
    assertTrue(status.path("provider").asText().isEmpty());
    assertTrue(status.path("cwd").asText().endsWith("ws"), "UI 仍然需要它的上下文");

    HttpResponse<String> refused = post("/api/message", "{\"text\":\"hi\"}");
    assertEquals(409, refused.statusCode());
    assertTrue(refused.body().contains("没有配置模型——打开「设置」添加一个"), refused.body());

    JsonNode config = json("/api/config");
    assertFalse(config.path("configured").asBoolean());
    assertTrue(config.path("providers").size() >= 2, config.toString());
    assertTrue(config.path("configFile").asText().endsWith("config.json"));
    assertEquals("none", config.path("apiKeySource").asText());

    try (Sse sse = watch()) {
      assertFalse(sse.await("status", 3000).path("configured").asBoolean());
    }
  }

  @Test
  void savingSettingsSwitchesTheModelAndPersistsTheFile() throws Exception {
    Files.writeString(
        configFile,
        "{\"systemPrompt\": \"keep me\", \"outputLimitBytes\": 4096, \"model\": \"old\"}");

    JsonNode saved =
        postJson(
            "/api/config",
            "{\"provider\":\"anthropic\",\"model\":\"claude-test\",\"baseUrl\":\"http://relay.invalid\",\"apiKey\":\"sk-written\",\"maxTokens\":900}");

    assertEquals("claude-test", saved.path("model").asText());
    assertEquals("anthropic", saved.path("provider").asText());
    assertTrue(saved.path("configured").asBoolean());
    assertEquals("claude-test", lastBuilt.name(), "新提供方必须是正在使用的那个");

    JsonNode file = Json.parse(Files.readString(configFile));
    assertEquals("anthropic", file.path("provider").asText());
    assertEquals("claude-test", file.path("model").asText());
    assertEquals("sk-written", file.path("apiKey").asText());
    assertEquals(900, file.path("maxTokens").asInt());
    assertEquals("keep me", file.path("systemPrompt").asText(), "未接管的键必须留得住");
    assertEquals(4096, file.path("outputLimitBytes").asInt());
    assertEquals(
        "rw-------",
        java.nio.file.attribute.PosixFilePermissions.toString(
            Files.getPosixFilePermissions(configFile)),
        "这个文件可能存有密钥");

    JsonNode config = json("/api/config");
    assertEquals("config", config.path("apiKeySource").asText());
    assertFalse(config.toString().contains("sk-written"), "密钥绝不能离开服务器");

    // 而且下一个回合真的会走重建后的提供方
    lastBuilt.reply(Message.Assistant.text("answered by the new model"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      assertEquals("answered by the new model", sse.await("done", 5000).path("finalText").asText());
    }
  }

  @Test
  void aHandWrittenConfigCanBeSavedFromTheForm() throws Exception {
    // 有人报的缺陷：一份手敲出来的配置文件——一个端点加一把密钥，没有 `settingsFor` 标记，手写
    // 文件就是这样——根本没法从设置表单里保存。表单总会提交它的密钥变量字段，并预填了提供方的
    // 默认值，而这一点被读成了「这次改动自己指定了凭据」：文件里那对没有标记的值被丢掉，随后
    // 构建提供方就失败于 `no API key for provider 'openai'`。
    Files.writeString(
        configFile,
        "{\"provider\":\"openai\",\"model\":\"hand-written\",\"apiKey\":\"sk-hand-written\"}");
    restartFromConfigFile();

    JsonNode saved = postJson("/api/config", "{\"provider\":\"openai\",\"model\":\"typed-model\","
        + "\"apiKeyEnv\":\"OPENAI_API_KEY\"}");

    assertEquals("typed-model", saved.path("model").asText(), saved.toString());
    assertEquals(
        "sk-hand-written",
        Config.fromFile(configFile).apiKey(),
        "原本就在那儿的密钥必须挺过一次没有替换它的保存");
  }

  @Test
  void namingANonDefaultKeyVariableStillCountsAsNamingACredential() throws Exception {
    // 同一条规则的另一半：一次说清了密钥来自哪里的表单保存仍然是一个刻意的动作，它替换掉的那
    // 一对会被丢弃，而不是被继承。
    Files.writeString(
        configFile,
        "{\"provider\":\"openai\",\"model\":\"hand-written\",\"apiKey\":\"sk-hand-written\"}");
    restartFromConfigFile();

    postJson("/api/config", "{\"provider\":\"openai\",\"model\":\"m\","
        + "\"apiKeyEnv\":\"MY_OWN_KEY_VARIABLE\"}");

    assertEquals("MY_OWN_KEY_VARIABLE", Config.fromFile(configFile).apiKeyEnv());
    assertNull(
        Config.fromFile(configFile).apiKey(),
        "一次自己指定了凭据的保存，不会继承原本在那儿的那个");
  }

  @Test
  void aRejectedSettingChangesNothingOnDiskOrInMemory() throws Exception {
    JsonNode before = json("/api/config");

    HttpResponse<String> rejected = post("/api/config", "{\"provider\":\"gemini\"}");

    assertEquals(400, rejected.statusCode(), rejected.body());
    assertTrue(rejected.body().contains("gemini"), rejected.body());
    assertFalse(Files.exists(configFile), "被拒的改动绝不能创建配置文件");
    assertEquals(before.path("provider").asText(), json("/api/config").path("provider").asText());
    assertEquals("openai", json("/api/status").path("provider").asText());
  }

  @Test
  void outOfRangeValuesAreRejected() throws Exception {
    assertEquals(400, post("/api/config", "{\"temperature\":9}").statusCode());
    assertEquals(400, post("/api/config", "{\"temperature\":-1}").statusCode());
    assertEquals(400, post("/api/config", "{\"temperature\":\"warm\"}").statusCode());
    assertEquals(400, post("/api/config", "{\"maxTokens\":\"lots\"}").statusCode());
    assertFalse(Files.exists(configFile));
  }

  @Test
  void clearingAStoredKeyFallsBackToTheEnvironment() throws Exception {
    postJson("/api/config", "{\"apiKey\":\"sk-temp\"}");
    assertEquals("config", json("/api/config").path("apiKeySource").asText());

    JsonNode cleared = postJson("/api/config", "{\"clearApiKey\":true}");

    assertTrue(cleared.path("configured").asBoolean(), cleared.toString());
    assertFalse(Files.readString(configFile).contains("sk-temp"), "密钥必须没了");
    assertEquals("none", json("/api/config").path("apiKeySource").asText());
  }

  @Test
  void testingSettingsDoesNotSaveThem() throws Exception {
    factoryCalls.set(0);

    JsonNode result = postJson("/api/config/test", "{\"provider\":\"anthropic\",\"model\":\"probe\"}");

    assertTrue(result.path("ok").asBoolean(), result.toString());
    assertTrue(result.path("elapsedMs").asInt() >= 0);
    assertEquals(1, factoryCalls.get(), "探测必须构建一个用完就丢的提供方");
    assertFalse(Files.exists(configFile), "测试绝不能持久化任何东西");
    assertEquals("openai", json("/api/config").path("provider").asText(), "内存中的配置未变");
    assertEquals("openai", json("/api/status").path("provider").asText());
  }

  @Test
  void aFailingProbeIsReportedAsARejection() throws Exception {
    HttpResponse<String> failure =
        post("/api/config/test", "{\"provider\":\"gemini\",\"model\":\"x\"}");

    assertEquals(400, failure.statusCode(), failure.body());
    assertTrue(failure.body().contains("gemini"), failure.body());
  }

  @Test
  void aBudgetOfZeroIsRefusedRatherThanSaved() throws Exception {
    // 它会意味着「什么都不写」，而拒绝必须在文件被写之前就到达。
    Files.writeString(configFile, "{}");

    HttpResponse<String> refused =
        post("/api/config", "{\"visionBaseUrl\":\"http://127.0.0.1:1/v1\",\"visionMaxTokens\":0}");

    assertEquals(400, refused.statusCode(), refused.body());
    assertTrue(refused.body().contains("视觉补全预算至少要有 1 个 token"), refused.body());
    assertFalse(Files.readString(configFile).contains("vision"), "什么都没写");
  }
}
