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
  private static final class RecordingTool implements Tool {
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
    assertTrue(h.notices().stream().noneMatch(n -> n.contains("step limit")));
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

  @Test
  void maxStepsBoundsARunawayToolLoop() {
    RecordingTool tool = new RecordingTool("bash", "ok");
    ScriptedProvider provider =
        new ScriptedProvider(
            ScriptedProvider.Reply.calls(new Message.ToolCall("c1", "bash", "{}")));
    Harness h = harness(new AgentOptions(null, null, null, null, 2), ToolRegistry.of(tool), provider);

    AgentLoop.Result result = h.loop().run("loop forever");

    assertEquals(2, provider.callCount());
    assertEquals(2, result.steps());
    assertTrue(
        h.notices().stream().anyMatch(n -> n.contains("2 steps")),
        "user must be told why the run stopped: " + h.notices());
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
}
