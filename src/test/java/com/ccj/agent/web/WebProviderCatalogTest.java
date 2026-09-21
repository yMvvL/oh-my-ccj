package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 提供方目录与模型清单：内置与自定义的增删改、密钥的归属，以及推理档位如何进入下一个回合。
 *
 * <p>单独成类，是因为这一组的被测对象是那份目录本身（providers.json 与配置里的 settingsFor），
 * 端点只是它的投影。
 */
class WebProviderCatalogTest extends WebHarness {

  @Test
  void theCatalogueListsBuiltInsAndUserDefinitions() throws Exception {
    providerStore.save(
        new com.ccj.agent.core.ProviderDefinition(
            "myrelay",
            com.ccj.agent.core.ProviderDefinition.OPENAI,
            "https://relay.invalid/v1",
            null,
            List.of("fast-model", "smart-model")));

    JsonNode catalog = json("/api/models");

    JsonNode relay =
        lastWhere(catalog.path("providers"), entry -> entry.path("name").asText().equals("myrelay"));
    assertFalse(relay.path("builtIn").asBoolean());
    assertEquals("https://relay.invalid/v1", relay.path("baseUrl").asText());
    assertEquals(2, relay.path("models").size());

    JsonNode openai =
        lastWhere(catalog.path("providers"), entry -> entry.path("name").asText().equals("openai"));
    assertTrue(openai.path("builtIn").asBoolean());
    assertEquals(List.of("gpt-4o-mini"), modelsOf(openai));

    JsonNode fast =
        lastWhere(catalog.path("models"), entry -> entry.path("model").asText().equals("fast-model"));
    assertEquals("myrelay", fast.path("provider").asText());
    assertEquals("config", fast.path("source").asText(), "这个条目从哪儿来的");
  }

  @Test
  void aCustomProviderIsOfferedByTheSettingsForm() throws Exception {
    providerStore.save(
        new com.ccj.agent.core.ProviderDefinition(
            "myrelay", com.ccj.agent.core.ProviderDefinition.OPENAI, "https://relay.invalid/v1", null,
            List.of("fast-model")));

    List<String> providers = new ArrayList<>();
    json("/api/config").path("providers").forEach(name -> providers.add(name.asText()));

    assertTrue(providers.contains("openai"), providers.toString());
    assertTrue(providers.contains("myrelay"), "自定义的提供方必须可选");

    // 而且它立刻就能用：把它保存为当前提供方绝不能被拒
    JsonNode saved =
        postJson(
            "/api/config",
            "{\"provider\":\"myrelay\",\"model\":\"fast-model\",\"apiKey\":\"sk-custom\"}");
    assertEquals("myrelay", saved.path("provider").asText());
    assertTrue(saved.path("configured").asBoolean());
  }

  @Test
  void aProviderCanBeDefinedFromTheUiAndIsThenUsable() throws Exception {
    JsonNode added =
        postJson(
            "/api/providers",
            "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"http://127.0.0.1:1/v1\","
                + "\"apiKeyEnv\":\"MY_KEY\",\"models\":\"fast,smart\"}");

    JsonNode relay =
        lastWhere(added.path("providers"), entry -> entry.path("name").asText().equals("myrelay"));
    assertFalse(relay.path("builtIn").asBoolean());
    assertEquals(List.of("fast", "smart"), modelsOf(relay), "逗号分隔的列表是能被读懂的");

    // 它可选，而且正在运行的会话可以立刻切到它上面
    JsonNode saved =
        postJson(
            "/api/config",
            "{\"provider\":\"myrelay\",\"model\":\"fast\",\"baseUrl\":\"http://127.0.0.1:1/v1\"}");
    assertEquals("myrelay", saved.path("provider").asText());
    assertEquals("fast", saved.path("model").asText());
  }

  @Test
  void aBadProviderDefinitionIsRejectedWithAReason() throws Exception {
    assertEquals(
        400,
        post("/api/providers", "{\"name\":\"bad name\",\"kind\":\"openai\",\"baseUrl\":\"http://x\"}")
            .statusCode());
    assertEquals(
        400,
        post("/api/providers", "{\"name\":\"relay\",\"kind\":\"grpc\",\"baseUrl\":\"http://x\"}")
            .statusCode());
    assertEquals(
        400, post("/api/providers", "{\"name\":\"relay\",\"kind\":\"openai\"}").statusCode());
    assertTrue(providerStore.list().isEmpty(), "被拒的定义绝不能存下来");
  }

  @Test
  void switchingProviderKeepsTheEndpointAndKeyUnderTheProviderItIsLeaving() throws Exception {
    // 输入框上的选择器会提交的东西：提供方和模型，别的什么都没有。存着的 baseUrl 和密钥属于
    // 正要离开的那个提供方，拿它们去给新提供方用，就是一个自称 "myrelay" 的会话最后拿着上一个
    // 提供方的密钥、把流量发到上一个提供方的地址上——记在上一个提供方的账上。它们会被留在自己
    // 所属的名字底下，而不是留在当前那一对里，这才让切回去是免费的。
    postJson(
        "/api/config",
        "{\"provider\":\"openai\",\"model\":\"m\",\"baseUrl\":\"https://previous.example.com/v1\",\"apiKey\":\"sk-previous\"}");
    assertEquals("config", json("/api/config").path("apiKeySource").asText());

    postJson(
        "/api/providers",
        "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"https://relay.example.com/v1\"}");
    JsonNode switched = postJson("/api/config", "{\"provider\":\"myrelay\",\"model\":\"m\"}");

    assertEquals("myrelay", switched.path("provider").asText());
    JsonNode stored = Json.parse(Files.readString(configFile));
    assertNull(
        stored.path("apiKey").isTextual() ? stored.path("apiKey").asText() : null,
        "旧的那一对里任何东西都不能留在当前字段里：" + stored);
    assertFalse(
        stored.path("baseUrl").asText("").contains("previous.example.com"),
        "正要离开的那个端点也不能留在当前那一对里：" + stored);
    assertEquals(
        "https://relay.example.com/v1",
        switched.path("baseUrl").asText(),
        "报告出来的端点必须是这次请求将要使用的那个，也就是定义里的那个");
    assertEquals(
        "none",
        json("/api/config").path("apiKeySource").asText(),
        "表单绝不能报告一个不会被发出去的密钥");
    assertEquals(
        "sk-previous",
        stored.path("remembered").path("openai").path("apiKey").asText(),
        "它被留在当初录入它的那个提供方名下：" + stored);
  }

  @Test
  void aKeyIsRememberedPerProviderSoSwitchingBackRestoresIt() throws Exception {
    postJson(
        "/api/providers",
        "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"https://relay.example.com/v1\"}");
    postJson("/api/config", "{\"provider\":\"openai\",\"model\":\"gpt-x\",\"apiKey\":\"sk-openai\"}");
    postJson("/api/config", "{\"provider\":\"myrelay\",\"model\":\"m\"}");
    postJson("/api/config", "{\"apiKey\":\"sk-relay\"}");

    JsonNode cfg = json("/api/config");
    assertEquals(
        List.of("openai", "myrelay"),
        strings(cfg.path("rememberedProviders")),
        "每一个存有密钥的提供方——openai 的是被记着的，myrelay 的此刻正在生效。"
            + " 只给名字，绝不给密钥");
    assertEquals("config", cfg.path("apiKeySource").asText());

    // 回到 openai：它自己的端点和密钥回来了，什么都不用重新粘贴。
    JsonNode back = postJson("/api/config", "{\"provider\":\"openai\",\"model\":\"gpt-x\"}");
    assertEquals("openai", back.path("provider").asText());
    JsonNode stored = Json.parse(Files.readString(configFile));
    assertEquals("sk-openai", stored.path("apiKey").asText(), "回到它自己的密钥上：" + stored);
    assertFalse(
        stored.path("baseUrl").asText("").contains("relay.example.com"),
        "而不是停在它被切走的那个端点上：" + stored);
    assertEquals(
        "sk-relay",
        stored.path("remembered").path("myrelay").path("apiKey").asText(),
        "而 myrelay 的密钥等着回去的路：" + stored);
  }

  @Test
  void clearingAKeyForgetsItForThatProviderOnly() throws Exception {
    postJson(
        "/api/providers",
        "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"https://relay.example.com/v1\"}");
    postJson("/api/config", "{\"provider\":\"openai\",\"model\":\"gpt-x\",\"apiKey\":\"sk-openai\"}");
    postJson("/api/config", "{\"provider\":\"myrelay\",\"model\":\"m\",\"apiKey\":\"sk-relay\"}");

    postJson("/api/config", "{\"clearApiKey\":true}");

    assertEquals("none", json("/api/config").path("apiKeySource").asText());
    JsonNode stored = Json.parse(Files.readString(configFile));
    assertFalse(
        stored.path("remembered").has("myrelay"), "被忘掉的密钥绝不能回来：" + stored);
    assertEquals(
        "sk-openai",
        stored.path("remembered").path("openai").path("apiKey").asText(),
        "另一个提供方的密钥不关这个提供方的事：" + stored);
  }

  @Test
  void aKeySavedForTheActiveProviderSaysSoAndSurvivesAnUnrelatedSave() throws Exception {
    postJson(
        "/api/providers",
        "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"https://relay.example.com/v1\"}");
    postJson("/api/config", "{\"provider\":\"myrelay\",\"model\":\"m\"}");
    postJson("/api/config", "{\"apiKey\":\"sk-mine\"}");

    assertEquals("config", json("/api/config").path("apiKeySource").asText());
    assertTrue(json("/api/config").path("usesStoredSettings").asBoolean());
    assertEquals(
        "myrelay",
        Json.parse(Files.readString(configFile)).path("settingsFor").asText(),
        "文件记下了这些端点和密钥是谁的");

    // 之后一次对密钥只字未提的保存会把它留下：它是这个提供方的。
    postJson("/api/config", "{\"reasoning\":\"high\"}");
    assertEquals("config", json("/api/config").path("apiKeySource").asText());
    assertTrue(Files.readString(configFile).contains("sk-mine"));
  }

  @Test
  void removingTheProviderInUseWorksAndSaysWhatItMeans() throws Exception {
    postJson(
        "/api/providers",
        "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"http://127.0.0.1:1/v1\",\"models\":\"m\"}");
    postJson("/api/config", "{\"provider\":\"myrelay\",\"model\":\"m\",\"baseUrl\":\"http://127.0.0.1:1/v1\"}");

    HttpResponse<String> removed =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=myrelay"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, removed.statusCode(), removed.body());
    assertTrue(providerStore.list().isEmpty(), "定义没了");
    assertTrue(
        json("/api/status").path("configured").asBoolean(),
        "正在运行的会话留着它的提供方：它是在被选中时构建的");
  }

  @Test
  void theReasoningTierCanBeChosenChangedAndCleared() throws Exception {
    JsonNode saved = postJson("/api/config", "{\"reasoning\":\"high\"}");

    assertEquals("high", saved.path("reasoning").asText());
    assertEquals(List.of("low", "high", "max"), levels(saved), "选择器提供这些档位");
    assertEquals("high", json("/api/config").path("reasoning").asText());

    assertEquals("max", postJson("/api/config", "{\"reasoning\":\"max\"}").path("reasoning").asText());

    JsonNode cleared = postJson("/api/config", "{\"reasoning\":\"default\"}");
    assertTrue(cleared.path("reasoning").isNull(), "default 意味着由提供方决定");
    assertTrue(json("/api/config").path("reasoning").isNull());

    HttpResponse<String> bad = post("/api/config", "{\"reasoning\":\"turbo\"}");
    assertEquals(400, bad.statusCode(), bad.body());
    assertTrue(bad.body().contains("low, high, max"), bad.body());
  }

  @Test
  void theChosenEffortTierIsCarriedIntoTheNextTurn() throws Exception {
    postJson("/api/config", "{\"reasoning\":\"high\"}");
    provider.reply(Message.Assistant.text("answered"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"think hard\"}");
      sse.await("done", 5000);
    }

    // 这个档位必须走通整条路径：设置 -> 存下来的配置 -> 回合的选项 -> 提供方真正收到的那个
    // 请求。只断言配置抓不到路上被丢掉的参数。
    var requests = lastBuilt.requests();
    assertEquals("high", requests.get(requests.size() - 1).reasoning(), requests.toString());
    assertEquals(1, requests.size(), "一个回合，一个请求");
  }

  @Test
  void aModelAddedToABuiltInProviderIsRemembered() throws Exception {
    // 原话就是这么抱怨的：在一个自己没有定义的提供方底下敲一个模型名。
    JsonNode after = postJson("/api/models", "{\"provider\":\"openai\",\"model\":\"gpt-5-preview\"}");

    JsonNode openai = providerOf(after, "openai");
    assertEquals(List.of("gpt-4o-mini", "gpt-5-preview"), modelsOf(openai), "是添加，不是替换");
    assertTrue(after.path("models").toString().contains("gpt-5-preview"), "被提供为一个可选项");
    assertTrue(
        Files.readString(tmp.resolve("providers.json")).contains("gpt-5-preview"),
        "而且被记了下来，所以它能挺过一次重启");

    // 删掉它是算数的，因为记下来的列表才是权威
    HttpResponse<String> removed =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(origin + "/api/models?provider=openai&model=gpt-5-preview"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, removed.statusCode(), removed.body());
    assertEquals(List.of("gpt-4o-mini"), modelsOf(providerOf(Json.parse(removed.body()), "openai")));
    assertEquals(
        List.of("gpt-4o-mini"),
        modelsOf(providerOf(json("/api/models"), "openai")),
        "下次读取时仍然是没有的");

    // 重建一次存储也不能把它带回来
    assertEquals(List.of("gpt-4o-mini"), modelsOf(providerOf(json("/api/models"), "openai")));
  }

  @Test
  void modelEditsAreValidated() throws Exception {
    assertEquals(400, post("/api/models", "{\"provider\":\"nope\",\"model\":\"m\"}").statusCode());
    assertEquals(400, post("/api/models", "{\"provider\":\"openai\"}").statusCode());
    assertEquals(400, post("/api/models", "{\"model\":\"m\"}").statusCode());
    assertEquals(200, get("/api/models?provider=openai&model=x").statusCode(), "GET 会列出目录");
    assertTrue(providerStore.modelsFor("openai").isEmpty(), "这些拒绝什么都没记下");
  }

  @Test
  void theModelInUseCanStillBeRemovedFromTheOfferList() throws Exception {
    // 这里替掉的那个死锁：唯一被提供的模型也正是正在用的那个，于是它删不掉，也没有别的可切。
    postJson("/api/config", "{\"provider\":\"openai\",\"model\":\"gpt-4o-mini\"}");

    HttpResponse<String> removed =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(origin + "/api/models?provider=openai&model=gpt-4o-mini"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, removed.statusCode(), removed.body());
    assertEquals(
        List.of(), modelsOf(providerOf(Json.parse(removed.body()), "openai")), "不再被建议");
    assertEquals(
        "gpt-4o-mini",
        json("/api/status").path("model").asText(),
        "把它从列表里删掉绝不能改变会话在用的东西");

    // 而且下次读取时它真的没了
    assertEquals(List.of(), modelsOf(providerOf(json("/api/models"), "openai")));
  }

  @Test
  void aBuiltInProviderCanBeDeletedAndAddedBack() throws Exception {
    assertTrue(providerNames(json("/api/models")).contains("groq"), "一开始就在那儿");

    HttpResponse<String> deleted =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=groq")).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, deleted.statusCode(), deleted.body());
    JsonNode afterDelete = Json.parse(deleted.body());
    assertFalse(providerNames(afterDelete).contains("groq"), "从列表里没了");
    assertEquals(
        List.of(), afterDelete.path("hidden").findValuesAsText("hidden"),
        "没有任何东西被记成隐藏——「删掉」就是这个意思");
    assertTrue(availableBuiltIns(afterDelete).contains("groq"), "但它还能再加回来");
    assertFalse(providerNames(json("/api/models")).contains("groq"), "下次读取时仍然是没有的");
    assertFalse(providerStore.shown().contains("groq"), "显式列表里已经没它了");
    // 数量不写死在这里：内置名单会变，而这条断言问的是「其余的都还在」，不是「一共几个」。
    assertEquals(
        com.ccj.agent.provider.Providers.supported().size() - 1,
        providerStore.shown().size(),
        "其余的每一个都还在：" + providerStore.shown());

    // 把一个内置提供方加回来就是一次普通的添加，不是一次恢复。
    HttpResponse<String> added =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/providers"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"groq\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, added.statusCode(), added.body());
    assertTrue(providerNames(Json.parse(added.body())).contains("groq"));
    assertFalse(availableBuiltIns(Json.parse(added.body())).contains("groq"), "只提供一次");

    // 删两次不是错误，添加已经在的东西也不是。
    assertEquals(
        400,
        client.send(
                HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=nope"))
                    .DELETE()
                    .build(),
            HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        400,
        client.send(
                HttpRequest.newBuilder(URI.create(origin + "/api/providers"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"groq\"}"))
                    .build(),
            HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  @Test
  void aUserDefinedProviderIsDeletedOutright() throws Exception {
    postJson(
        "/api/providers",
        "{\"name\":\"mine\",\"kind\":\"openai\",\"baseUrl\":\"http://127.0.0.1:9/v1\",\"models\":\"m\"}");
    assertTrue(providerNames(json("/api/models")).contains("mine"));

    HttpResponse<String> removed =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=mine")).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, removed.statusCode(), removed.body());
    assertFalse(providerNames(Json.parse(removed.body())).contains("mine"));
    assertTrue(providerStore.list().isEmpty(), "定义没了");
    assertEquals(
        List.of(),
        providerStore.shown(),
        "删掉一个定义不会动内置列表：本来就没有一个被缩窄过的列表");
  }

  @Test
  void deletingTheProviderInUseKeepsTheSessionRunning() throws Exception {
    client.send(
        HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=openai")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());

    // 删除是列表层面的决定，不是能力的移除：已配置的提供方继续工作。
    assertEquals("openai", json("/api/status").path("provider").asText());
    assertTrue(json("/api/status").path("configured").asBoolean());
  }

  @Test
  void removingUnknownProvidersAndAddingNonBuiltInsAreRejected() throws Exception {
    HttpResponse<String> unknown =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=nope")).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, unknown.statusCode(), unknown.body());

    HttpResponse<String> notBuiltIn =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/providers"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"whatever\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, notBuiltIn.statusCode(), notBuiltIn.body());
    assertTrue(notBuiltIn.body().contains("'whatever' 不是内置提供方；请改为自定义一个"), notBuiltIn.body());
  }

  @Test
  void aNewDefinitionJoinsAnExplicitListInsteadOfBeingInvisible() throws Exception {
    // 有人报的 bug：用户先把列表缩窄了，然后定义了一个提供方，而它从未出现——定义被保存了，但
    // 列表没有提到它。
    client.send(
        HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=groq")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
    assertFalse(providerNames(json("/api/models")).contains("groq"), "一开始就是缩窄过的");

    JsonNode added =
        postJson(
            "/api/providers",
            "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"http://127.0.0.1:9/v1\",\"models\":\"m1\"}");

    assertTrue(providerNames(added).contains("myrelay"), "已保存并且列出：" + added);
    assertTrue(providerStore.shown().contains("myrelay"), "而且在显式列表里");
    assertTrue(providerNames(json("/api/models")).contains("myrelay"), "下次读取时仍然在");
  }

  @Test
  void deletingADefinitionAlsoLeavesTheExplicitList() throws Exception {
    // 先把列表缩窄，好让「这个列表」是一件真东西，而不是隐含的「所有一切」。
    client.send(
        HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=groq")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
    postJson(
        "/api/providers",
        "{\"name\":\"mine\",\"kind\":\"openai\",\"baseUrl\":\"http://127.0.0.1:9/v1\",\"models\":\"m\"}");
    assertTrue(providerStore.shown().contains("mine"), "一个定义会加入列表：" + providerStore.shown());

    client.send(
        HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=mine")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());

    assertFalse(providerStore.shown().contains("mine"), "被删掉的名字绝不能留着");
    assertFalse(
        providerNames(json("/api/models")).contains("mine"),
        "也不能像一个幽灵内置提供方那样回来");
  }

  @Test
  void removingTheLastProviderLeavesTheListEmptyInsteadOfRestoringThemAll() throws Exception {
    // 那个 bug：存储分不清「从未缩窄」和「我把一切都删了」，于是删掉最后一个提供方被读成「没有
    // 意见」，整个目录又全都冒了出来。
    for (String name : providerNames(json("/api/models"))) {
      assertEquals(
          200,
          client.send(
                  HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=" + name))
                      .DELETE()
                      .build(),
              HttpResponse.BodyHandlers.ofString())
              .statusCode(),
          "删除 " + name);
    }

    JsonNode emptied = json("/api/models");
    assertTrue(providerNames(emptied).isEmpty(), "列表保持为空：" + emptied);
    assertFalse(emptied.path("builtIns").isEmpty(), "而且每个内置提供方仍然作为回去的路被提供");

    // 没有任何东西被记成隐藏：加回来就是一次普通的添加。
    JsonNode restored =
        Json.parse(
            client.send(
                    HttpRequest.newBuilder(URI.create(origin + "/api/providers"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"anthropic\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString())
                .body());
    assertEquals(List.of("anthropic"), providerNames(restored), restored.toString());
  }

  private static List<String> levels(JsonNode config) {
    List<String> levels = new ArrayList<>();
    config.path("reasoningLevels").forEach(level -> levels.add(level.asText()));
    return levels;
  }

  private static List<String> providerNames(JsonNode catalog) {
    List<String> names = new ArrayList<>();
    catalog.path("providers").forEach(entry -> names.add(entry.path("name").asText()));
    return names;
  }

  private static List<String> availableBuiltIns(JsonNode catalog) {
    List<String> names = new ArrayList<>();
    catalog.path("builtIns").forEach(entry -> names.add(entry.asText()));
    return names;
  }

  private static JsonNode providerOf(JsonNode catalog, String name) {
    return lastWhere(catalog.path("providers"), entry -> entry.path("name").asText().equals(name));
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    if (array != null && array.isArray()) {
      array.forEach(entry -> out.add(entry.asText()));
    }
    return out;
  }

  private static List<String> modelsOf(JsonNode provider) {
    List<String> models = new ArrayList<>();
    provider.path("models").forEach(model -> models.add(model.asText()));
    return models;
  }
}
