package com.ccj.agent.core;

/**
 * 一次工具调用的结果，会回喂给模型。
 *
 * <p>{@code error} 是线路层的标志：两个提供方都会把它暴露出来，好让模型分辨「工具说不行」和「工具干完了」。
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
