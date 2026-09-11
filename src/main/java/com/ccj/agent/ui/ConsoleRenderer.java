package com.ccj.agent.ui;

import com.ccj.agent.core.AgentListener;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.PrintStream;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Turns loop callbacks into something a human watching a terminal can follow.
 *
 * <p>Assistant prose is streamed as it arrives; reasoning is dimmed so it reads as a whisper; tool
 * calls become a compact card whose argument summary is derived from the call's JSON so the user
 * sees {@code read src/Main.java} rather than a wall of arguments. The renderer must never be the
 * reason a run is unreadable, so every decision here degrades to plain lines when there is no TTY.
 */
public final class ConsoleRenderer implements AgentListener {

  private static final int SUMMARY_WIDTH = 80;
  private static final int RESULT_LINES = 3;

  private final PrintStream out;
  private final PrintStream err;
  private final boolean color;
  private final Spinner spinner;

  private final Set<String> pendingStart = new LinkedHashSet<>();
  private final Set<String> rendered = new LinkedHashSet<>();

  private boolean lineOpen;
  private boolean turnTextStarted;
  private boolean reasoningStarted;
  private boolean spinnerPaused;
  private int pendingTools;

  /** Auto-detects colour from the terminal and the {@code NO_COLOR} convention. */
  public ConsoleRenderer() {
    this(System.out, System.err, Ansi.enabled());
  }

  public ConsoleRenderer(PrintStream out, PrintStream err, boolean color) {
    this.out = out;
    this.err = err;
    this.color = color;
    this.spinner = new Spinner();
  }

  @Override
  public void onTurnStart(int step) {
    turnTextStarted = false;
    reasoningStarted = false;
    pendingStart.clear();
    rendered.clear();
  }

  @Override
  public void onText(String delta) {
    if (delta == null || delta.isEmpty()) {
      return;
    }
    if (!turnTextStarted) {
      beginTextBlock();
      turnTextStarted = true;
    }
    out.print(delta);
    out.flush();
    lineOpen = !delta.endsWith("\n");
  }

  @Override
  public void onReasoning(String delta) {
    if (delta == null || delta.isEmpty()) {
      return;
    }
    if (!reasoningStarted) {
      ensureNewline();
      reasoningStarted = true;
    }
    out.print(Ansi.style("2", delta, color));
    out.flush();
    lineOpen = !delta.endsWith("\n");
  }

  @Override
  public void onAssistant(Message.Assistant message) {
    // Providers that do not stream deltas still have to be visible.
    if (!turnTextStarted && !message.text().isBlank()) {
      beginTextBlock();
      out.print(message.text());
      out.flush();
      lineOpen = !message.text().endsWith("\n");
    }
    ensureNewline();

    for (Message.ToolCall call : message.toolCalls()) {
      if (pendingStart.remove(call.id()) || !rendered.contains(call.id())) {
        printToolCard(call);
        rendered.add(call.id());
      }
    }
    reasoningStarted = false;
  }

  @Override
  public void onToolStart(Message.ToolCall call) {
    pendingTools++;
    if (!spinnerPaused) {
      spinner.start();
    }
    // The start event carries no arguments; the card is printed once onAssistant has them.
    if (call.arguments() == null || call.arguments().isBlank()) {
      pendingStart.add(call.id());
      return;
    }
    printToolCard(call);
    rendered.add(call.id());
  }

  @Override
  public void onToolEnd(Message.ToolCall call, ToolResult result, long elapsedMillis) {
    pendingTools = Math.max(0, pendingTools - 1);
    if (pendingTools == 0) {
      spinner.stop();
    }
    ensureNewline();
    boolean failed = result.error();
    String line =
        (failed ? "✖ " : "✔ ") + call.name() + " (" + elapsedMillis + " ms)";
    out.println(Ansi.style(failed ? "31" : "32", line, color));
    printResultBody(result.content());
    out.flush();
  }

  @Override
  public void onNotice(String text) {
    if (text == null || text.isBlank()) {
      return;
    }
    ensureNewline();
    out.println(Ansi.style("2", "· " + text, color));
    out.flush();
  }

  /**
   * Silences the spinner while something else owns the terminal — an approval prompt, for example.
   * A spinner redrawing itself over a question the user is trying to answer is worse than no
   * spinner at all.
   */
  public void pauseSpinner() {
    spinnerPaused = true;
    spinner.stop();
  }

  /** Restores the spinner, but only if a tool is still running. */
  public void resumeSpinner() {
    if (!spinnerPaused) {
      return;
    }
    spinnerPaused = false;
    if (pendingTools > 0) {
      spinner.start();
    }
  }

  /** Clears transient state between runs: stops the spinner and closes any half-written line. */
  public void reset() {
    pendingTools = 0;
    spinnerPaused = false;
    spinner.stop();
    ensureNewline();
    out.flush();
    err.flush();
    pendingStart.clear();
    rendered.clear();
    turnTextStarted = false;
    reasoningStarted = false;
  }

  private void beginTextBlock() {
    ensureNewline();
    out.println();
  }

  private void ensureNewline() {
    if (lineOpen) {
      out.println();
      lineOpen = false;
    }
  }

  private void printToolCard(Message.ToolCall call) {
    ensureNewline();
    StringBuilder line = new StringBuilder();
    line.append(Ansi.style("33", "⚙", color)).append(' ');
    line.append(Ansi.style("1", call.name(), color));
    String summary = summarize(call);
    if (!summary.isEmpty()) {
      line.append(' ').append(Ansi.style("2", summary, color));
    }
    out.println(line.toString());
    out.flush();
  }

  private void printResultBody(String content) {
    if (content == null || content.isBlank()) {
      return;
    }
    String[] lines = content.stripTrailing().split("\n", -1);
    int shown = Math.min(RESULT_LINES, lines.length);
    for (int i = 0; i < shown; i++) {
      out.println(Ansi.style("2", "    " + lines[i], color));
    }
    int remaining = lines.length - shown;
    if (remaining > 0) {
      out.println(Ansi.style("2", "    … (" + remaining + " more lines)", color));
    }
  }

  private String summarize(Message.ToolCall call) {
    String raw = call.arguments();
    if (raw == null || raw.isBlank()) {
      return "";
    }
    JsonNode args;
    try {
      args = Json.parse(raw);
    } catch (IllegalArgumentException e) {
      return clip(raw);
    }
    if (!args.isObject()) {
      return clip(raw);
    }
    String tool = call.name() == null ? "" : call.name();
    String value =
        switch (tool) {
          case "read", "write", "edit" -> firstText(args, "path", "file_path", "file");
          case "bash" -> firstText(args, "command", "cmd", "script");
          case "grep", "glob" -> firstText(args, "pattern", "query");
          default -> null;
        };
    return value != null && !value.isBlank() ? clip(value) : clip(Json.write(args));
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

  private static String clip(String text) {
    if (text == null) {
      return "";
    }
    String flat = text.replaceAll("\\s+", " ").strip();
    if (flat.length() <= SUMMARY_WIDTH) {
      return flat;
    }
    return flat.substring(0, SUMMARY_WIDTH - 1).stripTrailing() + "…";
  }

  /** Single-line stderr spinner, active only on a real terminal so pipes and tests stay clean. */
  private final class Spinner implements Runnable {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private volatile boolean running;
    private Thread thread;

    void start() {
      if (running || !Ansi.tty()) {
        return;
      }
      running = true;
      thread = new Thread(this, "ccj-spinner");
      thread.setDaemon(true);
      thread.start();
    }

    void stop() {
      if (!running) {
        return;
      }
      running = false;
      Thread current = thread;
      thread = null;
      if (current != null) {
        current.interrupt();
        try {
          current.join(250);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      err.print(color ? "\r\u001b[2K" : "\r        \r");
      err.flush();
    }

    @Override
    public void run() {
      int frame = 0;
      while (running) {
        err.print("\r" + FRAMES[frame++ % FRAMES.length]);
        err.flush();
        try {
          Thread.sleep(90);
        } catch (InterruptedException e) {
          return;
        }
      }
    }
  }
}
