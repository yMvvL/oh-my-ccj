package com.ccj.agent.session;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.UsageTotals;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes {@link Message} to and from the JSON shape stored in a session file.
 *
 * <p>The format is one flat object per message with a {@code type} discriminator, so a session log
 * stays readable with ordinary JSON tooling and stays throwable away by any reader that does not
 * know a new message kind. Tool call arguments are kept verbatim as a raw string: the core never
 * parses them, and re-serializing the model's bytes would change what the next request sends.
 *
 * <p>Decoding is deliberately strict. A session file is history the loop will feed back to a
 * provider, so a silent coercion (missing text becoming {@code ""}, an object accepted where a
 * string belongs) would corrupt the conversation instead of surfacing the bad file.
 */
public final class MessageCodec {

  public static final String TYPE_SYSTEM = "system";
  public static final String TYPE_USER = "user";
  public static final String TYPE_ASSISTANT = "assistant";
  public static final String TYPE_TOOL_RESULT = "tool_result";

  private MessageCodec() {}

  public static ObjectNode toNode(Message message) {
    if (message == null) {
      throw new IllegalArgumentException("message must not be null");
    }
    ObjectNode node = Json.object();
    switch (message) {
      case Message.System system -> {
        node.put("type", TYPE_SYSTEM);
        node.put("text", system.text());
      }
      case Message.User user -> {
        node.put("type", TYPE_USER);
        node.put("text", user.text());
      }
      case Message.Assistant assistant -> {
        node.put("type", TYPE_ASSISTANT);
        node.put("text", assistant.text());
        ArrayNode calls = node.putArray("tool_calls");
        for (Message.ToolCall call : assistant.toolCalls()) {
          ObjectNode encoded = calls.addObject();
          encoded.put("id", call.id());
          encoded.put("name", call.name());
          encoded.put("arguments", call.arguments());
        }
        // Written only when there is one: a session recorded before thinking existed stays
        // byte-identical, and the field costs nothing on every other provider.
        if (!assistant.thinking().isEmpty()) {
          ArrayNode blocks = node.putArray("thinking");
          for (Message.Thinking block : assistant.thinking()) {
            ObjectNode encoded = blocks.addObject();
            if (!block.text().isEmpty()) {
              encoded.put("text", block.text());
            }
            if (!block.signature().isEmpty()) {
              encoded.put("signature", block.signature());
            }
            if (!block.data().isEmpty()) {
              encoded.put("data", block.data());
            }
          }
        }
      }
      case Message.ToolResult result -> {
        node.put("type", TYPE_TOOL_RESULT);
        node.put("tool_call_id", result.toolCallId());
        node.put("tool_name", result.toolName());
        node.put("content", result.content());
        node.put("error", result.error());
      }
    }
    return node;
  }

  public static String toJson(Message message) {
    return Json.write(toNode(message));
  }

  public static Message fromNode(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException(
          "message must be a JSON object, got " + describe(node));
    }
    JsonNode type = node.get("type");
    if (type == null || type.isNull()) {
      throw new IllegalArgumentException("message is missing the 'type' discriminator");
    }
    if (!type.isTextual()) {
      throw new IllegalArgumentException("message 'type' must be a string, got " + describe(type));
    }
    return switch (type.asText()) {
      case TYPE_SYSTEM -> new Message.System(text(node, "text"));
      case TYPE_USER -> new Message.User(text(node, "text"));
      case TYPE_ASSISTANT ->
          new Message.Assistant(
              text(node, "text"), toolCalls(node.get("tool_calls")), thinking(node.get("thinking")));
      case TYPE_TOOL_RESULT ->
          new Message.ToolResult(
              text(node, "tool_call_id"),
              text(node, "tool_name"),
              text(node, "content"),
              bool(node, "error"));
      default -> throw new IllegalArgumentException("unknown message type: " + type.asText());
    };
  }

  public static Message fromJson(String json) {
    return fromNode(Json.parse(json));
  }

  private static List<Message.ToolCall> toolCalls(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException(
          "message field 'tool_calls' must be an array, got " + describe(node));
    }
    List<Message.ToolCall> calls = new ArrayList<>(node.size());
    for (int i = 0; i < node.size(); i++) {
      JsonNode item = node.get(i);
      if (!item.isObject()) {
        throw new IllegalArgumentException(
            "tool_calls[" + i + "] must be a JSON object, got " + describe(item));
      }
      try {
        calls.add(
            new Message.ToolCall(text(item, "id"), text(item, "name"), text(item, "arguments")));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("tool_calls[" + i + "]: " + e.getMessage(), e);
      }
    }
    return calls;
  }

  /**
   * Thinking blocks, or nothing when the record has none. A block that carries neither text,
   * signature nor data is skipped rather than stored: it is a replay the API would reject.
   */
  private static List<Message.Thinking> thinking(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException(
          "message field 'thinking' must be an array, got " + describe(node));
    }
    List<Message.Thinking> blocks = new ArrayList<>(node.size());
    for (int i = 0; i < node.size(); i++) {
      JsonNode item = node.get(i);
      if (!item.isObject()) {
        throw new IllegalArgumentException(
            "thinking[" + i + "] must be a JSON object, got " + describe(item));
      }
      Message.Thinking block =
          new Message.Thinking(
              optionalText(item, "text"), optionalText(item, "signature"), optionalText(item, "data"));
      if (!block.text().isEmpty() || !block.signature().isEmpty() || !block.data().isEmpty()) {
        blocks.add(block);
      }
    }
    return blocks;
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || !value.isTextual() ? "" : value.asText();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("message field '" + field + "' is missing");
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(
          "message field '" + field + "' must be a string, got " + describe(value));
    }
    return value.asText();
  }

  private static boolean bool(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("message field '" + field + "' is missing");
    }
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(
          "message field '" + field + "' must be a boolean, got " + describe(value));
    }
    return value.asBoolean();
  }

  private static String describe(JsonNode node) {
    return node == null ? "nothing" : node.getNodeType().toString().toLowerCase();
  }

  /**
   * Accounting records share the session file but are not messages: the codec writes them so totals
   * survive a restart, and {@code FileSession} filters them out of the conversation.
   */
  public static String totalsToJson(UsageTotals totals) {
    ObjectNode node = Json.object();
    node.put("type", FileSession.USAGE_TYPE);
    node.put("input_tokens", totals.inputTokens());
    node.put("output_tokens", totals.outputTokens());
    node.put("cached_input_tokens", totals.cachedInputTokens());
    node.put("user_turns", totals.userTurns());
    node.put("model_turns", totals.modelTurns());
    node.put("tool_calls", totals.toolCalls());
    node.put("tool_errors", totals.toolErrors());
    node.put("elapsed_ms", totals.elapsedMillis());
    node.put("cache_reported", totals.cacheReported());
    return Json.write(node);
  }

  public static UsageTotals totalsFromJson(String line) {
    JsonNode node = Json.parse(line);
    if (!FileSession.USAGE_TYPE.equals(node.path("type").asText())) {
      throw new IllegalArgumentException("not a usage record");
    }
    return new UsageTotals(
        node.path("input_tokens").asLong(),
        node.path("output_tokens").asLong(),
        node.path("cached_input_tokens").asLong(),
        node.path("user_turns").asInt(),
        node.path("model_turns").asInt(),
        node.path("tool_calls").asInt(),
        node.path("tool_errors").asInt(),
        node.path("elapsed_ms").asLong(),
        node.path("cache_reported").asBoolean());
  }
}
