package com.ccj.agent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 每个任务跑在一个真的空目录里，而每一条断言都真的对过。
 *
 * <p>失败的那一半比通过的那一半重要：一台把所有任务都判成通过的评测机看起来和一个全绿的套件一模一样，
 * 而它唯一的作用是让人放心地把一个坏掉的改动提交上去。所以这里逐条钉住每一种「没对上」——文件内容、工具
 * 序列、步数上限、被拒绝的调用、以及运行时抛出的异常。
 */
class EvalRunnerTest {

  @TempDir Path tmp;

  @Test
  void aTaskThatDoesWhatItSaysPassesAndReportsItsNumbers() {
    EvalTask task =
        new EvalTask(
            "make-a-file",
            "建一个文件",
            "建一个文件",
            Map.of(),
            List.of(
                ScriptedModel.Step.call("write", "{\"path\":\"proof.txt\",\"content\":\"done\\n\"}"),
                new ScriptedModel.Step("建好了。", List.of(), new int[] {700, 40, 512})),
            true,
            new EvalTask.Expect(Map.of("proof.txt", "done\n"), List.of(), List.of("建好了"), List.of("write"), 3));

    EvalReport report = EvalRunner.run(List.of(task), null);

    assertTrue(report.allPassed(), report.render());
    assertEquals(1, report.passed());
    assertEquals(0, report.failed());
    EvalReport.TaskResult result = report.tasks().get(0);
    assertEquals(2, result.steps());
    assertEquals(1, result.toolCalls());
    assertEquals(700, result.inputTokens());
    assertEquals(40, result.outputTokens());
    assertEquals(512, result.cachedTokens());
    assertEquals("scripted", result.mode());
    assertEquals("建好了。", result.finalText());
    // 任务的工作目录在跑完之后就没了：一个任务不该看得见另一个任务的残留。
    assertFalse(Files.exists(Path.of("proof.txt")));
  }

  @Test
  void aWrongFileContentFailsWithBothTheExpectedAndTheActual() {
    EvalTask task =
        new EvalTask(
            "wrong-content",
            "",
            "写点什么",
            Map.of(),
            List.of(
                ScriptedModel.Step.call("write", "{\"path\":\"proof.txt\",\"content\":\"not what\\n\"}"),
                ScriptedModel.Step.say("好了")),
            true,
            new EvalTask.Expect(Map.of("proof.txt", "expected\n"), List.of(), List.of(), null, null));

    EvalReport.TaskResult result = EvalRunner.run(List.of(task), null).tasks().get(0);

    assertFalse(result.ok());
    String failure = result.failures().get(0);
    assertTrue(failure.contains("expected"), failure);
    assertTrue(failure.contains("not what"), failure);
  }

  @Test
  void aMissingFileAndAnExtraFileAreBothFailures() {
    EvalTask missing =
        new EvalTask(
            "missing",
            "",
            "建一个文件",
            Map.of(),
            List.of(ScriptedModel.Step.say("我不会")),
            true,
            new EvalTask.Expect(Map.of("proof.txt", "x"), List.of(), List.of(), null, null));
    EvalReport.TaskResult missingResult = EvalRunner.run(List.of(missing), null).tasks().get(0);
    assertFalse(missingResult.ok());
    assertTrue(missingResult.failures().get(0).contains("没有被建出来"), missingResult.failures().toString());

    EvalTask extra =
        new EvalTask(
            "extra",
            "",
            "别动那个文件",
            Map.of("keep.txt", "keep\n"),
            List.of(ScriptedModel.Step.say("我什么都没做")),
            true,
            new EvalTask.Expect(Map.of(), List.of("keep.txt"), List.of(), null, null));
    EvalReport.TaskResult extraResult = EvalRunner.run(List.of(extra), null).tasks().get(0);
    assertFalse(extraResult.ok());
    assertTrue(extraResult.failures().get(0).contains("本来不该存在"), extraResult.failures().toString());
  }

  @Test
  void aToolSequenceThatDiffersFromTheScriptFails() {
    EvalTask task =
        new EvalTask(
            "sequence",
            "",
            "读一个文件",
            Map.of("notes.txt", "hi\n"),
            List.of(ScriptedModel.Step.call("read", "{\"path\":\"notes.txt\"}"), ScriptedModel.Step.say("读到了")),
            true,
            new EvalTask.Expect(Map.of(), List.of(), List.of(), List.of("glob", "read"), null));

    EvalReport.TaskResult result = EvalRunner.run(List.of(task), null).tasks().get(0);

    assertFalse(result.ok());
    assertTrue(result.failures().get(0).contains("[glob, read]"), result.failures().toString());
    assertTrue(result.failures().get(0).contains("[read]"), result.failures().toString());
  }

  @Test
  void tooManyStepsIsAFailureOfItsOwn() {
    EvalTask task =
        new EvalTask(
            "too-many-steps",
            "",
            "读两次",
            Map.of("notes.txt", "hi\n"),
            List.of(
                ScriptedModel.Step.call("read", "{\"path\":\"notes.txt\"}"),
                ScriptedModel.Step.call("read", "{\"path\":\"notes.txt\"}"),
                ScriptedModel.Step.say("读完了")),
            true,
            new EvalTask.Expect(Map.of(), List.of(), List.of(), null, 2));

    EvalReport.TaskResult result = EvalRunner.run(List.of(task), null).tasks().get(0);

    assertFalse(result.ok());
    assertTrue(result.failures().get(0).contains("超过了"), result.failures().toString());
  }

  @Test
  void aDeniedToolChangesNothingAndTheTurnStillEnds() {
    // `approve: false` 是这一个套件里唯一会拒绝东西的地方，而它要证明的是护栏本身：拒绝不是错误，它是一次
    // 什么都没发生的工具调用，而模型看得见它。
    EvalTask task =
        new EvalTask(
            "refused",
            "",
            "删掉 victim.txt",
            Map.of("victim.txt", "keep me\n"),
            List.of(
                ScriptedModel.Step.call("bash", "{\"command\":\"rm victim.txt\"}"),
                ScriptedModel.Step.say("没做")),
            false,
            new EvalTask.Expect(Map.of("victim.txt", "keep me\n"), List.of(), List.of("没做"), List.of("bash"), 3));

    EvalReport.TaskResult result = EvalRunner.run(List.of(task), null).tasks().get(0);

    assertTrue(result.ok(), result.failures().toString());
  }

  @Test
  void aRunThatThrowsIsReportedAsAFailureRatherThanKillingTheSuite() {
    Provider exploding =
        new Provider() {
          @Override
          public String name() {
            return "exploding";
          }

          @Override
          public Message.Assistant complete(Request request, Consumer<Event> listener) {
            throw new IllegalStateException("提供方不可达");
          }
        };
    EvalTask broken =
        new EvalTask("broken", "", "说点什么", Map.of(), List.of(), true, EvalTask.Expect.none());
    EvalTask fine =
        new EvalTask(
            "fine", "", "说点什么", Map.of(), List.of(ScriptedModel.Step.say("好")), true, EvalTask.Expect.none());

    EvalReport report = EvalRunner.run(List.of(broken, fine), exploding);

    assertEquals(1, report.failed());
    assertEquals(1, report.passed());
    assertTrue(report.tasks().get(0).failures().get(0).contains("提供方不可达"), report.render());
    assertTrue(report.tasks().get(0).failures().get(0).contains("异常结束"), report.render());
  }

  @Test
  void aTaskWithoutAScriptIsRefusedWhenNoModelWasGiven() {
    EvalTask needsModel =
        new EvalTask("needs-model", "", "随便说点什么", Map.of(), List.of(), true, EvalTask.Expect.none());

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> EvalRunner.run(List.of(needsModel), null));
    assertTrue(failure.getMessage().contains("needs-model"), failure.getMessage());
    assertTrue(failure.getMessage().contains("模型"), failure.getMessage());
  }

  @Test
  void aTaskFileThatEscapesTheWorkspaceIsRefused() {
    EvalTask escaping =
        new EvalTask(
            "escaping",
            "",
            "写出去",
            Map.of("../escaped.txt", "nope\n"),
            List.of(ScriptedModel.Step.say("好")),
            true,
            EvalTask.Expect.none());

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> EvalRunner.run(List.of(escaping), null));
    assertTrue(failure.getMessage().contains("跑出了它的工作目录"), failure.getMessage());
    assertFalse(Files.exists(tmp.resolve("escaped.txt")), "什么东西都不该被写出来");
  }

  @Test
  void theReportAddsUpWhatEveryTaskSpent() {
    EvalTask first =
        new EvalTask(
            "first",
            "",
            "a",
            Map.of(),
            List.of(
                ScriptedModel.Step.call("read", "{\"path\":\"x\"}"),
                new ScriptedModel.Step("好", List.of(), new int[] {100, 10, -1})),
            true,
            EvalTask.Expect.none());
    EvalTask second =
        new EvalTask(
            "second", "", "b", Map.of(), List.of(new ScriptedModel.Step("好", List.of(), new int[] {200, 20, -1})), true, EvalTask.Expect.none());

    EvalReport report = EvalRunner.run(List.of(first, second), null);

    assertEquals(300, report.totalInputTokens());
    assertEquals(30, report.totalOutputTokens());
    assertEquals(1, report.totalToolCalls());
    // 第一个任务两个回合（一次工具调用，然后一句回答），第二个一个回合。
    assertEquals(3, report.totalSteps());
    assertTrue(report.render().contains("合计：2/2 通过"), report.render());
    assertEquals(2, report.toJson().path("tasks").size());
    assertTrue(report.toJson().path("tasks").get(0).path("cachedTokens").isNull(), "没有缓存上报");
  }
}
