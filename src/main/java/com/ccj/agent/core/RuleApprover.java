package com.ccj.agent.core;

import java.util.function.Consumer;

/**
 * 决定审批的链条：先看用户的规则，再看人。
 *
 * <p>顺序很重要，而且这就是整个设计。拒绝的规则压过一切，所以规则文件想禁止什么就能禁止什么；允许的规则
 * 不用弹出提示就能作答；只有两者都不说话时，问题才会递到盯着界面的人手里——浏览器或终端。人随后给出的
 * 答复会记在他们指定的地方：本会话、本项目的规则文件——那里记下的既可以是一句「可以」，也可以是一句
 * 「不行」——或就这一次。
 *
 * <p>通知挂钩的存在，是因为没有痕迹的自动答复不可信：转录里写着「规则批准 — bash mvn -q -o test」，
 * 这样「它为什么没问我」就有一个写下来的答案，而不是靠「没弹提示」去推断。
 */
public final class RuleApprover implements Approver {

  private final ApprovalRules rules;
  private final Approver delegate;
  private final Consumer<String> announced;

  public RuleApprover(ApprovalRules rules, Approver delegate, Consumer<String> announced) {
    this.rules = rules;
    this.delegate = delegate;
    this.announced = announced == null ? text -> {} : announced;
  }

  /** 背后没有人的规则：无头运行得到的就是它，测试想要的也是它。 */
  public static RuleApprover of(ApprovalRules rules, Approver delegate) {
    return new RuleApprover(rules, delegate, null);
  }

  @Override
  public ApprovalAnswer approve(ApprovalRequest request) {
    java.util.Optional<Boolean> verdict;
    try {
      verdict = rules.verdict(request);
    } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
      // 读不了的规则文件不等于「没有规则」：用户写了规则，并指望它们生效。拒绝自动作答，可以一边说明哪里
      // 出了问题，一边让防线继续立着。
      announced.accept("审批文件无法读取（" + e.getMessage() + "），因此改为询问");
      return delegate.approve(request);
    }
    if (verdict.isPresent()) {
      if (verdict.get()) {
        announced.accept("规则批准 — " + request.summary());
        return ApprovalAnswer.ALLOW_ONCE;
      }
      // 文件的位置在这里报出来，而不是留给「被规则拒绝」那句话：答复是枚举里的一个字面量，它拿不到本次
      // 运行用的是哪个文件，而这个链条拿着。
      announced.accept("规则拒绝 — " + request.summary() + "，规则见 " + rules.file());
      return ApprovalAnswer.DENY_BY_RULE;
    }
    ApprovalAnswer answer = delegate.approve(request);
    if (answer == ApprovalAnswer.ALLOW_SESSION) {
      rules.rememberForSession(request);
      announced.accept("本会话内已批准 — " + request.summary());
    } else if (answer == ApprovalAnswer.ALLOW_ALWAYS) {
      rules.remember(request);
      announced.accept("今后一直批准，规则写入 " + rules.file() + " — " + request.summary());
    } else if (answer == ApprovalAnswer.DENY_ALWAYS) {
      rules.rememberDeny(request);
      announced.accept("今后一直拒绝，规则写入 " + rules.file() + " — " + request.summary());
    }
    return answer;
  }
}
