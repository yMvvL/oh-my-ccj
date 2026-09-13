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
    try {
      // Nobody is going to type at this process: the agent has no terminal to hand it. Leaving the
      // pipe open makes every command that reads stdin — `cat`, `sort`, a `read` in a script — wait
      // for input that will never come until the timeout kills it. Closing it hands them EOF, which
      // is what a command run by a script should see.
      process.getOutputStream().close();
    } catch (IOException e) {
      // Already gone; the exit status is reported either way.
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

    boolean finished = waitFor(process, timeout, ctx);
    if (!finished) {
      killTree(process);
    }
    pump.join(PUMP_JOIN_MILLIS);

    String body = capture.render(limit);
    if (!finished) {
      return ToolResult.error(
          "exit code -1 ("
              + (ctx.isCancelled() ? "aborted by the user" : "timed out after " + timeout + "s")
              + "; process tree killed)\n"
              + body);
    }
    String content = "exit code " + process.exitValue() + "\n" + body;
    return process.exitValue() == 0 ? ToolResult.ok(content) : ToolResult.error(content);
  }

  /** How often a running command checks whether the run was cancelled. */
  private static final long CANCEL_POLL_MILLIS = 150;

  /**
   * Waits for the command to finish, its own timeout to expire, or the run to be cancelled — the
   * last one is what makes "stop" mean stop instead of "wait for the ten-minute timeout".
   */
  private static boolean waitFor(Process process, int timeoutSeconds, ToolContext ctx)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (true) {
      if (process.waitFor(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
        return true;
      }
      if (ctx.isCancelled() || System.nanoTime() >= deadline) {
        return false;
      }
    }
  }

  /** Kills the command and everything it started, since a shell's children outlive the shell. */
  private static void killTree(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    try {
      process.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
      byte[] last = tailBytes();
      if (total <= limit) {
        // Nothing was dropped, so the two buffers are contiguous and are decoded as one array: a
        // character straddling the boundary between them must not become two replacement glyphs.
        byte[] whole = new byte[headLength + tailLength];
        System.arraycopy(head, 0, whole, 0, headLength);
        System.arraycopy(last, 0, whole, headLength, tailLength);
        return new String(whole, StandardCharsets.UTF_8);
      }
      // Something was omitted, so each half is decoded on its own — and a half that was cut in the
      // middle of a character drops that partial sequence instead of rendering it as garbage.
      int headEnd = headLength - partialTail(head, headLength);
      int tailStart = partialHead(last, tailLength);
      String headText = new String(head, 0, headEnd, StandardCharsets.UTF_8);
      String tailText = new String(last, tailStart, tailLength - tailStart, StandardCharsets.UTF_8);
      long omitted = total - headLength - tailLength;
      return headText + "\n... omitted " + omitted + " bytes ...\n" + tailText;
    }

    /**
     * Bytes at the end of a slice that are the beginning of a UTF-8 sequence whose remaining bytes
     * are not in the slice — what a cut in the middle of a character leaves behind.
     */
    private static int partialTail(byte[] bytes, int length) {
      int lead = -1;
      for (int i = length - 1; i >= 0 && i >= length - 4; i--) {
        int b = bytes[i] & 0xFF;
        if ((b & 0xC0) != 0x80) {
          lead = i;
          break;
        }
      }
      if (lead < 0) {
        return 0;
      }
      int expected = sequenceLength(bytes[lead] & 0xFF);
      int present = length - lead;
      return expected > present ? present : 0;
    }

    /** Leading continuation bytes of a slice whose first character started before it. */
    private static int partialHead(byte[] bytes, int length) {
      int skip = 0;
      while (skip < length && skip < 3 && (bytes[skip] & 0xC0) == 0x80) {
        skip++;
      }
      return skip;
    }

    private static int sequenceLength(int lead) {
      if (lead >= 0xF0) {
        return 4;
      }
      if (lead >= 0xE0) {
        return 3;
      }
      if (lead >= 0xC0) {
        return 2;
      }
      return 1;
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
