package com.ccj.agent.core;

import java.util.ArrayList;
import java.util.List;

/** 内存中的会话，供测试使用，也供不需要在磁盘上留历史的单次运行使用。 */
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
