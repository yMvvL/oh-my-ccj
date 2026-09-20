package com.ccj.agent.ui;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具调用参数的一行摘要，被每个前端共用。
 *
 * <p>工具卡片里的原始 JSON 读不了，而什么都不给又毫无用处，所以终端和 web UI 显示同样的东西：
 * 人真正在意的那一个参数，裁到一行。对一次委派，人真正在意的是谁被派去做什么，而不是那条被转义成一行的
 * 任务文本。
 */
public final class ToolSummary {

  public static final int WIDTH = 80;

  private ToolSummary() {}

  public static String summarise(Message.ToolCall call) {
    return summarise(call, WIDTH);
  }

  public static String summarise(Message.ToolCall call, int width) {
    String raw = call.arguments();
    if (raw == null || raw.isBlank()) {
      return "";
    }
    JsonNode args;
    try {
      args = Json.parse(raw);
    } catch (IllegalArgumentException e) {
      return clip(raw, width);
    }
    if (!args.isObject()) {
      return clip(raw, width);
    }
    String tool = call.name() == null ? "" : call.name();
    String value =
        switch (tool) {
          case "read", "write", "edit" -> firstText(args, "path", "file_path", "file");
          case "bash" -> firstText(args, "command", "cmd", "script");
          case "grep", "glob" -> firstText(args, "pattern", "query");
          case "task" -> delegation(args);
          default -> null;
        };
    return value != null && !value.isBlank() ? clip(value, width) : clip(Json.write(args), width);
  }

  private static String firstText(JsonNode args, String... fields) {
    for (String field : fields) {
      JsonNode value = args.get(field);
      if (value != null && value.isTextual()) {
        return value.asText();
      }
    }
    return null;
  }

  /**
   * 一次委派的卡片：谁被派去做什么。
   *
   * <p>那条参数 JSON 是一整段任务文本被转义成的一行，读起来比任务本身还长；而少了角色名，一张卡也说不清被
   * 派出去的是谁——那恰恰是这次调用唯一让人意外的地方。角色加任务的第一行，和别处所有卡片的信息量一致。
   */
  private static String delegation(JsonNode args) {
    String role = firstText(args, "role");
    String task = firstText(args, "task");
    String opening = task == null ? "" : firstLine(task);
    if (opening.isEmpty()) {
      return role;
    }
    return role == null || role.isBlank() ? opening : role + ": " + opening;
  }

  /** 任务的第一行：多行的任务说明是由它开头那句话定义的，而不是它中途的某一行。 */
  private static String firstLine(String text) {
    int end = text.indexOf('\n');
    return (end < 0 ? text : text.substring(0, end)).strip();
  }

  private static String clip(String text, int width) {
    if (text == null) {
      return "";
    }
    String flat = text.replaceAll("\\s+", " ").strip();
    if (flat.length() <= width) {
      return flat;
    }
    return flat.substring(0, width - 1).stripTrailing() + "…";
  }
}
