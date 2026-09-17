package com.ccj.agent.ui;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One-line digest of a tool call's arguments, shared by every front end.
 *
 * <p>Raw JSON in a tool card is unreadable and nothing at all is useless, so both the terminal and
 * the web UI show the same thing: the one argument a human actually cares about, clipped to one line.
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
