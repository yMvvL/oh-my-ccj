package com.ccj.agent.core;

/**
 * Outcome of one tool invocation, as fed back to the model.
 *
 * <p>{@code error} is a wire-level flag: both providers surface it so the model can tell "the tool
 * said no" from "the tool worked".
 */
public record ToolResult(String content, boolean error) {

  public ToolResult {
    content = content == null ? "" : content;
  }

  public static ToolResult ok(String content) {
    return new ToolResult(content, false);
  }

  public static ToolResult error(String content) {
    return new ToolResult(content, true);
  }
}
