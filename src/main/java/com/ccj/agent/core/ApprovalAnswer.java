package com.ccj.agent.core;

/**
 * What came back from an approval request, which is four answers and not two.
 *
 * <p>The boolean this replaced forced every answer into yes-or-no, and the only way to say "stop
 * asking about this" was to switch approval off for the whole session — measured as the reason
 * people run `--yolo`. "Yes, this once" and "yes, always" are different answers with different
 * consequences, and the caller has to be able to tell them apart, so they are separate values.
 */
public enum ApprovalAnswer {

  /** Do it, and ask again next time. */
  ALLOW_ONCE,

  /** Do it, and do not ask again in this session for the same command or path. */
  ALLOW_SESSION,

  /** Do it, and write a rule so it is never asked again in this project. */
  ALLOW_ALWAYS,

  /** Refused by the person. */
  DENY,

  /**
   * Refused by a rule the user wrote earlier, without asking anybody.
   *
   * <p>Distinct from {@link #DENY} because the two read differently in a transcript: one is "I said
   * no just now", the other is "a rule you wrote says no" — and only the second is worth going to
   * look at.
   */
  DENY_BY_RULE;

  public boolean allowed() {
    return this == ALLOW_ONCE || this == ALLOW_SESSION || this == ALLOW_ALWAYS;
  }

  /** What the tool hands back to the model when the answer was no. */
  public String refusal() {
    return this == DENY_BY_RULE
        ? "denied by a rule in the approvals file (see ccj --help and SECURITY.md)"
        : "rejected by user";
  }
}
