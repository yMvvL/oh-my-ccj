package com.ccj.agent.core;

import java.util.List;

/**
 * Conversation history plus wherever it is kept.
 *
 * <p>The loop appends to the session as it goes, which is what makes resuming a session on the next
 * process start a matter of reading the file back.
 */
public interface Session {

  String id();

  /** Live, ordered view of the conversation. */
  List<Message> messages();

  void append(Message message);

  /**
   * Accounting for this session, restored when it is reopened. Defaults to nothing so a session that
   * keeps no books ({@link MemorySession}) stays valid.
   */
  default UsageTotals totals() {
    return UsageTotals.empty();
  }

  /** Records the totals; implementations that persist them may ignore or store them. */
  default void totals(UsageTotals totals) {}

  default void close() {}
}
