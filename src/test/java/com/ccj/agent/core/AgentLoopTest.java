package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLoopTest {

  @TempDir Path cwd;

  /** 记录每一次调用，好让测试断言循环确实调用了该工具。 */
  private static class RecordingTool implements Tool {
    private final String name;
    private final List<String> seenArguments = new ArrayList<>();
    private final String result;
    private final boolean error;
    private Runnable onExecute = () -> {};

    RecordingTool(String name, String result) {
      this(name, result, false);
    }

    RecordingTool(String name, String result, boolean error) {
      this.name = name;
      this.result = result;
      this.error = error;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String description() {
      return "test tool";
    }

    @Override
    public String parametersJson() {
      return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public ToolResult execute(String argumentsJson, ToolContext ctx) {
      seenArguments.add(argumentsJson);
      onExecute.run();
      return new ToolResult(result, error);
    }
  }

  private record Harness(
      AgentLoop loop,
      ScriptedProvider provider,
      MemorySession session,
      List<String> notices,
      List<String> text) {}

  private Harness harness(AgentOptions options, ToolRegistry registry, ScriptedProvider provider) {
    MemorySession session = new MemorySession("s1");
    List<String> notices = new ArrayList<>();
    List<String> text = new ArrayList<>();
    List<String> toolStarts = new ArrayList<>();
    AgentListener listener =
        new AgentListener() {
          @Override
          public void onText(String delta) {
            text.add(delta);
          }

          @Override
          public void onNotice(String message) {
            notices.add(message);
          }

          @Override
          public void onToolStart(Message.ToolCall call) {
            toolStarts.add(call.name());
          }
        };
    AgentLoop loop =
        new AgentLoop(
            provider,
            registry,
            session,
            options,
            new ToolContext(cwd, Approver.ALWAYS, 0),
            listener);
    return new Harness(loop, provider, session, notices, text);
  }

  /** 一个只读、且肯花时间的工具，这样重叠才是可测量的。 */
  private static final class SlowReader extends RecordingTool {
    private final long millis;

    SlowReader(String name, long millis) {
      super(name, "read " + name);
      this.millis = millis;
    }

    @Override
    public boolean readOnly() {
      return true;
    }

    @Override
    public ToolResult execute(String argumentsJson, ToolContext ctx) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return super.execute(argumentsJson, ctx);
    }
  }

  /** 一个就地结束本次运行的工具，就像 `restart` 工具那样。 */
  private static final class EndingTool extends RecordingTool {

    EndingTool(String name) {
      super(name, "installed; restarting on it");
    }

    @Override
    public ToolResult execute(String argumentsJson, ToolContext ctx) {
      ToolResult result = super.execute(argumentsJson, ctx);
      ctx.endRun();
      return result;
    }
  }

  @Test
  void aToolThatEndsTheRunStopsTheCallsBesideIt() {
    // `restart` 会装上 jar 并就地结束本次运行。模型在同一回合里请求的那些调用永远不会发出去，
    // 所以第二个工具绝不能执行 —— 而它收到的那个调用会被记为「未运行」，因为一段有调用却
    // 没有结果的对话，任何提供方都不会再接受。
    EndingTool ender = new EndingTool("restart");
    RecordingTool writer = new RecordingTool("write", "wrote");
    ScriptedProvider provider =
        new ScriptedProvider(
            ScriptedProvider.Reply.calls(
                new Message.ToolCall("call_1", "restart", "{}"),
                new Message.ToolCall("call_2", "write", "{}")),
            ScriptedProvider.Reply.text("unreachable"));

    Harness h =
        harness(
            new AgentOptions(null, null, null, null, null), ToolRegistry.of(ender, writer), provider);

    AgentLoop.Result result = h.loop().run("install the new jar");

    assertEquals(List.of(), writer.seenArguments, "与结束运行的那个调用并排的调用绝不能执行");
    assertEquals(1, result.steps(), "只有一个模型回合，而那个回合结束了运行");
    Message.ToolResult unrun =
        h.session().messages().stream()
            .filter(Message.ToolResult.class::isInstance)
            .map(Message.ToolResult.class::cast)
            .filter(r -> r.toolCallId().equals("call_2"))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "未运行的调用必须被记录：" + h.session().messages()));
    assertTrue(unrun.error(), unrun.content());
  }

  @Test
  void readOnlyCallsInOneTurnOverlapAndKeepTheirOrder() {
    // 一个回合里读三个文件，等待的是一次往返的时间，而不是三次。
    RecordingTool first = new SlowReader("read_a", 300);
    RecordingTool second = new SlowReader("read_b", 300);
    RecordingTool third = new SlowReader("read_c", 300);
    ScriptedProvider provider =
        new ScriptedProvider(
            ScriptedProvider.Reply.calls(
                new Message.ToolCall("call_1", "read_a", "{}"),
                new Message.ToolCall("call_2", "read_b", "{}"),
                new Message.ToolCall("call_3", "read_c", "{}")),
            ScriptedProvider.Reply.text("read all three"));
    Harness h =
        harness(
            AgentOptions.defaults(),
            ToolRegistry.of(first, second, third),
            provider);

    long started = System.nanoTime();
    AgentLoop.Result result = h.loop().run("read everything");
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

    assertEquals("read all three", result.finalText());
    assertTrue(
        elapsedMillis < 850,
        "三次 300 毫秒的读取必须重叠，而不是排队：" + elapsedMillis + "ms");

    // 无论文件系统如何调度，会话都按模型请求的顺序记录读取结果。
    List<Message> history = h.session().messages();
    List<String> resultIds =
        history.stream()
            .filter(Message.ToolResult.class::isInstance)
            .map(Message.ToolResult.class::cast)
            .map(Message.ToolResult::toolCallId)
            .toList();
    assertEquals(List.of("call_1", "call_2", "call_3"), resultIds);
  }

  @Test
  void aWritingCallBetweenReadsBreaksTheBatch() {
    // 只有不改变任何东西的工具才适合重叠执行，而一次写两侧的那些读操作不得绕过它重排。
    List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
    RecordingTool readA = new SlowReader("read_a", 50);
    RecordingTool write = new RecordingTool("write", "written");
    RecordingTool readB = new SlowReader("read_b", 50);
    write.onExecute = () -> order.add("write");
    ScriptedProvider provider =
        new ScriptedProvider(
            ScriptedProvider.Reply.calls(
                new Message.ToolCall("call_1", "read_a", "{}"),
                new Message.ToolCall("call_2", "write", "{}"),
                new Message.ToolCall("call_3", "read_b", "{}")),
            ScriptedProvider.Reply.text("done"));
    Harness h =
        harness(AgentOptions.defaults(), ToolRegistry.of(readA, write, readB), provider);

    h.loop().run("read, write, read");

    List<Message> history = h.session().messages();
    List<String> resultNames =
        history.stream()
            .filter(Message.ToolResult.class::isInstance)
            .map(Message.ToolResult.class::cast)
            .map(Message.ToolResult::toolName)
            .toList();
    assertEquals(List.of("read_a", "write", "read_b"), resultNames);
    assertEquals(List.of("write"), order);
  }

  @Test
  void aLongConversationIsProjectedOntoTheContextBudgetAndSaidSo() {
    // 会话保留一切；真正发到线上的只是装得下的那部分。
    RecordingTool tool = new RecordingTool("read", "x".repeat(40_000));
    List<ScriptedProvider.Reply> script = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      script.add(ScriptedProvider.Reply.calls(new Message.ToolCall("call_" + i, "read", "{}")));
    }
    script.add(ScriptedProvider.Reply.text("finally"));
    ScriptedProvider provider = new ScriptedProvider(script.toArray(ScriptedProvider.Reply[]::new));
    AgentOptions options =
        new AgentOptions("m", null, null, null, null, 5_000);
    Harness h = harness(options, ToolRegistry.of(tool), provider);

    h.loop().run("read it all");

    assertEquals(10, h.session().messages().size(), "会话保留每条消息");
    Provider.Request last = provider.requests().get(provider.requests().size() - 1);
    assertTrue(
        TokenEstimate.of(last.messages()) <= 5_000,
        "请求必须落在预算之内：" + TokenEstimate.of(last.messages()));
    assertTrue(
        h.session().messages().size() > last.messages().size(),
        "而会话仍要保留请求略去的那些内容");
    assertTrue(
        h.notices().stream().anyMatch(notice -> notice.startsWith("上下文：")),
        "被裁剪的提示词会被报告：" + h.notices());
  }

  @Test
  void abortStopsATurnThatIsStillStreamingFromTheModel() throws Exception {
    // 用户反馈：模型还在思考时按下停止，很久都没有反应。循环只在步骤之间检查标志，而一个阻塞在
    // 流式输出中途的提供方并不处于任何「步骤之间」—— 整个回复到齐之前回合不会结束。停止必须
    // 打断它正在等待的那次调用，而不是等它自己结束。
    ScriptedProvider provider =
        new ScriptedProvider(ScriptedProvider.Reply.text("never arrives")).streaming(300, 30);
    Harness h = harness(AgentOptions.defaults(), ToolRegistry.of(), provider);

    Thread runner = new Thread(() -> h.loop().run("think for a while"), "abort-streaming-test");
    runner.start();
    // 等到提供方确实进入了这次调用，再按停止。
    long waitForCall = System.nanoTime() + 5_000_000_000L;
    while (provider.callCount() == 0 && System.nanoTime() < waitForCall) {
      Thread.sleep(10);
    }
    assertEquals(1, provider.callCount(), "提供方必须已被调用");

    long started = System.nanoTime();
    h.loop().abort();
    runner.join(5_000);
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

    assertFalse(runner.isAlive(), "中止必须能结束一个正在等待模型的回合");
    assertTrue(
        elapsedMillis < 2_000,
        "中止不得等待回复完全到齐：" + elapsedMillis + "ms");
  }

  @Test
  void abortStopsTheToolThatIsRunningInsteadOfWaitingForItsTimeout() throws Exception {
    ScriptedProvider provider =
        new ScriptedProvider(
            ScriptedProvider.Reply.calls(
                new Message.ToolCall(
                    "call_1",
                    "bash",
                    "{\"command\":\"sleep 30\",\"timeout_seconds\":600}")));
    Harness h = harness(AgentOptions.defaults(), com.ccj.agent.tool.Tools.standard(), provider);

    Thread runner = new Thread(() -> h.loop().run("run something slow"), "abort-test");
    runner.start();
    Thread.sleep(500);
    long started = System.nanoTime();
    h.loop().abort();
    runner.join(10_000);
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

    assertFalse(runner.isAlive(), "运行必须返回");
    assertTrue(
        elapsedMillis < 3_000,
        "中止必须触达正在运行的命令，而不是等它超时：" + elapsedMillis + "ms");
    assertEquals(1, provider.callCount(), "中止之后不会再问模型");
    List<Message> history = h.session().messages();
    assertEquals(3, history.size(), history.toString());
    Message.ToolResult result = (Message.ToolResult) history.get(2);
    assertTrue(result.error(), result.content());
    assertTrue(
        result.content().contains("用户已中止"),
        "转录会说明命令为何停止：" + result.content());
  }

  @Test
  void anAbortBetweenTheTurnAndItsToolsStillLeavesAUsableSession() {
    // 这个测试所针对的 bug：assistant 回合在它的工具调用执行之前就被写入，所以此时若发生中止，
    // 会话就会变成之后每个请求都被拒绝的状态 —— 而且是永久性的。
    RecordingTool tool = new RecordingTool("read", "contents");
    ScriptedProvider provider =
        new ScriptedProvider(
            ScriptedProvider.Reply.calls(
                new Message.ToolCall("call_1", "read", "{}"),
                new Message.ToolCall("call_2", "read", "{}")));
    Harness h = harness(AgentOptions.defaults(), ToolRegistry.of(tool), provider);
    // 就在请求这些调用的那个回合中途中止：这是最窄的窗口，而用户的「中止」按钮偏偏总落在这里。
    AgentLoop loop = abortOnAssistant(h.session(), ToolRegistry.of(tool), provider);

    AgentLoop.Result result = loop.run("read both files");

    assertTrue(result.aborted());
    assertEquals(1, provider.callCount(), "不会再问模型");
    assertEquals(List.of(), tool.seenArguments, "两个调用都没有执行");
    List<Message> history = h.session().messages();
    for (Message message : history) {
      if (message instanceof Message.Assistant assistant) {
        for (Message.ToolCall call : assistant.toolCalls()) {
          assertTrue(
              history.stream()
                  .anyMatch(
                      m ->
                          m instanceof Message.ToolResult r
                              && r.toolCallId().equals(call.id())
                              && r.error()),
              "回合发出的每个调用都必须有结果：" + history);
        }
      }
    }
  }

  /** 一个监听器在回合请求工具的那一刻就中止运行的循环。 */
  private AgentLoop abortOnAssistant(
      MemorySession session, ToolRegistry registry, ScriptedProvider provider) {
    AgentLoop[] holder = new AgentLoop[1];
    AgentListener listener =
        new AgentListener() {
          @Override
          public void onAssistant(Message.Assistant message) {
            if (message.hasToolCalls()) {
              holder[0].abort();
            }
          }
        };
    AgentLoop loop =
        new AgentLoop(
            provider,
            registry,
            session,
            AgentOptions.defaults(),
            new ToolContext(cwd, Approver.ALWAYS, 0),
            listener);
    holder[0] = loop;
    return loop;
  }

  @Test
  void aHistoryBrokenByAnEarlierInterruptionIsRepairedForTheNextRequest() {
    // 用户看到的现象：对话被打断过一次，之后每条消息都被拒绝，理由是
    // "an assistant message with tool_calls must be followed by tool messages"。
    MemorySession session = new MemorySession("broken");
    session.append(new Message.User("read the file"));
    session.append(
        new Message.Assistant("", List.of(new Message.ToolCall("call_1", "read", "{}"))));
    ScriptedProvider provider = new ScriptedProvider(ScriptedProvider.Reply.text("all better"));
    List<String> notices = new ArrayList<>();
    AgentListener listener =
        new AgentListener() {
          @Override
          public void onNotice(String text) {
            notices.add(text);
          }
        };
    AgentLoop loop =
        new AgentLoop(
            provider,
            new ToolRegistry(),
            session,
            AgentOptions.defaults(),
            new ToolContext(cwd, Approver.ALWAYS, 0),
            listener);

    AgentLoop.Result result = loop.run("say something");

    assertEquals("all better", result.finalText());
    List<Message> sent = provider.requests().get(0).messages();
    boolean answered =
        sent.stream()
            .anyMatch(
                m ->
                    m instanceof Message.ToolResult r
                        && r.toolCallId().equals("call_1")
                        && r.error());
    assertTrue(answered, "请求必须回应那个被悬置的调用：" + sent);
    assertTrue(
        notices.stream().anyMatch(text -> text.contains("被中断的工具调用被标记为未运行")),
        "并且要告诉用户为何它忽然又能用了：" + notices);
    // 会话文件保留自己的记录：修复只是一次投影，而不是重写。
    assertTrue(
        session.messages().stream().noneMatch(Message.ToolResult.class::isInstance),
        "没有把任何东西写回会话：" + session.messages());
  }

  @Test
  void plainAnswerEndsTheRunAfterOneTurn() {
    ScriptedProvider provider = new ScriptedProvider(ScriptedProvider.Reply.text("all done"));
    Harness h = harness(AgentOptions.defaults(), new ToolRegistry(), provider);

    AgentLoop.Result result = h.loop().run("do the thing");

    assertEquals("all done", result.finalText());
    assertEquals(1, result.steps());
    assertFalse(result.aborted());
    assertEquals(
        List.of(new Message.User("do the thing"), new Message.Assistant("all done", List.of())),
        h.session().messages());
    assertEquals(List.of("all done"), h.text(), "增量到达时必须送达监听器");
  }

  @Test
  void toolCallIsExecutedAndItsResultGoesBackToTheModel() {
    RecordingTool tool = new RecordingTool("read", "file contents");
    ScriptedProvider provider =
        new ScriptedProvider(
            ScriptedProvider.Reply.calls(new Message.ToolCall("call_1", "read", "{\"path\":\"a.txt\"}")),
            ScriptedProvider.Reply.text("the file says hello"));
    Harness h = harness(AgentOptions.defaults(), ToolRegistry.of(tool), provider);

    AgentLoop.Result result = h.loop().run("what is in a.txt?");

    assertEquals("the file says hello", result.finalText());
    assertEquals(2, result.steps());
    assertEquals(List.of("{\"path\":\"a.txt\"}"), tool.seenArguments);
    assertEquals(2, provider.callCount());

    List<Message> history = h.session().messages();
    assertEquals(4, history.size());
    assertInstanceOf(Message.User.class, history.get(0));
    assertInstanceOf(Message.Assistant.class, history.get(1));

    Message.ToolResult toolResult = assertInstanceOf(Message.ToolResult.class, history.get(2));
    assertEquals("call_1", toolResult.toolCallId());
    assertEquals("read", toolResult.toolName());
    assertEquals("file contents", toolResult.content());
    assertFalse(toolResult.error());

    assertInstanceOf(Message.Assistant.class, history.get(3));
    assertTrue(
        h.notices().stream().allMatch(n -> n.startsWith("token：")),
        "以回答结束的回合不会发出任何告警：" + h.notices());
  }

  @Test
  void theSecondRequestCarriesTheToolResultAndTheToolCatalogue() {
    RecordingTool tool = new RecordingTool("bash", "ok");
    ScriptedProvider provider =
        new ScriptedProvider(
            ScriptedProvider.Reply.calls(new Message.ToolCall("call_1", "bash", "{\"command\":\"ls\"}")),
            ScriptedProvider.Reply.text("done"));
    Harness h = harness(AgentOptions.defaults(), ToolRegistry.of(tool), provider);

    h.loop().run("list files");

    Provider.Request second = h.provider().requests().get(1);
    assertEquals(List.of("bash"), second.tools().stream().map(ToolSpec::name).toList());
    assertEquals(Prompts.DEFAULT_SYSTEM, second.system());
    assertEquals(3, second.messages().size());
    assertEquals("ok", ((Message.ToolResult) second.messages().get(2)).content());
  }

  @Test
  void hallucinatedToolNamesAndBadArgumentsBecomeErrorResults() {
    Tool strict =
        new Tool() {
          @Override
          public String name() {
            return "edit";
          }

          @Override
          public String description() {
            return "edit a file";
          }

          @Override
          public String parametersJson() {
            return "{}";
          }

          @Override
          public ToolResult execute(String argumentsJson, ToolContext ctx) {
            Json.parse(argumentsJson);
            return ToolResult.ok("edited");
          }
        };
    ScriptedProvider provider =
        new ScriptedProvider(
            ScriptedProvider.Reply.calls(
                new Message.ToolCall("c1", "teleport", "{}"),
                new Message.ToolCall("c2", "edit", "{not json")),
            ScriptedProvider.Reply.text("recovered"));
    Harness h = harness(AgentOptions.defaults(), ToolRegistry.of(strict), provider);

    AgentLoop.Result result = h.loop().run("go");

    assertEquals("recovered", result.finalText(), "循环必须能在坏的工具调用下存活");
    List<Message> history = h.session().messages();
    Message.ToolResult unknown = (Message.ToolResult) history.get(2);
    Message.ToolResult badArgs = (Message.ToolResult) history.get(3);
    assertTrue(unknown.error());
    assertTrue(unknown.content().contains("未知工具 'teleport'"), unknown.content());
    assertTrue(unknown.content().contains("edit"), "错误里应当列出可用的工具");
    assertTrue(badArgs.error());
    assertTrue(badArgs.content().contains("参数无效"), badArgs.content());
  }

  @Test
  void aThrowingToolIsReportedInsteadOfKillingTheSession() {
    RecordingTool tool = new RecordingTool("bash", "unused");
    tool.onExecute = () -> {
      throw new IllegalStateException("boom");
    };
    ScriptedProvider provider =
        new ScriptedProvider(
            ScriptedProvider.Reply.calls(new Message.ToolCall("c1", "bash", "{}")),
            ScriptedProvider.Reply.text("recovered"));
    Harness h = harness(AgentOptions.defaults(), ToolRegistry.of(tool), provider);

    h.loop().run("run it");

    Message.ToolResult result = (Message.ToolResult) h.session().messages().get(2);
    assertTrue(result.error());
    assertTrue(result.content().contains("boom"), result.content());
  }

  @Test
  void abortedRunsStopBeforeTheNextTurn() {
    RecordingTool tool = new RecordingTool("bash", "ok");
    ScriptedProvider provider =
        new ScriptedProvider(
            ScriptedProvider.Reply.calls(new Message.ToolCall("c1", "bash", "{}")),
            ScriptedProvider.Reply.text("should never be produced"));
    Harness h = harness(AgentOptions.defaults(), ToolRegistry.of(tool), provider);
    tool.onExecute = h.loop()::abort;

    AgentLoop.Result result = h.loop().run("run it");

    assertTrue(result.aborted());
    assertEquals(1, provider.callCount(), "中止之后不再有模型回合");
    assertEquals(1, result.steps());
  }

  /**
   * 没有步数上限：一次运行只由模型给出回答、或由中止来结束，别无其他。长时间的活计不该被某个人
   * 猜出来的数字砍断，所以这里钉住的是：超过旧默认值 25 步的运行会径直走到它的答案。
   */
  @Test
  void aRunIsNotCutOffByAStepCeiling() {
    int steps = 40;   // 远超过循环过去会停下的 25 步
    List<ScriptedProvider.Reply> script = new ArrayList<>();
    for (int i = 0; i < steps; i++) {
      script.add(ScriptedProvider.Reply.calls(new Message.ToolCall("c" + i, "bash", "{}")));
    }
    script.add(ScriptedProvider.Reply.text("done after " + steps + " tool turns"));

    RecordingTool tool = new RecordingTool("bash", "ok");
    ScriptedProvider provider = new ScriptedProvider("scripted", script);
    Harness h = harness(AgentOptions.defaults(), ToolRegistry.of(tool), provider);

    AgentLoop.Result result = h.loop().run("a long task");

    assertEquals(steps, tool.seenArguments.size(), "每个被请求的调用都执行了");
    assertEquals(steps + 1, provider.callCount(), "每次都重新询问了模型");
    assertEquals("done after " + steps + " tool turns", result.finalText());
    assertFalse(result.aborted());
  }

  @Test
  void providerFailuresSurfaceAsAgentException() {
    ScriptedProvider provider =
        new ScriptedProvider(ScriptedProvider.Reply.text("x"))
            .failingWith(new RuntimeException("connection reset"));
    Harness h = harness(AgentOptions.defaults(), new ToolRegistry(), provider);

    AgentException error = assertThrows(AgentException.class, () -> h.loop().run("hi"));

    assertTrue(error.getMessage().contains("scripted"), error.getMessage());
    assertTrue(error.getMessage().contains("connection reset"), error.getMessage());
    assertInstanceOf(RuntimeException.class, error.getCause());
  }

  @Test
  void providerFailuresFromCheckedExceptionsAreWrappedToo() {
    ScriptedProvider provider =
        new ScriptedProvider(ScriptedProvider.Reply.text("x"))
            .failingWith(new IOException("peer closed the stream"));
    Harness h = harness(AgentOptions.defaults(), new ToolRegistry(), provider);

    AgentException error = assertThrows(AgentException.class, () -> h.loop().run("hi"));

    assertInstanceOf(IOException.class, error.getCause());
    assertTrue(error.getMessage().contains("peer closed the stream"), error.getMessage());
  }

  @Test
  void stepCountStartsAtZeroForTheListener() {
    List<Integer> steps = new ArrayList<>();
    ScriptedProvider provider =
        new ScriptedProvider(
            ScriptedProvider.Reply.calls(new Message.ToolCall("c1", "bash", "{}")),
            ScriptedProvider.Reply.text("done"));
    MemorySession session = new MemorySession();
    AgentLoop loop =
        new AgentLoop(
            provider,
            ToolRegistry.of(new RecordingTool("bash", "ok")),
            session,
            AgentOptions.defaults(),
            new ToolContext(cwd, Approver.ALWAYS, 0),
            new AgentListener() {
              @Override
              public void onTurnStart(int step) {
                steps.add(step);
              }
            });

    loop.run("go");

    assertEquals(List.of(0, 1), steps);
  }

  @Test
  void successiveRunsAccumulateInOneHistory() {
    ScriptedProvider provider = new ScriptedProvider(ScriptedProvider.Reply.text("hi"));
    Harness h = harness(AgentOptions.defaults(), new ToolRegistry(), provider);

    h.loop().run("first");
    h.loop().run("second");

    assertEquals(
        List.of("first", "hi", "second", "hi"),
        h.session().messages().stream()
            .map(m -> switch (m) {
              case Message.User u -> u.text();
              case Message.Assistant a -> a.text();
              default -> "";
            })
            .toList());
  }

  @Test
  void reportsHowMuchOfThePromptTheProviderHadCached() {
    ScriptedProvider provider =
        new ScriptedProvider(ScriptedProvider.Reply.text("hi")).usage(100, 5, 80);
    Harness h = harness(AgentOptions.defaults(), new ToolRegistry(), provider);

    h.loop().run("go");

    assertTrue(
        h.notices().stream().anyMatch(n -> n.contains("token：100 输入（缓存 80%） / 5 输出")),
        h.notices().toString());
  }

  @Test
  void staysQuietAboutCachingWhenTheProviderSaysNothing() {
    ScriptedProvider provider =
        new ScriptedProvider(ScriptedProvider.Reply.text("hi")).usage(100, 5, null);
    Harness h = harness(AgentOptions.defaults(), new ToolRegistry(), provider);

    h.loop().run("go");

    assertTrue(
        h.notices().stream().anyMatch(n -> n.contains("token：100 输入 / 5 输出")), h.notices().toString());
    assertTrue(
        h.notices().stream().noneMatch(n -> n.contains("缓存")),
        "未被报告的比率不得凭空捏造：" + h.notices());
  }

  @Test
  void theReasoningTierReachesEveryRequest() {
    ScriptedProvider provider = new ScriptedProvider(ScriptedProvider.Reply.text("hi"));
    Harness h =
        harness(new AgentOptions(null, null, null, null, "high"), new ToolRegistry(), provider);

    h.loop().run("think hard");

    assertEquals("high", h.provider().requests().get(0).reasoning());
  }
}
