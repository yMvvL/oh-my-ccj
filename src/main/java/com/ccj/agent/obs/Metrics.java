package com.ccj.agent.obs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * 一个进程里的计数器、仪表与直方图，按 {@link Metric} 登记，以 Prometheus 的文本格式暴露。
 *
 * <p>手写，因为一个抓取端点要的东西少得可怜：一个名字、一个类型、一个数字。为此引入一个指标库，就等于在
 * 「一个运行时依赖」这条线上让步——而这条线是这个项目可读性的来源。这里没有推送到网关、没有打点聚合、
 * 没有客户端侧的窗口：它们都是那一类东西的下一层，而这一层要回答的问题只有「现在是多少」。
 *
 * <p>累加用的是 {@link DoubleAdder} 与 {@link LongAdder}，因为这些数字是在热路径上被记下来的——每一个
 * 工具调用的两头、每一次 token 上报——而那些路径里有虚拟线程，也有并发跑着的只读工具。用一把锁把它们串起来，
 * 就是把观测的代价加到被观测的东西身上。
 *
 * <p>标签在登记时校验，而不是在渲染时：名字写错一个字母不会让任何东西失败，它只会让这个数字安静地消失，
 * 所以这里当场抛出，并说出这个指标本来有哪些标签。
 */
public final class Metrics {

  /**
   * 直方图的分档，以秒计。
   *
   * <p>这一套是给「一个模型回合一秒到几十秒、一次工具调用几毫秒到一分钟」这个量级挑的，也就是这台机器上
   * 真正会发生的事情。分成更细的档不会让答案更准，只会让暴露出来的行数变多。
   */
  static final double[] BUCKETS = {
    0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 300
  };

  /** 一个指标下的一个标签组合，以及它积累的那些数。 */
  private static final class Series {
    private final DoubleAdder value = new DoubleAdder();
    private final DoubleAdder sum = new DoubleAdder();
    private final LongAdder count = new LongAdder();
    /** 累计分档：一次观测同时进所有上界不小于它的桶，渲染时不必再累加。 */
    private final LongAdder[] buckets = new LongAdder[BUCKETS.length + 1];

    Series() {
      for (int i = 0; i < buckets.length; i++) {
        buckets[i] = new LongAdder();
      }
    }
  }

  private final Map<String, Series> series = new ConcurrentHashMap<>();

  /** 计数器加一。 */
  public void inc(Metric metric, String... labels) {
    add(metric, 1, labels);
  }

  /** 计数器或仪表按量加一个值。 */
  public void add(Metric metric, double value, String... labels) {
    require(metric, labels);
    requireKind(metric, Metric.Kind.COUNTER, Metric.Kind.GAUGE);
    series(metric, labels).value.add(value);
  }

  /** 仪表设成一个值。 */
  public void set(Metric metric, double value, String... labels) {
    require(metric, labels);
    requireKind(metric, Metric.Kind.GAUGE);
    Series target = series(metric, labels);
    target.value.reset();
    target.value.add(value);
  }

  /**
   * 往直方图里记一次观测，单位是秒。
   *
   * <p>负数不接受：一个负的耗时来自一个算错的时钟或者一个假装的零，而它进到直方图里之后，与一次真实的
   * 观测再也分不开了——{@code -1} 这类「这次没有数字」的哨兵值必须在调用点上就被拦住。
   */
  public void observe(Metric metric, double seconds, String... labels) {
    require(metric, labels);
    requireKind(metric, Metric.Kind.HISTOGRAM);
    if (seconds < 0 || Double.isNaN(seconds)) {
      throw new IllegalArgumentException(
          metric.metricName() + " 不接受负的或非数字的观测：" + seconds);
    }
    Series target = series(metric, labels);
    target.sum.add(seconds);
    target.count.increment();
    for (int i = 0; i < BUCKETS.length; i++) {
      if (seconds <= BUCKETS[i]) {
        target.buckets[i].increment();
      }
    }
    target.buckets[BUCKETS.length].increment(); // +Inf
  }

  /** 一个计数器或仪表的当前值，或者一个直方图的所有观测之和；没有样本时是 0。 */
  public double value(Metric metric, String... labels) {
    require(metric, labels);
    Series found = series.get(key(metric, labels));
    if (found == null) {
      return 0;
    }
    return metric.kind() == Metric.Kind.HISTOGRAM ? found.sum.sum() : found.value.sum();
  }

  /** 一个直方图被观测了多少次；不是直方图时是 0。 */
  public long count(Metric metric, String... labels) {
    require(metric, labels);
    Series found = series.get(key(metric, labels));
    return found == null ? 0 : found.count.sum();
  }

  /**
   * 一个指标所有序列之和，不看标签。
   *
   * <p>它回答的是「一共多少次工具调用」「一共花了多少 token」这一类问题——那些问题在被按工具、按方向拆开
   * 之前就有了，而调用方为了问它们而把每个标签值都列一遍，等于把「有哪些标签值」这件事复制到了调用点上，
   * 而那是唯一会漂移的地方。
   *
   * <p>直方图在这里求和的是观测值之和（秒），与 {@link #value} 对单个序列的语义一致。
   */
  public double total(Metric metric) {
    double sum = 0;
    String prefix = metric.metricName() + '\u0000';
    for (Map.Entry<String, Series> entry : series.entrySet()) {
      if (!entry.getKey().startsWith(prefix)) {
        continue;
      }
      sum +=
          metric.kind() == Metric.Kind.HISTOGRAM
              ? entry.getValue().sum.sum()
              : entry.getValue().value.sum();
    }
    return sum;
  }

  /**
   * 全部样本，Prometheus 文本格式。
   *
   * <p>顺序是稳定的（按指标、再按标签串，都是排过序的），因为一个每次抓取都换顺序的端点无法被 diff——
   * 而这张表最容易被用到的场合，就是有人把两次抓取放在一起看。
   *
   * <p>没有标签的指标即使一个样本都没有也会输出一个 0：一个仪表为 0 和一个仪表不存在是两件事，而
   * 「现在一个 SSE 连接都没有」正是前者的例子。带标签的指标在没有样本时只输出 `# HELP`/`# TYPE`，
   * 因为凭空造一个标签值出来，就等于报告一件没发生过的事。
   */
  public String render() {
    StringBuilder out = new StringBuilder();
    for (Metric metric : Metric.values()) {
      List<Map.Entry<String, Series>> mine = new ArrayList<>();
      String prefix = metric.metricName() + "\u0000";
      for (Map.Entry<String, Series> entry : series.entrySet()) {
        if (entry.getKey().startsWith(prefix)) {
          mine.add(entry);
        }
      }
      if (mine.isEmpty()
          && (metric.kind() == Metric.Kind.HISTOGRAM || !metric.labelNames().isEmpty())) {
        // 一个没有观测的直方图什么都不输出：`name 0` 会让抓取端读到一个没有 `_count` 的「直方图」——
        // 那不是「零次观测」，那是一行没有意义的东西。计数器和仪表反过来，它们的 0 是一个事实。
        continue;
      }
      mine.sort(Comparator.comparing(Map.Entry::getKey));
      out.append("# HELP ").append(metric.metricName()).append(' ').append(metric.help()).append('\n');
      out.append("# TYPE ")
          .append(metric.metricName())
          .append(' ')
          .append(metric.kind().prometheus())
          .append('\n');
      if (mine.isEmpty()) {
        out.append(metric.metricName()).append(" 0\n");
        continue;
      }
      for (Map.Entry<String, Series> entry : mine) {
        String labels = entry.getKey().substring(prefix.length());
        render(out, metric, labels, entry.getValue());
      }
    }
    return out.toString();
  }

  private static void render(StringBuilder out, Metric metric, String labels, Series series) {
    if (metric.kind() == Metric.Kind.HISTOGRAM) {
      for (int i = 0; i < BUCKETS.length; i++) {
        out.append(metric.metricName())
            .append("_bucket")
            .append(withLabel(labels, "le", format(BUCKETS[i])))
            .append(' ')
            .append(series.buckets[i].sum())
            .append('\n');
      }
      out.append(metric.metricName())
          .append("_bucket")
          .append(withLabel(labels, "le", "+Inf"))
          .append(' ')
          .append(series.buckets[BUCKETS.length].sum())
          .append('\n');
      out.append(metric.metricName())
          .append("_sum")
          .append(withLabel(labels, null, null))
          .append(' ')
          .append(format(series.sum.sum()))
          .append('\n');
      out.append(metric.metricName())
          .append("_count")
          .append(withLabel(labels, null, null))
          .append(' ')
          .append(series.count.sum())
          .append('\n');
      return;
    }
    out.append(metric.metricName())
        .append(withLabel(labels, null, null))
        .append(' ')
        .append(format(series.value.sum()))
        .append('\n');
  }

  /** 把标签串与一对额外的标签（直方图的 {@code le}）拼起来，空的那一半不出现。 */
  private static String withLabel(String labels, String extraName, String extraValue) {
    StringBuilder out = new StringBuilder();
    if (!labels.isEmpty()) {
      out.append('{').append(labels);
      if (extraName != null) {
        out.append(',').append(extraName).append("=\"").append(extraValue).append('"');
      }
      out.append('}');
    } else if (extraName != null) {
      out.append('{').append(extraName).append("=\"").append(extraValue).append('"').append('}');
    }
    return out.toString();
  }

  /**
   * 一个数字的线上写法：整数没有小数点，其余的交给它自己的最短表示。
   *
   * <p>不固定小数位，因为被记下来的东西里既有 {@code 3} 这种计数，也有 {@code 0.0042} 这种秒数，而一个
   * 对前者好看的格式（{@code 3.000}）对后者就是丢信息。
   */
  private static String format(double value) {
    if (value == Math.rint(value) && Math.abs(value) < 1e15) {
      return Long.toString((long) value);
    }
    if (value < 0.0001) {
      // 科学计数法，但保留足够的小数位，好让一次亚毫秒的观测不被压成 0。
      return String.format(Locale.ROOT, "%.6e", value);
    }
    return String.valueOf(value);
  }

  private Series series(Metric metric, String... labels) {
    return series.computeIfAbsent(key(metric, labels), key -> new Series());
  }

  /** 一个样本的键：指标名，加上按声明顺序排好的标签名与值。 */
  private String key(Metric metric, String... labels) {
    StringBuilder out = new StringBuilder(metric.metricName()).append('\u0000');
    List<String> names = metric.labelNames();
    for (int i = 0; i < names.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append(names.get(i))
          .append("=\"")
          .append(escape(labels[i * 2 + 1]))
          .append('"');
    }
    return out.toString();
  }

  /** 标签必须是「名字, 值」成对出现，且名字与这个指标声明的完全一致。 */
  private static void require(Metric metric, String... labels) {
    if (labels.length % 2 != 0) {
      throw new IllegalArgumentException(
          metric.metricName() + " 的标签必须是 名字, 值 成对出现，实际给了 " + labels.length + " 个");
    }
    List<String> names = metric.labelNames();
    if (labels.length / 2 != names.size()) {
      throw new IllegalArgumentException(
          metric.metricName() + " 要 " + names.size() + " 个标签" + (names.isEmpty() ? "" : "：" + names)
              + "，实际给了 " + (labels.length / 2) + " 个");
    }
    for (int i = 0; i < names.size(); i++) {
      if (!names.get(i).equals(labels[i * 2])) {
        throw new IllegalArgumentException(
            metric.metricName() + " 的第 " + (i + 1) + " 个标签是 '" + names.get(i) + "'，不是 '"
                + labels[i * 2] + "'");
      }
    }
  }

  private static void requireKind(Metric metric, Metric.Kind... allowed) {
    for (Metric.Kind kind : allowed) {
      if (metric.kind() == kind) {
        return;
      }
    }
    throw new IllegalArgumentException(
        metric.metricName() + " 是 " + metric.kind().prometheus() + "，不能按别的方式记");
  }

  /** 反斜杠、双引号与换行是 Prometheus 文本里仅有的三个需要转义的字符。 */
  private static String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n");
  }
}
