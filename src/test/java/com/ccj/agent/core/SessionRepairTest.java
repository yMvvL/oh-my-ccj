package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The repair that makes an interrupted conversation usable again. Both wire formats refuse a turn
 * where an assistant asked for a tool call and no result answers it, and the session is append-only —
 * so an abort between the two leaves a history that no later turn can ever send.
 */
class SessionRepairTest {

  private static Message.Assistant calls(String... ids) {
    List<Message.ToolCall> toolCalls = new ArrayList<>();
    for (String id : ids) {
      toolCalls.add(new Message.ToolCall(id, "read", "{}"));
    }
    return new Message.Assistant("", toolCalls);
  }

  private static Message.ToolResult result(String id) {
    return new Message.ToolResult(id, "read", "contents", false);
  }

  @Test
  void anAnsweredTurnIsLeftExactlyAsItWas() {
    List<Message> messages =
        List.of(
            new Message.User("read it"),
            calls("call_1"),
            result("call_1"),
            new Message.Assistant("done", List.of()));

    SessionRepair.Result repaired = SessionRepair.apply(messages);

    assertEquals(messages, repaired.messages());
    assertEquals(0, repaired.filled());
    assertFalse(repaired.repaired());
    assertEquals(null, repaired.notice());
  }

  @Test
  void anInterruptedTurnGetsTheResultItNeverGot() {
    // Exactly the state an abort leaves behind: the assistant turn is on disk, its result is not.
    List<Message> messages = List.of(new Message.User("read it"), calls("call_1"));

    SessionRepair.Result repaired = SessionRepair.apply(messages);

    assertEquals(3, repaired.messages().size());
    assertEquals(1, repaired.filled());
    Message.ToolResult filled = (Message.ToolResult) repaired.messages().get(2);
    assertEquals("call_1", filled.toolCallId());
    assertEquals("read", filled.toolName());
    assertTrue(filled.error(), "a call that did not run is not a success");
    assertTrue(filled.content().contains("not run"), filled.content());
    assertTrue(repaired.notice().contains("1 interrupted tool call"), repaired.notice());
  }

  @Test
  void theResultLandsWithItsTurnNotAtTheEndOfTheConversation() {
    // Position matters: both APIs require the answers to follow the turn that asked, so a repair
    // that appended at the end would be as invalid as the hole it filled.
    List<Message> messages =
        List.of(
            new Message.User("first"),
            calls("call_1"),
            new Message.User("second"),
            new Message.Assistant("an answer", List.of()));

    SessionRepair.Result repaired = SessionRepair.apply(messages);

    assertEquals(5, repaired.messages().size());
    assertEquals("call_1", ((Message.ToolResult) repaired.messages().get(2)).toolCallId());
    assertEquals("second", ((Message.User) repaired.messages().get(3)).text());
  }

  @Test
  void onlyTheUnansweredCallIsFilledIn() {
    List<Message> messages =
        List.of(
            new Message.User("read both"),
            calls("call_1", "call_2"),
            result("call_1"),
            new Message.User("and now?"),
            calls("call_3"),
            result("call_3"));

    SessionRepair.Result repaired = SessionRepair.apply(messages);

    assertEquals(1, repaired.filled(), repaired.messages().toString());
    // call_2's result belongs after call_1's, before the next user turn.
    assertEquals("call_2", ((Message.ToolResult) repaired.messages().get(3)).toolCallId());
    assertEquals("call_3", ((Message.ToolResult) repaired.messages().get(6)).toolCallId());
  }

  @Test
  void parallelCallsThatShareAnEmptyIdStillCountAsTwo() {
    // Providers exist that omit call ids entirely; matching by id alone would think one result
    // answered both calls and leave the session invalid.
    List<Message> messages =
        List.of(calls("", ""), result(""));

    SessionRepair.Result repaired = SessionRepair.apply(messages);

    assertEquals(1, repaired.filled());
    assertEquals(3, repaired.messages().size());
  }

  @Test
  void severalInterruptedTurnsAreAllRepaired() {
    List<Message> messages =
        List.of(
            calls("call_1"),
            new Message.User("try again"),
            calls("call_2", "call_3"),
            new Message.User("and again"),
            calls("call_4"));

    SessionRepair.Result repaired = SessionRepair.apply(messages);

    assertEquals(4, repaired.filled());
    assertEquals(9, repaired.messages().size(), repaired.messages().toString());
    assertEquals(
        List.of("call_1", "call_2", "call_3", "call_4"),
        repaired.messages().stream()
            .filter(Message.ToolResult.class::isInstance)
            .map(Message.ToolResult.class::cast)
            .map(Message.ToolResult::toolCallId)
            .toList());
  }

  @Test
  void aMessageInTheMiddleOfATurnsAnswersStillEndsUpAfterThem() {
    // The shape a second writer on one session leaves behind: the assistant asked for a call, a user
    // message landed before its result did, and the call now looks unanswered while its real result
    // sits past the interruption. Sent as recorded, that is an orphan tool message — which is exactly
    // the request OpenAI rejects with "Messages with role 'tool' must be a response to a preceding
    // message with 'tool_calls'".
    List<Message> messages =
        List.of(
            new Message.User("run it"),
            calls("call_1"),
            new Message.User("zzz a probe written mid-turn"),
            result("call_1"));

    SessionRepair.Result repaired = SessionRepair.apply(messages);

    List<Message> out = repaired.messages();
    assertEquals(4, out.size(), out.toString());
    assertEquals("call_1", ((Message.ToolResult) out.get(2)).toolCallId(), out.toString());
    assertEquals("zzz a probe written mid-turn", ((Message.User) out.get(3)).text());
    assertEquals(0, repaired.filled(), "the call was answered: nothing had to be invented");
    assertEquals(1, repaired.moved(), repaired.notice());
    assertTrue(repaired.notice().contains("moved back"), repaired.notice());
  }

  @Test
  void severalAnswersThatWaitedThroughAnInterruptionAreAllCarriedBack() {
    List<Message> messages =
        List.of(
            new Message.User("read both"),
            calls("call_1", "call_2"),
            new Message.User("meanwhile"),
            result("call_1"),
            new Message.Assistant("still talking", List.of()),
            result("call_2"));

    SessionRepair.Result repaired = SessionRepair.apply(messages);

    assertEquals(
        List.of("call_1", "call_2"),
        repaired.messages().stream()
            .filter(Message.ToolResult.class::isInstance)
            .map(Message.ToolResult.class::cast)
            .map(Message.ToolResult::toolCallId)
            .toList());
    assertEquals(0, repaired.filled());
    assertEquals(2, repaired.moved());
    assertEquals("meanwhile", ((Message.User) repaired.messages().get(4)).text());
    assertEquals("still talking", ((Message.Assistant) repaired.messages().get(5)).text());
  }

  @Test
  void aResultThatAnswersNoRecordedCallIsNotSentAtAll() {
    // Sending it is the same rejected request: a tool message with no call for it to answer. The
    // projection leaves it out and says so, because a request is only sendable when every result is
    // an answer to a call.
    List<Message> messages =
        List.of(
            new Message.User("read it"),
            calls("call_1"),
            result("call_1"),
            result("call_from_a_session_that_was_never_recorded"));

    SessionRepair.Result repaired = SessionRepair.apply(messages);

    assertEquals(3, repaired.messages().size(), repaired.messages().toString());
    assertEquals(0, repaired.filled());
    assertEquals(1, repaired.dropped(), repaired.notice());
    assertTrue(repaired.notice().contains("answer no call"), repaired.notice());
  }

  @Test
  void anAnswerThatArrivesTwiceIsSentOnce() {
    List<Message> messages =
        List.of(new Message.User("read it"), calls("call_1"), result("call_1"), result("call_1"));

    SessionRepair.Result repaired = SessionRepair.apply(messages);

    assertEquals(3, repaired.messages().size(), repaired.messages().toString());
    assertEquals(1, repaired.dropped());
  }

  @Test
  void anInterruptedTurnAndAStrayWriteInItAreBothRepaired() {
    List<Message> messages =
        List.of(
            new Message.User("read two files"),
            calls("call_1", "call_2"),
            result("call_1"),
            new Message.User("are you done?"));

    SessionRepair.Result repaired = SessionRepair.apply(messages);

    List<Message> out = repaired.messages();
    assertEquals(5, out.size(), out.toString());
    assertEquals("call_1", ((Message.ToolResult) out.get(2)).toolCallId());
    Message.ToolResult supplied = (Message.ToolResult) out.get(3);
    assertEquals("call_2", supplied.toolCallId());
    assertTrue(supplied.content().contains("not run"), supplied.content());
    assertEquals("are you done?", ((Message.User) out.get(4)).text());
    assertEquals(1, repaired.filled());
    assertEquals(0, repaired.moved(), "the answer that was there did not move: " + repaired.notice());
  }

  @Test
  void aProjectionIsSendableWhateverASecondWriterDidToTheFile() {
    // Whatever order results and interruptions arrive in, the shape both wire formats validate has to
    // hold: one well-formed run of answers per call, right after the turn that asked for them.
    List<Message> messages =
        List.of(
            new Message.User("go"),
            calls("call_1", "call_2"),
            new Message.User("written mid-turn"),
            result("call_2"),
            result("call_1"),
            new Message.Assistant("done", List.of()),
            calls("call_3"),
            result("call_3"));

    List<Message> out = SessionRepair.apply(messages).messages();

    for (int i = 0; i < out.size(); i++) {
      if (!(out.get(i) instanceof Message.Assistant assistant) || !assistant.hasToolCalls()) {
        continue;
      }
      List<String> ids =
          assistant.toolCalls().stream().map(Message.ToolCall::id).toList();
      int end = i + 1;
      while (end < out.size() && out.get(end) instanceof Message.ToolResult) {
        end++;
      }
      assertEquals(
          ids,
          out.subList(i + 1, end).stream()
              .map(Message.ToolResult.class::cast)
              .map(Message.ToolResult::toolCallId)
              .toList(),
          "answers in call order, right after the turn at " + i + ": " + out);
    }
  }

  @Test
  void anEmptyOrPlainConversationIsUntouched() {
    assertTrue(SessionRepair.apply(List.of()).messages().isEmpty());
    assertTrue(SessionRepair.apply(null).messages().isEmpty());

    List<Message> plain = List.of(new Message.User("hi"), new Message.Assistant("hello", List.of()));
    assertEquals(plain, SessionRepair.apply(plain).messages());
    assertEquals(0, SessionRepair.apply(plain).filled());
  }
}
