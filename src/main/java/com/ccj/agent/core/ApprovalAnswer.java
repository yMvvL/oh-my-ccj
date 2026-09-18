package com.ccj.agent.core;

/**
 * 审批请求得到的答复，是四种答案，不是两种。
 *
 * <p>它取代的那个布尔值把所有答案都逼成是或否，而想说「别再问这个了」的唯一办法是把整个会话的审批关掉——
 * 那正是人们跑 `--yolo` 的原因。「这一次可以」和「一直可以」是后果不同的两种答复，调用方必须能区分它们，
 * 所以它们是各自独立的值。
 */
public enum ApprovalAnswer {

  /** 照做，下次再问一遍。 */
  ALLOW_ONCE,

  /** 照做，本会话内对同一条命令或同一个路径不再询问。 */
  ALLOW_SESSION,

  /** 照做，并写入一条规则，使本项目内永远不再询问。 */
  ALLOW_ALWAYS,

  /** 被人拒绝。 */
  DENY,

  /**
   * 被用户先前写下的规则拒绝，没有问过任何人。
   *
   * <p>与 {@link #DENY} 区分开，因为两者在转录里的读法不同：一个是「我刚才说了不行」，另一个是「你写的
   * 一条规则说不行」——只有后者值得去翻出来看。
   */
  DENY_BY_RULE;

  public boolean allowed() {
    return this == ALLOW_ONCE || this == ALLOW_SESSION || this == ALLOW_ALWAYS;
  }

  /** 答案为否时，工具交还给模型的内容。 */
  public String refusal() {
    return this == DENY_BY_RULE
        ? "被审批文件中的某条规则拒绝（可运行 ccj --help、查看 SECURITY.md 了解如何改规则）"
        : "被用户拒绝";
  }
}
