package com.ccj.agent.core;

/**
 * 工具执行副作用之前要经过的关卡。
 *
 * <p>只读工具从不调用它；任何写入、删除或执行的动作都要先问一声，除非已有规则代它作答。审批返回的是
 * {@link ApprovalAnswer} 而不是布尔值：因为「别再问这个了」和「可以」是不同的答案，也因为「规则在未询问
 * 的情况下拒绝」必须能与「人拒绝」区分开。
 */
@FunctionalInterface
public interface Approver {

  /** 全部批准，但每次请求都要问一遍：`--yolo` 和自动批准开关构造的就是这种形态。 */
  Approver ALWAYS = request -> ApprovalAnswer.ALLOW_ONCE;

  Approver NEVER = request -> ApprovalAnswer.DENY;

  ApprovalAnswer approve(ApprovalRequest request);
}
