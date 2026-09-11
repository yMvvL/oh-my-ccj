package com.ccj.agent.tool;

import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Shell command runner.
 *
 * <p>Output is bounded in both directions: a chatty command that prints gigabytes must not exhaust
 * the agent's heap, so only the first 60% and last 40% of the configured budget are retained and
 * the omitted byte count is reported. stderr is merged into stdout so the interleaving the human
 * would see in a terminal is what the model reads.
 */
public final class BashTool implements Tool {

  private static final String SHELL = "/bin/bash";
  private static final int DEFAULT_TIMEOUT_SECONDS = 120;
  private static final int MAX_TIMEOUT_SECONDS = 600;
  private static final int PUMP_JOIN_MILLIS = 2000;

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

    Process process;
    try {
      process =
          new ProcessBuilder(SHELL, "-lc", command)
              .directory(directory.toFile())
              .redirectErrorStream(true)
              .start();
    } catch (IOException e) {
      return ToolResult.error("failed to start " + SHELL + ": " + e.getMessage());
    }

    int limit = ctx.outputLimitBytes();
    int headCapacity = Math.max(1, limit * 6 / 10);
    Capture capture = new Capture(headCapacity, Math.max(1, limit - headCapacity));
    Thread pump =
        Thread.ofVirtual()
            .name("bashtool-output")
            .start(
                () -> {
                  try (InputStream in = process.getInputStream()) {
                    capture.drain(in);
                  } catch (IOException ignored) {
                    // The process was killed mid-read; keep whatever was captured.
                  }
                });

    boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
    if (!finished) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    pump.join(PUMP_JOIN_MILLIS);

    String body = capture.render(limit);
    if (!finished) {
      return ToolResult.error(
          "exit code -1 (timed out after "
              + timeout
              + "s; process tree killed)\n"
              + body);
    }
    String content = "exit code " + process.exitValue() + "\n" + body;
    return process.exitValue() == 0 ? ToolResult.ok(content) : ToolResult.error(content);
  }

  /**
   * Head-and-tail capture: the first {@code headCapacity} bytes and a ring of the last {@code
   * tailCapacity} bytes, with the total counted so the omission can be reported exactly.
   */
  private static final class Capture {

    private final byte[] head;
    private final byte[] tail;
    private int headLength;
    private int tailPosition;
    private int tailLength;
    private long total;

    Capture(int headCapacity, int tailCapacity) {
      this.head = new byte[headCapacity];
      this.tail = new byte[tailCapacity];
    }

    void drain(InputStream in) throws IOException {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        write(buffer, read);
      }
    }

    private void write(byte[] buffer, int length) {
      total += length;
      int index = 0;
      while (index < length && headLength < head.length) {
        head[headLength++] = buffer[index++];
      }
      while (index < length && tail.length > 0) {
        tail[tailPosition] = buffer[index++];
        tailPosition = (tailPosition + 1) % tail.length;
        if (tailLength < tail.length) {
          tailLength++;
        }
      }
    }

    String render(int limit) {
      String headText = new String(head, 0, headLength, StandardCharsets.UTF_8);
      String tailText = new String(tailBytes(), StandardCharsets.UTF_8);
      if (total <= limit) {
        return headText + tailText;
      }
      long omitted = total - headLength - tailLength;
      return headText + "\n... omitted " + omitted + " bytes ...\n" + tailText;
    }

    private byte[] tailBytes() {
      byte[] ordered = new byte[tailLength];
      if (tailLength == 0) {
        return ordered;
      }
      int start = (tailPosition - tailLength + tail.length) % tail.length;
      for (int i = 0; i < tailLength; i++) {
        ordered[i] = tail[(start + i) % tail.length];
      }
      return ordered;
    }
  }
}
