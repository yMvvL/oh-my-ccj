package com.ccj.agent.obs;

import com.ccj.agent.core.AgentListener;
import com.ccj.agent.core.AgentLoop;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.ToolResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * 代理循环的观察点：把 {@link AgentListener} 的回调变成指标与一棵 span 树，再交给别人。
 *
 * <p>它是一次运行的东西，不是进程的东西——每个 {@link com.ccj.agent.core.AgentLoop} 一个——因为它手里的
 * 状态（这次运行什么时候开始的、哪个回合还开着、哪次工具调用刚出发）只在一次运行里有意义。循环本身一行
 * 都没改：指标要的每一个事实，{@link AgentListener} 早就在送了。这就是那个接缝在还债。
 *
 * <p>它也是一个<em>装饰</em>：真正的听众（终端渲染器、web 的事件发布者）从它手里拿到同一批回调，一毫秒
 * 不差。观察者坏掉不该改变被观察的东西，所以这里除了记账什么都不做，也不向外抛异常。
 *
 * <p>回调的并发由循环负责：工具的开始与结束是串行送出的（并发的那几个只读调用在循环里被一把锁串起来），
 * 回合与用量走的是循环自己的线程。所以这里不需要再加一层锁——而加一层锁，会把一次观测的代价加到被观测的
 * 东西身上，正是这个包想避免的事。
 */
public final class Recorder implements AgentListener {

  private final Metrics metrics;
  private final AgentListener delegate;
  private final String sessionId;
  private final TraceSink sink;

  private Span runSpan;
  private Span turnSpan;
  private long runStartedNanos;
  /** 这个回合的 span 是什么时候开的：它一直开到下一个回合开始（含它要求的那些工具）。 */
  private long turnSpanStartedNanos;
  /** 这个回合里那次模型调用是什么时候发出的；{@code agent} 的时延量的是这一段。 */
  private long modelStartedNanos;
  /** 已经出发、还没回来的工具调用，按调用 id。 */
  private final Map<String, Span> toolSpans = new HashMap<>();

  /**
   * @param metrics 记到哪里；null 表示不记（终端那条路上没有人读它）
   * @param sessionId 这次运行属于哪个会话，带在痕迹里
   * @param sink 一次运行结束时把痕迹交给谁；null 表示不留
   * @param delegate 真正的听众；null 表示没有
   */
  public Recorder(Metrics metrics, String sessionId, TraceSink sink, AgentListener delegate) {
    this.metrics = metrics;
    this.sessionId = sessionId;
    this.delegate = delegate == null ? AgentListener.NOOP : delegate;
    this.sink = sink;
  }

  /** 一次运行结束后，它的痕迹去哪。 */
  @FunctionalInterface
  public interface TraceSink {
    void accept(RunTrace trace);
  }

  /**
   * 一个把每次运行的痕迹追加到一份 JSONL 里的接收者。
   *
   * <p>与文件一起给出去，而不是让调用方自己去写：一次运行一行、写完就 flush，与会话文件是同一条规矩
   * ——被 kill 掉的进程最多丢掉最后一行，而它前面那些仍然读得出来。
   */
  public static TraceSink toFile(Path file) {
    return trace -> {
      try {
        if (file.getParent() != null) {
          // 一个光秃秃的文件名没有父目录，而 Path.of("traces.jsonl").getParent() 是 null——不是为了宽容，
          // 是因为「写进当前目录」本来就是一份相对路径在说的事。
          Files.createDirectories(file.getParent());
        }
        Files.writeString(
            file,
            com.ccj.agent.core.Json.write(trace.toJson()) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } catch (IOException e) {
        throw new UncheckedIOException("无法把运行痕迹写入 " + file, e);
      }
    };
  }

  @Override
  public void onRunStart() {
    runSpan = new Span("run");
    runStartedNanos = System.nanoTime();
    delegate.onRunStart();
  }

  @Override
  public void onTurnStart(int step) {
    // 上一个回合在下一个回合开始时才真正结束——一次循环的迭代是「问模型一次，再跑它要求的工具」，而不是
    // 只到回答到达为止。所以工具挂在提出它们的那个回合下面，而不是和它并排。
    closeTurn();
    turnSpan = runSpan == null ? null : runSpan.child("turn").attribute("step", Integer.toString(step));
    turnSpanStartedNanos = System.nanoTime();
    modelStartedNanos = turnSpanStartedNanos;
    if (metrics != null) {
      metrics.inc(Metric.MODEL_TURNS);
    }
    delegate.onTurnStart(step);
  }

  @Override
  public void onAssistant(Message.Assistant message) {
    // 模型那一半到此为止：从这个回合开始到最后一个增量到达。工具那一半还没跑，所以回合的 span 仍然开着。
    if (turnSpan != null && modelStartedNanos > 0) {
      turnSpan
          .attribute("toolCalls", Integer.toString(message.toolCalls().size()))
          .attribute("textBytes", Integer.toString(message.text() == null ? 0 : message.text().length()));
      if (metrics != null) {
        metrics.observe(
            Metric.MODEL_TURN_SECONDS, (System.nanoTime() - modelStartedNanos) / 1_000_000_000.0);
      }
      modelStartedNanos = 0;
    }
    delegate.onAssistant(message);
  }

  @Override
  public void onToolStart(Message.ToolCall call) {
    // 工具挂在提出它的那个回合下面；一个没有开着回合的工具调用（理论上不会发生）退回挂到运行上，总好过
    // 掉进虚空里。
    Span parent = turnSpan != null ? turnSpan : runSpan;
    if (parent != null) {
      toolSpans.put(call.id(), parent.child("tool").attribute("tool", call.name()));
    }
    if (metrics != null) {
      metrics.inc(Metric.TOOL_CALLS, "tool", call.name());
    }
    delegate.onToolStart(call);
  }

  @Override
  public void onToolEnd(Message.ToolCall call, ToolResult result, long elapsedMillis) {
    Span span = toolSpans.remove(call.id());
    if (span != null) {
      span.attribute("ok", Boolean.toString(!result.error()))
          .attribute("outputBytes", Integer.toString(result.content().length()))
          .end(elapsedMillis < 0 ? 0 : elapsedMillis);
    }
    if (metrics != null) {
      if (result.error()) {
        metrics.inc(Metric.TOOL_ERRORS, "tool", call.name());
      }
      // 负的耗时是一个哨兵：这次调用从未运行（回合在它之前被中止了）。把它当成 0 记进去，就会在直方图里
      // 多出一次「瞬时完成」的观测——一个假的测量比一个缺口更糟，所以它干脆不进直方图。
      if (elapsedMillis >= 0) {
        metrics.observe(Metric.TOOL_SECONDS, elapsedMillis / 1000.0, "tool", call.name());
      }
    }
    delegate.onToolEnd(call, result, elapsedMillis);
  }

  @Override
  public void onUsage(int inputTokens, int outputTokens, Integer cachedInputTokens) {
    if (metrics != null) {
      metrics.add(Metric.TOKENS, inputTokens, "direction", "input");
      metrics.add(Metric.TOKENS, outputTokens, "direction", "output");
      if (cachedInputTokens != null) {
        // 「没有信息」和「没有命中」是两件事，所以只有提供方真的报了缓存时才记这一笔。
        metrics.add(Metric.TOKENS, cachedInputTokens, "direction", "cached");
      }
    }
    delegate.onUsage(inputTokens, outputTokens, cachedInputTokens);
  }

  @Override
  public void onText(String delta) {
    delegate.onText(delta);
  }

  @Override
  public void onReasoning(String delta) {
    delegate.onReasoning(delta);
  }

  @Override
  public void onNotice(String text) {
    delegate.onNotice(text);
  }

  @Override
  public void onRunEnd(AgentLoop.Result result, Throwable failure) {
    closeTurn();
    // 结局是一个词，而不是两个布尔值：失败、被中止、完成是三种不同的收场，而把失败并进「被中止」里，
    // 会让一次模型不可达看起来像是用户按了停止。
    String outcome = failure != null ? "error" : result != null && result.aborted() ? "aborted" : "ok";
    long elapsedMillis = (System.nanoTime() - runStartedNanos) / 1_000_000;
    if (runSpan != null) {
      runSpan
          .attribute("outcome", outcome)
          .attribute("steps", Integer.toString(result == null ? 0 : result.steps()))
          .attribute("finalTextBytes", Integer.toString(result == null || result.finalText() == null ? 0 : result.finalText().length()))
          .end(elapsedMillis);
      if (failure != null) {
        runSpan.attribute("failure", failure.getClass().getSimpleName());
      }
    }
    if (metrics != null) {
      metrics.inc(Metric.RUNS, "outcome", outcome);
      metrics.observe(Metric.RUN_SECONDS, elapsedMillis / 1000.0);
    }
    if (sink != null && runSpan != null) {
      sink.accept(new RunTrace(sessionId, runSpan));
    }
    delegate.onRunEnd(result, failure);
  }

  /** 关掉还开着的那个回合；没有开的就什么都不做。 */
  private void closeTurn() {
    if (turnSpan == null) {
      return;
    }
    turnSpan.end((System.nanoTime() - turnSpanStartedNanos) / 1_000_000);
    turnSpan = null;
  }
}
