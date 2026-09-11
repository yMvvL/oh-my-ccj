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

  default void close() {}
}
