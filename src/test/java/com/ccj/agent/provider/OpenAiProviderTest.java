package com.ccj.agent.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class OpenAiProviderTest {

  private static final String TOOL_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";
  private static final String TOOL_ARGUMENTS = "{\"path\":\"a\"}";

  /** 每 19 个字符切一刀，所以帧和帧里的 JSON 都会从 token 中间断开。 */
  private static final String STREAM =
      """
      data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}

      data: {"id":"1","choices":[{"index":0,"delta":{"content":"Hel"}}]}

      data: {"id":"1","choices":[{"index":0,"delta":{"content":"lo"}}]}

      data: {"id":"1","choices":[{"index":0,"delta":{"reasoning_content":"th"}}]}

      data: {"id":"1","choices":[{"index":0,"delta":{"reasoning_content":"ink"}}]}

      data: {"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read","arguments":"{\\\"pa"}}]}}]}

      data: {"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\\\":\\\"a\\\"}"}}]}}]}

      data: {"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

      data: {"id":"1","choices":[],"usage":{"prompt_tokens":11,"completion_tokens":7}}

      data: [DONE]

      """;

  private static final String EXPECTED_REQUEST =
      """
      {
        "model": "gpt-test",
        "messages": [
          {"role": "system", "content": "be nice"},
          {"role": "user", "content": "hi"},
          {"role": "assistant", "content": "calling", "tool_calls": [
            {"id": "call_1", "type": "function",
             "function": {"name": "read", "arguments": "{\\\"path\\\":\\\"a\\\"}"}}
          ]},
          {"role": "tool", "tool_call_id": "call_1", "content": "boom (error)"}
        ],
        "tools": [
          {"type": "function", "function": {
            "name": "read", "description": "Read a file",
            "parameters": {"type": "object", "properties": {"path": {"type": "string"}}}}}
        ],
        "stream": true,
        "stream_options": {"include_usage": true},
        "temperature": 0.5,
        "max_tokens": 128
      }
      """;

  @Test
  void sendsTheDocumentedRequestBody() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(STREAM))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");

      Message.Assistant assistant = provider.complete(request(), event -> {});

      assertEquals("Hello", assistant.text());
      assertEquals(
          List.of(new Message.ToolCall("call_1", "read", TOOL_ARGUMENTS)), assistant.toolCalls());
      assertEquals(Json.parse(EXPECTED_REQUEST), Json.parse(server.body(0)));
      assertEquals("/chat/completions", server.path(0));
      assertEquals("Bearer sk-test", server.header(0, "authorization"));
      assertEquals(1, server.count());
      provider.close();
    }
  }

  @Test
  void streamsOnlyRealDeltasAndAnnouncesToolCalls() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(STREAM))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");
      List<Provider.Event> events = new ArrayList<>();

      Message.Assistant assistant = provider.complete(request(), events::add);

      assertEquals(
          List.of(
              new Provider.Event.TextDelta("Hel"),
              new Provider.Event.TextDelta("lo"),
              new Provider.Event.ReasoningDelta("th"),
              new Provider.Event.ReasoningDelta("ink"),
              new Provider.Event.ToolCallStart("call_1", "read"),
              new Provider.Event.Usage(11, 7)),
          events);
      assertEquals(
          new Message.ToolCall("call_1", "read", TOOL_ARGUMENTS), assistant.toolCalls().get(0));
      provider.close();
    }
  }

  @Test
  void fragmentsWithoutAnIndexStaySeparateCalls() throws Exception {
    // 好几个 OpenAI 兼容服务器会省掉 `index`。把它默认成 0，会把每次并行调用都折进第一次里：一个未知工
    // 具，参数是两次调用拼起来的。
    String stream =
        """
        data: {"choices":[{"delta":{"tool_calls":[{"id":"call_a","function":{"name":"read","arguments":"{\\\"path\\\":\\\"a\\\"}"}}]}}]}

        data: {"choices":[{"delta":{"tool_calls":[{"id":"call_b","function":{"name":"write","arguments":"{\\\"path\\\":\\\"b\\\"}"}}]}}]}

        data: [DONE]

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");

      Message.Assistant assistant =
          provider.complete(
              new Provider.Request(
                  "gpt-test", null, List.of(new Message.User("hi")), List.of(), null, null, null),
              event -> {});

      assertEquals(
          List.of(
              new Message.ToolCall("call_a", "read", "{\"path\":\"a\"}"),
              new Message.ToolCall("call_b", "write", "{\"path\":\"b\"}")),
          assistant.toolCalls());
      provider.close();
    }
  }

  @Test
  void aHugeErrorPageIsNotHeldInMemoryToBeTruncated() throws Exception {
    // 那个 bug：错误响应体被完整收下来、格式化时才截断，于是那道显示上限本想省下的内存早就花掉了。一个返回
    // 大 HTML 错误页的网关——代理的启动画面、配置错误的 nginx——在被丢掉之前被整段读完了。
    //
    // 断言的是到达调用方的东西，而不是堆大小，因为消息才是测试看得见的那部分：有界读取产出的响应体会被截断并且
    // 明说，而无界读取本会产出整页。
    String enormous = "x".repeat(2_000_000);
    try (FakeServer server = FakeServer.start(FakeServer.Reply.json(500, enormous))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");

      Exception failure =
          assertThrows(
              Exception.class,
              () ->
                  provider.complete(
                      new Provider.Request(
                          "gpt-test", null, List.of(new Message.User("hi")), List.of(), null, null,
                          null),
                      event -> {}));

      String message = failure.getMessage();
      assertTrue(message.contains("已截断"), "截断有明确告知：" + message.length());
      assertTrue(
          message.length() < 4_000,
          "消息里是一段有界的响应体，而不是两兆字节：" + message.length());
    }
  }

  void aRepeatedIdWithoutAnIndexContinuesTheCallInsteadOfStartingANewOne() throws Exception {
    // 一个省掉 `index`、转发它构建出的整个调用对象的服务器，会在每个片段上重复这个 id。把它读成第二次调
    // 用，会把一次调用拆成两个：真的那个拿着被截断的参数，旁边还有一个连名字都没有的调用被唤起。
    String stream =
        """
        data: {"choices":[{"delta":{"tool_calls":[{"id":"call_a","function":{"name":"read","arguments":"{\\\"path\\\":"}}]}}]}

        data: {"choices":[{"delta":{"tool_calls":[{"id":"call_a","function":{"arguments":"\\\"a\\\"}"}}]}}]}

        data: [DONE]

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");

      Message.Assistant assistant =
          provider.complete(
              new Provider.Request(
                  "gpt-test", null, List.of(new Message.User("hi")), List.of(), null, null, null),
              event -> {});

      assertEquals(
          List.of(new Message.ToolCall("call_a", "read", "{\"path\":\"a\"}")),
          assistant.toolCalls());
      provider.close();
    }
  }

  @Test
  void aTwoHundredThatIsNotAnEventStreamIsNotAnEmptyAnswer() throws Exception {
    // 上游失败的中继会用 JSON 回一个 200。帧解码器在其中找不到任何东西，所以如果不说一句收到的是什么，这
    // 次运行就会带着一个空回答「成功」结束。
    try (FakeServer server =
        FakeServer.start(FakeServer.Reply.json(200, "{\"error\":{\"message\":\"upstream exploded\"}}"))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");

      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class, () -> provider.complete(request(), event -> {}));

      assertTrue(failure.getMessage().contains("没有返回任何事件"), failure.getMessage());
      assertTrue(failure.getMessage().contains("upstream exploded"), failure.getMessage());
      provider.close();
    }
  }

  @Test
  void aNullUsageFieldIsNotZeroTokens() throws Exception {
    String stream =
        """
        data: {"choices":[{"delta":{"content":"hi"}}],"usage":{"prompt_tokens":null,"completion_tokens":null}}

        data: [DONE]

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");
      List<Provider.Event> events = new ArrayList<>();

      provider.complete(request(), events::add);

      assertTrue(
          events.stream().noneMatch(Provider.Event.Usage.class::isInstance),
          "显式为 null 的计数不是一次测量：" + events);
      provider.close();
    }
  }

  @Test
  void omitsToolsAndSamplingFieldsThatWereNotRequested() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");
      Provider.Request minimal =
          new Provider.Request(
              "gpt-mini", null, List.of(new Message.User("hi")), List.of(), null, null, null);

      provider.complete(minimal, event -> {});

      JsonNode sent = Json.parse(server.body(0));
      assertEquals(
          Json.parse(
              "{\"model\":\"gpt-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                  + "\"stream\":true,\"stream_options\":{\"include_usage\":true}}"),
          sent);
      assertFalse(sent.has("tools"));
      assertFalse(sent.has("temperature"));
      assertFalse(sent.has("max_tokens"));
      provider.close();
    }
  }

  @Test
  void reportsStatusAndBodyForClientErrors() throws Exception {
    try (FakeServer server =
        FakeServer.start(FakeServer.Reply.json(400, "{\"error\":{\"message\":\"bad model\"}}"))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");

      Exception failure =
          assertThrows(Exception.class, () -> provider.complete(request(), event -> {}));

      assertTrue(failure.getMessage().contains("400"), failure.getMessage());
      assertTrue(failure.getMessage().contains("bad model"), failure.getMessage());
      provider.close();
    }
  }

  @Test
  void doesNotRetryAClientError() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.status(400))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");

      assertThrows(Exception.class, () -> provider.complete(request(), event -> {}));

      assertEquals(1, server.count());
      provider.close();
    }
  }

  @Test
  void aThrottledEndpointDecidesHowLongToWait() throws Exception {
    // 一个说明该等多久的服务器是认真的：更早重试，正是 429 变成封禁的原因。
    try (FakeServer server =
        FakeServer.start(
            FakeServer.Reply.json(429, "{}", java.util.Map.of("Retry-After", "1")),
            FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");
      List<Provider.Event> events = new ArrayList<>();

      provider.complete(request(), events::add);

      List<Provider.Event.Retry> retries =
          events.stream()
              .filter(Provider.Event.Retry.class::isInstance)
              .map(Provider.Event.Retry.class::cast)
              .toList();
      assertEquals(1, retries.size());
      assertEquals(1000, retries.get(0).delayMillis(), "首部胜过退避曲线");
      assertEquals(2, server.count());
      provider.close();
    }
  }

  @Test
  void retriesAfterAThrottledResponse() throws Exception {
    try (FakeServer server =
        FakeServer.start(FakeServer.Reply.status(429), FakeServer.Reply.sse(STREAM))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");
      List<Provider.Event> events = new ArrayList<>();

      Message.Assistant assistant = provider.complete(request(), events::add);

      assertEquals("Hello", assistant.text());
      assertEquals(2, server.count());
      List<Provider.Event.Retry> retries =
          events.stream()
              .filter(Provider.Event.Retry.class::isInstance)
              .map(Provider.Event.Retry.class::cast)
              .toList();
      assertEquals(1, retries.size());
      assertEquals(1, retries.get(0).attempt());
      assertTrue(retries.get(0).delayMillis() > 0);
      provider.close();
    }
  }

  @Test
  void givesUpAfterTheAttemptBudgetWithTheServerBody() throws Exception {
    try (FakeServer server =
        FakeServer.start(FakeServer.Reply.json(429, "{\"error\":{\"message\":\"slow down\"}}"))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");
      List<Provider.Event> events = new ArrayList<>();

      Exception failure =
          assertThrows(Exception.class, () -> provider.complete(request(), events::add));

      assertTrue(failure.getMessage().contains("429"), failure.getMessage());
      assertTrue(failure.getMessage().contains("slow down"), failure.getMessage());
      assertEquals(3, server.count());
      assertEquals(2, events.stream().filter(Provider.Event.Retry.class::isInstance).count());
      provider.close();
    }
  }

  private static Provider.Request request() {
    return new Provider.Request(
        "gpt-test",
        "be nice",
        List.of(
            new Message.User("hi"),
            new Message.Assistant(
                "calling", List.of(new Message.ToolCall("call_1", "read", TOOL_ARGUMENTS))),
            new Message.ToolResult("call_1", "read", "boom", true)),
        List.of(new ToolSpec("read", "Read a file", TOOL_SCHEMA)),
        0.5,
        128, null);
  }

  @Test
  void reportsCachedPromptTokensWhenTheEndpointSendsThem() throws Exception {
    String stream =
        """
        data: {"choices":[{"index":0,"delta":{"content":"hi"}}]}

        data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":80}}}

        data: [DONE]

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");
      List<Provider.Event> events = new ArrayList<>();

      provider.complete(request(), events::add);

      assertTrue(events.contains(new Provider.Event.Usage(100, 5, 80)), events.toString());
      provider.close();
    }
  }

  @Test
  void understandsDeepSeekStyleCacheFields() throws Exception {
    String stream =
        """
        data: {"choices":[{"index":0,"delta":{"content":"hi"}}],"usage":{"prompt_tokens":50,"completion_tokens":2,"prompt_cache_hit_tokens":40}}

        data: [DONE]

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");
      List<Provider.Event> events = new ArrayList<>();

      provider.complete(request(), events::add);

      assertTrue(events.contains(new Provider.Event.Usage(50, 2, 40)), events.toString());
      provider.close();
    }
  }

  @Test
  void anEndpointThatReportsNoCacheLeavesItUnknown() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(STREAM))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");
      List<Provider.Event> events = new ArrayList<>();

      provider.complete(request(), events::add);

      Provider.Event.Usage usage =
          (Provider.Event.Usage)
              events.stream().filter(Provider.Event.Usage.class::isInstance).findFirst().orElseThrow();
      assertNull(
          usage.cachedInputTokens(),
          "没有缓存字段就意味着未知，绝不能渲染成 0%");
      provider.close();
    }
  }

  @Test
  void theReasoningTierBecomesReasoningEffortAndTheTopTierAlsoRaisesTheBudget() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");

      provider.complete(reasoningRequest("low"), event -> {});
      JsonNode low = Json.parse(server.body(0));
      assertEquals("low", low.path("reasoning_effort").asText());
      assertFalse(low.has("max_completion_tokens"), low.toString());

      provider.complete(reasoningRequest("high"), event -> {});
      assertEquals("high", Json.parse(server.body(1)).path("reasoning_effort").asText());

      provider.complete(reasoningRequest("max"), event -> {});
      JsonNode max = Json.parse(server.body(2));
      assertEquals("high", max.path("reasoning_effort").asText(), "协议最高只到 high");
      assertEquals(32768, max.path("max_completion_tokens").asInt(), "max 还买来了思考的空间");
      assertFalse(max.has("max_tokens"), "两个上限不会一起发");

      provider.close();
    }
  }

  @Test
  void withoutATierNothingAboutReasoningIsSent() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");

      provider.complete(request(), event -> {});

      JsonNode body = Json.parse(server.body(0));
      assertFalse(body.has("reasoning_effort"), body.toString());
      assertEquals(128, body.path("max_tokens").asInt(), "普通的上限保持原样");
      provider.close();
    }
  }

  private Provider.Request reasoningRequest(String level) {
    return new Provider.Request(
        "gpt-test", "be nice", List.of(new Message.User("hi")), List.of(toolSpec()), 0.5, 128, level);
  }

  private static ToolSpec toolSpec() {
    return new ToolSpec("read", "Read a file", TOOL_SCHEMA);
  }
}
