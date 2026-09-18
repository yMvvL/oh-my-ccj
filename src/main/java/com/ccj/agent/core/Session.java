package com.ccj.agent.core;

import java.util.List;

/**
 * 对话历史，以及它存放的位置。
 *
 * <p>循环一边走一边往会话里追加，正因如此，下次进程启动时恢复会话就只是把文件读回来的事。
 */
public interface Session {

  String id();

  /** 对话的实时、有序视图。 */
  List<Message> messages();

  void append(Message message);

  /**
   * 本会话的用量记账，重新打开时恢复。默认什么都不记，这样不记账的会话（{@link MemorySession}）也依然合法。
   */
  default UsageTotals totals() {
    return UsageTotals.empty();
  }

  /** 记录总计；会持久化的实现可以忽略它，也可以存下来。 */
  default void totals(UsageTotals totals) {}

  default void close() {}
}
