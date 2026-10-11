package com.ccj.agent.obs;

import java.util.List;

/**
 * 这个工具向外报告的每一个数字，连同它的名字、类型与说明。
 *
 * <p>写成枚举而不是散落在各处的字符串，是因为一个指标有三个东西必须一致——暴露出来的名字、`# TYPE`、
 * 以及那段给人读的说明——而它们分头维护时，漂移是必然的：一个拼错的名字不会让任何东西失败，它只会让这个
 * 数字从此在仪表盘上消失，而没人会收到通知。在这里，三样东西是同一行。
 *
 * <p>名字与标签遵循 Prometheus 的约定：计数器以 {@code _total} 结尾，直方图以它计量的单位结尾
 * （{@code _seconds}），而标签名是复数。约定的价值不在好看，在于一个按惯例写的查询能不能直接跑。
 */
public enum Metric {

  /** 一次运行结束了，按它的结局分。 */
  RUNS("ccj_runs_total", Kind.COUNTER, "已结束的运行，按结局分（ok、aborted、error）", "outcome"),

  /** 一次运行从开始到收尾的耗时，含收尾那一段（落盘、清队列、自动压缩）。 */
  RUN_SECONDS("ccj_run_seconds", Kind.HISTOGRAM, "一次运行从开始到收尾的耗时"),

  /** 模型回合数：一次运行里的每一步。 */
  MODEL_TURNS("ccj_model_turns_total", Kind.COUNTER, "模型回合数（一次运行里对模型发出的每一次请求）"),

  /** 一个模型回合的时延，含整个流式回答。 */
  MODEL_TURN_SECONDS("ccj_model_turn_seconds", Kind.HISTOGRAM, "一个模型回合的时延（含流式回答）"),

  /** 工具调用数。 */
  TOOL_CALLS("ccj_tool_calls_total", Kind.COUNTER, "工具调用数", "tool"),

  /** 返回错误的工具调用数：参数不对、文件不在、被拒绝。 */
  TOOL_ERRORS("ccj_tool_errors_total", Kind.COUNTER, "返回错误的工具调用数", "tool"),

  /** 一次工具调用的耗时。 */
  TOOL_SECONDS("ccj_tool_seconds", Kind.HISTOGRAM, "一次工具调用的耗时", "tool"),

  /** 提供方报告的 token，按方向分。 */
  TOKENS("ccj_tokens_total", Kind.COUNTER, "提供方报告的 token 数，按方向分（input、output、cached）", "direction"),

  /** 审批请求，按答案分：五种答复加上「被规则拒绝」。 */
  APPROVALS("ccj_approvals_total", Kind.COUNTER, "审批请求，按答案分", "answer"),

  /** 一次审批请求等了多久。 */
  APPROVAL_SECONDS("ccj_approval_seconds", Kind.HISTOGRAM, "一次审批请求等了多久"),

  /** 每个回合固定发出去的前缀（系统提示与工具 schema）的估算 token。 */
  PREFIX_TOKENS("ccj_prompt_prefix_tokens", Kind.GAUGE, "每个回合固定发出去的前缀的估算 token"),

  /** 列一次会话目录的耗时。 */
  SESSION_LIST_SECONDS("ccj_session_list_seconds", Kind.HISTOGRAM, "列一次会话目录的耗时"),

  /** 当前打开的 SSE 连接数。 */
  SSE_SUBSCRIBERS("ccj_sse_subscribers", Kind.GAUGE, "当前打开的 SSE 连接数"),

  /** 已经发布出去的事件数。 */
  SSE_EVENTS("ccj_sse_events_total", Kind.COUNTER, "已经发布的 SSE 事件数"),

  /** 因为跟不上而被丢掉的 SSE 客户端数。 */
  SSE_DROPPED("ccj_sse_dropped_total", Kind.COUNTER, "因为跟不上而被丢掉的 SSE 客户端数"),

  /** 正在排队等待的用户消息数。 */
  QUEUED_MESSAGES("ccj_queued_messages", Kind.GAUGE, "正在排队等待的用户消息数"),

  /** 正在跑回合的对话数。 */
  RUNNING_CONVERSATIONS("ccj_running_conversations", Kind.GAUGE, "正在跑回合的对话数");

  /** 这个数字是累加的、可上可下的，还是一组分档的观测。 */
  public enum Kind {
    COUNTER("counter"),
    GAUGE("gauge"),
    HISTOGRAM("histogram");

    private final String prometheus;

    Kind(String prometheus) {
      this.prometheus = prometheus;
    }

    /** 线上文本里 `# TYPE` 那一行写的名字。 */
    public String prometheus() {
      return prometheus;
    }
  }

  private final String name;
  private final Kind kind;
  private final String help;
  private final List<String> labelNames;

  Metric(String name, Kind kind, String help, String... labelNames) {
    this.name = name;
    this.kind = kind;
    this.help = help;
    this.labelNames = List.of(labelNames);
  }

  public String metricName() {
    return name;
  }

  public Kind kind() {
    return kind;
  }

  public String help() {
    return help;
  }

  /** 这个指标的标签名，按它们应当出现在线上的顺序。 */
  public List<String> labelNames() {
    return labelNames;
  }
}
