package com.ccj.agent.eval;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 两次评测的对比：同一套任务、两个模型（或两版提示词），一行一行放在一起看。
 *
 * <p>一次评测回答「它还是对的吗」；**两次评测回答「哪一版更好」**——而后者才是换模型、改提示词时真正要问的
 * 问题。在此之前那个问题的答案只能靠记忆和感觉，而这两样在第十次改动之后都不可靠。
 *
 * <p>比的是什么也一样重要：先比**通过与否**，通过率一样时再比**步数**与**token**。一个「还是对的，但多绕了
 * 三圈、多花了一倍 token」的改动，在只比通过率的表里是隐形的——而那正是评测里最贵的一类变化。任一侧缺失的
 * 任务会被单独列出来，而不是当作「两边都不通过」：一次没跑到的评测和一次失败的评测是两件事。
 *
 * <p>用法上它是两个命令，不是一次调用：第一次跑完用 {@code --eval-json} 把报告存下来，第二次带上
 * {@code --eval-compare} 指回那份文件。两次运行各自是一个进程、各自一份配置（`--model` 就在那里给），所以
 * 这里不需要任何「第二个模型」的新概念——只需要一个能被读回来的报告。
 */
public record EvalComparison(String labelA, EvalReport a, String labelB, EvalReport b) {

  public EvalComparison {
    labelA = labelA == null || labelA.isBlank() ? "A" : labelA;
    labelB = labelB == null || labelB.isBlank() ? "B" : labelB;
  }

  /** 一个任务在两边的样子，以及那句判断。 */
  public record Row(
      String id,
      boolean inA,
      boolean inB,
      boolean okA,
      boolean okB,
      int stepsA,
      int stepsB,
      long tokensA,
      long tokensB,
      String verdict) {}

  /** 逐任务对齐：按 id 配对，只在一侧存在的单独成行。 */
  public List<Row> rows() {
    Map<String, EvalReport.TaskResult> first = index(a);
    Map<String, EvalReport.TaskResult> second = index(b);
    List<String> ids = new ArrayList<>(first.keySet());
    for (String id : second.keySet()) {
      if (!first.containsKey(id)) {
        ids.add(id);
      }
    }
    List<Row> rows = new ArrayList<>(ids.size());
    for (String id : ids) {
      EvalReport.TaskResult left = first.get(id);
      EvalReport.TaskResult right = second.get(id);
      rows.add(
          new Row(
              id,
              left != null,
              right != null,
              left != null && left.ok(),
              right != null && right.ok(),
              left == null ? 0 : left.steps(),
              right == null ? 0 : right.steps(),
              left == null ? 0 : left.inputTokens() + left.outputTokens(),
              right == null ? 0 : right.inputTokens() + right.outputTokens(),
              verdict(left, right)));
    }
    return List.copyOf(rows);
  }

  private static Map<String, EvalReport.TaskResult> index(EvalReport report) {
    Map<String, EvalReport.TaskResult> out = new LinkedHashMap<>();
    for (EvalReport.TaskResult task : report.tasks()) {
      out.put(task.id(), task);
    }
    return out;
  }

  /**
   * 一句话的判断。
   *
   * <p>通过率优先，然后才是代价：一个少走两步但答错的版本，不是「更好」。
   */
  private String verdict(EvalReport.TaskResult left, EvalReport.TaskResult right) {
    if (left == null) {
      return "只在 " + labelB + " 里有（" + labelA + " 没跑它）";
    }
    if (right == null) {
      return "只在 " + labelA + " 里有（" + labelB + " 没跑它）";
    }
    if (left.ok() != right.ok()) {
      // 名字而不是 A/B：一张写着「旧/新」或两个模型名的表，比一张要求读者记得哪一列是谁的表有用。
      return left.ok()
          ? labelA + " 通过、" + labelB + " 没通过"
          : labelB + " 通过、" + labelA + " 没通过";
    }
    if (!left.ok()) {
      return "两边都没通过";
    }
    if (left.steps() != right.steps()) {
      int difference = Math.abs(left.steps() - right.steps());
      return (left.steps() < right.steps() ? labelA : labelB) + " 少 " + difference + " 步";
    }
    long leftTokens = left.inputTokens() + left.outputTokens();
    long rightTokens = right.inputTokens() + right.outputTokens();
    if (leftTokens != rightTokens) {
      long difference = Math.abs(leftTokens - rightTokens);
      return (leftTokens < rightTokens ? labelA : labelB) + " 少 " + difference + " token";
    }
    return "一样";
  }

  /** 给人看的表：一行一个任务，最后是合计。 */
  public String render() {
    List<Row> rows = rows();
    StringBuilder out = new StringBuilder();
    int verdicts = 0;
    for (Row row : rows) {
      if (!"一样".equals(row.verdict())) {
        verdicts++;
      }
    }
    out.append("对比：").append(labelA).append(" vs ").append(labelB)
        .append("（").append(rows.size()).append(" 个任务，").append(verdicts).append(" 个不一样）\n");
    out.append("─".repeat(72)).append('\n');
    out.append(String.format(Locale.ROOT, "%-34s %-14s %-14s %s%n", "任务", labelA, labelB, "判断"));
    for (Row row : rows) {
      out.append(
          String.format(
              Locale.ROOT,
              "%-34s %-14s %-14s %s%n",
              clip(row.id()),
              cell(row.inA(), row.okA(), row.stepsA(), row.tokensA()),
              cell(row.inB(), row.okB(), row.stepsB(), row.tokensB()),
              row.verdict()));
    }
    out.append(
        String.format(
            Locale.ROOT,
            "%n合计：%s %d/%d 通过、%d token；%s %d/%d 通过、%d token%n",
            labelA,
            a.passed(),
            a.tasks().size(),
            a.totalInputTokens() + a.totalOutputTokens(),
            labelB,
            b.passed(),
            b.tasks().size(),
            b.totalInputTokens() + b.totalOutputTokens()));
    return out.toString();
  }

  private static String cell(boolean present, boolean ok, int steps, long tokens) {
    if (!present) {
      return "—";
    }
    return (ok ? "ok" : "FAIL") + " " + steps + " 步 " + tokens + "t";
  }

  private static String clip(String id) {
    return id.length() <= 32 ? id : id.substring(0, 31) + "…";
  }

  /** 同一份对比的机器可读形状——它自己也可以被存下来、被下一个人读。 */
  public ObjectNode toJson() {
    ObjectNode node = Json.object();
    node.put("a", labelA);
    node.put("b", labelB);
    ArrayNode rows = node.putArray("rows");
    for (Row row : rows()) {
      rows.addObject()
          .put("id", row.id())
          .put("inA", row.inA())
          .put("inB", row.inB())
          .put("okA", row.okA())
          .put("okB", row.okB())
          .put("stepsA", row.stepsA())
          .put("stepsB", row.stepsB())
          .put("tokensA", row.tokensA())
          .put("tokensB", row.tokensB())
          .put("verdict", row.verdict());
    }
    node.put("passedA", a.passed());
    node.put("passedB", b.passed());
    node.put("totalA", a.tasks().size());
    node.put("totalB", b.tasks().size());
    node.set("reportA", a.toJson());
    node.set("reportB", b.toJson());
    return node;
  }

  /** 从一个被 {@code --eval-json} 写下来的文件读回一份报告。 */
  public static EvalReport read(JsonNode node) {
    List<EvalReport.TaskResult> tasks = new ArrayList<>();
    for (JsonNode task : node.path("tasks")) {
      List<String> failures = new ArrayList<>();
      task.path("failures").forEach(entry -> failures.add(entry.asText()));
      tasks.add(
          new EvalReport.TaskResult(
              task.path("id").asText(""),
              task.path("description").asText(""),
              task.path("ok").asBoolean(false),
              failures,
              task.path("steps").asInt(0),
              task.path("toolCalls").asInt(0),
              task.path("inputTokens").asLong(0),
              task.path("outputTokens").asLong(0),
              task.path("cachedTokens").isNull() ? null : task.path("cachedTokens").asInt(),
              task.path("elapsedMs").asLong(0),
              task.path("mode").asText(""),
              task.path("finalText").asText("")));
    }
    return new EvalReport(tasks);
  }
}
