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
 * 一次真正跑起来的子代理。
 *
 * <p>这里的每一条都是设计所依赖的断言，并且是对着一个真实的 {@link AgentLoop} 和真实的文件系统
 * 检验过的，而不是在注释里争论出来的：
 *
 * <ul>
 *   <li>它不能再往下委派 —— 它的注册表里没有 {@code task}，所以递归是不可能，而不只是受限；
 *   <li>它的阅读不会到达调用方，它的事件也不会；
 *   <li>写入角色的改动会去问主代理所问的同一个人；
 *   <li>主回合被取消时它会停下。
 * </ul>
 */
class SubAgentRunnerTest {

  @TempDir Path project;

  /** 一个按脚本作答、并记录自己被问了什么的提供方。 */
  private static final class ScriptedProvider implements Provider {

    private final Deque<Message.Assistant> script = new ArrayDeque<>();
    /**
     * 每个脚本化回复的花费，与 {@code script} 对齐。空数组意味着「这个回复什么都没报告」，这正是
     * 一个与 token 无关的用例想要的；真实的提供方总会送来用量，所以到达调用方的计数用另一种形式
     * 来检验。
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

    /** 一个同时报告自己花费的文本回复：输入、输出、缓存输入。 */
    ScriptedProvider text(String text, int input, int output, int cached) {
      return reply(Message.Assistant.text(text), new int[] {input, output, cached});
    }

    /** 一个请求一次工具调用的回合。 */
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

  /** 没有给出审批者时，子代理不问任何人就运行 —— 这正是没有用户的调用方想要的。 */
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

  /** 一个小小的接缝，让测试能传 lambda，而不必到处 import BooleanSupplier。 */
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
    // 它做过的阅读不在回来的东西里的任何地方：那正是这个特性的全部意义。
    assertFalse(report.findings().contains("def "), report.findings());
  }

  @Test
  void aSubAgentIsNotOfferedTheTaskTool() {
    // 递归被构造性地拒绝：那个工具干脆不在注册表里，所以既没有会弄错的深度上限，子代理也没有
    // 办法去要求一个。
    ScriptedProvider provider = new ScriptedProvider().text("STATUS: done\nFINDINGS:\nnone");
    // 请求里点名的工具，就是模型能看到的东西；对请求做断言，就是对接供的东西做断言。
    runner(provider, null).run(SubAgentRole.of("explore").get(), "look around", null);

    List<String> offered =
        provider.requests().get(0).tools().stream().map(ToolSpec::name).toList();
    assertFalse(offered.contains("task"), "子代理绝不能往下委派：" + offered);
    assertTrue(offered.contains("read"), offered.toString());
    assertTrue(offered.contains("grep"), offered.toString());
  }

  @Test
  void aReadOnlyRoleIsNotOfferedTheWriteTools() {
    ScriptedProvider provider = new ScriptedProvider().text("STATUS: done\nFINDINGS:\none");
    runner(provider, null).run(SubAgentRole.of("verify").get(), "check it", null);

    List<String> offered =
        provider.requests().get(0).tools().stream().map(ToolSpec::name).toList();
    assertFalse(offered.contains("write"), "核查者不能改动它所核查的东西：" + offered);
    assertFalse(offered.contains("edit"), offered.toString());
    assertFalse(offered.contains("bash"), "也不能运行命令：" + offered);
  }

  @Test
  void aBuildRoleGetsTheWriteTools() {
    ScriptedProvider provider = new ScriptedProvider().text("STATUS: done\nFINDINGS:\nwrote it");

    runner(provider, null).run(SubAgentRole.of("build").get(), "write the file", null);

    List<String> offered =
        provider.requests().get(0).tools().stream().map(ToolSpec::name).toList();
    assertTrue(offered.contains("write"), offered.toString());
    assertTrue(offered.contains("edit"), offered.toString());
    assertFalse(offered.contains("task"), "仍然没有委派：" + offered);
  }

  @Test
  void theSubAgentsTokensReachTheConversationThatPaid() {
    // 阅读保持私密；账单不是。子代理的模型调用，是同一个账号上的同一个模型，所以一份不带花费就
    // 回来的报告，会让用量面板低报实际花掉的东西 —— 而一个悄悄出错的数字比没有数字更糟。
    ScriptedProvider provider =
        new ScriptedProvider().text("STATUS: done\nFINDINGS:\nok", 120, 30, 100);

    SubAgentReport report =
        runner(provider, null).run(SubAgentRole.of("explore").get(), "look around", null);

    assertEquals(120, report.usage().inputTokens(), report.render("."));
    assertEquals(30, report.usage().outputTokens());
    assertEquals(100, report.usage().cachedInputTokens());
    assertEquals(1, report.usage().userTurns(), "任务文本就是一个用户回合");
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

    // 两次模型调用：请求读取的那次，和撰写报告的那次。
    assertEquals(2, report.usage().modelTurns(), report.render("."));
    assertEquals(1, report.usage().toolCalls());
    assertEquals(10, report.usage().inputTokens(), "被报告的是第二个回合的用量");
  }

  @Test
  void aBuildSubAgentWritesWhereTheMainAgentWrites() throws Exception {
    // 子代理不是另一种代理：它在会话自己的目录里工作，它的文件立刻就在那里，没有提升这一步。
    // 它所取代的那个暂存目录，存在的唯一目的就是让一个无法审批的写入者远离项目，而它花的比赚的
    // 多：一个没能在同一回合里完成提升的成品文件，被悄悄丢弃了。
    ScriptedProvider provider =
        new ScriptedProvider()
            .calls("c1", "write", "{\"path\":\"std.cpp\",\"content\":\"int main(){}\"}")
            .text("STATUS: done\nFILES:\n  std.cpp  final  the solution\nFINDINGS:\nwritten");

    SubAgentReport report =
        runner(provider, null).run(SubAgentRole.of("build").get(), "write std", null);

    assertTrue(
        Files.exists(project.resolve("std.cpp")),
        "文件就在项目里，也就是主代理会把它放的地方");
    assertEquals(1, report.keepable().size());
    assertEquals("std.cpp", report.keepable().get(0).path());
  }

  @Test
  void theProjectRulesStillReachASubAgent() throws Exception {
    // 子代理像任何一次运行那样读取文件，所以因为工作被委派出去就丢掉项目自己的 CCJ.md，会让它的
    // 表现比派它出去的那个代理还差。
    Files.writeString(project.resolve(ProjectPrompt.FILE_NAME), "PROJECT RULE: this tree uses tabs.");
    ScriptedProvider provider = new ScriptedProvider().text("STATUS: done\nFINDINGS:\nok");

    runner(provider, null).run(SubAgentRole.of("explore").get(), "look", "BASE PROMPT");

    String system = provider.requests().get(0).system();
    assertNotNull(system);
    assertTrue(system.contains("PROJECT RULE"), system);
    assertTrue(system.contains("BASE PROMPT"), system);
    assertTrue(system.contains("sub-agent"), "而且它被告知了自己是什么：" + system);
  }

  @Test
  void theRoleIsToldWhatItIs() {
    ScriptedProvider provider = new ScriptedProvider().text("STATUS: done\nFINDINGS:\nok");
    runner(provider, null).run(SubAgentRole.of("verify").get(), "check", null);

    String system = provider.requests().get(0).system();
    assertTrue(system.contains("report what you find"), system);
    assertTrue(system.contains("STATUS:"), "以及它必须使用的报告格式：" + system);
  }

  @Test
  void aCancelledMainTurnStopsTheSubAgent() {
    // 中止这个回合，必须触达这个回合委派出去的工作，否则用户按下停止之后，会留下一个仍在运行的
    // 子代理，而屏幕上没有任何东西说明这一点。
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
    // 什么都没有回来，就是一次什么都没产出的运行。把它称作 "done"，会让主代理基于一个并不存在的
    // 答案继续往下走。
    ScriptedProvider provider = new ScriptedProvider().text("");

    SubAgentReport report =
        runner(provider, null).run(SubAgentRole.of("explore").get(), "look", null);

    assertEquals("failed", report.status());
  }

  @Test
  void aProviderThatThrowsDoesNotKillTheCaller() {
    // 无论如何主代理都得继续干活；这里抛一个异常，会为了一个主代理本可以应对的失败，结束用户正在
    // 看着的那个回合。
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
    // 等待写锁是 loop.abort() 触达不到的一种状态 —— 没有设置工作线程，也没有模型调用在进行中 ——
    // 所以一个只中止循环的截止时间，覆盖不了排队这一段。在计时 tryLock 之前已实测过：一次排在
    // 另一个写入者后面的运行，等了永远那么久，然后才开始计算自己的截止时间。
    //
    // 这把锁是进程级、静态的，所以这个测试只有在它就是持锁者的那一个时才说明问题。来自另一个测试
    // 的残留持锁者会让它去等别人的运行，那是另一种测量 —— 下面的断言会说明这一点，而不是因为
    // 错误的理由而通过。
    assertTrue(
        SubAgentRunner.noWriterRunning(),
        "另一个测试的写入运行仍持有那把进程级写锁");
    // 第一个写入者在它的运行*内部*阻塞，这样锁就确实被持有，而第二个会排队：一个瞬间返回的提供方
    // 会在第二个开口之前就把锁放掉。
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
    // 等到第一个真正进入它的运行，这样锁被持有，第二个就会排队。
    assertTrue(inRun.await(5, java.util.concurrent.TimeUnit.SECONDS), "第一个写入者已经启动");
    ScriptedProvider second = new ScriptedProvider().text("STATUS: done\nFINDINGS:\nsecond");
    long start = System.currentTimeMillis();
    SubAgentReport report =
        runner(second, null, Duration.ofSeconds(2))
            .run(SubAgentRole.of("build").get(), "wait behind it", null);

    long waited = System.currentTimeMillis() - start;
    assertEquals("failed", report.status(), report.render("."));
    assertTrue(report.summary().contains("为另一个写入任务等了"), report.summary());
    // 余量是故意留宽的：截止时间是 2 秒，而第一个写入者被按住的时间远长于此，所以这里测量的是
    // 「还在排队时就放弃了」，而不是它所在机器的速度。
    assertTrue(waited >= 1_500, "它确实等满了截止时间：" + waited + "ms");
    assertTrue(waited < 15_000, "然后就放弃了，而不是永远等下去：" + waited + "ms");
    assertTrue(second.requests().isEmpty(), "而且从未问过模型");
    release.countDown();
    first.join(10_000);
  }

  @Test
  void aTaskWithNoRoleOrNoWorkIsRefusedWithoutCallingTheModel() {
    ScriptedProvider provider = new ScriptedProvider().text("should not be asked");

    assertEquals("failed", runner(provider, null).run(null, "look", null).status());
    assertEquals("failed", runner(provider, null).run(SubAgentRole.of("explore").get(), "  ", null).status());

    assertTrue(provider.requests().isEmpty(), "模型不会被要求去做一件没有内容的事");
  }

  @Test
  void theSubAgentsReadingNeverReachesTheCaller() throws Exception {
    // 把节省下来的上下文写成一个测试：读十个文件，返回一个结论。如果那些阅读也一并回来，这个特性
    // 就等于什么都没做。
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

    assertEquals(11, provider.requests().size(), "十次读取加上最后的回答");
    String rendered = report.render(".");
    assertFalse(rendered.contains("contents of file"), "那些阅读没有回来：" + rendered);
    assertTrue(rendered.contains("file3.txt:1"), rendered);
  }

  @Test
  void aWritingSubAgentAsksBeforeItChangesAnything() throws Exception {
    // 子代理在它*读*什么上是不可见的；在它*做*什么上不是。它的请求会经过启动它的那段对话，所以
    // 提示会出现在用户本来就在看的转录里 —— 而一次拒绝会挡住这次写入。
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

    assertEquals(java.util.List.of("write"), asked, "这次写入被提交给了审批者");
    assertFalse(Files.exists(project.resolve("blocked.txt")), "而它被拒绝，所以什么都没写");
    assertEquals("blocked", report.status());
  }

  @Test
  void aReadingSubAgentDoesNotAsk() {
    // read、glob 和 grep 都被声明为只读，所以它们从不到达审批者：一个连看一眼文件都要请求许可的
    // 子代理，会在它搜索的每一步都弹一次提示。
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

    assertEquals(0, asked.get(), "只读工具不询问任何人");
  }

  @Test
  void aWritingRoleIsToldWhereItWorksAndThatItMustAsk() {
    // 没有暂存区，也没有提升步骤，所以提示词要说的话不一样了：会话自己的目录，以及那里的改动会
    // 走主代理所走的同一道许可。
    ScriptedProvider provider = new ScriptedProvider().text("STATUS: done\nFINDINGS:\nok");

    runner(provider, null).run(SubAgentRole.of("build").get(), "write it", null);

    String system = provider.requests().get(0).system();
    assertTrue(
        system.contains(project.toString()),
        "写入角色被告知它在哪儿工作：" + system);
    assertTrue(
        system.contains("permission") || system.contains("approv"),
        "以及它的改动由同一个人批准：" + system);
    assertFalse(
        system.contains("promote"),
        "没有提升步骤需要描述：" + system);
  }
}
