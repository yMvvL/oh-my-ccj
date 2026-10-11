package com.ccj.agent.obs;

import com.ccj.agent.core.ApprovalAnswer;
import com.ccj.agent.core.ApprovalRequest;
import com.ccj.agent.core.Approver;
import java.util.Locale;

/**
 * 记一次审批：等了多久，答案是什么。
 *
 * <p>它是审批人的一层装饰，而不是 {@link Recorder} 的一部分，因为审批不经过代理循环的回调——它发生在一次
 * 工具调用的<em>里面</em>，由工具自己发起。这是观测那一层唯一一个必须自己包住的东西。
 *
 * <p>等待的时长是这里最值钱的一个数字，而它恰恰是别处量不到的：一次审批可能要等到一个人从别的窗口回来，
 * 而这段时间挂在一次工具调用的耗时里，与工具真正干活的时间混在一起。
 */
public final class MeteredApprover implements Approver {

  private final Metrics metrics;
  private final Approver delegate;

  /** {@code metrics} 为 null 表示只放行、不记账；{@code delegate} 为空表示一律拒绝。 */
  public MeteredApprover(Metrics metrics, Approver delegate) {
    this.metrics = metrics;
    this.delegate = delegate == null ? Approver.NEVER : delegate;
  }

  @Override
  public ApprovalAnswer approve(ApprovalRequest request) {
    long started = System.nanoTime();
    ApprovalAnswer answer;
    try {
      answer = delegate.approve(request);
    } finally {
      // 无论答案是回来了还是抛了，等待的时间都真的过去了。
      if (metrics != null) {
        metrics.observe(Metric.APPROVAL_SECONDS, (System.nanoTime() - started) / 1_000_000_000.0);
      }
    }
    if (metrics != null) {
      metrics.inc(Metric.APPROVALS, "answer", answer.name().toLowerCase(Locale.ROOT));
    }
    return answer;
  }
}
