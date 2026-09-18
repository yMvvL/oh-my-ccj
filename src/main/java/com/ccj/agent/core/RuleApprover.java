package com.ccj.agent.core;

import java.util.function.Consumer;

/**
 * The chain that decides an approval: the user's rules first, the person second.
 *
 * <p>Order matters and is the whole design. A rule that refuses wins over everything, so the file can
 * forbid what it likes; a rule that allows answers without a prompt; and only when neither speaks
 * does the question reach whoever is watching — the browser or the terminal. What the person then
 * answers is remembered where they said it should be: for this session, for this project, or just
 * this once.
 *
 * <p>The announcement hook exists because an automatic answer with no trace is not trustworthy: the
 * transcript says "allowed by rule: bash mvn -q -o test", so "why did it not ask me" has an answer
 * that is written down rather than inferred from the absence of a prompt.
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

  /** Rules with no person behind them: what a headless run gets, and what tests want. */
  public static RuleApprover of(ApprovalRules rules, Approver delegate) {
    return new RuleApprover(rules, delegate, null);
  }

  @Override
  public ApprovalAnswer approve(ApprovalRequest request) {
    java.util.Optional<Boolean> verdict;
    try {
      verdict = rules.verdict(request);
    } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
      // A rules file that cannot be read is not "no rules": the user wrote rules and expects them.
      // Refusing to answer automatically keeps the guard on while saying what is wrong.
      announced.accept("the approvals file could not be read (" + e.getMessage() + "), so this was asked for");
      return delegate.approve(request);
    }
    if (verdict.isPresent()) {
      if (verdict.get()) {
        announced.accept("allowed by rule — " + request.summary());
        return ApprovalAnswer.ALLOW_ONCE;
      }
      announced.accept("denied by rule — " + request.summary());
      return ApprovalAnswer.DENY_BY_RULE;
    }
    ApprovalAnswer answer = delegate.approve(request);
    if (answer == ApprovalAnswer.ALLOW_SESSION) {
      rules.rememberForSession(request);
      announced.accept("allowed for this session — " + request.summary());
    } else if (answer == ApprovalAnswer.ALLOW_ALWAYS) {
      rules.remember(request);
      announced.accept("allowed from now on, in " + rules.file() + " — " + request.summary());
    }
    return answer;
  }
}
