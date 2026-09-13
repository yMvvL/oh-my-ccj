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

  /** Records every invocation so tests can assert the loop actually called the tool. */
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

  /** A tool that only reads and takes its time, so overlap is measurable. */
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

  /** A tool that ends the run where it stands, the way the `restart` tool does. */
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
    // `restart` installs the jar and ends the run where it stands. The calls the model asked for in
    // the same turn will never be sent, so the second tool must not run — and the call it was given
    // is recorded as not run, because a conversation with a call and no result is one no provider
    // will accept again.
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

    assertEquals(List.of(), writer.seenArguments, "the call beside the ending one must not run");
    assertEquals(1, result.steps(), "one model turn, and that turn ended the run");
    Message.ToolResult unrun =
        h.session().messages().stream()
            .filter(Message.ToolResult.class::isInstance)
            .map(Message.ToolResult.class::cast)
            .filter(r -> r.toolCallId().equals("call_2"))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the unrun call must be recorded: " + h.session().messages()));
    assertTrue(unrun.error(), unrun.content());
  }

  @Test
  void readOnlyCallsInOneTurnOverlapAndKeepTheirOrder() {
    // Three files read in one turn is one round trip's worth of waiting, not three.
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
        "three 300 ms reads must overlap, not queue: " + elapsedMillis + "ms");

    // The session reads in the order the model asked, whatever the file system did.
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
    // Overlap is only safe for tools that change nothing, and a run of reads either side of a write
    // must not be reordered around it.
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
    // The session keeps everything; what goes on the wire is what fits.
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

    assertEquals(10, h.session().messages().size(), "the session keeps every message");
    Provider.Request last = provider.requests().get(provider.requests().size() - 1);
    assertTrue(
        TokenEstimate.of(last.messages()) <= 5_000,
        "the request must fit the budget: " + TokenEstimate.of(last.messages()));
    assertTrue(
        h.session().messages().size() > last.messages().size(),
        "and the session must still hold what the request left out");
    assertTrue(
        h.notices().stream().anyMatch(notice -> notice.startsWith("context:")),
        "a trimmed prompt is reported: " + h.notices());
  }

  @Test
  void abortStopsATurnThatIsStillStreamingFromTheModel() throws Exception {
    // Reported: pressing stop while the model was thinking took ages to do anything. The loop checked
    // its flag between steps, and a provider blocked mid-stream is not *between* anything — the turn
    // only ended when the whole reply had arrived. Stopping has to interrupt the call it is waiting
    // on, not wait for it to finish.
    ScriptedProvider provider =
        new ScriptedProvider(ScriptedProvider.Reply.text("never arrives")).streaming(300, 30);
    Harness h = harness(AgentOptions.defaults(), ToolRegistry.of(), provider);

    Thread runner = new Thread(() -> h.loop().run("think for a while"), "abort-streaming-test");
    runner.start();
    // Wait until the provider is actually inside the call, then stop.
    long waitForCall = System.nanoTime() + 5_000_000_000L;
    while (provider.callCount() == 0 && System.nanoTime() < waitForCall) {
      Thread.sleep(10);
    }
    assertEquals(1, provider.callCount(), "the provider must have been called");

    long started = System.nanoTime();
    h.loop().abort();
    runner.join(5_000);
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

    assertFalse(runner.isAlive(), "abort must end a turn waiting on the model");
    assertTrue(
        elapsedMillis < 2_000,
        "abort must not wait for the reply to finish arriving: " + elapsedMillis + "ms");
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

    assertFalse(runner.isAlive(), "the run must come back");
    assertTrue(
        elapsedMillis < 3_000,
        "abort must reach the running command, not wait out its timeout: " + elapsedMillis + "ms");
    assertEquals(1, provider.callCount(), "the model is not asked again after an abort");
    List<Message> history = h.session().messages();
    assertEquals(3, history.size(), history.toString());
    Message.ToolResult result = (Message.ToolResult) history.get(2);
    assertTrue(result.error(), result.content());
    assertTrue(
        result.content().contains("aborted"),
        "the transcript says why the command stopped: " + result.content());
  }

  @Test
  void anAbortBetweenTheTurnAndItsToolsStillLeavesAUsableSession() {
    // The bug this exists for: the assistant turn is appended before its calls run, so an abort in
    // between left a session that every later request was refused for — permanently.
    RecordingTool tool = new RecordingTool("read", "contents");
    ScriptedProvider provider =
        new ScriptedProvider(
            ScriptedProvider.Reply.calls(
                new Message.ToolCall("call_1", "read", "{}"),
                new Message.ToolCall("call_2", "read", "{}")));
    Harness h = harness(AgentOptions.defaults(), ToolRegistry.of(tool), provider);
    // Abort from inside the turn that asked for the calls: the narrowest possible window, and the
    // one a user's Abort button lands in all the time.
    AgentLoop loop = abortOnAssistant(h.session(), ToolRegistry.of(tool), provider);

    AgentLoop.Result result = loop.run("read both files");

    assertTrue(result.aborted());
    assertEquals(1, provider.callCount(), "the model is not asked again");
    assertEquals(List.of(), tool.seenArguments, "neither call ran");
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
              "every call the turn made must have a result: " + history);
        }
      }
    }
  }

  /** A loop whose listener aborts the run the moment a turn asks for a tool. */
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
    // What the user sees: a conversation interrupted once, then every later message refused with
    // "an assistant message with tool_calls must be followed by tool messages".
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
    assertTrue(answered, "the request must answer the call that was left hanging: " + sent);
    assertTrue(
        notices.stream().anyMatch(text -> text.contains("interrupted tool call")),
        "and the user is told why it suddenly works: " + notices);
    // The session file keeps its own record: the repair is a projection, not a rewrite.
    assertTrue(
        session.messages().stream().noneMatch(Message.ToolResult.class::isInstance),
        "nothing was written back into the session: " + session.messages());
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
    assertEquals(List.of("all done"), h.text(), "deltas must reach the listener as they arrive");
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
        h.notices().stream().allMatch(n -> n.startsWith("tokens:")),
        "a turn that ends in an answer warns about nothing: " + h.notices());
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

    assertEquals("recovered", result.finalText(), "the loop must survive bad tool calls");
    List<Message> history = h.session().messages();
    Message.ToolResult unknown = (Message.ToolResult) history.get(2);
    Message.ToolResult badArgs = (Message.ToolResult) history.get(3);
    assertTrue(unknown.error());
    assertTrue(unknown.content().contains("unknown tool 'teleport'"), unknown.content());
    assertTrue(unknown.content().contains("edit"), "the error should list available tools");
    assertTrue(badArgs.error());
    assertTrue(badArgs.content().contains("invalid arguments"), badArgs.content());
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
    assertEquals(1, provider.callCount(), "no further model turn after abort");
    assertEquals(1, result.steps());
  }

  /**
   * There is no step ceiling: a run is ended by the model answering or by an abort, nothing else. A
   * long piece of work must not be cut off at a number somebody guessed, so the pin here is that a
   * run past the old default of 25 steps simply continues to its answer.
   */
  @Test
  void aRunIsNotCutOffByAStepCeiling() {
    int steps = 40;   // comfortably past the 25 the loop used to stop at
    List<ScriptedProvider.Reply> script = new ArrayList<>();
    for (int i = 0; i < steps; i++) {
      script.add(ScriptedProvider.Reply.calls(new Message.ToolCall("c" + i, "bash", "{}")));
    }
    script.add(ScriptedProvider.Reply.text("done after " + steps + " tool turns"));

    RecordingTool tool = new RecordingTool("bash", "ok");
    ScriptedProvider provider = new ScriptedProvider("scripted", script);
    Harness h = harness(AgentOptions.defaults(), ToolRegistry.of(tool), provider);

    AgentLoop.Result result = h.loop().run("a long task");

    assertEquals(steps, tool.seenArguments.size(), "every requested call ran");
    assertEquals(steps + 1, provider.callCount(), "the model was asked again every time");
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
        h.notices().stream().anyMatch(n -> n.contains("100 in (80% cached) / 5 out")),
        h.notices().toString());
  }

  @Test
  void staysQuietAboutCachingWhenTheProviderSaysNothing() {
    ScriptedProvider provider =
        new ScriptedProvider(ScriptedProvider.Reply.text("hi")).usage(100, 5, null);
    Harness h = harness(AgentOptions.defaults(), new ToolRegistry(), provider);

    h.loop().run("go");

    assertTrue(
        h.notices().stream().anyMatch(n -> n.contains("100 in / 5 out")), h.notices().toString());
    assertTrue(
        h.notices().stream().noneMatch(n -> n.contains("cached")),
        "an unreported rate must not be invented: " + h.notices());
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
