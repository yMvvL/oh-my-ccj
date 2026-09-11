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

class AnthropicProviderTest {

  private static final String TOOL_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"}}}";
  private static final String TOOL_ARGUMENTS = "{\"pattern\":\"*.java\"}";

  /** Chunks are cut every 19 characters, so frames and the JSON inside them split mid-token. */
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
        "system": "be nice",
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
          {"role": "user", "content": "next"}
        ],
        "tools": [
          {"name": "read", "description": "Read a file",
           "input_schema": {"type": "object", "properties": {"pattern": {"type": "string"}}}}
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
              "claude-mini", null, List.of(new Message.User("hi")), List.of(), null, 512);

      provider.complete(minimal, event -> {});

      JsonNode sent = Json.parse(server.body(0));
      assertEquals(
          Json.parse(
              "{\"model\":\"claude-mini\",\"max_tokens\":512,"
                  + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}"),
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
        null);
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

      // 9 + 100 + 20: cache reads and writes are billed on top of input_tokens, so a cached turn
      // must not look smaller than an uncached one.
      assertTrue(events.contains(new Provider.Event.Usage(129, 4, 100)), events.toString());
      provider.close();
    }
  }
}
