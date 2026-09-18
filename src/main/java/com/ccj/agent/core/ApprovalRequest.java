package com.ccj.agent.core;

import java.nio.file.Path;

/**
 * 工具在申请做什么，用能写进规则的措辞表达。
 *
 * <p>在此之前，请求是两个字符串——一个标题和一段细节——那恰是人需要的形状，也恰是规则用不了的形状：
 * 「我能运行这条命令吗」和「我能写这个路径吗」是两个不同的问题，而一条靠差异文本的散文来匹配的规则，只会是
 * 一条碰巧匹配上的规则。散文仍然保留，因为提示本身仍要可读。
 *
 * @param tool 工具名，也是规则最先点名的东西
 * @param command shell 命令，适用于会运行命令的工具，否则为 null
 * @param path 被写入的文件，适用于会写文件的工具，否则为 null
 * @param title 提示问的是什么（"bash"、"edit"）
 * @param detail 完整的人类可读请求，展示给作答的人
 */
public record ApprovalRequest(String tool, String command, Path path, String title, String detail) {

  public ApprovalRequest {
    if (tool == null || tool.isBlank()) {
      throw new IllegalArgumentException("审批请求必须指明工具名");
    }
    title = title == null || title.isBlank() ? tool : title;
    detail = detail == null ? "" : detail;
  }

  /** 运行一条 shell 命令的请求。 */
  public static ApprovalRequest command(String command, String detail) {
    return new ApprovalRequest("bash", command, null, "bash", detail);
  }

  /** 写入一个文件的请求，整体写或部分写。 */
  public static ApprovalRequest file(String tool, Path path, String detail) {
    return new ApprovalRequest(tool, null, path, tool, detail);
  }

  /** 两者都不是的请求：`restart` 会结束本次运行，除了名字它没有可匹配的东西。 */
  public static ApprovalRequest tool(String tool, String detail) {
    return new ApprovalRequest(tool, null, null, tool, detail);
  }

  /** 转录里的一行，说明批准了什么、依哪条规则。 */
  public String summary() {
    if (command != null) {
      return tool + ": " + command;
    }
    return path != null ? tool + ": " + path : tool;
  }
}
