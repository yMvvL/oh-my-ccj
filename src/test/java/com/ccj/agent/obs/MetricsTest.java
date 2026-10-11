package com.ccj.agent.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 指标表本身的形状：Prometheus 的文本格式、标签的校验，以及直方图那几个派生出来的行。
 *
 * <p>它们钉住的都是「安静地错」的那一类：一个拼错的标签名、一个没被转义的引号、一个假装的零，都不会让
 * 任何东西失败——只会让一个数字在仪表盘上消失，或者让一条坏掉的样本被解析成一个看起来合理的值。那种错
 * 要在这里被抓住，因为一旦它进了线上文本，下游没有任何东西能把它和真的分开。
 */
class MetricsTest {

  @Test
  void countersGaugesAndHistogramsRenderInTheShapeAFetcherExpects() {
    Metrics metrics = new Metrics();
    metrics.inc(Metric.RUNS, "outcome", "ok");
    metrics.inc(Metric.RUNS, "outcome", "ok");
    metrics.inc(Metric.RUNS, "outcome", "aborted");
    metrics.set(Metric.SSE_SUBSCRIBERS, 2);
    metrics.observe(Metric.TOOL_SECONDS, 0.03, "tool", "read");

    String text = metrics.render();

    assertTrue(text.contains("# HELP ccj_runs_total 已结束的运行，按结局分（ok、aborted、error）\n"), text);
    assertTrue(text.contains("# TYPE ccj_runs_total counter\n"), text);
    assertTrue(text.contains("ccj_runs_total{outcome=\"aborted\"} 1\n"), text);
    assertTrue(text.contains("ccj_runs_total{outcome=\"ok\"} 2\n"), text);
    assertTrue(text.contains("ccj_sse_subscribers 2\n"), text);

    // 直方图的三样东西：分档（累计的）、和、以及观测次数。`le` 排在序列自己的标签之后，这是抓取端约定
    // 的写法，而顺序错了的样本读起来仍然像一条样本。
    assertTrue(text.contains("# TYPE ccj_tool_seconds histogram\n"), text);
    assertTrue(text.contains("ccj_tool_seconds_bucket{tool=\"read\",le=\"0.025\"} 0\n"), text);
    assertTrue(text.contains("ccj_tool_seconds_bucket{tool=\"read\",le=\"0.05\"} 1\n"), text);
    assertTrue(text.contains("ccj_tool_seconds_bucket{tool=\"read\",le=\"+Inf\"} 1\n"), text);
    assertTrue(text.contains("ccj_tool_seconds_sum{tool=\"read\"} 0.03\n"), text);
    assertTrue(text.contains("ccj_tool_seconds_count{tool=\"read\"} 1\n"), text);
  }

  @Test
  void aMetricWithNoSamplesStillAnswersZeroWhenItHasNoLabels() {
    // 「现在一个 SSE 连接都没有」是一个事实，而不是「没有信息」：抓取端要能把两者分开，而这个指标没有
    // 标签可用来表达这件事，所以它输出一个 0。
    String text = new Metrics().render();
    assertTrue(text.contains("# TYPE ccj_sse_subscribers gauge\n"), text);
    assertTrue(text.contains("ccj_sse_subscribers 0\n"), text);
  }

  @Test
  void aLabelledMetricWithNoSamplesIsLeftOutRatherThanGivenAnInventedLabel() {
    // 带标签的指标反过来：凭空造一个标签值出来，就等于报告一件没发生过的事。
    String text = new Metrics().render();
    assertFalse(text.contains("ccj_runs_total"), text);
    assertFalse(text.contains("ccj_tool_calls_total"), text);
  }

  @Test
  void aHistogramThatNeverObservedAnythingIsLeftOutEntirely() {
    // `name 0` 会让抓取端读到一个没有 `_count` 的「直方图」，那不是零次观测，那是一行没有意义的东西。
    String text = new Metrics().render();
    assertFalse(text.contains("ccj_run_seconds"), text);
    assertFalse(text.contains("ccj_tool_seconds"), text);
    assertFalse(text.contains("ccj_session_list_seconds"), text);
  }

  @Test
  void aLabelNameTypoFailsHereInsteadOfDisappearingFromTheOutput() {
    Metrics metrics = new Metrics();
    IllegalArgumentException wrongName =
        assertThrows(
            IllegalArgumentException.class, () -> metrics.inc(Metric.RUNS, "result", "ok"));
    assertTrue(wrongName.getMessage().contains("outcome"), wrongName.getMessage());

    assertThrows(IllegalArgumentException.class, () -> metrics.inc(Metric.RUNS));
    assertThrows(
        IllegalArgumentException.class, () -> metrics.inc(Metric.RUNS, "outcome", "ok", "extra", "x"));
    // 一个仪表不能被当成计数器那样加，一个计数器也不能被直接设值：种类是声明的一部分。
    assertThrows(IllegalArgumentException.class, () -> metrics.observe(Metric.RUNS, 1));
    assertThrows(IllegalArgumentException.class, () -> metrics.set(Metric.MODEL_TURNS, 1));
  }

  @Test
  void aNegativeObservationIsRefusedBecauseItIsIndistinguishableFromAMeasurement() {
    // 循环用一个负数表示「这次调用从未运行」。放进直方图之后，它与一次 0 毫秒的真实观测再也分不开，
    // 于是那个「瞬时完成」的桶里会多出一次从未发生的事。
    Metrics metrics = new Metrics();
    assertThrows(
        IllegalArgumentException.class, () -> metrics.observe(Metric.TOOL_SECONDS, -1, "tool", "bash"));
    assertThrows(
        IllegalArgumentException.class,
        () -> metrics.observe(Metric.TOOL_SECONDS, Double.NaN, "tool", "bash"));
    assertEquals(0, metrics.count(Metric.TOOL_SECONDS, "tool", "bash"));
  }

  @Test
  void aLabelValueCannotBreakTheLineItIsWrittenOn() {
    // 工具名来自模型，而模型可以编出任何名字——包括一个带引号和换行的。让它原样进到线上文本里，得到的是
    // 一条被截断的样本，以及一条看起来像另一个指标的、凭空多出来的行。
    Metrics metrics = new Metrics();
    metrics.inc(Metric.TOOL_CALLS, "tool", "we\"ird\nname");

    String text = metrics.render();
    assertTrue(text.contains("ccj_tool_calls_total{tool=\"we\\\"ird\\nname\"} 1\n"), text);
    assertEquals(
        1,
        text.lines().filter(line -> line.startsWith("ccj_tool_calls_total")).count(),
        "一次观测只写一行：\n" + text);
  }

  @Test
  void theSeriesAreOrderedSoTwoFetchesCanBeCompared() {
    Metrics metrics = new Metrics();
    metrics.inc(Metric.TOOL_CALLS, "tool", "write");
    metrics.inc(Metric.TOOL_CALLS, "tool", "bash");
    metrics.inc(Metric.TOOL_CALLS, "tool", "read");

    String first = metrics.render();
    metrics.inc(Metric.TOOL_CALLS, "tool", "edit");
    String second = metrics.render();

    assertEquals(
        first.indexOf("tool=\"bash\""),
        second.indexOf("tool=\"bash\""),
        "样本的顺序必须稳定，否则两次抓取没法放在一起看");
    assertTrue(first.indexOf("tool=\"bash\"") < first.indexOf("tool=\"read\""), first);
  }
}
