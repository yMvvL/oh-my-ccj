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
 * Provider for Anthropic's {@code /v1/messages} API.
 *
 * <p>The Messages API is stricter than the chat-completions family in two ways that shape this
 * class. Tool results are content blocks inside a <em>user</em> turn and every {@code tool_result}
 * for one assistant turn must live in the same user turn, so consecutive results are merged rather
 * than emitted one message each. And reply content is an ordered list of blocks, streamed by index,
 * so assembly keeps a block table keyed by index instead of appending in arrival order.
 */
public final class AnthropicProvider implements Provider {

  public static final String NAME = "anthropic";

  /** The Messages API has no model-specific default for {@code max_tokens}; it is mandatory. */
  public static final int DEFAULT_MAX_TOKENS = 4096;

  private static final String API_VERSION = "2023-06-01";

  private final String baseUrl;
  private final String apiKey;
  private final ExecutorService executor;
  private final HttpClient http;

  public AnthropicProvider(String baseUrl, String apiKey) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("anthropic base URL is required");
    }
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.apiKey = apiKey == null ? "" : apiKey;
    this.executor = Transport.newExecutor("ccj-anthropic-http");
    this.http = Transport.newClient(executor);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Message.Assistant complete(Request request, Consumer<Event> listener) throws Exception {
    Consumer<Event> sink = listener == null ? event -> {} : listener;
    HttpResponse<Stream<String>> response = Transport.send(http, buildRequest(request), sink);
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
    return HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
        .timeout(Transport.REQUEST_TIMEOUT)
        .header("content-type", "application/json")
        .header("accept", "text/event-stream")
        .header("x-api-key", apiKey)
        .header("anthropic-version", API_VERSION)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
  }

  private ObjectNode buildBody(Request request) {
    String model = request.model();
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("anthropic requests need a model");
    }
    ObjectNode root = Json.object();
    root.put("model", model);
    root.put("max_tokens", request.maxTokens() == null ? DEFAULT_MAX_TOKENS : request.maxTokens());
    if (request.system() != null && !request.system().isBlank()) {
      root.put("system", request.system());
    }
    appendMessages(request.messages(), root.putArray("messages"));
    if (!request.tools().isEmpty()) {
      ArrayNode tools = root.putArray("tools");
      for (ToolSpec tool : request.tools()) {
        ObjectNode entry = tools.addObject();
        entry.put("name", tool.name());
        entry.put("description", tool.description());
        entry.set("input_schema", Json.parse(tool.parametersJson()));
      }
    }
    root.put("stream", true);
    String effort = request.reasoning();
    if (effort != null) {
      // Extended thinking: a budget in tokens, max_tokens must exceed it, and temperature cannot be
      // customised while thinking is on — the API rejects the combination rather than ignoring it.
      int budget = "low".equals(effort) ? 2_048 : "high".equals(effort) ? 8_192 : 32_768;
      root.putObject("thinking").put("type", "enabled").put("budget_tokens", budget);
      int cap = root.path("max_tokens").asInt(4_096);
      if (cap <= budget) {
        root.put("max_tokens", budget + 1_024);
      }
    } else if (request.temperature() != null) {
      root.put("temperature", request.temperature().doubleValue());
    }
    return root;
  }

  /** Writes the conversation, merging tool-result runs into the one user turn the API expects. */
  private static void appendMessages(List<Message> source, ArrayNode messages) {
    int index = 0;
    while (index < source.size()) {
      if (source.get(index) instanceof Message.ToolResult) {
        ObjectNode turn = messages.addObject();
        turn.put("role", "user");
        ArrayNode blocks = turn.putArray("content");
        while (index < source.size() && source.get(index) instanceof Message.ToolResult result) {
          blocks.add(toolResultBlock(result));
          index++;
        }
        continue;
      }
      messages.add(toWire(source.get(index)));
      index++;
    }
  }

  private static ObjectNode toWire(Message message) {
    return switch (message) {
      case Message.System system -> textTurn(system.text());
      case Message.User user -> textTurn(user.text());
      case Message.Assistant assistant -> assistantTurn(assistant);
      case Message.ToolResult result -> {
        ObjectNode turn = Json.object();
        turn.put("role", "user");
        turn.putArray("content").add(toolResultBlock(result));
        yield turn;
      }
    };
  }

  private static ObjectNode textTurn(String text) {
    ObjectNode node = Json.object();
    node.put("role", "user");
    node.put("content", text);
    return node;
  }

  private static ObjectNode assistantTurn(Message.Assistant assistant) {
    ObjectNode node = Json.object();
    node.put("role", "assistant");
    ArrayNode blocks = node.putArray("content");
    if (!assistant.text().isEmpty()) {
      ObjectNode text = blocks.addObject();
      text.put("type", "text");
      text.put("text", assistant.text());
    }
    for (Message.ToolCall call : assistant.toolCalls()) {
      ObjectNode use = blocks.addObject();
      use.put("type", "tool_use");
      use.put("id", call.id());
      use.put("name", call.name());
      use.set("input", Json.parse(call.arguments()));
    }
    if (blocks.isEmpty()) {
      // An empty content array is rejected, so a silent assistant turn still needs one block.
      ObjectNode text = blocks.addObject();
      text.put("type", "text");
      text.put("text", "");
    }
    return node;
  }

  private static ObjectNode toolResultBlock(Message.ToolResult result) {
    ObjectNode block = Json.object();
    block.put("type", "tool_result");
    block.put("tool_use_id", result.toolCallId());
    block.put("content", result.content());
    block.put("is_error", result.error());
    return block;
  }

  /**
   * Reads the event stream to completion. Usage is reported once, when {@code message_delta} turns
   * the output token count into a total, because the input count on its own is not a completed
   * measurement a caller could act on.
   */
  private Message.Assistant consume(Stream<String> lines, Consumer<Event> sink) throws Exception {
    Map<Integer, Block> blocks = new TreeMap<>();
    int inputTokens = 0;
    int outputTokens = 0;
    Integer cachedInputTokens = null;
    boolean usageEmitted = false;
    try (Sse sse = Sse.of(lines)) {
      for (Sse.Event event = sse.next(); event != null; event = sse.next()) {
        if (event.isDone()) {
          break;
        }
        JsonNode payload = Json.parse(event.data());
        String type = payload.path("type").asText(event.event());
        switch (type) {
          case "message_start" -> {
            JsonNode usage = payload.path("message").path("usage");
            JsonNode read = usage.get("cache_read_input_tokens");
            JsonNode created = usage.get("cache_creation_input_tokens");
            if (read == null && created == null) {
              inputTokens = usage.path("input_tokens").asInt(inputTokens);
            } else {
              // Cache reads and writes are reported *in addition to* input_tokens, so the prompt is
              // their sum — otherwise a cached turn would look smaller than an uncached one.
              int readTokens = read == null ? 0 : read.asInt();
              int createdTokens = created == null ? 0 : created.asInt();
              inputTokens = usage.path("input_tokens").asInt(0) + readTokens + createdTokens;
              cachedInputTokens = readTokens;
            }
          }
          case "content_block_start" -> openBlock(payload, blocks, sink);
          case "content_block_delta" -> appendDelta(payload, blocks, sink);
          case "content_block_stop" -> {
            // Every fragment of the block has arrived; nothing left to do.
          }
          case "message_delta" -> {
            outputTokens = payload.path("usage").path("output_tokens").asInt(outputTokens);
            sink.accept(new Event.Usage(inputTokens, outputTokens, cachedInputTokens));
            usageEmitted = true;
          }
          case "message_stop" -> {
            if (!usageEmitted && (inputTokens != 0 || outputTokens != 0)) {
              sink.accept(new Event.Usage(inputTokens, outputTokens, cachedInputTokens));
            }
          }
          case "error" -> throw new Exception("anthropic error: " + payload.path("error"));
          default -> {
            // ping and future event types carry no content.
          }
        }
      }
    }
    StringBuilder text = new StringBuilder();
    List<Message.ToolCall> calls = new ArrayList<>();
    for (Block block : blocks.values()) {
      if (block.toolUse) {
        calls.add(new Message.ToolCall(block.id, block.name, block.arguments.toString()));
      } else {
        text.append(block.text);
      }
    }
    return new Message.Assistant(text.toString(), calls);
  }

  private static void openBlock(
      JsonNode payload, Map<Integer, Block> blocks, Consumer<Event> sink) {
    JsonNode content = payload.path("content_block");
    Block block = new Block("tool_use".equals(content.path("type").asText()));
    if (block.toolUse) {
      block.id = content.path("id").asText("");
      block.name = content.path("name").asText("");
      sink.accept(new Event.ToolCallStart(block.id, block.name));
    }
    blocks.put(payload.path("index").asInt(0), block);
  }

  private static void appendDelta(
      JsonNode payload, Map<Integer, Block> blocks, Consumer<Event> sink) {
    int index = payload.path("index").asInt(0);
    JsonNode delta = payload.path("delta");
    switch (delta.path("type").asText("")) {
      case "text_delta" -> {
        String text = delta.path("text").asText("");
        if (!text.isEmpty()) {
          blocks.computeIfAbsent(index, i -> new Block(false)).text.append(text);
          sink.accept(new Event.TextDelta(text));
        }
      }
      case "input_json_delta" ->
          blocks
              .computeIfAbsent(index, i -> new Block(true))
              .arguments
              .append(delta.path("partial_json").asText(""));
      default -> {
        // thinking/signature deltas are not part of the assistant turn.
      }
    }
  }

  private static String stripTrailingSlash(String url) {
    String stripped = url.strip();
    return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
  }

  /** One content block of the reply: either prose or one tool call, streamed in fragments. */
  private static final class Block {
    private final boolean toolUse;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();
    private String id = "";
    private String name = "";

    private Block(boolean toolUse) {
      this.toolUse = toolUse;
    }
  }
}
