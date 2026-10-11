package com.ccj.agent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A/B 对比：先比通过与否，通过率一样时再比步数与 token。
 *
 * <p>这里最要紧的一条是**顺序**：一个少走两步但答错的版本不是「更好」，而一个通过的版本不该因为多花了一百
 * token 而被判输。两次评测放在一起看时，这两件事最容易被读反。
 */
class EvalComparisonTest {

  private static EvalReport.TaskResult task(String id, boolean ok, int steps, int input, int output) {
    return new EvalReport.TaskResult(id, "", ok, List.of(), steps, 1, input, output, null, 10, "scripted", "");
  }

  private static EvalComparison compare(EvalReport left, EvalReport right) {
    return new EvalComparison("旧", left, "新", right);
  }

  @Test
  void passingBeatsFewerSteps() {
    EvalComparison.Row row =
        compare(new EvalReport(List.of(task("t", true, 9, 10, 10))), new EvalReport(List.of(task("t", false, 2, 1, 1))))
            .rows()
            .get(0);

    assertEquals("旧 通过、新 没通过", row.verdict(), "通过率优先于步数");
    assertFalse(row.okB());
  }

  @Test
  void whenBothPassTheCheaperOneWinsAndSaysByHowMuch() {
    EvalComparison.Row steps =
        compare(
                new EvalReport(List.of(task("t", true, 6, 100, 10))),
                new EvalReport(List.of(task("t", true, 4, 100, 10))))
            .rows()
            .get(0);
    evalStepsVerdict(steps);

    EvalComparison.Row tokens =
        compare(
                new EvalReport(List.of(task("t", true, 4, 100, 10))),
                new EvalReport(List.of(task("t", true, 4, 400, 10))))
            .rows()
            .get(0);
    evalTokenVerdict(tokens);

    EvalComparison.Row identical =
        compare(new EvalReport(List.of(task("t", true, 4, 100, 10))), new EvalReport(List.of(task("t", true, 4, 100, 10))))
            .rows()
            .get(0);
    assertEquals("一样", identical.verdict());
  }

  private static void evalStepsVerdict(EvalComparison.Row row) {
    assertEquals("新 少 2 步", row.verdict(), "步数不同就先说步数");
  }

  private static void evalTokenVerdict(EvalComparison.Row row) {
    assertEquals("旧 少 300 token", row.verdict());
  }

  @Test
  void aTaskOnlyOneSideRanIsNotCountedAsAFailureOnBoth() {
    // 一次没跑到的评测和一次失败的评测是两件事：把它算成「两边都不通过」，会让人以为两版都错了。
    EvalComparison comparison =
        compare(
            new EvalReport(List.of(task("only-a", true, 1, 1, 1))),
            new EvalReport(List.of(task("only-b", true, 1, 1, 1))));

    List<EvalComparison.Row> rows = comparison.rows();
    assertEquals(2, rows.size(), "只在一侧存在的任务也要单独成行");
    assertEquals("只在 新 里有（旧 没跑它）", rows.get(1).verdict(), rows.toString());
    assertFalse(rows.get(1).inA());
    assertTrue(rows.get(1).inB());
    assertTrue(comparison.render().contains("只在 新 里有"), comparison.render());
  }

  @Test
  void theTableShowsBothSidesAndTheTotals() {
    EvalReport before = new EvalReport(List.of(task("a", true, 2, 100, 10), task("b", false, 3, 200, 20)));
    EvalReport after = new EvalReport(List.of(task("a", true, 2, 100, 10), task("b", true, 5, 200, 20)));

    String text = compare(before, after).render();

    assertTrue(text.contains("对比：旧 vs 新"), text);
    assertTrue(text.contains("2 个任务，1 个不一样"), text);
    assertTrue(text.contains("新 通过、旧 没通过"), text);
    assertTrue(text.contains("ok 2 步 110t"), text);
    assertTrue(text.contains("合计：旧 1/2 通过、330 token"), text);
  }

  @Test
  void aStoredReportReadsBackIntoTheSameTasks() {
    // --eval-json 存下来的报告必须能被 --eval-compare 原样读回来：它是这两个命令之间唯一的接口。
    EvalReport original =
        new EvalReport(
            List.of(
                task("x", true, 3, 700, 40),
                new EvalReport.TaskResult("y", "描述", false, List.of("文件不对"), 2, 1, 10, 5, 512, 33, "model", "答案")));

    EvalReport read = EvalComparison.read(Json.parse(Json.write(original.toJson())));

    assertEquals(original.passed(), read.passed());
    assertEquals(original.totalSteps(), read.totalSteps());
    assertEquals(original.totalInputTokens(), read.totalInputTokens());
    assertEquals("y", read.tasks().get(1).id());
    assertEquals("描述", read.tasks().get(1).description());
    assertEquals(List.of("文件不对"), read.tasks().get(1).failures());
    assertEquals(512, read.tasks().get(1).cachedTokens());
    assertEquals("model", read.tasks().get(1).mode());
    assertEquals("答案", read.tasks().get(1).finalText());
  }

  @Test
  void theJsonCarriesEveryRowSoAReportCanItselfBeComparedLater() {
    var json =
        compare(new EvalReport(List.of(task("a", true, 1, 1, 1))), new EvalReport(List.of(task("a", true, 2, 5, 5))))
            .toJson();

    assertEquals("旧", json.path("a").asText());
    assertEquals("新", json.path("b").asText());
    assertEquals(1, json.path("rows").size());
    assertEquals("旧 少 1 步", json.path("rows").get(0).path("verdict").asText());
    assertEquals(1, json.path("passedA").asInt());
    assertEquals(1, json.path("reportA").path("tasks").size());
  }
}
