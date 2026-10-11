package com.ccj.agent.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.AgentListener;
import com.ccj.agent.core.AgentLoop;
import com.ccj.agent.core.AgentOptions;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.MemorySession;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.SessionRepair;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.ccj.agent.tool.Tools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 观察者本身：一次真的代理运行留下的数字与那棵树。
 *
 * <p>用的是**真的**循环、**真的**工具注册表和**真的**磁盘（一个临时目录），配一个脚本化的提供方——
 * 与回合成交互的那一层不能是替身，否则这些用例只是在证明它自己的桩。
 *
 * <p>树的形状是这里最要紧的一条主张：工具挂在提出它的那个回合下面。一次循环的迭代是「问模型一次，再跑它
 * 要求的工具」，而把工具摆成回合的兄弟节点，会让「这一次为什么慢」在树里读不出来——那正是这棵树存在的理由。
 */
class RecorderTest {

  @TempDir Path tmp;

  @Test
  void oneRunBecomesATreeWithItsToolsNestedInTheTurnThatAskedForThem() throws Exception {
    Files.writeString(tmp.resolve("notes.txt"), "the file\n");
    Metrics metrics = new Metrics();
    AtomicReference<RunTrace> captured = new AtomicReference<>();

    Provider provider =
        replies(call("read", "path", "notes.txt"), Message.Assistant.text("read it"));
    AgentLoop loop =
        new AgentLoop(
            provider,
            Tools.standard(),
            new MemorySession("session-1"),
            AgentOptions.defaults(),
            ToolContext.of(tmp),
            new Recorder(metrics, "session-1", captured::set, AgentListener.NOOP));

    AgentLoop.Result result = loop.run("peek at notes.txt");

    assertEquals(2, result.steps());
    assertFalse(result.aborted());
    assertEquals(1, metrics.value(Metric.RUNS, "outcome", "ok"));
    assertEquals(2, metrics.value(Metric.MODEL_TURNS));
    assertEquals(1, metrics.value(Metric.TOOL_CALLS, "tool", "read"));
    assertEquals(0, metrics.value(Metric.TOOL_ERRORS, "tool", "read"));
    assertEquals(1, metrics.count(Metric.TOOL_SECONDS, "tool", "read"));
    assertEquals(2, metrics.count(Metric.MODEL_TURN_SECONDS));
    assertEquals(1, metrics.count(Metric.RUN_SECONDS));

    RunTrace trace = captured.get();
    assertEquals("session-1", trace.sessionId());
    Span run = trace.run();
    assertEquals("run", run.name());
    assertEquals("ok", run.attribute("outcome"));
    assertEquals("2", run.attribute("steps"));
    assertEquals(2, run.children().size(), "两个模型回合：" + run.toJson());

    Span firstTurn = run.children().get(0);
    assertEquals("turn", firstTurn.name());
    assertEquals("0", firstTurn.attribute("step"));
    assertEquals("1", firstTurn.attribute("toolCalls"));
    assertEquals(1, firstTurn.children().size(), "工具在提出它的那个回合里面：" + firstTurn.toJson());

    Span tool = firstTurn.children().get(0);
    assertEquals("tool", tool.name());
    assertEquals("read", tool.attribute("tool"));
    assertEquals("true", tool.attribute("ok"));
    assertTrue(Integer.parseInt(tool.attribute("outputBytes")) > 0, tool.toJson().toString());
    assertTrue(tool.endMillis() >= tool.startMillis(), tool.toJson().toString());

    Span secondTurn = run.children().get(1);
    assertEquals("1", secondTurn.attribute("step"));
    assertEquals("0", secondTurn.attribute("toolCalls"));
    assertTrue(secondTurn.children().isEmpty());
  }

  @Test
  void tokenAccountingIsCountedByDirectionAndCacheOnlyWhenItWasReported() throws Exception {
    Metrics metrics = new Metrics();
    Provider provider =
        replies(700, 40, 512, Message.Assistant.text("hello"));
    AgentLoop loop =
        new AgentLoop(
            provider,
            Tools.standard(),
            new MemorySession(),
            AgentOptions.defaults(),
            ToolContext.of(tmp),
            new Recorder(metrics, "s", null, AgentListener.NOOP));

    loop.run("hi");

    assertEquals(700, metrics.value(Metric.TOKENS, "direction", "input"));
    assertEquals(40, metrics.value(Metric.TOKENS, "direction", "output"));
    assertEquals(512, metrics.value(Metric.TOKENS, "direction", "cached"));
  }

  @Test
  void aRunWhoseProviderNeverReportsCachingDoesNotInventACacheHit() throws Exception {
    // 「这个提供方从不报缓存」和「命中率为零」是两件事。把一个不报的提供方记成 0，会让整个进程的命中率
    // 从「未上报」变成「0.0%」——一个看起来像结论的谎。
    Metrics metrics = new Metrics();
    AgentLoop loop =
        new AgentLoop(
            replies(700, 40, null, Message.Assistant.text("hello")),
            Tools.standard(),
            new MemorySession(),
            AgentOptions.defaults(),
            ToolContext.of(tmp),
            new Recorder(metrics, "s", null, AgentListener.NOOP));

    loop.run("hi");

    assertEquals(0, metrics.count(Metric.TOKENS, "direction", "cached"), metrics.render());
  }

  @Test
  void aFailedRunIsCountedAsAnErrorRatherThanAsAnAbort() {
    // 三种收场：完成、被中止、失败。把失败并进「被中止」里，会让一次模型不可达看起来像是用户按了停止。
    Metrics metrics = new Metrics();
    Recorder recorder = new Recorder(metrics, "s", null, AgentListener.NOOP);

    recorder.onRunStart();
    recorder.onTurnStart(0);
    RuntimeException boom = new RuntimeException("提供方不可达");
    recorder.onRunEnd(null, boom);

    assertEquals(1, metrics.value(Metric.RUNS, "outcome", "error"));
    assertEquals(0, metrics.value(Metric.RUNS, "outcome", "aborted"));
    assertEquals(0, metrics.value(Metric.RUNS, "outcome", "ok"));
  }

  @Test
  void anAbortedRunIsCountedAsAborted() {
    Metrics metrics = new Metrics();
    Recorder recorder = new Recorder(metrics, "s", null, AgentListener.NOOP);

    recorder.onRunStart();
    recorder.onTurnStart(0);
    recorder.onAssistant(Message.Assistant.text("partial"));
    recorder.onRunEnd(new AgentLoop.Result("partial", 1, true), null);

    assertEquals(1, metrics.value(Metric.RUNS, "outcome", "aborted"));
    assertEquals(0, metrics.value(Metric.RUNS, "outcome", "error"));
  }

  @Test
  void aToolThatNeverRanIsNotPutInTheHistogram() {
    // 中止会为它没来得及跑的调用记一条「未运行」的结果，而那条结果带着一个负的耗时哨兵。把它当成一次观测
    // 记进去，直方图里就会多出一次从未发生的、瞬时完成的调用。
    Metrics metrics = new Metrics();
    Recorder recorder = new Recorder(metrics, "s", null, AgentListener.NOOP);
    Message.ToolCall call = new Message.ToolCall("c1", "bash", "{\"command\":\"echo hi\"}");

    recorder.onRunStart();
    recorder.onTurnStart(0);
    recorder.onToolStart(call);
    recorder.onToolEnd(call, new ToolResult(SessionRepair.NOT_RUN, true), -1);
    recorder.onRunEnd(new AgentLoop.Result("", 1, true), null);

    assertEquals(1, metrics.value(Metric.TOOL_CALLS, "tool", "bash"));
    assertEquals(0, metrics.count(Metric.TOOL_SECONDS, "tool", "bash"), metrics.render());
  }

  @Test
  void theListenerPassesEveryCallbackToTheFrontEndItWraps() throws Exception {
    // 观察者是装饰，不是替代：真正画界面的那个听众拿到的回调必须一字不差。少一次 onText，页面就少一段
    // 回答——而那种坏法在指标上完全看不出来。
    StringBuilder seen = new StringBuilder();
    AgentListener frontEnd =
        new AgentListener() {
          @Override
          public void onText(String delta) {
            seen.append(delta);
          }

          @Override
          public void onToolStart(Message.ToolCall call) {
            seen.append("|start:").append(call.name());
          }

          @Override
          public void onToolEnd(Message.ToolCall call, ToolResult result, long elapsedMillis) {
            seen.append("|end:").append(call.name()).append(':').append(elapsedMillis >= 0);
          }

          @Override
          public void onRunEnd(AgentLoop.Result result, Throwable failure) {
            seen.append("|end-run:").append(failure == null);
          }
        };
    Files.writeString(tmp.resolve("notes.txt"), "hi\n");
    AgentLoop loop =
        new AgentLoop(
            replies(call("read", "path", "notes.txt"), Message.Assistant.text("done")),
            Tools.standard(),
            new MemorySession(),
            AgentOptions.defaults(),
            ToolContext.of(tmp),
            new Recorder(new Metrics(), "s", null, frontEnd));

    loop.run("read it");

    assertEquals("|start:read|end:read:truedone|end-run:true", seen.toString());
  }

  @Test
  void theMeteredApproverCountsTheAnswerAndTheWait() {
    Metrics metrics = new Metrics();
    MeteredApprover approver = new MeteredApprover(metrics, request -> {
      try {
        Thread.sleep(2);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return com.ccj.agent.core.ApprovalAnswer.ALLOW_ALWAYS;
    });

    com.ccj.agent.core.ApprovalAnswer answer =
        approver.approve(com.ccj.agent.core.ApprovalRequest.command("echo hi", "bash: echo hi"));

    assertSame(com.ccj.agent.core.ApprovalAnswer.ALLOW_ALWAYS, answer);
    assertEquals(1, metrics.value(Metric.APPROVALS, "answer", "allow_always"));
    assertEquals(1, metrics.count(Metric.APPROVAL_SECONDS));
    assertTrue(metrics.value(Metric.APPROVAL_SECONDS) >= 0.002, metrics.render());
  }

  // ------------------------------------------------------------------ 脚本化的提供方

  private static Message.Assistant call(String name, String field, String value) {
    return new Message.Assistant(
        "",
        List.of(
            new Message.ToolCall(
                "call_" + name, name, Json.write(Json.object().put(field, value)))));
  }

  private static Provider replies(Message.Assistant... script) {
    return replies(-1, -1, null, script);
  }

  private static Provider replies(
      int inputTokens, int outputTokens, Integer cached, Message.Assistant... script) {
    return new Provider() {
      private int cursor;

      @Override
      public String name() {
        return "scripted";
      }

      @Override
      public Message.Assistant complete(Request request, Consumer<Event> listener) {
        Message.Assistant next = script[Math.min(cursor++, script.length - 1)];
        if (!next.text().isEmpty()) {
          listener.accept(new Event.TextDelta(next.text()));
        }
        for (Message.ToolCall call : next.toolCalls()) {
          listener.accept(new Event.ToolCallStart(call.id(), call.name()));
        }
        if (inputTokens >= 0) {
          listener.accept(new Event.Usage(inputTokens, outputTokens, cached));
        }
        return next;
      }
    };
  }
}
