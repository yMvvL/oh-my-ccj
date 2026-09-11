package com.ccj.agent.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SseTest {

  @Test
  void parsesEventNameAndData() {
    Sse sse = Sse.of(Stream.of("event: message_start", "data: {\"a\":1}", ""));

    Sse.Event event = sse.next();

    assertEquals("message_start", event.event());
    assertEquals("{\"a\":1}", event.data());
    assertFalse(event.isDone());
    assertNull(sse.next());
  }

  @Test
  void defaultsToMessageWhenNoEventFieldIsPresent() {
    Sse sse = Sse.of(Stream.of("data: {}", ""));

    assertEquals(Sse.DEFAULT_EVENT, sse.next().event());
  }

  @Test
  void joinsPayloadsSplitOverManyDataLines() {
    Sse sse =
        Sse.of(
            Stream.of(
                "data: first", "data: second", "data:", "data: fourth", "", "data: other", ""));

    assertEquals("first\nsecond\n\nfourth", sse.next().data());
    assertEquals("other", sse.next().data());
  }

  @Test
  void ignoresCommentsAndBlankLinesAndStripsCarriageReturns() {
    Sse sse =
        Sse.of(
            Stream.of(
                ": keep-alive\r",
                "",
                "\r",
                "event: ping\r",
                "data: {}\r",
                "\r",
                "data: x",
                ""));

    Sse.Event event = sse.next();

    assertEquals("ping", event.event());
    assertEquals("{}", event.data());
    assertEquals("x", sse.next().data());
  }

  @Test
  void acceptsDataWithoutSpaceAndKeepsColonsInTheValue() {
    Sse sse = Sse.of(Stream.of("data:{\"url\":\"http://x\"}", ""));

    assertEquals("{\"url\":\"http://x\"}", sse.next().data());
  }

  @Test
  void treatsEmptyDataFieldAsAnEmptyPayload() {
    Sse sse = Sse.of(Stream.of("data:", "", "data: after", ""));

    assertEquals("", sse.next().data());
    assertEquals("after", sse.next().data());
  }

  @Test
  void doneEndsTheStream() {
    Sse sse = Sse.of(Stream.of("data: [DONE]", "", "data: ignored", ""));

    Sse.Event event = sse.next();

    assertTrue(event.isDone());
    assertNull(sse.next());
  }

  @Test
  void dispatchesAFrameLeftWithoutItsBlankLine() {
    Sse sse = Sse.of(Stream.of("data: last"));

    assertEquals("last", sse.next().data());
    assertNull(sse.next());
  }

  @Test
  void skipsUnknownFields() {
    Sse sse = Sse.of(Stream.of("id: 42", "retry: 100", "data: payload", ""));

    assertEquals("payload", sse.next().data());
  }
}
