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

class ResponsesProviderTest {

  private static final String TOOL_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";
  private static final String TOOL_ARGUMENTS = "{\"path\":\"a\"}";

  /**
   * 一段按官方事件形状写的流：只有 {@code data:} 行，和真实端点上一样由载荷里的 {@code type} 说明自己是
   * 什么；每 19 个字符切一刀，所以帧和帧里的 JSON 都会从 token 中间断开。
   */
  private static final String STREAM =
      """
      data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_1","status":"in_progress","usage":null}}

      data: {"type":"response.in_progress","sequence_number":1,"response":{"id":"resp_1","status":"in_progress"}}

      data: {"type":"response.output_item.added","sequence_number":2,"output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[]}}

      data: {"type":"response.reasoning_summary_text.delta","sequence_number":3,"item_id":"rs_1","output_index":0,"summary_index":0,"delta":"Let me "}

      data: {"type":"response.reasoning_summary_text.delta","sequence_number":4,"item_id":"rs_1","output_index":0,"summary_index":0,"delta":"look"}

      data: {"type":"response.output_item.added","sequence_number":5,"output_index":1,"item":{"type":"message","id":"msg_1","status":"in_progress","role":"assistant","content":[]}}

      data: {"type":"response.output_text.delta","sequence_number":6,"item_id":"msg_1","output_index":1,"content_index":0,"delta":"Hel"}

      data: {"type":"response.output_text.delta","sequence_number":7,"item_id":"msg_1","output_index":1,"content_index":0,"delta":"lo"}

      data: {"type":"response.output_item.added","sequence_number":8,"output_index":2,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"read","arguments":""}}

      data: {"type":"response.function_call_arguments.delta","sequence_number":9,"item_id":"fc_1","output_index":2,"delta":"{\\"pa"}

      data: {"type":"response.function_call_arguments.delta","sequence_number":10,"item_id":"fc_1","output_index":2,"delta":"th\\":\\"a\\"}"}

      data: {"type":"response.web_search_call.searching","sequence_number":11,"item_id":"ws_1","output_index":3}

      data: {"type":"response.function_call_arguments.done","sequence_number":12,"item_id":"fc_1","output_index":2,"arguments":"{\\"path\\":\\"a\\"}"}

      data: {"type":"response.output_item.done","sequence_number":13,"output_index":2,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"read","arguments":"{\\"path\\":\\"a\\"}"}}

      data: {"type":"response.completed","sequence_number":14,"response":{"id":"resp_1","status":"completed","usage":{"input_tokens":11,"input_tokens_details":{"cached_tokens":4,"cache_write_tokens":0},"output_tokens":7,"output_tokens_details":{"reasoning_tokens":2},"total_tokens":18}}}

      """;

  private static final String EXPECTED_REQUEST =
      """
      {
        "model": "gpt-test",
        "instructions": "be nice",
        "input": [
          {"role": "user", "content": "hi"},
          {"type": "message", "role": "assistant",
           "content": [{"type": "output_text", "text": "calling"}]},
          {"type": "function_call", "call_id": "call_1", "name": "read",
           "arguments": "{\\"path\\":\\"a\\"}"},
          {"type": "function_call_output", "call_id": "call_1", "output": "boom (error)"}
        ],
        "tools": [
          {"type": "function", "name": "read", "description": "Read a file",
           "parameters": {"type": "object", "properties": {"path": {"type": "string"}}}}
        ],
        "stream": true,
        "store": false,
        "temperature": 0.5,
        "max_output_tokens": 128
      }
      """;

  /** 一个没有内容的回合：真实的 Responses 流末尾没有 {@code [DONE]}，只有这个终局事件。 */
  private static final String EMPTY_TURN =
      """
      data: {"type":"response.completed","sequence_number":0,"response":{"id":"resp_0","status":"completed"}}

      """;

  @Test
  void sendsTheDocumentedRequestBody() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(STREAM))) {
      ResponsesProvider provider = new ResponsesProvider(server.url(), "sk-test");

      Message.Assistant assistant = provider.complete(request(), event -> {});

      assertEquals("Hello", assistant.text());
      assertEquals(
          List.of(new Message.ToolCall("call_1", "read", TOOL_ARGUMENTS)), assistant.toolCalls());
      assertEquals(Json.parse(EXPECTED_REQUEST), Json.parse(server.body(0)));
      assertEquals("/responses", server.path(0));
      assertEquals("Bearer sk-test", server.header(0, "authorization"));
      assertEquals(1, server.count());
      provider.close();
    }
  }

  @Test
  void mapsDeltasAndUsageOntoEvents() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(STREAM))) {
      ResponsesProvider provider = new ResponsesProvider(server.url(), "sk-test");
      List<Provider.Event> events = new ArrayList<>();

      Message.Assistant assistant = provider.complete(request(), events::add);

      assertEquals(
          List.of(
              new Provider.Event.ReasoningDelta("Let me "),
              new Provider.Event.ReasoningDelta("look"),
              new Provider.Event.TextDelta("Hel"),
              new Provider.Event.TextDelta("lo"),
              new Provider.Event.ToolCallStart("call_1", "read"),
              // 缓存命中是 input_tokens 的分解，不在它之外另行计费，所以这里是 11 而不是 11 + 4。
              new Provider.Event.Usage(11, 7, 4)),
          events);
      assertEquals(TOOL_ARGUMENTS, assistant.toolCalls().get(0).arguments());
      provider.close();
    }
  }

  @Test
  void usageRepeatedAcrossFramesIsCountedOnce() throws Exception {
    // 终局帧被重放的网关（或者自己合成一个终局帧的代理）会把同一份 usage 送两遍；算两遍看起来只是「这个回
    // 合更大」，比漏报更难被发现。
    String stream =
        """
        data: {"type":"response.completed","sequence_number":0,"response":{"id":"resp_2","status":"completed","usage":{"input_tokens":3,"input_tokens_details":{"cached_tokens":0},"output_tokens":5,"total_tokens":8}}}

        data: {"type":"response.completed","sequence_number":1,"response":{"id":"resp_2","status":"completed","usage":{"input_tokens":3,"input_tokens_details":{"cached_tokens":0},"output_tokens":5,"total_tokens":8}}}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      ResponsesProvider provider = new ResponsesProvider(server.url(), "sk-test");
      List<Provider.Event> events = new ArrayList<>();

      provider.complete(request(), events::add);

      assertEquals(List.of(new Provider.Event.Usage(3, 5, 0)), events, events.toString());
      provider.close();
    }
  }

  @Test
  void anUnreportedCacheIsNotAZeroCache() throws Exception {
    // 网关会在 API 省略字段的地方发来显式的 null。把它读成「什么都没缓存」，会让 UI 显示出一个看起来像测量
    // 值的 0% 命中率。
    String stream =
        """
        data: {"type":"response.output_text.delta","sequence_number":0,"item_id":"msg_1","output_index":0,"content_index":0,"delta":"hi"}

        data: {"type":"response.completed","sequence_number":1,"response":{"id":"resp_4","status":"completed","usage":{"input_tokens":9,"input_tokens_details":{"cached_tokens":null},"output_tokens":2,"total_tokens":11}}}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      ResponsesProvider provider = new ResponsesProvider(server.url(), "sk-test");
      List<Provider.Event> events = new ArrayList<>();

      provider.complete(request(), events::add);

      assertTrue(events.contains(new Provider.Event.Usage(9, 2, null)), events.toString());
      provider.close();
    }
  }

  @Test
  void turnsErrorEventsIntoExceptions() throws Exception {
    String stream =
        """
        data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_5","status":"in_progress"}}

        data: {"type":"error","sequence_number":1,"code":"server_error","message":"upstream exploded","param":null}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      ResponsesProvider provider = new ResponsesProvider(server.url(), "sk-test");

      Exception failure =
          assertThrows(Exception.class, () -> provider.complete(request(), event -> {}));

      assertTrue(failure.getMessage().contains("upstream exploded"), failure.getMessage());
      provider.close();
    }
  }

  @Test
  void turnsFailedResponsesIntoExceptions() throws Exception {
    // response.failed 把原因藏在 response.error 里，不是顶层——照着 error 事件去读它会得到一句什么都没有的
    // 「回合失败」。
    String stream =
        """
        data: {"type":"response.failed","sequence_number":0,"response":{"id":"resp_6","status":"failed","output":[],"error":{"code":"rate_limit_exceeded","message":"slow down"}}}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      ResponsesProvider provider = new ResponsesProvider(server.url(), "sk-test");

      Exception failure =
          assertThrows(Exception.class, () -> provider.complete(request(), event -> {}));

      assertTrue(failure.getMessage().contains("slow down"), failure.getMessage());
      provider.close();
    }
  }

  @Test
  void turnsResponseErrorFramesIntoExceptions() throws Exception {
    // 错误事件的第三种拼法：有的线路实现把错误对象又包一层，读错了一层就只剩一句「出错了」。
    String stream =
        """
        data: {"type":"response.error","sequence_number":0,"error":{"code":"server_error","message":"boom"}}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      ResponsesProvider provider = new ResponsesProvider(server.url(), "sk-test");

      Exception failure =
          assertThrows(Exception.class, () -> provider.complete(request(), event -> {}));

      assertTrue(failure.getMessage().contains("boom"), failure.getMessage());
      provider.close();
    }
  }

  @Test
  void unknownEventTypesAreIgnoredRatherThanFatal() throws Exception {
    // 认得的事件比 API 少只说明这个提供方旧；抛出去会让 API 的一次升级连带弄坏一个本来能用的回合。
    String stream =
        """
        data: {"type":"response.some_future_thing","sequence_number":0,"item_id":"x","delta":"?"}

        data: {"type":"response.audio_transcript.delta","sequence_number":1,"item_id":"a","output_index":0,"delta":"lalala"}

        data: {"type":"response.output_text.delta","sequence_number":2,"item_id":"msg_1","output_index":0,"content_index":0,"delta":"ok"}

        data: {"type":"response.completed","sequence_number":3,"response":{"id":"resp_7","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}

        """;
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(stream))) {
      ResponsesProvider provider = new ResponsesProvider(server.url(), "sk-test");

      Message.Assistant assistant = provider.complete(request(), event -> {});

      assertEquals("ok", assistant.text());
      provider.close();
    }
  }

  @Test
  void omitsInstructionsToolsAndSamplingFieldsThatWereNotRequested() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(EMPTY_TURN))) {
      ResponsesProvider provider = new ResponsesProvider(server.url(), "sk-test");
      Provider.Request minimal =
          new Provider.Request(
              "gpt-mini", null, List.of(new Message.User("hi")), List.of(), null, null, null);

      provider.complete(minimal, event -> {});

      JsonNode sent = Json.parse(server.body(0));
      assertEquals(
          Json.parse(
              "{\"model\":\"gpt-mini\",\"input\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                  + "\"stream\":true,\"store\":false}"),
          sent);
      assertFalse(sent.has("instructions"));
      assertFalse(sent.has("tools"));
      assertFalse(sent.has("temperature"));
      assertFalse(sent.has("reasoning"));
      assertFalse(sent.has("max_output_tokens"));
      assertFalse(
          sent.path("store").asBoolean(true),
          "store 的默认值是 true，也就是让端点上留着整段会话；必须每次都写出来：" + sent);
      provider.close();
    }
  }

  @Test
  void theReasoningTierBecomesEffortWithAnOutputCapThatLeavesRoomForIt() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(EMPTY_TURN))) {
      ResponsesProvider provider = new ResponsesProvider(server.url(), "sk-test");

      provider.complete(reasoningRequest("low", 512), event -> {});
      JsonNode low = Json.parse(server.body(0));
      assertEquals("low", low.path("reasoning").path("effort").asText());
      assertEquals(512, low.path("max_output_tokens").asInt(), "档位没开到顶就不动这个上限");

      provider.complete(reasoningRequest("max", 512), event -> {});
      JsonNode max = Json.parse(server.body(1));
      assertEquals("max", max.path("reasoning").path("effort").asText(), "Responses 认得 max 这个取值");
      assertTrue(
          max.path("max_output_tokens").asInt() > 512,
          "上限里也含推理 token，所以开到顶的档位不能再被一个普通回合的额度卡住："
              + max.path("max_output_tokens").asInt());
      provider.close();
    }
  }

  @Test
  void aTruncatedToolCallStillLeavesASendableRequest() throws Exception {
    // 一次在调用中途被切断的流会留下只写了一半的参数。把它原样交回下一次请求，就是赌这个会话唯一没有出路的
    // 那种失败：之后每个回合都会重建同一段历史，于是它再也发不出去。
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(EMPTY_TURN))) {
      ResponsesProvider provider = new ResponsesProvider(server.url(), "sk-test");
      Provider.Request request =
          new Provider.Request(
              "gpt-test",
              null,
              List.of(
                  new Message.User("hi"),
                  new Message.Assistant(
                      "", List.of(new Message.ToolCall("call_1", "read", "{\"path\": \"oops"))),
                  new Message.ToolResult("call_1", "read", "invalid arguments", true)),
              List.of(),
              null,
              null,
              null);

      provider.complete(request, event -> {});

      assertEquals(
          "{}",
          Json.parse(server.body(0)).path("input").path(1).path("arguments").asText(),
          "读不成 JSON 的参数会变成空对象，而不是一个死掉的会话");
      provider.close();
    }
  }

  @Test
  void aTwoHundredThatIsNotAnEventStreamIsNotAnEmptyAnswer() throws Exception {
    try (FakeServer server =
        FakeServer.start(
            FakeServer.Reply.json(200, "{\"error\":{\"message\":\"upstream exploded\"}}"))) {
      ResponsesProvider provider = new ResponsesProvider(server.url(), "sk-test");

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
        "gpt-test",
        "be nice",
        List.of(
            new Message.User("hi"),
            new Message.Assistant(
                "calling", List.of(new Message.ToolCall("call_1", "read", TOOL_ARGUMENTS))),
            new Message.ToolResult("call_1", "read", "boom", true)),
        List.of(new ToolSpec("read", "Read a file", TOOL_SCHEMA)),
        0.5,
        128,
        null);
  }

  private static Provider.Request reasoningRequest(String level, Integer maxTokens) {
    return new Provider.Request(
        "gpt-test", "be nice", List.of(new Message.User("hi")), List.of(), null, maxTokens, level);
  }
}
