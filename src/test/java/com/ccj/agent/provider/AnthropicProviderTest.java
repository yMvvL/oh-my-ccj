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
    // A stream cut off mid-call leaves half-written arguments behind. Throwing while the *next*
    // request is built would strand the session: every later turn rebuilds the same history.
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
          "an unparsable argument string becomes an empty input, not a dead session");
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
    // The API verifies the signature of every thinking block it is handed, and rejects a turn that
    // drops the ones the model produced; keeping them is the difference between a working
    // extended-thinking conversation and one that fails on its second request.
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
          "the block and its signature travel with the turn");

      // And the next request hands it back, before anything else in that assistant turn.
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
          "thinking first, then the call it reasoned about");
      provider.close();
    }
  }

  @Test
  void thinkingBlocksAreNotReplayedWhenThinkingIsOff() throws Exception {
    // The blocks belong to a setting the request is no longer asking for; sending them anyway is
    // how a user who switches the tier back to `default` would get a rejected turn.
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
          "no thinking block without a reasoning tier");
      provider.close();
    }
  }

  @Test
  void anUnsignedThinkingBlockIsNotReplayed() throws Exception {
    // A stream cut before the signature arrived leaves text without a signature; the API would
    // reject it, so it is dropped rather than sent.
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
    // Prompt caching is the reason a long agent session does not cost its whole history every turn:
    // turn n+1 sends everything turn n sent plus its answer, so the stable prefix is the system
    // prompt, the tool set, and the conversation up to now. Those are the three breakpoints, and the
    // API allows four — a fourth would be spent for nothing.
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(STREAM))) {
      AnthropicProvider provider = new AnthropicProvider(server.url(), "sk-ant-test");
      provider.complete(request(), event -> {});

      JsonNode body = Json.parse(server.body(0));
      assertEquals(3, countCacheControl(body), "three breakpoints: " + body);
      assertTrue(body.path("system").isArray(), "the system prompt is a block array now: " + body);
      assertEquals(
          "ephemeral",
          body.path("system").get(0).path("cache_control").path("type").asText(),
          body.toString());
      JsonNode tools = body.path("tools");
      assertEquals(
          "ephemeral",
          tools.get(tools.size() - 1).path("cache_control").path("type").asText(),
          "after the last tool, so tools and system cache together: " + body);
      ArrayNode messages = (ArrayNode) body.path("messages");
      JsonNode lastContent = messages.get(messages.size() - 1).path("content");
      assertEquals(
          "ephemeral",
          lastContent.get(lastContent.size() - 1).path("cache_control").path("type").asText(),
          "and at the end of the conversation: " + body);
      // Nothing earlier carries one: a breakpoint in the middle would be a prefix nobody reuses.
      for (int i = 0; i < messages.size() - 1; i++) {
        assertTrue(
            countCacheControl(messages.get(i)) == 0,
            "message " + i + " must not carry a breakpoint: " + messages.get(i));
      }
      provider.close();
    }
  }

  /** How many caching breakpoints a piece of the body carries, at any depth. */
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

      // 9 + 100 + 20: cache reads and writes are billed on top of input_tokens, so a cached turn
      // must not look smaller than an uncached one.
      assertTrue(events.contains(new Provider.Event.Usage(129, 4, 100)), events.toString());
      provider.close();
    }
  }

  @Test
  void explicitNullCacheFieldsLeaveTheCacheUnreported() throws Exception {
    // A gateway sends explicit nulls where the API omits the field. Jackson hands back a NullNode,
    // so a plain null check read "not reported" as "nothing was cached" and the UI showed a 0% hit
    // rate that looked like a measurement.
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
    // With a reasoning tier set the API can think for seconds; discarding the deltas left the user
    // watching a spinner with nothing to read.
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

      assertEquals("answer", assistant.text(), "thinking is not part of the reply");
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
          "arguments that arrive with the block must not be dropped");
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

      assertTrue(failure.getMessage().contains("no events"), failure.getMessage());
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
      assertFalse(low.has("temperature"), "the API rejects a custom temperature while thinking");

      provider.complete(reasoningRequest("max"), event -> {});
      JsonNode max = Json.parse(server.body(1));
      assertEquals(32768, max.path("thinking").path("budget_tokens").asInt());
      assertTrue(
          max.path("max_tokens").asInt() > 32768,
          "max_tokens must exceed the thinking budget: " + max.path("max_tokens").asInt());

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
      assertEquals(0.7, body.path("temperature").asDouble(), 0.001, "temperature still applies");
      provider.close();
    }
  }

  private Provider.Request reasoningRequest(String level) {
    return new Provider.Request(
        "claude-test", "be nice", List.of(new Message.User("hi")), List.of(), 0.5, 4096, level);
  }
}
