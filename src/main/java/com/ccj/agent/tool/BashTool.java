package com.ccj.agent.tool;

import com.ccj.agent.core.ApprovalRequest;
import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shell 命令执行器。
 *
 * <p>输出两端都有限：一条话多到打印出几个 GB 的命令不能耗光代理的堆，所以只保留配置预算的前 60% 与后
 * 40%，并报告被省略的字节数。stderr 合并进 stdout，因此人在终端里看到的交错顺序正是模型读到的内容。
 * 具体机制——关闭的 stdin、有界的捕获、截止时间，以及能杀到整棵进程树的 kill——都在 {@link ProcessRunner}
 * 里，编辑后检查同样通过它执行命令。
 */
public final class BashTool implements Tool {

  private static final int DEFAULT_TIMEOUT_SECONDS = 120;
  private static final int MAX_TIMEOUT_SECONDS = 600;

  @Override
  public String name() {
    return "bash";
  }

  @Override
  public String description() {
    return "Run a command with /bin/bash -lc, with stderr merged into stdout, and return the exit "
        + "code and output. Prefer the read/write/edit/glob/grep tools for file work.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "command": {
              "type": "string",
              "description": "Shell command to run."
            },
            "cwd": {
              "type": "string",
              "description": "Directory to run in. Defaults to the session working directory."
            },
            "timeout_seconds": {
              "type": "integer",
              "description": "Kill the command after this many seconds. Defaults to 120, maximum 600."
            }
          },
          "required": ["command"],
          "additionalProperties": false
        }""";
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) throws Exception {
    JsonNode args = ToolSupport.args(argumentsJson);
    String command = ToolSupport.requireNonBlank(args, "command");
    String cwdArg = ToolSupport.optionalText(args, "cwd");
    Path directory =
        cwdArg == null || cwdArg.isBlank() ? ctx.cwd() : ctx.resolve(cwdArg);
    if (!Files.isDirectory(directory)) {
      return ToolResult.error("cwd 不是目录: " + ToolSupport.display(ctx, directory));
    }
    int timeout =
        ToolSupport.optionalInt(
            args, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS, 1, MAX_TIMEOUT_SECONDS);

    StringBuilder detail =
        new StringBuilder(command)
            .append("\n(工作目录: ")
            .append(directory)
            .append("，超时: ")
            .append(timeout)
            .append("s)");
    if (!ctx.insideCwd(directory)) {
      detail.append(" [在会话工作区之外 ").append(ctx.cwd()).append(']');
    }
    String refusal = ctx.refusal(ApprovalRequest.command(command, detail.toString()));
    if (refusal != null) {
      return ToolResult.error(refusal);
    }

    ProcessRunner.Result result = ProcessRunner.run(command, directory, timeout, ctx.outputLimitBytes(), ctx);
    if (!result.finished()) {
      return ToolResult.error(
          "exit code -1 ("
              + (result.cancelled() ? "用户已中止" : "运行 " + timeout + "s 后超时")
              + "；整个进程树已被杀掉)\n"
              + result.output());
    }
    String content = "exit code " + result.exitCode() + "\n" + result.output();
    return result.exitCode() == 0 ? ToolResult.ok(content) : ToolResult.error(content);
  }
}
