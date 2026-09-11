package com.ccj.agent.provider;

import java.util.Iterator;
import java.util.stream.Stream;

/**
 * Incremental server-sent-events decoder over a stream of text lines.
 *
 * <p>Both supported APIs stream one record per blank-line-terminated frame, so the decoder is
 * pull-based: {@link #next()} blocks only for as long as the sender makes it wait, which is exactly
 * what keeps a long turn responsive. A record is dispatched when its blank line arrives, so a
 * payload split over several {@code data:} lines is joined before the caller ever sees it.
 */
public final class Sse implements AutoCloseable {

  /** Payload that ends an OpenAI-style stream; it carries no JSON of its own. */
  public static final String DONE = "[DONE]";

  /** Event name assumed when a frame carries no {@code event:} field. */
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

  /** One dispatched frame: its event type plus every {@code data:} line joined with newlines. */
  public record Event(String event, String data) {
    public boolean isDone() {
      return DONE.equals(data);
    }
  }

  /**
   * Returns the next frame, or {@code null} once the stream is exhausted or {@code [DONE]} has been
   * seen. A dangling payload at end of stream is still returned rather than dropped, so a server
   * that forgets the final blank line does not silently lose the last tool call.
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
        continue; // bare newline: keep-alive, not a frame
      }
      if (line.charAt(0) == ':') {
        continue; // comment
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
          // id/retry/unknown fields carry nothing either provider needs.
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

  /** The wire format allows exactly one optional space after the colon. */
  private static String stripLeadingSpace(String value) {
    return value.startsWith(" ") ? value.substring(1) : value;
  }

  @Override
  public void close() {
    finished = true;
    lines.close();
  }
}
