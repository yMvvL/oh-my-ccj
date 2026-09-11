package com.ccj.agent.provider;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Provider for OpenAI's {@code /chat/completions} API and the servers that copy it (DeepSeek,
 * Groq, Ollama, ...).
 *
 * <p>Two wire details drive the mapping. Tool call arguments travel as a JSON <em>string</em>, not
 * as an object, so a call assembled from streamed fragments must keep its raw text. And streamed
 * tool calls arrive as fragments keyed by {@code index}: the first one names the call, later ones
 * append argument text, which is why the assembler buffers per index instead of assuming order.
 */
public final class OpenAiProvider implements Provider {

  public static final String NAME = "openai";

  private static final List<String> REASONING_FIELDS = List.of("reasoning_content", "reasoning");

  private final String baseUrl;
  private final String apiKey;
  private final ExecutorService executor;
  private final HttpClient http;

  public OpenAiProvider(String baseUrl, String apiKey) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("openai base URL is required");
    }
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.apiKey = apiKey == null ? "" : apiKey;
    this.executor = Transport.newExecutor("ccj-openai-http");
    this.http = Transport.newClient(executor);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Message.Assistant complete(Request request, Consumer<Event> listener) throws Exception {
    Consumer<Event> sink = listener == null ? event -> {} : listener;
    HttpResponse<Stream<String>> response =
        Transport.send(http, buildRequest(request), sink);
    try (Stream<String> lines = response.body()) {
      return consume(lines, sink);
    } catch (UncheckedIOException e) {
      throw new Exception("connection lost while streaming the response: " + e.getCause(), e);
    }
  }

  @Override
  public void close() {
    executor.shutdown();
  }

  private HttpRequest buildRequest(Request request) {
    String body = Json.write(buildBody(request));
    return HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
        .timeout(Transport.REQUEST_TIMEOUT)
        .header("content-type", "application/json")
        .header("accept", "text/event-stream")
        .header("authorization", "Bearer " + apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
  }

  private ObjectNode buildBody(Request request) {
    String model = request.model();
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("openai requests need a model");
    }
    ObjectNode root = Json.object();
    root.put("model", model);
    ArrayNode messages = root.putArray("messages");
    if (request.system() != null && !request.system().isBlank()) {
      messages.add(textTurn("system", request.system()));
    }
    for (Message message : request.messages()) {
      messages.add(toWire(message));
    }
    if (!request.tools().isEmpty()) {
      ArrayNode tools = root.putArray("tools");
      for (ToolSpec tool : request.tools()) {
        ObjectNode entry = tools.addObject();
        entry.put("type", "function");
        ObjectNode function = entry.putObject("function");
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.set("parameters", Json.parse(tool.parametersJson()));
      }
    }
    root.put("stream", true);
    root.putObject("stream_options").put("include_usage", true);
    if (request.temperature() != null) {
      root.put("temperature", request.temperature().doubleValue());
    }
    if (request.maxTokens() != null) {
      root.put("max_tokens", request.maxTokens());
    }
    return root;
  }

  private static ObjectNode textTurn(String role, String text) {
    ObjectNode node = Json.object();
    node.put("role", role);
    node.put("content", text);
    return node;
  }

  private static ObjectNode toWire(Message message) {
    return switch (message) {
      case Message.System system -> textTurn("system", system.text());
      case Message.User user -> textTurn("user", user.text());
      case Message.Assistant assistant -> assistantTurn(assistant);
      case Message.ToolResult result -> toolTurn(result);
    };
  }

  private static ObjectNode assistantTurn(Message.Assistant assistant) {
    ObjectNode node = textTurn("assistant", assistant.text());
    if (assistant.hasToolCalls()) {
      ArrayNode calls = node.putArray("tool_calls");
      for (Message.ToolCall call : assistant.toolCalls()) {
        ObjectNode entry = calls.addObject();
        entry.put("id", call.id());
        entry.put("type", "function");
        ObjectNode function = entry.putObject("function");
        function.put("name", call.name());
        // Arguments stay a JSON *string* on this wire format; the model parses them itself.
        function.put("arguments", call.arguments());
      }
    }
    return node;
  }

  private static ObjectNode toolTurn(Message.ToolResult result) {
    ObjectNode node = Json.object();
    node.put("role", "tool");
    node.put("tool_call_id", result.toolCallId());
    // There is no error channel on a tool turn, so the flag reaches the model only as prose.
    node.put("content", result.error() ? result.content() + " (error)" : result.content());
    return node;
  }

  /**
   * Reads the stream to completion. Only deltas observed on the wire are forwarded; the assembled
   * turn comes from the same fragments, so the listener and the caller can never disagree.
   */
  private Message.Assistant consume(Stream<String> lines, Consumer<Event> sink) {
    StringBuilder text = new StringBuilder();
    Map<Integer, ToolCallBuffer> buffers = new TreeMap<>();
    try (Sse sse = Sse.of(lines)) {
      for (Sse.Event event = sse.next(); event != null; event = sse.next()) {
        if (event.isDone()) {
          break;
        }
        handleChunk(Json.parse(event.data()), text, buffers, sink);
      }
    }
    List<Message.ToolCall> calls = new ArrayList<>(buffers.size());
    for (ToolCallBuffer buffer : buffers.values()) {
      calls.add(new Message.ToolCall(buffer.id, buffer.name, buffer.arguments.toString()));
    }
    return new Message.Assistant(text.toString(), calls);
  }

  private static void handleChunk(
      JsonNode chunk,
      StringBuilder text,
      Map<Integer, ToolCallBuffer> buffers,
      Consumer<Event> sink) {
    JsonNode error = chunk.get("error");
    if (error != null && !error.isNull()) {
      throw new IllegalStateException("provider error: " + error);
    }
    JsonNode choices = chunk.path("choices");
    JsonNode choice = choices.isArray() && !choices.isEmpty() ? choices.get(0) : null;
    if (choice != null) {
      JsonNode delta = choice.path("delta");
      appendText(delta.get("content"), text, sink);
      for (String field : REASONING_FIELDS) {
        JsonNode reasoning = delta.get(field);
        if (reasoning != null && reasoning.isTextual() && !reasoning.asText().isEmpty()) {
          sink.accept(new Event.ReasoningDelta(reasoning.asText()));
        }
      }
      appendToolCalls(delta.get("tool_calls"), buffers, sink);
    }
    emitUsage(chunk.get("usage"), choice == null ? null : choice.get("usage"), sink);
  }

  /**
   * Empty content is skipped: OpenAI primes every stream with a role-only chunk whose content is
   * {@code ""}, and forwarding it would only make the UI redraw for nothing.
   */
  private static void appendText(JsonNode content, StringBuilder text, Consumer<Event> sink) {
    if (content == null || !content.isTextual() || content.asText().isEmpty()) {
      return;
    }
    text.append(content.asText());
    sink.accept(new Event.TextDelta(content.asText()));
  }

  private static void appendToolCalls(
      JsonNode fragments, Map<Integer, ToolCallBuffer> buffers, Consumer<Event> sink) {
    if (fragments == null || !fragments.isArray()) {
      return;
    }
    for (JsonNode fragment : fragments) {
      ToolCallBuffer buffer =
          buffers.computeIfAbsent(
              fragment.path("index").asInt(0), index -> new ToolCallBuffer());
      JsonNode id = fragment.get("id");
      if (id != null && id.isTextual()) {
        buffer.id = id.asText();
      }
      JsonNode function = fragment.get("function");
      if (function != null) {
        JsonNode name = function.get("name");
        if (name != null && name.isTextual()) {
          buffer.name = buffer.name + name.asText();
        }
        JsonNode arguments = function.get("arguments");
        if (arguments != null && arguments.isTextual()) {
          buffer.arguments.append(arguments.asText());
        }
      }
      if (!buffer.announced && !buffer.name.isEmpty()) {
        buffer.announced = true;
        sink.accept(new Event.ToolCallStart(buffer.id, buffer.name));
      }
    }
  }

  /** Prefers the top-level usage object, then the choice-level copy some gateways send. */
  private static void emitUsage(JsonNode usage, JsonNode choiceUsage, Consumer<Event> sink) {
    JsonNode source = usage != null && usage.isObject() ? usage : choiceUsage;
    if (source == null || !source.isObject()) {
      return;
    }
    JsonNode input = source.get("prompt_tokens");
    JsonNode output = source.get("completion_tokens");
    if (input == null && output == null) {
      return;
    }
    sink.accept(
        new Event.Usage(input == null ? 0 : input.asInt(), output == null ? 0 : output.asInt()));
  }

  private static String stripTrailingSlash(String url) {
    String stripped = url.strip();
    return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
  }

  /** Mutable state for one streamed tool call; fragments address it by index, not by order. */
  private static final class ToolCallBuffer {
    private String id = "";
    private String name = "";
    private final StringBuilder arguments = new StringBuilder();
    private boolean announced;
  }
}
