package com.ccj.agent.tool;

import com.ccj.agent.core.ToolContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Running one command the way this agent runs commands: a shell, a closed stdin, a bounded capture,
 * a deadline, and a kill that reaches the process tree.
 *
 * <p>Shared by the two places that run something — the `bash` tool, where the command is the model's,
 * and the post-edit check, where it is the user's configuration. They need the same four things for
 * the same reasons, and the version of this that existed inside `bash` alone was already the version
 * a second caller would have copy-pasted.
 *
 * <p>What it deliberately does not do is decide whether running is allowed. Approval, and the
 * question of whether a command comes from the model or from the config file, belongs to the caller
 * that knows which it is.
 */
final class ProcessRunner {

  private static final String SHELL = "/bin/bash";
  private static final long CANCEL_POLL_MILLIS = 150;
  private static final int PUMP_JOIN_MILLIS = 2000;

  private ProcessRunner() {}

  /**
   * What a finished command left behind.
   *
   * @param exitCode the shell's status, or -1 when it never exited on its own
   * @param finished false when the deadline or a cancellation ended it, which is why it was killed
   * @param cancelled true when the run that owns {@code ctx} asked to stop
   * @param output stdout and stderr merged, bounded by the context's output limit
   * @param millis how long it ran, for the caller that wants to say so
   */
  record Result(
      int exitCode, boolean finished, boolean cancelled, String output, long millis) {

    /** True when the command ran to completion and reported success. */
    boolean succeeded() {
      return finished && exitCode == 0;
    }
  }

  /**
   * Runs {@code command} through {@code /bin/bash -lc} in {@code cwd}.
   *
   * <p>{@code outputLimitBytes} is the caller's, not the context's: a `bash` call spends the turn's
   * whole budget on the model's command, while a check that runs after every edit spends a few
   * kilobytes, because its output is going into a result the user pays for on every later turn.
   */
  static Result run(
      String command, Path cwd, int timeoutSeconds, int outputLimitBytes, ToolContext ctx)
      throws InterruptedException {
    long started = System.nanoTime();
    Process process;
    try {
      process =
          new ProcessBuilder(SHELL, "-lc", command)
              .directory(cwd.toFile())
              .redirectErrorStream(true)
              .start();
    } catch (IOException e) {
      return new Result(-1, false, ctx.isCancelled(), "failed to start " + SHELL + ": " + e.getMessage(), 0);
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

    int limit = Math.max(1, outputLimitBytes);
    int headCapacity = Math.max(1, limit * 6 / 10);
    Capture capture = new Capture(headCapacity, Math.max(1, limit - headCapacity));
    Thread pump =
        Thread.ofVirtual()
            .name("command-output")
            .start(
                () -> {
                  try (InputStream in = process.getInputStream()) {
                    capture.drain(in);
                  } catch (IOException ignored) {
                    // The process was killed mid-read; keep whatever was captured.
                  }
                });

    boolean finished = waitFor(process, timeoutSeconds, ctx);
    if (!finished) {
      killTree(process);
    }
    pump.join(PUMP_JOIN_MILLIS);

    long millis = (System.nanoTime() - started) / 1_000_000;
    int exit = finished ? process.exitValue() : -1;
    return new Result(exit, finished, ctx.isCancelled(), capture.render(limit), millis);
  }

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
