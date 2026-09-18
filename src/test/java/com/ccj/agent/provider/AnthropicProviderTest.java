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
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnthropicProviderTest {

  private static final String TOOL_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"}}}";
  private static final String TOOL_ARGUMENTS = "{\"pattern\":\"*.java\"}";

  /** 每 19 个字符切一刀，所以帧和帧里的 JSON 都会从 token 中间断开。 */
  private static final String STREAM =
      """
      event: message_start
      data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":9,"output_tokens":1}}}

      event: content_block_start
      data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

      event: content_block_delta
      data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

      event: content_block_delta
      data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}

      event: content_block_stop
      data: {"type":"content_block_stop","index":0}

      event: ping
      data: {"type":"ping"}

      event: content_block_start
      data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"glob"}}

      event: content_block_delta
      data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"pat"}}

      event: content_block_delta
      data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"tern\\":\\"*.java\\"}"}}

      event: content_block_stop
      data: {"type":"content_block_stop","index":1}

      event: message_delta
      data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}

      event: message_stop
      data: {"type":"message_stop"}

      """;

  private static final String EXPECTED_REQUEST =
      """
      {
        "model": "claude-test",
        "max_tokens": 4096,
        "system": [{"type": "text", "text": "be nice", "cache_control": {"type": "ephemeral"}}],
        "messages": [
          {"role": "user", "content": "hi"},
          {"role": "assistant", "content": [
            {"type": "text", "text": "calling"},
            {"type": "tool_use", "id": "toolu_1", "name": "read", "input": {"pattern": "*.java"}}
          ]},
          {"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": "toolu_1", "content": "boom", "is_error": true},
            {"type": "tool_result", "tool_use_id": "toolu_2", "content": "ok", "is_error": false}
          ]},
          {"role": "user", "content": [
            {"type": "text", "text": "next", "cache_control": {"type": "ephemeral"}}
          ]}
        ],
        "tools": [
          {"name": "read", "description": "Read a file",
           "input_schema": {"type": "object", "properties": {"pattern": {"type": "string"}}},
           "cache_control": {"type": "ephemeral"}}
        ],
        "stream": true,
        "temperature": 0.7
      }
      """;

  @Test
  void sendsTheDocumentedRequestBody() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(STREAM))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");

      Message.Assistant assistant = provider.complete(request(), event -> {});

      assertEquals("Hello", assistant.text());
      assertEquals(
          List.of(new Message.ToolCall("toolu_1", "glob", TOOL_ARGUMENTS)), assistant.toolCalls());
      assertEquals(Json.parse(EXPECTED_REQUEST), Json.parse(server.body(0)));
      assertEquals("/v1/messages", server.path(0));
      assertEquals("sk-ant-test", server.header(0, "x-api-key"));
      assertEquals("2023-06-01", server.header(0, "anthropic-version"));
      assertEquals(1, server.count());
      provider.close();
    }
  }

  @Test
  void reassemblesBlocksAndReportsUsageOnce() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(STREAM))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");
      List<Provider.Event> events = new ArrayList<>();

      Message.Assistant assistant = provider.complete(request(), events::add);

      assertEquals(
          List.of(
              new Provider.Event.TextDelta("Hel"),
              new Provider.Event.TextDelta("lo"),
              new Provider.Event.ToolCallStart("toolu_1", "glob"),
              new Provider.Event.Usage(9, 5)),
          events);
      assertEquals(TOOL_ARGUMENTS, assistant.toolCalls().get(0).arguments());
      provider.close();
    }
  }

  @Test
  void omitsSystemToolsAndSamplingFieldsThatWereNotRequested() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");
      Provider.Request minimal =
          new Provider.Request(
              "claude-mini", null, List.of(new Message.User("hi")), List.of(), null, 512, null);

      provider.complete(minimal, event -> {});

      JsonNode sent = Json.parse(server.body(0));
      assertEquals(
          Json.parse(
              "{\"model\":\"claude-mini\",\"max_tokens\":512,"
                  + "\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\","
                  + "\"text\":\"hi\",\"cache_control\":{\"type\":\"ephemeral\"}}]}],"
                  + "\"stream\":true}"),
          sent);
      assertFalse(sent.has("system"));
      assertFalse(sent.has("tools"));
      assertFalse(sent.has("temperature"));
      provider.close();
    }
  }

  @Test
  void turnsErrorEventsIntoExceptions() throws Exception {
    String script =
        """
        event: message_start
        data: {"type":"message_start","message":{"usage":{"input_tokens":3,"output_tokens":0}}}

        event: error
        data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(script))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");

      Exception failure =
          assertThrows(Exception.class, () -> provider.complete(request(), event -> {}));

      assertTrue(failure.getMessage().contains("Overloaded"), failure.getMessage());
      provider.close();
    }
  }

  @Test
  void reportsStatusAndBodyForClientErrors() throws Exception {
    try (FakeServer server =
        FakeServer.start(FakeServer.Reply.json(400, "{\"error\":{\"message\":\"bad model\"}}"))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");

      Exception failure =
          assertThrows(Exception.class, () -> provider.complete(request(), event -> {}));

      assertTrue(failure.getMessage().contains("400"), failure.getMessage());
      assertTrue(failure.getMessage().contains("bad model"), failure.getMessage());
      assertEquals(1, server.count());
      provider.close();
    }
  }

  @Test
  void givesUpAfterTheAttemptBudgetWithTheServerBody() throws Exception {
    try (FakeServer server =
        FakeServer.start(FakeServer.Reply.json(429, "{\"error\":{\"message\":\"slow down\"}}"))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");
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

  @Test
  void aTruncatedToolCallStillLeavesASendableRequest() throws Exception {
    // 一次在调用中途被切断的流会留下只写了一半的参数。在构建*下一个*请求时抛出会让这个会话搁浅：之后每个
    // 回合都会重建同一段历史。
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");
      Provider.Request request =
          new Provider.Request(
              "claude-test",
              null,
              List.of(
                  new Message.User("hi"),
                  new Message.Assistant(
                      "", List.of(new Message.ToolCall("toolu_1", "read", "{\"path\": \"oops"))),
                  new Message.ToolResult("toolu_1", "read", "invalid arguments", true)),
              List.of(),
              null,
              null,
              null);

      provider.complete(request, event -> {});

      assertEquals(
          Json.parse("{}"),
          Json.parse(server.body(0)).path("messages").path(1).path("content").path(0).path("input"),
          "无法解析的参数串会变成空 input，而不是一个死掉的会话");
      provider.close();
    }
  }

  @Test
  void textCarriedInTheOpeningBlockIsNotDropped() throws Exception {
    String script =
        """
        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Hi"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" there"}}

        event: message_stop
        data: {"type":"message_stop"}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(script))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");
      List<Provider.Event> events = new ArrayList<>();

      Message.Assistant assistant =
          provider.complete(
              new Provider.Request(
                  "claude-test", null, List.of(new Message.User("hi")), List.of(), null, null, null),
              events::add);

      assertEquals("Hi there", assistant.text());
      assertEquals(
          List.of(new Provider.Event.TextDelta("Hi"), new Provider.Event.TextDelta(" there")),
          events);
      provider.close();
    }
  }

  @Test
  void thinkingBlocksAreKeptAndHandedBackWhenThinkingIsOn() throws Exception {
    // API 会校验交给它的每一个思考块的签名，并拒绝丢掉模型产出的那些块的回合；留住它们，就是一段能用的扩展
    // 思考对话和一段在第二次请求上就失败的对话之间的区别。
    String script =
        """
        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me look"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-abc"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: content_block_start
        data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_9","name":"read"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"a\\"}"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":1}

        event: message_stop
        data: {"type":"message_stop"}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(script))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");

      Message.Assistant assistant = provider.complete(reasoningRequest("high"), event -> {});

      assertEquals(
          List.of(new Message.Thinking("Let me look", "sig-abc", "")),
          assistant.thinking(),
          "这个块和它的签名跟着回合一起走");

      // 而下一个请求会把它交还回去，放在那个助手回合里所有内容之前。
      provider.complete(
          new Provider.Request(
              "claude-test",
              null,
              List.of(new Message.User("hi"), assistant, new Message.ToolResult("toolu_9", "read", "ok", false)),
              List.of(),
              null,
              null,
              "high"),
          event -> {});

      JsonNode sent = Json.parse(server.body(1));
      JsonNode assistantTurn = sent.path("messages").path(1);
      assertEquals("assistant", assistantTurn.path("role").asText());
      assertEquals(
          Json.parse(
              """
              [
                {"type": "thinking", "thinking": "Let me look", "signature": "sig-abc"},
                {"type": "tool_use", "id": "toolu_9", "name": "read", "input": {"path": "a"}}
              ]
              """),
          assistantTurn.path("content"),
          "思考在前，然后是它推理出的那次调用");
      provider.close();
    }
  }

  @Test
  void thinkingBlocksAreNotReplayedWhenThinkingIsOff() throws Exception {
    // 这些块属于一个请求已经不再要求的设置；照发不误，正是把档位切回 `default` 的用户会得到一个被拒回合的
    // 原因。
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");
      Message.Assistant withThinking =
          new Message.Assistant(
              "answer", List.of(), List.of(new Message.Thinking("thought", "sig", "")));

      provider.complete(
          new Provider.Request(
              "claude-test",
              null,
              List.of(withThinking),
              List.of(),
              null,
              null,
              null),
          event -> {});

      JsonNode content = Json.parse(server.body(0)).path("messages").path(0).path("content");
      assertEquals(
          Json.parse(
              "[{\"type\": \"text\", \"text\": \"answer\","
                  + " \"cache_control\": {\"type\": \"ephemeral\"}}]"),
          content,
          "没有推理档位就不带思考块");
      provider.close();
    }
  }

  @Test
  void anUnsignedThinkingBlockIsNotReplayed() throws Exception {
    // 在签名到达之前被切断的流会留下没有签名的文本；API 会拒绝它，所以宁可丢掉也不发出去。
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");
      Message.Assistant unsigned =
          new Message.Assistant(
              "answer", List.of(), List.of(new Message.Thinking("half a thought", "", "")));

      provider.complete(reasoningRequest("low"), event -> {});

      provider.complete(
          new Provider.Request(
              "claude-test", null, List.of(unsigned), List.of(), null, null, "low"),
          event -> {});

      JsonNode content = Json.parse(server.body(1)).path("messages").path(0).path("content");
      assertEquals(
          Json.parse(
              "[{\"type\": \"text\", \"text\": \"answer\","
                  + " \"cache_control\": {\"type\": \"ephemeral\"}}]"),
          content,
          content.toString());
      provider.close();
    }
  }

  @Test
  void aRedactedThinkingBlockIsKeptVerbatim() throws Exception {
    String script =
        """
        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"redacted_thinking","data":"opaque-1"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: message_stop
        data: {"type":"message_stop"}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(script))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");

      Message.Assistant assistant = provider.complete(reasoningRequest("high"), event -> {});

      assertEquals(List.of(Message.Thinking.redacted("opaque-1")), assistant.thinking());

      provider.complete(
          new Provider.Request(
              "claude-test", null, List.of(assistant), List.of(), null, null, "high"),
          event -> {});

      assertEquals(
          Json.parse(
              "[{\"type\": \"redacted_thinking\", \"data\": \"opaque-1\","
                  + " \"cache_control\": {\"type\": \"ephemeral\"}}]"),
          Json.parse(server.body(1)).path("messages").path(0).path("content"));
      provider.close();
    }
  }

  private static Provider.Request request() {
    return new Provider.Request(
        "claude-test",
        "be nice",
        List.of(
            new Message.User("hi"),
            new Message.Assistant(
                "calling", List.of(new Message.ToolCall("toolu_1", "read", TOOL_ARGUMENTS))),
            new Message.ToolResult("toolu_1", "read", "boom", true),
            new Message.ToolResult("toolu_2", "glob", "ok", false),
            new Message.User("next")),
        List.of(new ToolSpec("read", "Read a file", TOOL_SCHEMA)),
        0.7,
        null, null);
  }

  @Test
  void cacheBreakpointsLandOnTheSystemTheToolsAndTheEndOfTheConversation() throws Exception {
    // 前缀缓存正是长代理会话不必每个回合都为整段历史付钱的原因：回合 n+1 会发出回合 n 发过的一切加上它的回
    // 答，所以稳定的前缀是系统提示词、工具集，以及到目前为止的对话。这就是那三个断点，而 API 允许四个——第
    // 四个会白花掉。
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(STREAM))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");
      provider.complete(request(), event -> {});

      JsonNode body = Json.parse(server.body(0));
      assertEquals(3, countCacheControl(body), "三个断点：" + body);
      assertTrue(body.path("system").isArray(), "系统提示词现在是一个块数组：" + body);
      assertEquals(
          "ephemeral",
          body.path("system").get(0).path("cache_control").path("type").asText(),
          body.toString());
      JsonNode tools = body.path("tools");
      assertEquals(
          "ephemeral",
          tools.get(tools.size() - 1).path("cache_control").path("type").asText(),
          "在最后一个工具之后，好让工具和系统一起缓存：" + body);
      ArrayNode messages = (ArrayNode) body.path("messages");
      JsonNode lastContent = messages.get(messages.size() - 1).path("content");
      assertEquals(
          "ephemeral",
          lastContent.get(lastContent.size() - 1).path("cache_control").path("type").asText(),
          "以及在对话的末尾：" + body);
      // 前面没有任何一条带着它：放在中间的断点会是一段没人复用的前缀。
      for (int i = 0; i < messages.size() - 1; i++) {
        assertTrue(
            countCacheControl(messages.get(i)) == 0,
            "消息 " + i + " 不能带断点：" + messages.get(i));
      }
      provider.close();
    }
  }

  /** 响应体的某一部分携带了多少个缓存断点，任意深度。 */
  private static int countCacheControl(JsonNode node) {
    if (node == null || node.isNull()) {
      return 0;
    }
    int count = node.has("cache_control") ? 1 : 0;
    for (JsonNode child : node) {
      count += countCacheControl(child);
    }
    return count;
  }

  @Test
  void addsCacheReadsAndWritesToThePromptSize() throws Exception {
    String stream =
        """
        event: message_start
        data: {"type":"message_start","message":{"id":"m","usage":{"input_tokens":9,"cache_read_input_tokens":100,"cache_creation_input_tokens":20}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}

        event: message_stop
        data: {"type":"message_stop"}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");
      List<Provider.Event> events = new ArrayList<>();

      provider.complete(request(), events::add);

      // 9 + 100 + 20：缓存读取与写入是在 input_tokens 之上另行计费的，所以一个命中缓存的回合不能显得比没
      // 命中的还小。
      assertTrue(events.contains(new Provider.Event.Usage(129, 4, 100)), events.toString());
      provider.close();
    }
  }

  @Test
  void explicitNullCacheFieldsLeaveTheCacheUnreported() throws Exception {
    // 网关会在 API 省略字段的地方发来显式的 null。Jackson 交回的是 NullNode，所以单纯判 null 会把「没有
    // 上报」读成「什么都没缓存」，UI 于是显示出一个看起来像测量值的 0% 命中率。
    String stream =
        """
        event: message_start
        data: {"type":"message_start","message":{"id":"m","usage":{"input_tokens":100,"cache_read_input_tokens":null,"cache_creation_input_tokens":null}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}

        event: message_stop
        data: {"type":"message_stop"}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");
      List<Provider.Event> events = new ArrayList<>();

      provider.complete(request(), events::add);

      assertTrue(events.contains(new Provider.Event.Usage(100, 4, null)), events.toString());
      provider.close();
    }
  }

  @Test
  void thinkingDeltasReachTheListenerAndStayOutOfTheReply() throws Exception {
    // 设了推理档位后，API 可能思考好几秒；丢掉这些增量会让用户盯着一个转圈、无字可读。
    String stream =
        """
        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me check"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: content_block_start
        data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"answer"}}

        event: message_stop
        data: {"type":"message_stop"}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");
      List<Provider.Event> events = new ArrayList<>();

      Message.Assistant assistant = provider.complete(request(), events::add);

      assertEquals("answer", assistant.text(), "思考不是回复的一部分");
      assertEquals(
          List.of(
              new Provider.Event.ReasoningDelta("Let me check"),
              new Provider.Event.TextDelta("answer")),
          events);
      provider.close();
    }
  }

  @Test
  void argumentsSentInTheOpeningBlockAreKept() throws Exception {
    String stream =
        """
        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"read","input":{"path":"a"}}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: message_stop
        data: {"type":"message_stop"}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");

      Message.Assistant assistant = provider.complete(request(), event -> {});

      assertEquals(
          List.of(new Message.ToolCall("toolu_1", "read", "{\"path\":\"a\"}")),
          assistant.toolCalls(),
          "随块一起到达的参数不能被丢掉");
      provider.close();
    }
  }

  @Test
  void aTwoHundredThatIsNotAnEventStreamIsNotAnEmptyAnswer() throws Exception {
    try (FakeServer server =
        FakeServer.start(FakeServer.Reply.json(200, "{\"error\":{\"message\":\"upstream exploded\"}}"))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");

      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class, () -> provider.complete(request(), event -> {}));

      assertTrue(failure.getMessage().contains("没有返回任何事件"), failure.getMessage());
      assertTrue(failure.getMessage().contains("upstream exploded"), failure.getMessage());
      provider.close();
    }
  }

  @Test
  void theReasoningTierBecomesAnExtendedThinkingBudget() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");

      provider.complete(reasoningRequest("low"), event -> {});
      JsonNode low = Json.parse(server.body(0));
      assertEquals("enabled", low.path("thinking").path("type").asText());
      assertEquals(2048, low.path("thinking").path("budget_tokens").asInt());
      assertFalse(low.has("temperature"), "开着思考时 API 会拒绝自定义 temperature");

      provider.complete(reasoningRequest("max"), event -> {});
      JsonNode max = Json.parse(server.body(1));
      assertEquals(32768, max.path("thinking").path("budget_tokens").asInt());
      assertTrue(
          max.path("max_tokens").asInt() > 32768,
          "max_tokens 必须大于思考预算：" + max.path("max_tokens").asInt());

      provider.close();
    }
  }

  @Test
  void withoutATierTheRequestIsUnchanged() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");

      provider.complete(request(), event -> {});

      JsonNode body = Json.parse(server.body(0));
      assertFalse(body.has("thinking"), body.toString());
      assertEquals(0.7, body.path("temperature").asDouble(), 0.001, "temperature 仍然生效");
      provider.close();
    }
  }

  private Provider.Request reasoningRequest(String level) {
    return new Provider.Request(
        "claude-test", "be nice", List.of(new Message.User("hi")), List.of(), 0.5, 4096, level);
  }
}
