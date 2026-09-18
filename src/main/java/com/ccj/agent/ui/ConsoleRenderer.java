package com.ccj.agent.ui;

import com.ccj.agent.core.AgentListener;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.ToolResult;
import java.io.PrintStream;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 把主循环的回调变成盯在终端上的人能跟得上的东西。
 *
 * <p>助手的散文随到随流式输出；推理内容被调暗，读起来像一声低语；工具调用变成一张紧凑的卡片，
 * 其参数摘要从调用的 JSON 里推导，因此用户看到的是 {@code read src/Main.java}，而不是一堵参数
 * 墙。渲染器绝不能成为一次运行读不下去的原因，所以这里的每个决定在没有 TTY 时都会退化成朴素的
 * 行。
 */
public final class ConsoleRenderer implements AgentListener {

  private static final int RESULT_LINES = 3;

  private final PrintStream out;
  private final PrintStream err;
  private final boolean colour;
  private final Spinner spinner;

  private final Set<String> rendered = new LinkedHashSet<>();

  private boolean lineOpen;
  private boolean turnTextStarted;
  private boolean reasoningStarted;
  private boolean spinnerPaused;
  private int pendingTools;

  /** 从终端和 {@code NO_COLOR} 约定自动检测颜色。 */
  public ConsoleRenderer() {
    this(System.out, System.err, Ansi.enabled());
  }

  public ConsoleRenderer(PrintStream out, PrintStream err, boolean colour) {
    this.out = out;
    this.err = err;
    this.colour = colour;
    this.spinner = new Spinner();
  }

  @Override
  public void onTurnStart(int step) {
    turnTextStarted = false;
    reasoningStarted = false;
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
    out.print(Ansi.style("2", delta, colour));
    out.flush();
    lineOpen = !delta.endsWith("\n");
  }

  @Override
  public void onAssistant(Message.Assistant message) {
    // 不流式输出增量的提供方，同样必须能被看见。
    if (!turnTextStarted && !message.text().isBlank()) {
      beginTextBlock();
      out.print(message.text());
      out.flush();
      lineOpen = !message.text().endsWith("\n");
    }
    ensureNewline();

    for (Message.ToolCall call : message.toolCalls()) {
      if (!rendered.contains(call.id())) {
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
    if (!rendered.contains(call.id())) {
      printToolCard(call);
      rendered.add(call.id());
    }
  }

  @Override
  public void onToolEnd(Message.ToolCall call, ToolResult result, long elapsedMillis) {
    pendingTools = Math.max(0, pendingTools - 1);
    if (pendingTools == 0) {
      spinner.stop();
    }
    ensureNewline();
    boolean failed = result.error();
    // 负的耗时是在运行前就被中断的调用：它没有时长，而「0 ms」会被读成一次测量。
    String timing = elapsedMillis < 0 ? "（未运行）" : "(" + elapsedMillis + " ms)";
    String line = (failed ? "✖ " : "✔ ") + call.name() + " " + timing;
    out.println(Ansi.style(failed ? "31" : "32", line, colour));
    printResultBody(result.content());
    out.flush();
  }

  @Override
  public void onNotice(String text) {
    if (text == null || text.isBlank()) {
      return;
    }
    ensureNewline();
    out.println(Ansi.style("2", "· " + text, colour));
    out.flush();
  }

  /**
   * 当别的东西占住终端时让 spinner 闭嘴——比如一个审批提示。spinner 在一个用户正要回答的问题
   * 上反复重画，比没有 spinner 更糟。
   */
  public void pauseSpinner() {
    spinnerPaused = true;
    spinner.stop();
  }

  /** 恢复 spinner，但只在还有工具在跑的时候。 */
  public void resumeSpinner() {
    if (!spinnerPaused) {
      return;
    }
    spinnerPaused = false;
    if (pendingTools > 0) {
      spinner.start();
    }
  }

  /** 清掉运行之间的瞬时状态：停掉 spinner，并收尾任何写到一半的行。 */
  public void reset() {
    pendingTools = 0;
    spinnerPaused = false;
    spinner.stop();
    ensureNewline();
    out.flush();
    err.flush();
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
    line.append(Ansi.style("33", "⚙", colour)).append(' ');
    line.append(Ansi.style("1", call.name(), colour));
    String summary = ToolSummary.summarise(call);
    if (!summary.isEmpty()) {
      line.append(' ').append(Ansi.style("2", summary, colour));
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
      out.println(Ansi.style("2", "    " + lines[i], colour));
    }
    int remaining = lines.length - shown;
    if (remaining > 0) {
      out.println(Ansi.style("2", "    … （还有 " + remaining + " 行）", colour));
    }
  }

  /** 单行 stderr spinner，只在真实终端上活跃，因此管道和测试都保持干净。 */
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
      err.print(colour ? "\r\u001b[2K" : "\r        \r");
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
