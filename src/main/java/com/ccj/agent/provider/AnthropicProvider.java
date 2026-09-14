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
      return consume(response, lines, sink);
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
    String effort = request.reasoning();
    appendMessages(request.messages(), root.putArray("messages"), effort != null);
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
  private static void appendMessages(List<Message> source, ArrayNode messages, boolean thinking) {
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
      messages.add(toWire(source.get(index), thinking));
      index++;
    }
  }

  private static ObjectNode toWire(Message message, boolean thinking) {
    return switch (message) {
      case Message.System system -> textTurn(system.text());
      case Message.User user -> textTurn(user.text());
      // A summary goes on the wire as a user turn: the Messages API has no other shape that can carry
      // prose the model needs to read but did not say, and the marker inside the text is what keeps it
      // from reading as its own earlier words.
      case Message.Summary summary -> textTurn(summary.text());
      case Message.Assistant assistant -> assistantTurn(assistant, thinking);
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

  private static ObjectNode assistantTurn(Message.Assistant assistant, boolean thinking) {
    ObjectNode node = Json.object();
    node.put("role", "assistant");
    ArrayNode blocks = node.putArray("content");
    if (thinking) {
      // The API wants the model's own thinking back, first in the turn, whenever the request enables
      // extended thinking — the signature is what makes the block unforgeable, and a turn that drops
      // it is rejected. Nothing is sent when thinking is off: the blocks belong to a setting the
      // request is not asking for any more.
      for (Message.Thinking block : assistant.thinking()) {
        if (!block.redacted() && block.signature().isEmpty()) {
          // Unsigned thinking cannot be replayed: the stream was cut before the signature arrived.
          continue;
        }
        ObjectNode entry = blocks.addObject();
        if (block.redacted()) {
          entry.put("type", "redacted_thinking");
          entry.put("data", block.data());
        } else {
          entry.put("type", "thinking");
          entry.put("thinking", block.text());
          entry.put("signature", block.signature());
        }
      }
    }
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
      use.set("input", inputOf(call.arguments()));
    }
    if (blocks.isEmpty()) {
      // An empty content array is rejected, so a silent assistant turn still needs one block.
      ObjectNode text = blocks.addObject();
      text.put("type", "text");
      text.put("text", "");
    }
    return node;
  }

  /**
   * Tool arguments as the object {@code tool_use.input} must be.
   *
   * <p>The Messages API rejects a non-object input, and a stream that was cut off mid-call — the
   * model hit its token cap, the connection died — leaves the arguments half-written. Parsing that
   * text strictly would throw while the *next* request is being built, which is the one failure
   * with no way out: every later turn rebuilds the same history, so the session could never be sent
   * again. An empty input instead reaches the tool, which reports the missing argument back to the
   * model and keeps the conversation moving.
   */
  private static JsonNode inputOf(String arguments) {
    JsonNode parsed;
    try {
      parsed = Json.parse(arguments);
    } catch (IllegalArgumentException e) {
      return Json.object();
    }
    return parsed.isObject() ? parsed : Json.object();
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
  private Message.Assistant consume(
      HttpResponse<?> response, Stream<String> lines, Consumer<Event> sink) throws Exception {
    Map<Integer, Block> blocks = new TreeMap<>();
    int inputTokens = 0;
    int outputTokens = 0;
    Integer cachedInputTokens = null;
    boolean usageEmitted = false;
    StringBuilder arrived = new StringBuilder();
    int frames = 0;
    try (Stream<String> peeking = lines.peek(line -> Transport.remember(arrived, line));
        Sse sse = Sse.of(peeking)) {
      for (Sse.Event event = sse.next(); event != null; event = sse.next()) {
        frames++;
        if (event.isDone()) {
          break;
        }
        JsonNode payload = Json.parse(event.data());
        String type = payload.path("type").asText(event.event());
        switch (type) {
          case "message_start" -> {
            JsonNode usage = payload.path("message").path("usage");
            // A field that is present but null is not a measurement: only a number is a report, and
            // gateways send explicit nulls where the API itself omits the field.
            JsonNode read = number(usage.get("cache_read_input_tokens"));
            JsonNode created = number(usage.get("cache_creation_input_tokens"));
            if (read == null && created == null) {
              JsonNode input = number(usage.get("input_tokens"));
              inputTokens = input == null ? inputTokens : input.asInt();
            } else {
              // Cache reads and writes are reported *in addition to* input_tokens, so the prompt is
              // their sum — otherwise a cached turn would look smaller than an uncached one.
              int readTokens = read == null ? 0 : read.asInt();
              int createdTokens = created == null ? 0 : created.asInt();
              JsonNode input = number(usage.get("input_tokens"));
              inputTokens = (input == null ? 0 : input.asInt()) + readTokens + createdTokens;
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
    List<Message.Thinking> thinking = new ArrayList<>();
    if (frames == 0) {
      // A body with no frames is not a turn: reporting it as an empty answer would leave the user
      // with a silent stop and no way to see that the endpoint never spoke this protocol.
      throw new IllegalStateException(Transport.noEvents(response, arrived.toString()));
    }
    for (Block block : blocks.values()) {
      if (block.toolUse()) {
        calls.add(new Message.ToolCall(block.id, block.name, block.arguments.toString()));
      } else if (block.thinking() || block.redactedThinking()) {
        // Kept for the next request: with extended thinking on, the API expects these back.
        thinking.add(
            block.redactedThinking()
                ? Message.Thinking.redacted(block.payload)
                : Message.Thinking.of(block.text.toString(), block.signature.toString()));
      } else {
        text.append(block.text);
      }
    }
    return new Message.Assistant(text.toString(), calls, thinking);
  }

  private static void openBlock(
      JsonNode payload, Map<Integer, Block> blocks, Consumer<Event> sink) {
    JsonNode content = payload.path("content_block");
    String kind = content.path("type").asText("");
    Block block = new Block(kind);
    switch (kind) {
      case "tool_use" -> {
        block.id = content.path("id").asText("");
        block.name = content.path("name").asText("");
        JsonNode input = content.get("input");
        if (input != null && input.isObject() && !input.isEmpty()) {
          // The documented stream opens with an empty input and streams the arguments as
          // input_json_delta; a gateway that sends the finished object here would otherwise leave
          // the call with no arguments at all, and the tool would run with none.
          block.arguments.append(Json.write(input));
        }
        sink.accept(new Event.ToolCallStart(block.id, block.name));
      }
      case "thinking", "redacted_thinking" -> {
        // The opening block may already carry the first words (or, for a redacted block, the whole
        // opaque payload); the rest arrives as deltas.
        block.text.append(content.path("thinking").asText(""));
        block.payload = content.path("data").asText("");
        if (!block.text.isEmpty()) {
          sink.accept(new Event.ReasoningDelta(block.text.toString()));
        }
      }
      default -> {
        // The documented shape opens a text block empty and streams every character as a delta, but
        // a gateway is free to put the first words in the opening block instead. Dropping them would
        // be silent data loss in the middle of a sentence, so they are treated as a delta.
        String opening = content.path("text").asText("");
        if (!opening.isEmpty()) {
          block.text.append(opening);
          sink.accept(new Event.TextDelta(opening));
        }
      }
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
          blocks.computeIfAbsent(index, i -> new Block("text")).text.append(text);
          sink.accept(new Event.TextDelta(text));
        }
      }
      case "input_json_delta" ->
          blocks
              .computeIfAbsent(index, i -> new Block("tool_use"))
              .arguments
              .append(delta.path("partial_json").asText(""));
      case "thinking_delta" -> {
        // Extended thinking is on whenever a reasoning tier is set, and a turn can spend a long time
        // there; forwarding it keeps the front end from looking stalled, and keeping it is what lets
        // the next request hand the block back.
        String thinking = delta.path("thinking").asText("");
        blocks.computeIfAbsent(index, i -> new Block("thinking")).text.append(thinking);
        if (!thinking.isEmpty()) {
          sink.accept(new Event.ReasoningDelta(thinking));
        }
      }
      case "signature_delta" ->
          blocks
              .computeIfAbsent(index, i -> new Block("thinking"))
              .signature
              .append(delta.path("signature").asText(""));
      default -> {
        // Redacted thinking arrives whole in its opening block; future delta kinds carry nothing.
      }
    }
  }

  /**
   * A JSON number, or null when the field is absent <em>or</em> explicitly null.
   *
   * <p>Jackson hands back a {@code NullNode} rather than Java null for a null it saw, so a plain
   * null check would read "the endpoint reported no cache figures" as "zero tokens were cached" and
   * turn an unknown into a measurement.
   */
  private static JsonNode number(JsonNode node) {
    return node == null || !node.isNumber() ? null : node;
  }

  private static String stripTrailingSlash(String url) {
    String stripped = url.strip();
    return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
  }

  /**
   * One content block of the reply: prose, one tool call, or one thinking block, streamed in
   * fragments. Which it is decides what a fragment appends to and what the finished turn carries.
   */
  private static final class Block {
    private final String kind;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();
    private final StringBuilder signature = new StringBuilder();
    private String id = "";
    private String name = "";
    /** The opaque payload of a {@code redacted_thinking} block, which cannot be read as text. */
    private String payload = "";

    private Block(String kind) {
      this.kind = kind;
    }

    private boolean toolUse() {
      return "tool_use".equals(kind);
    }

    private boolean thinking() {
      return "thinking".equals(kind);
    }

    private boolean redactedThinking() {
      return "redacted_thinking".equals(kind);
    }
  }
}
