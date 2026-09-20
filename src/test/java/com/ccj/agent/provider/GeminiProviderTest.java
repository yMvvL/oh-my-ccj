package com.ccj.agent.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeminiProviderTest {

  private static final String TOOL_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"}}}";
  private static final String TOOL_ARGUMENTS = "{\"pattern\":\"*.java\"}";

  /**
   * 每 19 个字符切一刀，所以帧和帧里的 JSON 都会从 token 中间断开；用量在每个 chunk 上重复出现，而输出计数
   * 只在最后一个 chunk 上长齐。
   */
  private static final String STREAM =
      """
      data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Hel"}]}}]}

      data: {"candidates":[{"content":{"role":"model","parts":[{"text":"lo"}]}}],"usageMetadata":{"promptTokenCount":11}}

      data: {"candidates":[{"content":{"role":"model","parts":[{"text":"think","thought":true}]}}]}

      data: {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"read","args":{"pattern":"*.java"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":7}}

      """;

  private static final String EXPECTED_REQUEST =
      """
      {
        "systemInstruction": {"parts": [{"text": "be nice"}]},
        "contents": [
          {"role": "user", "parts": [{"text": "hi"}]},
          {"role": "model", "parts": [
            {"text": "calling"},
            {"functionCall": {"name": "read", "args": {"pattern": "*.java"}, "id": "call_7"}}
          ]},
          {"role": "user", "parts": [
            {"functionResponse": {"name": "read", "id": "call_7", "response": {"error": "boom"}}},
            {"functionResponse": {"name": "glob", "id": "call_8", "response": {"output": "ok"}}},
            {"text": "next"}
          ]}
        ],
        "tools": [{"functionDeclarations": [
          {"name": "read", "description": "Read a file",
           "parameters": {"type": "object", "properties": {"pattern": {"type": "string"}}}}
        ]}],
        "generationConfig": {"maxOutputTokens": 128, "temperature": 0.7}
      }
      """;

  @Test
  void sendsTheDocumentedRequestBody() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(STREAM))) {
      GeminiProvider provider = new GeminiProvider(server.url(), "key-test");

      Message.Assistant assistant = provider.complete(request(), event -> {});

      assertEquals("Hello", assistant.text());
      assertEquals(
          List.of(new Message.ToolCall("call_1", "read", TOOL_ARGUMENTS)),
          assistant.toolCalls(),
          "模型没给 id 的调用按本回合顺序编一个，工具结果回来才找得着它");
      assertEquals(Json.parse(EXPECTED_REQUEST), Json.parse(server.body(0)));
      assertEquals("/v1beta/models/gemini-test:streamGenerateContent", server.path(0));
      assertEquals("alt=sse", server.query(0), "流式端点靠在 URL 上要 alt=sse");
      assertEquals("key-test", server.header(0, "x-goog-api-key"));
      assertEquals(1, server.count());
      provider.close();
    }
  }

  @Test
  void reassemblesPartsAndReportsUsageOnce() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(STREAM))) {
      GeminiProvider provider = new GeminiProvider(server.url(), "key-test");
      List<Provider.Event> events = new ArrayList<>();

      provider.complete(request(), events::add);

      assertEquals(
          List.of(
              new Provider.Event.TextDelta("Hel"),
              new Provider.Event.TextDelta("lo"),
              new Provider.Event.ReasoningDelta("think"),
              new Provider.Event.ToolCallStart("call_1", "read"),
              new Provider.Event.Usage(11, 7)),
          events,
          "文本跨 chunk 拼回来，带 thought 标志的 part 是推理而不是回答，而用量在整个回合只播报一次");
      provider.close();
    }
  }

  @Test
  void cachedPromptTokensAreReportedWhenTheEndpointNamesThem() throws Exception {
    String stream =
        """
        data: {"candidates":[{"content":{"role":"model","parts":[{"text":"hi"}]}}],"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":4,"cachedContentTokenCount":80}}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      GeminiProvider provider = new GeminiProvider(server.url(), "key-test");
      List<Provider.Event> events = new ArrayList<>();

      provider.complete(request(), events::add);

      assertTrue(events.contains(new Provider.Event.Usage(100, 4, 80)), events.toString());
      provider.close();
    }
  }

  @Test
  void anAbsentCacheFieldLeavesCachingUnreported() throws Exception {
    // 「没有上报」和「缓存了零个」是两个不同的事实，不能都渲染成 0% 命中率。
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(STREAM))) {
      GeminiProvider provider = new GeminiProvider(server.url(), "key-test");
      List<Provider.Event> events = new ArrayList<>();

      provider.complete(request(), events::add);

      long usages = events.stream().filter(Provider.Event.Usage.class::isInstance).count();
      assertEquals(1, usages, "重复出现的 usageMetadata 只算一次：" + events);
      assertEquals(
          new Provider.Event.Usage(11, 7, null),
          events.stream()
              .filter(Provider.Event.Usage.class::isInstance)
              .findFirst()
              .orElseThrow(),
          "端点没报缓存字段时，缓存保持为 null");
      provider.close();
    }
  }

  @Test
  void unknownPartsAreIgnored() throws Exception {
    // 前向兼容：一个将来才有的 part 种类，或者一次被中继插入的 inlineData，都不该让整个回合失败。
    String stream =
        """
        data: {"candidates":[{"content":{"role":"model","parts":[{"inlineData":{"mimeType":"image/png","data":"AAAA"}},{"text":"ok"},{"executableCode":{"language":"PYTHON","code":"1"}}]}}],"modelVersion":"gemini-x"}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      GeminiProvider provider = new GeminiProvider(server.url(), "key-test");

      Message.Assistant assistant = provider.complete(request(), event -> {});

      assertEquals("ok", assistant.text());
      assertEquals(List.of(), assistant.toolCalls());
      provider.close();
    }
  }

  @Test
  void anUnparseableToolCallStillLeavesASendableRequest() throws Exception {
    // 一次在调用中途被切断的流会留下只写了一半的参数。在构建*下一个*请求时抛出会让这条会话搁浅：之后每个回合
    // 都会重建同一段历史。
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      GeminiProvider provider = new GeminiProvider(server.url(), "key-test");
      Provider.Request broken =
          new Provider.Request(
              "gemini-test",
              null,
              List.of(
                  new Message.User("hi"),
                  new Message.Assistant(
                      "", List.of(new Message.ToolCall("call_1", "read", "{\"pattern\": \"*"))),
                  new Message.ToolResult("call_1", "read", "invalid arguments", true)),
              List.of(),
              null,
              null,
              null);

      provider.complete(broken, event -> {});

      assertEquals(
          Json.parse("{}"),
          Json.parse(server.body(0))
              .path("contents")
              .path(1)
              .path("parts")
              .path(0)
              .path("functionCall")
              .path("args"),
          "坏掉的参数串会变成空对象，而不是一个死掉的会话");
      provider.close();
    }
  }

  @Test
  void adjacentTurnsOfTheSameRoleShareOneContent() throws Exception {
    // 这个 API 要求 user 与 model 交替，所以一段「工具结果 + 用户追问」的历史必须落进同一个 Content；各发一条
    // 会得到 400 INVALID_ARGUMENT。
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      GeminiProvider provider = new GeminiProvider(server.url(), "key-test");
      Provider.Request merged =
          new Provider.Request(
              "gemini-test",
              null,
              List.of(
                  new Message.User("one"),
                  new Message.Summary("what happened", 2, "auto"),
                  new Message.User("two")),
              List.of(),
              null,
              null,
              null);

      provider.complete(merged, event -> {});

      assertEquals(
          Json.parse(
              """
              [{"role": "user", "parts": [{"text": "one"}, {"text": "what happened"},
                                          {"text": "two"}]}]
              """),
          Json.parse(server.body(0)).path("contents"));
      provider.close();
    }
  }

  @Test
  void aQualifiedModelNameIsNotPrefixedTwice() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      GeminiProvider provider = new GeminiProvider(server.url(), "key-test");

      provider.complete(
          new Provider.Request(
              "models/gemini-2.5-flash",
              null,
              List.of(new Message.User("hi")),
              List.of(),
              null,
              null,
              null),
          event -> {});

      assertEquals("/v1beta/models/gemini-2.5-flash:streamGenerateContent", server.path(0));
      provider.close();
    }
  }

  @Test
  void omitsSystemToolsAndSamplingFieldsThatWereNotRequested() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      GeminiProvider provider = new GeminiProvider(server.url(), "key-test");
      Provider.Request minimal =
          new Provider.Request(
              "gemini-mini", null, List.of(new Message.User("hi")), List.of(), null, null, null);

      provider.complete(minimal, event -> {});

      JsonNode sent = Json.parse(server.body(0));
      assertEquals(
          Json.parse(
              "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}]}"), sent);
      assertFalse(sent.has("systemInstruction"));
      assertFalse(sent.has("tools"));
      assertFalse(sent.has("generationConfig"));
      provider.close();
    }
  }

  @Test
  void theReasoningTierBecomesAThinkingBudget() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      GeminiProvider provider = new GeminiProvider(server.url(), "key-test");

      provider.complete(reasoningRequest("low"), event -> {});
      JsonNode low = Json.parse(server.body(0)).path("generationConfig");
      assertEquals(2048, low.path("thinkingConfig").path("thinkingBudget").asInt());
      assertTrue(low.path("thinkingConfig").path("includeThoughts").asBoolean());

      provider.complete(reasoningRequest("max"), event -> {});
      JsonNode max = Json.parse(server.body(1)).path("generationConfig");
      assertEquals(32768, max.path("thinkingConfig").path("thinkingBudget").asInt());
      assertTrue(
          max.path("maxOutputTokens").asInt() > 32768,
          "maxOutputTokens 要容得下思考预算和回答：" + max.path("maxOutputTokens").asInt());

      provider.complete(request(), event -> {});
      assertFalse(
          Json.parse(server.body(2)).path("generationConfig").has("thinkingConfig"),
          "没有档位就不要碰思考配置");
      provider.close();
    }
  }

  @Test
  void turnsErrorPayloadsIntoExceptions() throws Exception {
    String stream =
        """
        data: {"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      GeminiProvider provider = new GeminiProvider(server.url(), "key-test");

      Exception failure =
          assertThrows(Exception.class, () -> provider.complete(request(), event -> {}));

      assertTrue(failure.getMessage().contains("API key not valid"), failure.getMessage());
      provider.close();
    }
  }

  @Test
  void reportsStatusAndBodyForClientErrors() throws Exception {
    try (FakeServer server =
        FakeServer.start(
            FakeServer.Reply.json(400, "{\"error\":{\"message\":\"bad model\"}}"))) {
      GeminiProvider provider = new GeminiProvider(server.url(), "key-test");

      Exception failure =
          assertThrows(Exception.class, () -> provider.complete(request(), event -> {}));

      assertTrue(failure.getMessage().contains("400"), failure.getMessage());
      assertTrue(failure.getMessage().contains("bad model"), failure.getMessage());
      assertEquals(1, server.count());
      provider.close();
    }
  }

  @Test
  void aTwoHundredThatIsNotAnEventStreamIsNotAnEmptyAnswer() throws Exception {
    try (FakeServer server =
        FakeServer.start(
            FakeServer.Reply.json(200, "{\"error\":{\"message\":\"upstream exploded\"}}"))) {
      GeminiProvider provider = new GeminiProvider(server.url(), "key-test");

      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class, () -> provider.complete(request(), event -> {}));

      assertTrue(failure.getMessage().contains("没有返回任何事件"), failure.getMessage());
      assertTrue(failure.getMessage().contains("upstream exploded"), failure.getMessage());
      provider.close();
    }
  }

  private static Provider.Request request() {
    return new Provider.Request(
        "gemini-test",
        "be nice",
        List.of(
            new Message.User("hi"),
            new Message.Assistant(
                "calling", List.of(new Message.ToolCall("call_7", "read", TOOL_ARGUMENTS))),
            new Message.ToolResult("call_7", "read", "boom", true),
            new Message.ToolResult("call_8", "glob", "ok", false),
            new Message.User("next")),
        List.of(new ToolSpec("read", "Read a file", TOOL_SCHEMA)),
        0.7,
        128,
        null);
  }

  private static Provider.Request reasoningRequest(String level) {
    return new Provider.Request(
        "gemini-test", null, List.of(new Message.User("hi")), List.of(), null, 4096, level);
  }
}
