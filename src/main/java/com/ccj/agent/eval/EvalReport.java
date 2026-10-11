package com.ccj.agent.eval;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;

/**
 * 一次评测跑完之后留下的东西：每个任务的结果，以及它们加起来是多少。
 *
 * <p>它是一份**报告**，不是一句「通过」。一次运行里最有用的部分往往是那些数字——步数、工具调用数、token、
 * 耗时——因为一个从「对」变成「对，但多花了三倍 token」的改动，用「通过」是看不出来的，而那正是评测里
 * 最贵的一类变化。
 */
public final class EvalReport {

  /**
   * 一个任务的结果。
   *
   * @param failures 没通过的那些断言，一条一行；通过时是空的
   * @param mode {@code scripted}（剧本回的）或 {@code model}（真模型回的）——两份报告混着看时，这一栏
   *     是它们能不能比的前提
   */
  public record TaskResult(
      String id,
      String description,
      boolean ok,
      List<String> failures,
      int steps,
      int toolCalls,
      long inputTokens,
      long outputTokens,
      Integer cachedTokens,
      long elapsedMillis,
      String mode,
      String finalText) {

    public TaskResult {
      failures = failures == null ? List.of() : List.copyOf(failures);
      finalText = finalText == null ? "" : finalText;
    }

    public ObjectNode toJson() {
      ObjectNode node = Json.object();
      node.put("id", id);
      node.put("description", description);
      node.put("ok", ok);
      node.put("mode", mode);
      node.put("steps", steps);
      node.put("toolCalls", toolCalls);
      node.put("inputTokens", inputTokens);
      node.put("outputTokens", outputTokens);
      if (cachedTokens == null) {
        node.putNull("cachedTokens");
      } else {
        node.put("cachedTokens", cachedTokens);
      }
      node.put("elapsedMs", elapsedMillis);
      ArrayNode failed = node.putArray("failures");
      failures.forEach(failed::add);
      node.put("finalText", finalText);
      return node;
    }
  }

  private final List<TaskResult> tasks;

  public EvalReport(List<TaskResult> tasks) {
    this.tasks = List.copyOf(tasks);
  }

  public List<TaskResult> tasks() {
    return tasks;
  }

  public boolean allPassed() {
    return tasks.stream().allMatch(TaskResult::ok);
  }

  public int passed() {
    return (int) tasks.stream().filter(TaskResult::ok).count();
  }

  public int failed() {
    return tasks.size() - passed();
  }

  public int totalSteps() {
    return tasks.stream().mapToInt(TaskResult::steps).sum();
  }

  public int totalToolCalls() {
    return tasks.stream().mapToInt(TaskResult::toolCalls).sum();
  }

  public long totalInputTokens() {
    return tasks.stream().mapToLong(TaskResult::inputTokens).sum();
  }

  public long totalOutputTokens() {
    return tasks.stream().mapToLong(TaskResult::outputTokens).sum();
  }

  public long totalElapsedMillis() {
    return tasks.stream().mapToLong(TaskResult::elapsedMillis).sum();
  }

  /** 一份给人看的报告：每个任务一行，失败的行下面列出它的每一条原因，最后一行是合计。 */
  public String render() {
    // 列的宽度跟着最长的那一行，而不是一个凭感觉定下来的数字：一个被截断或被挤歪的任务 id，恰好是最需要
    // 读它的那一行——失败的那一行。
    int width = 28;
    for (TaskResult task : tasks) {
      width = Math.max(width, task.id().length() + 2);
    }
    StringBuilder out = new StringBuilder();
    out.append(
        String.format(
            Locale.ROOT,
            "%-" + width + "s %-5s %-10s %5s %5s %14s %9s%n",
            "任务", "结果", "模式", "步数", "工具", "token 入/出", "耗时"));
    for (TaskResult task : tasks) {
      out.append(
          String.format(
              Locale.ROOT,
              "%-" + width + "s %-5s %-10s %5d %5d %14s %7d ms%n",
              task.id(),
              task.ok() ? "ok" : "FAIL",
              task.mode(),
              task.steps(),
              task.toolCalls(),
              task.inputTokens() + "/" + task.outputTokens(),
              task.elapsedMillis()));
      for (String failure : task.failures()) {
        out.append("  · ").append(failure).append('\n');
      }
    }
    out.append(
        String.format(
            Locale.ROOT,
            "%n合计：%d/%d 通过，%d 步，%d 次工具调用，%d token，%d ms%n",
            passed(),
            tasks.size(),
            totalSteps(),
            totalToolCalls(),
            totalInputTokens() + totalOutputTokens(),
            totalElapsedMillis()));
    return out.toString();
  }

  /** 同一份报告，给机器读。 */
  public ObjectNode toJson() {
    ObjectNode node = Json.object();
    node.put("passed", passed());
    node.put("total", tasks.size());
    node.put("steps", totalSteps());
    node.put("toolCalls", totalToolCalls());
    node.put("inputTokens", totalInputTokens());
    node.put("outputTokens", totalOutputTokens());
    node.put("elapsedMs", totalElapsedMillis());
    ArrayNode results = node.putArray("tasks");
    for (TaskResult task : tasks) {
      results.add(task.toJson());
    }
    return node;
  }
}
