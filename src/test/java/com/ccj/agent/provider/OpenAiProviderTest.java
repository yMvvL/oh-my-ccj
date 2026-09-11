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

class OpenAiProviderTest {

  private static final String TOOL_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";
  private static final String TOOL_ARGUMENTS = "{\"path\":\"a\"}";

  /** Chunks are cut every 19 characters, so frames and the JSON inside them split mid-token. */
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
  void omitsToolsAndSamplingFieldsThatWereNotRequested() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse("data: [DONE]\n\n"))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");
      Provider.Request minimal =
          new Provider.Request(
              "gpt-mini", null, List.of(new Message.User("hi")), List.of(), null, null);

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
        128);
  }
}
