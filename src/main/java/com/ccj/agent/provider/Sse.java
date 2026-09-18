package com.ccj.agent.provider;

import java.util.Iterator;
import java.util.stream.Stream;

/**
 * 在文本行流之上的增量式 server-sent-events 解码器。
 *
 * <p>两个受支持的 API 都以「一个空行结束一帧」的方式流式发送记录，所以解码器采用拉取式：{@link #next()}
 * 只阻塞到发送方让它等的那一刻，而这正是让长回合保持响应性的原因。一条记录在它的空行到达时被派发，因此跨
 * 多行 {@code data:} 拆开的载荷，在调用者看到它之前就已经拼好了。
 */
public final class Sse implements AutoCloseable {

  /** 结束 OpenAI 式流的载荷；它自身不含 JSON。 */
  public static final String DONE = "[DONE]";

  /** 一帧没有 {@code event:} 字段时假定的那个事件名。 */
  public static final String DEFAULT_EVENT = "message";

  private final Stream<String> lines;
  private final Iterator<String> iterator;
  private boolean finished;

  public Sse(Stream<String> lines) {
    this.lines = lines;
    this.iterator = lines.iterator();
  }

  public static Sse of(Stream<String> lines) {
    return new Sse(lines);
  }

  /** 一个已派发的帧：事件类型，加上用换行拼起来的所有 {@code data:} 行。 */
  public record Event(String event, String data) {
    public boolean isDone() {
      return DONE.equals(data);
    }
  }

  /**
   * 返回下一帧；流已耗尽或已见到 {@code [DONE]} 时返回 {@code null}。流尾悬着的一段载荷仍会返回，而不是
   * 丢掉，这样一个忘了最后那个空行的服务器不会悄悄丢掉最后一次工具调用。
   */
  public Event next() {
    if (finished) {
      return null;
    }
    String event = null;
    StringBuilder data = new StringBuilder();
    boolean sawData = false;
    while (iterator.hasNext()) {
      String line = stripCarriageReturn(iterator.next());
      if (line.isEmpty()) {
        if (sawData) {
          return dispatch(event, data.toString());
        }
        continue; // 单独一个换行：保活，不是一帧
      }
      if (line.charAt(0) == ':') {
        continue; // 注释
      }
      int colon = line.indexOf(':');
      String field = colon < 0 ? line : line.substring(0, colon);
      String value = colon < 0 ? "" : stripLeadingSpace(line.substring(colon + 1));
      switch (field) {
        case "event" -> event = value;
        case "data" -> {
          if (sawData) {
            data.append('\n');
          }
          data.append(value);
          sawData = true;
        }
        default -> {
          // id/retry 和未知字段都不带两个提供方中任何一方需要的东西。
        }
      }
    }
    return sawData ? dispatch(event, data.toString()) : null;
  }

  private Event dispatch(String event, String data) {
    if (DONE.equals(data)) {
      finished = true;
    }
    return new Event(event == null ? DEFAULT_EVENT : event, data);
  }

  private static String stripCarriageReturn(String line) {
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }

  /** 线路格式允许冒号之后最多一个可选空格。 */
  private static String stripLeadingSpace(String value) {
    return value.startsWith(" ") ? value.substring(1) : value;
  }

  @Override
  public void close() {
    finished = true;
    lines.close();
  }
}
