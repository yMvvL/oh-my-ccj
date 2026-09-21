package com.ccj.agent.web;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * SSE 扇出：谁在听、重连的页面可以重放哪一段，以及每条事件在哪里拿到它的序号。
 *
 * <p>这三样东西一起被持有，而不是由调用方凑齐再传进来：序号是在与「缓冲区里有哪些事件」同一把锁下决定的，
 * 分开算的话，一个重连的页面会拿到一段有缺口的重放。{@link #publish} 里那把锁就是这个意思。
 *
 * <p>它不知道会话、回合或审批——{@link AgentHub} 决定发什么，这里只决定谁收得到。
 */
public final class EventStream {

  /** 重连的浏览器可以重放多少条事件。 */
  private static final int REPLAY_LIMIT = 500;

  private final List<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();
  private final Deque<Event> replay = new ArrayDeque<>();
  private final AtomicLong nextEventId = new AtomicLong();

  /**
   * 发生过的一件事，按线上格式塑形。
   *
   * <p>{@code sessionId} 属于事件而不是连接，是因为一条流承载服务器上的每一个对话：在回合并行运行的
   * 时候，一个不点名自己会话的事件会被渲染进当时碰巧打开的那份转录里。
   */
  public record Event(long id, String type, String sessionId, ObjectNode payload) {}

  /**
   * 注册一个订阅者并在同一个原子步骤里交回它错过的事件——否则在「读重放缓冲区」和「开始监听」之间发布
   * 的事件会丢掉。
   */
  public List<Event> subscribe(Consumer<Event> subscriber) {
    synchronized (replay) {
      subscribers.add(subscriber);
      return List.copyOf(replay);
    }
  }

  public void unsubscribe(Consumer<Event> subscriber) {
    synchronized (replay) {
      subscribers.remove(subscriber);
    }
  }

  void publish(String type, ObjectNode payload) {
    publish("", type, payload);
  }

  void publish(String sessionId, String type, ObjectNode payload) {
    ObjectNode node = payload == null ? Json.object() : payload;
    node.put("type", type);
    // 已经点名自己会话的载荷保留它——状态是*关于*某个对话的，构建时就拿着那个 id。只有什么都不说的
    // 载荷才会拿到传进来的 id，所以空的那个仍然是「这是关于服务器的」，而不是「这是关于会话 '' 的」。
    if (!node.has("sessionId")) {
      node.put("sessionId", sessionId == null ? "" : sessionId);
    }
    Event event =
        new Event(nextEventId.incrementAndGet(), type, node.path("sessionId").asText(), node);
    synchronized (replay) {
      replay.addLast(event);
      while (replay.size() > REPLAY_LIMIT) {
        replay.removeFirst();
      }
      for (Consumer<Event> subscriber : subscribers) {
        subscriber.accept(event);
      }
    }
  }
}
