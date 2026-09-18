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
 * 在 {@link Message} 与会话文件里存的 JSON 形状之间来回序列化。
 *
 * <p>格式是每条消息一个扁平对象，带一个 {@code type} 判别字段，于是会话日志用普通的 JSON 工具就能读，
 * 不认识新消息类型的读取方也能直接把它丢掉。工具调用的参数按原样保留为原始字符串：核心从不解析它们，
 * 而重新序列化模型给的字节会改变下一个请求发出去的内容。
 *
 * <p>解码是刻意严格的。会话文件是循环要回喂给提供方的历史，所以一次静默的强制转换（缺失的文本变成
 * {@code ""}、在该放字符串的地方接受了对象）会污染这段会话，而不是把坏文件暴露出来。
 */
public final class MessageCodec {

  public static final String TYPE_SYSTEM = "system";
  public static final String TYPE_USER = "user";
  public static final String TYPE_ASSISTANT = "assistant";
  public static final String TYPE_TOOL_RESULT = "tool_result";
  /** 模型写的摘要，代替一次压缩替换掉的那些消息。 */
  public static final String TYPE_SUMMARY = "summary";

  private MessageCodec() {}

  public static ObjectNode toNode(Message message) {
    if (message == null) {
      throw new IllegalArgumentException("message 不能为 null");
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
        // 有内容时才写出：在 thinking 存在之前记录的会话保持逐字节相同，而在其它提供方那里这个字段不花
        // 任何代价。
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
      case Message.Summary summary -> {
        node.put("type", TYPE_SUMMARY);
        node.put("text", summary.text());
        // 有内容可说时才写出：在压缩存在之前生成的代文件，或者测试手工构造的一个，没有这些字段时保持逐
        // 字节相同。
        if (summary.covers() > 0) {
          node.put("covers", summary.covers());
        }
        if (!summary.source().isEmpty()) {
          node.put("source", summary.source());
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
          "message 必须是 JSON 对象，实际为 " + describe(node));
    }
    JsonNode type = node.get("type");
    if (type == null || type.isNull()) {
      throw new IllegalArgumentException("message 缺少 'type' 判别字段");
    }
    if (!type.isTextual()) {
      throw new IllegalArgumentException("message 的 'type' 必须是字符串，实际为 " + describe(type));
    }
    return switch (type.asText()) {
      case TYPE_SYSTEM -> new Message.System(text(node, "text"));
      case TYPE_USER -> new Message.User(text(node, "text"));
      case TYPE_ASSISTANT ->
          new Message.Assistant(
              text(node, "text"), toolCalls(node.get("tool_calls")), thinking(node.get("thinking")));
      case TYPE_SUMMARY -> new Message.Summary(text(node, "text"), covers(node), source(node));
      case TYPE_TOOL_RESULT ->
          new Message.ToolResult(
              text(node, "tool_call_id"),
              text(node, "tool_name"),
              text(node, "content"),
              bool(node, "error"));
      default -> throw new IllegalArgumentException("未知的消息类型：" + type.asText());
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
          "message 的 'tool_calls' 字段必须是数组，实际为 " + describe(node));
    }
    List<Message.ToolCall> calls = new ArrayList<>(node.size());
    for (int i = 0; i < node.size(); i++) {
      JsonNode item = node.get(i);
      if (!item.isObject()) {
        throw new IllegalArgumentException(
            "tool_calls[" + i + "] 必须是 JSON 对象，实际为 " + describe(item));
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
   * thinking 块；记录里没有时什么也不给。既没有 text、signature 也没有 data 的块会被跳过而不存储：
   * 那是 API 会拒绝的一次重放。
   */
  private static List<Message.Thinking> thinking(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException(
          "message 的 'thinking' 字段必须是数组，实际为 " + describe(node));
    }
    List<Message.Thinking> blocks = new ArrayList<>(node.size());
    for (int i = 0; i < node.size(); i++) {
      JsonNode item = node.get(i);
      if (!item.isObject()) {
        throw new IllegalArgumentException(
            "thinking[" + i + "] 必须是 JSON 对象，实际为 " + describe(item));
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

  /**
   * 一条摘要替换掉了多少条消息。缺失时是 0 而不是错误：这个计数是转录要显示的东西，手工写的代文件
   * 没有它也仍然是一条可用的摘要。
   */
  private static int covers(JsonNode node) {
    JsonNode value = node.get("covers");
    if (value == null || value.isNull()) {
      return 0;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) {
      throw new IllegalArgumentException(
          "message 的 'covers' 字段必须是非负整数，实际为 " + describe(value));
    }
    return value.asInt();
  }

  /** 被摘要的那些消息仍在的文件，写的人没有指明时为空。 */
  private static String source(JsonNode node) {
    JsonNode value = node.get("source");
    return value == null || !value.isTextual() ? "" : value.asText();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("message 缺少 '" + field + "' 字段");
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(
          "message 的 '" + field + "' 字段必须是字符串，实际为 " + describe(value));
    }
    return value.asText();
  }

  private static boolean bool(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("message 缺少 '" + field + "' 字段");
    }
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(
          "message 的 '" + field + "' 字段必须是布尔值，实际为 " + describe(value));
    }
    return value.asBoolean();
  }

  private static String describe(JsonNode node) {
    return node == null ? "无" : node.getNodeType().toString().toLowerCase();
  }

  /**
   * 记账记录与会话共用同一个文件，但它们不是消息：codec 写出它们，好让累计值在重启后还在，
   * {@code FileSession} 则把它们从会话里滤掉。
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
    // 有内容时才写出：在压缩存在之前记录的会话，其 usage 行保持逐字节与原来相同，于是旧文件的任何部分
    // 都不会显得比它实际更新。
    if (totals.compactions() > 0) {
      node.put("compactions", totals.compactions());
    }
    node.put("cache_reported", totals.cacheReported());
    return Json.write(node);
  }

  public static UsageTotals totalsFromJson(String line) {
    JsonNode node = Json.parse(line);
    if (!FileSession.USAGE_TYPE.equals(node.path("type").asText())) {
      throw new IllegalArgumentException("不是 usage 记录");
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
        // 缺失读作 0，这正是从未被压缩过的会话记录下的值。
        node.path("compactions").asInt(0),
        node.path("cache_reported").asBoolean());
  }
}
