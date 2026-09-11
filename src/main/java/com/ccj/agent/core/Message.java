package com.ccj.agent.core;

import java.util.List;

/**
 * Provider-agnostic conversation message.
 *
 * <p>Tool call arguments stay raw JSON text: the core never interprets them, providers serialize
 * them onto the wire and tools parse them when invoked. That keeps the model of the conversation
 * independent from any particular vendor API.
 */
public sealed interface Message {

  record System(String text) implements Message {
    public System {
      text = text == null ? "" : text;
    }
  }

  record User(String text) implements Message {
    public User {
      text = text == null ? "" : text;
    }
  }

  /** Either prose, or tool calls, or both. An assistant turn with no tool calls ends the loop. */
  record Assistant(String text, List<ToolCall> toolCalls) implements Message {
    public Assistant {
      text = text == null ? "" : text;
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static Assistant text(String text) {
      return new Assistant(text, List.of());
    }

    public boolean hasToolCalls() {
      return !toolCalls.isEmpty();
    }
  }

  record ToolResult(String toolCallId, String toolName, String content, boolean error)
      implements Message {
    public ToolResult {
      toolCallId = toolCallId == null ? "" : toolCallId;
      toolName = toolName == null ? "" : toolName;
      content = content == null ? "" : content;
    }

    public static ToolResult ok(ToolCall call, String content) {
      return new ToolResult(call.id(), call.name(), content, false);
    }

    public static ToolResult failed(ToolCall call, String content) {
      return new ToolResult(call.id(), call.name(), content, true);
    }

    /** Wraps one execution outcome into the message that goes back to the model. */
    public static ToolResult of(ToolCall call, com.ccj.agent.core.ToolResult result) {
      return new ToolResult(call.id(), call.name(), result.content(), result.error());
    }
  }

  /** One tool invocation requested by the model. */
  record ToolCall(String id, String name, String arguments) {
    public ToolCall {
      id = id == null ? "" : id;
      name = name == null ? "" : name;
      arguments = arguments == null ? "" : arguments;
    }
  }
}
