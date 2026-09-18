package com.ccj.agent.core;

/**
 * Gate consulted before a tool performs a side effect.
 *
 * <p>Read-only tools never call it; anything that writes, deletes or executes asks first unless a
 * rule already answers for it. An approval is an {@link ApprovalAnswer} rather than a boolean because
 * "stop asking me about this" is a different answer from "yes", and because a rule that refused
 * without asking has to be distinguishable from a person who refused.
 */
@FunctionalInterface
public interface Approver {

  /** Approves everything, once per request: the shape `--yolo` and the auto-approve toggle build. */
  Approver ALWAYS = request -> ApprovalAnswer.ALLOW_ONCE;

  Approver NEVER = request -> ApprovalAnswer.DENY;

  ApprovalAnswer approve(ApprovalRequest request);
}
