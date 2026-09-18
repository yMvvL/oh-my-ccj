package com.ccj.agent.core;

import com.ccj.agent.core.ApprovalAnswer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.tool.Tools;
import java.nio.file.Files;
import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A sub-agent, actually run.
 *
 * <p>Everything here is a claim the design rests on, checked against a real {@link AgentLoop} and a
 * real file system rather than argued in a comment:
 *
 * <ul>
 *   <li>it cannot delegate further — no {@code task} in its registry, so recursion is impossible
 *       rather than merely limited;
 *   <li>its reading does not reach the caller, and neither do its events;
 *   <li>a writing role's changes ask the same person the main agent asks;
 *   <li>it stops when the main turn is cancelled.
 * </ul>
 */
class SubAgentRunnerTest {

  @TempDir Path project;

  /** A provider that answers from a script and records what it was asked. */
  private static final class ScriptedProvider implements Provider {

    private final Deque<Message.Assistant> script = new ArrayDeque<>();
    /**
     * What each scripted reply cost, aligned with {@code script}. An empty array means "this reply
     * reported nothing", which is what a case that is not about tokens wants; a real provider always
     * sends usage, so the tally that reaches the caller is checked with the other form.
     */
    private final Deque<int[]> costs = new ArrayDeque<>();
    private final List<Provider.Request> requests = new ArrayList<>();

    ScriptedProvider reply(Message.Assistant assistant) {
      return reply(assistant, null);
    }

    ScriptedProvider reply(Message.Assistant assistant, int[] cost) {
      script.add(assistant);
      costs.add(cost == null ? new int[0] : cost);
      return this;
    }

    ScriptedProvider text(String text) {
      return reply(Message.Assistant.text(text));
    }

    /** A text reply that also reports what it cost: input, output, cached input. */
    ScriptedProvider text(String text, int input, int output, int cached) {
      return reply(Message.Assistant.text(text), new int[] {input, output, cached});
    }

    /** A turn that asks for one tool call. */
    ScriptedProvider calls(String id, String name, String arguments) {
      return reply(
          new Message.Assistant("", List.of(new Message.ToolCall(id, name, arguments)), List.of()));
    }

    List<Provider.Request> requests() {
      return List.copyOf(requests);
    }

    @Override
    public String name() {
      return "scripted";
    }

    @Override
    public Message.Assistant complete(Request request, Consumer<Event> listener) {
      requests.add(request);
      Message.Assistant next =
          script.isEmpty() ? Message.Assistant.text("(no scripted reply left)") : script.poll();
      if (!next.text().isEmpty()) {
        listener.accept(new Event.TextDelta(next.text()));
      }
      int[] cost = costs.isEmpty() ? new int[0] : costs.poll();
      if (cost.length == 3) {
        listener.accept(new Event.Usage(cost[0], cost[1], cost[2]));
      }
      return next;
    }
  }

  private SubAgentRunner runner(Provider provider, BooleanSupplierLike cancelled) {
    return runner(provider, cancelled, null);
  }

  /** With no approver given, a sub-agent runs without asking — what a caller with no user wants. */
  private SubAgentRunner runner(
      Provider provider, BooleanSupplierLike cancelled, java.time.Duration deadline) {
    return runner(provider, cancelled, deadline, null);
  }

  private SubAgentRunner runner(
      Provider provider,
      BooleanSupplierLike cancelled,
      java.time.Duration deadline,
      Approver approver) {
    return new SubAgentRunner(
        provider,
        Tools.standard(),
        new AgentOptions("m", null, null, null, null, null),
        project,
        cancelled == null ? () -> false : cancelled,
        32 * 1024,
        deadline == null ? SubAgentRunner.DEFAULT_DEADLINE : deadline,
        approver);
  }

  /** A tiny seam so the tests can pass a lambda without importing BooleanSupplier everywhere. */
  @FunctionalInterface
  interface BooleanSupplierLike extends java.util.function.BooleanSupplier {}

  @Test
  void anExploreSubAgentReportsItsFindingsBack() throws Exception {
    Files.writeString(project.resolve("answer.txt"), "the answer is 42\n");
    ScriptedProvider provider =
        new ScriptedProvider()
            .calls("c1", "read", "{\"path\":\"answer.txt\"}")
            .text(
                """
                STATUS: done
                SUMMARY: Found the answer.
                FINDINGS:
                The answer is in answer.txt:1 — it is 42.
                """);

    SubAgentReport report =
        runner(provider, null).run(SubAgentRole.of("explore").get(), "find the answer", null);

    assertEquals("done", report.status());
    assertTrue(report.summary().contains("Found the answer"), report.summary());
    assertTrue(report.findings().contains("answer.txt:1"), report.findings());
    // The reading it did is nowhere in what came back: that is the whole point of the feature.
    assertFalse(report.findings().contains("def "), report.findings());
  }

  @Test
  void aSubAgentIsNotOfferedTheTaskTool() {
    // Recursion is refused by construction: the tool is simply not in the registry, so there is no
    // depth limit to get wrong and no way for a sub-agent to ask for one.
    ScriptedProvider provider = new ScriptedProvider().text("STATUS: done\nFINDINGS:\nnone");
    // The tool named in the request is what the model can see; asserting on the request is asserting
    // on what was offered.
    runner(provider, null).run(SubAgentRole.of("explore").get(), "look around", null);

    List<String> offered =
        provider.requests().get(0).tools().stream().map(ToolSpec::name).toList();
    assertFalse(offered.contains("task"), "a sub-agent must not be able to delegate: " + offered);
    assertTrue(offered.contains("read"), offered.toString());
    assertTrue(offered.contains("grep"), offered.toString());
  }

  @Test
  void aReadOnlyRoleIsNotOfferedTheWriteTools() {
    ScriptedProvider provider = new ScriptedProvider().text("STATUS: done\nFINDINGS:\none");
    runner(provider, null).run(SubAgentRole.of("verify").get(), "check it", null);

    List<String> offered =
        provider.requests().get(0).tools().stream().map(ToolSpec::name).toList();
    assertFalse(offered.contains("write"), "a verifier cannot change what it checks: " + offered);
    assertFalse(offered.contains("edit"), offered.toString());
    assertFalse(offered.contains("bash"), "and cannot run commands either: " + offered);
  }

  @Test
  void aBuildRoleGetsTheWriteTools() {
    ScriptedProvider provider = new ScriptedProvider().text("STATUS: done\nFINDINGS:\nwrote it");

    runner(provider, null).run(SubAgentRole.of("build").get(), "write the file", null);

    List<String> offered =
        provider.requests().get(0).tools().stream().map(ToolSpec::name).toList();
    assertTrue(offered.contains("write"), offered.toString());
    assertTrue(offered.contains("edit"), offered.toString());
    assertFalse(offered.contains("task"), "still no delegation: " + offered);
  }

  @Test
  void theSubAgentsTokensReachTheConversationThatPaid() {
    // The reading stays private; the bill does not. A sub-agent's model calls are the same model on
    // the same account, so a report that came back without its cost would make the usage panel
    // understate what was spent — and a number that is quietly wrong is worse than no number.
    ScriptedProvider provider =
        new ScriptedProvider().text("STATUS: done\nFINDINGS:\nok", 120, 30, 100);

    SubAgentReport report =
        runner(provider, null).run(SubAgentRole.of("explore").get(), "look around", null);

    assertEquals(120, report.usage().inputTokens(), report.render("."));
    assertEquals(30, report.usage().outputTokens());
    assertEquals(100, report.usage().cachedInputTokens());
    assertEquals(1, report.usage().userTurns(), "the task text is one user turn");
  }

  @Test
  void aSubAgentsToolCallsAndModelTurnsAreCountedToo() throws Exception {
    Files.writeString(project.resolve("a.txt"), "hi\n");
    ScriptedProvider provider =
        new ScriptedProvider()
            .calls("c1", "read", "{\"path\":\"a.txt\"}")
            .text("STATUS: done\nFINDINGS:\nok", 10, 2, 0);

    SubAgentReport report =
        runner(provider, null).run(SubAgentRole.of("explore").get(), "read it", null);

    // Two model calls: the one that asked for the read, and the one that wrote the report.
    assertEquals(2, report.usage().modelTurns(), report.render("."));
    assertEquals(1, report.usage().toolCalls());
    assertEquals(10, report.usage().inputTokens(), "the second turn's usage is the one reported");
  }

  @Test
  void aBuildSubAgentWritesWhereTheMainAgentWrites() throws Exception {
    // A sub-agent is not a second kind of agent: it works in the session's directory and its file is
    // there immediately, with no promote step. The staging directory this replaces existed only to
    // keep an unapprovable writer away from the project, and it cost more than it bought: a finished
    // file whose promotion did not happen in the same turn was discarded, silently.
    ScriptedProvider provider =
        new ScriptedProvider()
            .calls("c1", "write", "{\"path\":\"std.cpp\",\"content\":\"int main(){}\"}")
            .text("STATUS: done\nFILES:\n  std.cpp  final  the solution\nFINDINGS:\nwritten");

    SubAgentReport report =
        runner(provider, null).run(SubAgentRole.of("build").get(), "write std", null);

    assertTrue(
        Files.exists(project.resolve("std.cpp")),
        "the file is in the project, where the main agent would have put it");
    assertEquals(1, report.keepable().size());
    assertEquals("std.cpp", report.keepable().get(0).path());
  }

  @Test
  void theProjectRulesStillReachASubAgent() throws Exception {
    // A sub-agent reads files like any other run, so dropping the project's own CCJ.md because the
    // work was delegated would make it behave worse than the agent that sent it.
    Files.writeString(project.resolve(ProjectPrompt.FILE_NAME), "PROJECT RULE: this tree uses tabs.");
    ScriptedProvider provider = new ScriptedProvider().text("STATUS: done\nFINDINGS:\nok");

    runner(provider, null).run(SubAgentRole.of("explore").get(), "look", "BASE PROMPT");

    String system = provider.requests().get(0).system();
    assertNotNull(system);
    assertTrue(system.contains("PROJECT RULE"), system);
    assertTrue(system.contains("BASE PROMPT"), system);
    assertTrue(system.contains("sub-agent"), "and it is told what it is: " + system);
  }

  @Test
  void theRoleIsToldWhatItIs() {
    ScriptedProvider provider = new ScriptedProvider().text("STATUS: done\nFINDINGS:\nok");
    runner(provider, null).run(SubAgentRole.of("verify").get(), "check", null);

    String system = provider.requests().get(0).system();
    assertTrue(system.contains("report what you find"), system);
    assertTrue(system.contains("STATUS:"), "and the report format it must use: " + system);
  }

  @Test
  void aCancelledMainTurnStopsTheSubAgent() {
    // Aborting the turn has to reach the work the turn delegated, or the user's stop button leaves a
    // sub-agent running with nothing on screen to say so.
    ScriptedProvider provider =
        new ScriptedProvider()
            .calls("c1", "bash", "{\"command\":\"sleep 30\"}")
            .text("STATUS: done\nFINDINGS:\nshould not get here");

    SubAgentReport report =
        runner(provider, () -> true)
            .run(SubAgentRole.of("explore").get(), "wait forever", null);

    assertEquals("failed", report.status(), report.render("."));
  }

  @Test
  void aRunThatProducesNoReportIsReportedAsAFailure() {
    // Nothing back at all is a run that produced nothing. Calling that "done" would have the main
    // agent proceed on an answer that does not exist.
    ScriptedProvider provider = new ScriptedProvider().text("");

    SubAgentReport report =
        runner(provider, null).run(SubAgentRole.of("explore").get(), "look", null);

    assertEquals("failed", report.status());
  }

  @Test
  void aProviderThatThrowsDoesNotKillTheCaller() {
    // The main agent has to keep working either way; an exception here would end the turn the user
    // is watching over a failure the main agent could have reacted to.
    Provider broken =
        new Provider() {
          @Override
          public String name() {
            return "broken";
          }

          @Override
          public Message.Assistant complete(Request request, Consumer<Event> listener) {
            throw new IllegalStateException("the relay said no");
          }
        };

    SubAgentReport report =
        runner(broken, null).run(SubAgentRole.of("explore").get(), "look", null);

    assertEquals("failed", report.status());
    assertTrue(report.summary().contains("relay said no"), report.summary());
  }

  @Test
  void aQueuedWriterGivesUpRatherThanRunningPastItsDeadline() throws Exception {
    // Waiting on the write lock is a state loop.abort() cannot reach — no worker thread is set and no
    // model call is in flight — so a deadline that only aborts the loop does not cover the queueing.
    // Measured before the timed tryLock: a run queued behind another writer waited for ever and only
    // then started counting its own deadline.
    //
    // The lock is process-wide and static, so this test is only meaningful when it is the one holding
    // it. A leftover holder from another test would make it wait on somebody else's run, which is a
    // different measurement — the assertion below says so rather than passing for the wrong reason.
    assertTrue(
        SubAgentRunner.noWriterRunning(),
        "another test's writing run still holds the process-wide write lock");
    // The first writer blocks *inside* its run, so the lock is genuinely held while the second one
    // queues: a provider that returns instantly releases it before the second ever asks.
    java.util.concurrent.CountDownLatch inRun = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    Provider slow =
        new Provider() {
          @Override
          public String name() {
            return "slow";
          }

          @Override
          public Message.Assistant complete(Request request, Consumer<Event> listener) {
            inRun.countDown();
            try {
              release.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return Message.Assistant.text("STATUS: done\nFINDINGS:\nfirst");
          }
        };

    Thread first =
        new Thread(
            () ->
                runner(slow, null, Duration.ofMinutes(1))
                    .run(SubAgentRole.of("build").get(), "hold the lock", null));
    first.start();
    // Wait until the first is genuinely inside its run, so the lock is held and the second queues.
    assertTrue(inRun.await(5, java.util.concurrent.TimeUnit.SECONDS), "the first writer started");
    ScriptedProvider second = new ScriptedProvider().text("STATUS: done\nFINDINGS:\nsecond");
    long start = System.currentTimeMillis();
    SubAgentReport report =
        runner(second, null, Duration.ofSeconds(2))
            .run(SubAgentRole.of("build").get(), "wait behind it", null);

    long waited = System.currentTimeMillis() - start;
    assertEquals("failed", report.status(), report.render("."));
    assertTrue(report.summary().contains("waited"), report.summary());
    // The margin is wide on purpose: the deadline is 2s and the first writer is held for far longer,
    // so this measures "gave up while still queued" rather than the speed of the machine it runs on.
    assertTrue(waited >= 1_500, "it really did wait for the deadline: " + waited + "ms");
    assertTrue(waited < 15_000, "and then gave up rather than waiting for ever: " + waited + "ms");
    assertTrue(second.requests().isEmpty(), "and the model was never asked");
    release.countDown();
    first.join(10_000);
  }

  @Test
  void aTaskWithNoRoleOrNoWorkIsRefusedWithoutCallingTheModel() {
    ScriptedProvider provider = new ScriptedProvider().text("should not be asked");

    assertEquals("failed", runner(provider, null).run(null, "look", null).status());
    assertEquals("failed", runner(provider, null).run(SubAgentRole.of("explore").get(), "  ", null).status());

    assertTrue(provider.requests().isEmpty(), "the model is not asked to do nothing");
  }

  @Test
  void theSubAgentsReadingNeverReachesTheCaller() throws Exception {
    // The context saving, stated as a test: ten files read, one conclusion returned. If the reading
    // came back too, the feature would be doing nothing.
    for (int i = 0; i < 10; i++) {
      Files.writeString(project.resolve("file" + i + ".txt"), "contents of file " + i + "\n");
    }
    ScriptedProvider provider = new ScriptedProvider();
    for (int i = 0; i < 10; i++) {
      provider.calls("c" + i, "read", "{\"path\":\"file" + i + ".txt\"}");
    }
    provider.text("STATUS: done\nSUMMARY: only file 3 mattered.\nFINDINGS:\nfile3.txt:1");

    SubAgentReport report =
        runner(provider, null).run(SubAgentRole.of("explore").get(), "which file matters?", null);

    assertEquals(11, provider.requests().size(), "ten reads and the final answer");
    String rendered = report.render(".");
    assertFalse(rendered.contains("contents of file"), "the reading did not come back: " + rendered);
    assertTrue(rendered.contains("file3.txt:1"), rendered);
  }

  @Test
  void aWritingSubAgentAsksBeforeItChangesAnything() throws Exception {
    // The sub-agent is invisible in what it *reads*; it is not invisible in what it *does*. Its
    // request travels through the conversation that started it, so the prompt appears in the
    // transcript the user is already watching — and a refusal stops the write.
    java.util.List<String> asked = new java.util.ArrayList<>();
    Approver refusing =
        request -> {
          asked.add(request.title());
          return ApprovalAnswer.DENY;
        };
    ScriptedProvider provider =
        new ScriptedProvider()
            .calls("c1", "write", "{\"path\":\"blocked.txt\",\"content\":\"nope\"}")
            .text("STATUS: blocked\nFINDINGS:\nrefused");

    SubAgentReport report =
        runner(provider, null, null, refusing)
            .run(SubAgentRole.of("build").get(), "write it", null);

    assertEquals(java.util.List.of("write"), asked, "the write was put to the approver");
    assertFalse(Files.exists(project.resolve("blocked.txt")), "and refused, so nothing was written");
    assertEquals("blocked", report.status());
  }

  @Test
  void aReadingSubAgentDoesNotAsk() {
    // read, glob and grep are declared read-only, so they never reach the approver: a sub-agent that
    // asked permission to look at a file would be a prompt for every step of its search.
    java.util.concurrent.atomic.AtomicInteger asked =
        new java.util.concurrent.atomic.AtomicInteger();
    Approver counting =
        request -> {
          asked.incrementAndGet();
          return ApprovalAnswer.ALLOW_ONCE;
        };
    ScriptedProvider provider =
        new ScriptedProvider()
            .calls("c1", "glob", "{\"pattern\":\"*.txt\"}")
            .text("STATUS: done\nFINDINGS:\nnothing");

    runner(provider, null, null, counting)
        .run(SubAgentRole.of("explore").get(), "look around", null);

    assertEquals(0, asked.get(), "a read-only tool asks nobody");
  }

  @Test
  void aWritingRoleIsToldWhereItWorksAndThatItMustAsk() {
    // No staging area and no promotion step, so what the prompt has to say is different: the session's
    // own directory, and that a change there goes through the same permission the main agent asks for.
    ScriptedProvider provider = new ScriptedProvider().text("STATUS: done\nFINDINGS:\nok");

    runner(provider, null).run(SubAgentRole.of("build").get(), "write it", null);

    String system = provider.requests().get(0).system();
    assertTrue(
        system.contains(project.toString()),
        "a writing role is told where it works: " + system);
    assertTrue(
        system.contains("permission") || system.contains("approv"),
        "and that its changes are approved by the same person: " + system);
    assertFalse(
        system.contains("promote"),
        "with no promotion step to describe: " + system);
  }
}
