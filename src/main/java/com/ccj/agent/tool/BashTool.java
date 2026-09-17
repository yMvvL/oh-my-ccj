package com.ccj.agent.tool;

import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shell command runner.
 *
 * <p>Output is bounded in both directions: a chatty command that prints gigabytes must not exhaust
 * the agent's heap, so only the first 60% and last 40% of the configured budget are retained and
 * the omitted byte count is reported. stderr is merged into stdout so the interleaving the human
 * would see in a terminal is what the model reads. The mechanism — the closed stdin, the bounded
 * capture, the deadline and the kill that reaches the process tree — is {@link ProcessRunner}, which
 * the post-edit check runs commands through as well.
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
      return ToolResult.error("cwd is not a directory: " + ToolSupport.display(ctx, directory));
    }
    int timeout =
        ToolSupport.optionalInt(
            args, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS, 1, MAX_TIMEOUT_SECONDS);

    StringBuilder detail =
        new StringBuilder(command)
            .append("\n(cwd: ")
            .append(directory)
            .append(", timeout: ")
            .append(timeout)
            .append("s)");
    if (!ctx.insideCwd(directory)) {
      detail.append(" [OUTSIDE session cwd ").append(ctx.cwd()).append(']');
    }
    if (!ctx.approve("bash", detail.toString())) {
      return ToolResult.error("rejected by user");
    }

    ProcessRunner.Result result = ProcessRunner.run(command, directory, timeout, ctx.outputLimitBytes(), ctx);
    if (!result.finished()) {
      return ToolResult.error(
          "exit code -1 ("
              + (result.cancelled() ? "aborted by the user" : "timed out after " + timeout + "s")
              + "; process tree killed)\n"
              + result.output());
    }
    String content = "exit code " + result.exitCode() + "\n" + result.output();
    return result.exitCode() == 0 ? ToolResult.ok(content) : ToolResult.error(content);
  }
}
