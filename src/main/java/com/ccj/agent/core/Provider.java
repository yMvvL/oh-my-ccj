package com.ccj.agent.core;

import java.util.List;
import java.util.function.Consumer;

/**
 * 一个模型后端。
 *
 * <p>实现负责把 {@link Request} 翻译成自家厂商的线路格式，在响应到达的同时把 {@link Event} 流式推给调用
 * 方，并返回组装完成的助手回合。流式是必须的，不是可选项：监听器正是界面在长回合里保持响应的方式。
 */
public interface Provider extends AutoCloseable {

  String name();

  Message.Assistant complete(Request request, Consumer<Event> listener) throws Exception;

  @Override
  default void close() {}

  /**
   * 一个模型回合。
   *
   * @param system 系统提示词，没有则为 null
   * @param tools 本回合展示给模型的工具；可以为空
   */
  record Request(
      String model,
      String system,
      List<Message> messages,
      List<ToolSpec> tools,
      Double temperature,
      Integer maxTokens,
      String reasoning) {

    public Request {
      messages = messages == null ? List.of() : List.copyOf(messages);
      tools = tools == null ? List.of() : List.copyOf(tools);
    }
  }

  sealed interface Event {

    record TextDelta(String text) implements Event {}

    record ReasoningDelta(String text) implements Event {}

    record ToolCallStart(String id, String name) implements Event {}

    /**
     * 一个回合的 token 记账。
     *
     * @param inputTokens 按计费口径的提示词 token；对 Anthropic 来说，它是普通、缓存读取和缓存创建三项
     *     计数之和，也是唯一能在提供方之间比较的数字
     * @param cachedInputTokens 提示词中有多少来自提供方的缓存；提供方没有报告时为 null——「没有信息」和
     *     「没有命中缓存」是两个不同的事实，不能都渲染成 0%
     */
    record Usage(int inputTokens, int outputTokens, Integer cachedInputTokens) implements Event {
      public Usage(int inputTokens, int outputTokens) {
        this(inputTokens, outputTokens, null);
      }
    }

    /** 即将对一次瞬时传输故障重试时发出。 */
    record Retry(int attempt, String reason, long delayMillis) implements Event {}
  }
}
