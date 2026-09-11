package com.ccj.agent.core;

import java.util.ArrayList;
import java.util.List;

/** In-memory session, used by tests and by one-shot runs that need no history on disk. */
public final class MemorySession implements Session {

  private final String id;
  private final List<Message> messages = new ArrayList<>();

  public MemorySession() {
    this("memory");
  }

  public MemorySession(String id) {
    this.id = id;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public List<Message> messages() {
    return List.copyOf(messages);
  }

  @Override
  public void append(Message message) {
    messages.add(message);
  }
}
