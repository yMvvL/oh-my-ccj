package com.ccj.agent.core;

import java.util.List;
import java.util.function.Consumer;

/**
 * A model backend.
 *
 * <p>Implementations translate {@link Request} into their vendor wire format, stream {@link Event}s
 * to the caller while the response arrives, and return the assembled assistant turn. Streaming is
 * mandatory rather than optional: the listener is how the UI stays responsive on long turns.
 */
public interface Provider extends AutoCloseable {

  String name();

  Message.Assistant complete(Request request, Consumer<Event> listener) throws Exception;

  @Override
  default void close() {}

  /**
   * One model turn.
   *
   * @param system system prompt, or null when none
   * @param tools tools advertised for this turn; may be empty
   */
  record Request(
      String model,
      String system,
      List<Message> messages,
      List<ToolSpec> tools,
      Double temperature,
      Integer maxTokens) {

    public Request {
      messages = messages == null ? List.of() : List.copyOf(messages);
      tools = tools == null ? List.of() : List.copyOf(tools);
    }
  }

  sealed interface Event {

    record TextDelta(String text) implements Event {}

    record ReasoningDelta(String text) implements Event {}

    record ToolCallStart(String id, String name) implements Event {}

    record Usage(int inputTokens, int outputTokens) implements Event {}

    /** Emitted when a transient transport failure is about to be retried. */
    record Retry(int attempt, String reason, long delayMillis) implements Event {}
  }
}
