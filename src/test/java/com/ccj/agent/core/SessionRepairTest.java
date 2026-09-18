package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 让一段被打断的对话重新可用的修复。两种线上格式都会拒绝这样一个回合：assistant 请求了一次工具
 * 调用，却没有任何结果回应它；而会话是只追加的 —— 所以夹在两者之间的一次中止，会留下之后任何
 * 回合都永远发不出去的历史。
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
    // 正是一次中止留下来的状态：assistant 回合已经在磁盘上，它的结果没有。
    List<Message> messages = List.of(new Message.User("read it"), calls("call_1"));

    SessionRepair.Result repaired = SessionRepair.apply(messages);

    assertEquals(3, repaired.messages().size());
    assertEquals(1, repaired.filled());
    Message.ToolResult filled = (Message.ToolResult) repaired.messages().get(2);
    assertEquals("call_1", filled.toolCallId());
    assertEquals("read", filled.toolName());
    assertTrue(filled.error(), "一个没有运行的调用不算成功");
    assertTrue(filled.content().contains("未运行"), filled.content());
    assertTrue(repaired.notice().contains("1 次被中断的工具调用被标记为未运行"), repaired.notice());
  }

  @Test
  void theResultLandsWithItsTurnNotAtTheEndOfTheConversation() {
    // 位置很重要：两个 API 都要求回答跟随着发出请求的那个回合，所以追加到末尾的修复，会与它所
    // 填补的那个空洞一样不合法。
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
    // call_2 的结果应该在 call_1 的结果之后、下一个用户回合之前。
    assertEquals("call_2", ((Message.ToolResult) repaired.messages().get(3)).toolCallId());
    assertEquals("call_3", ((Message.ToolResult) repaired.messages().get(6)).toolCallId());
  }

  @Test
  void parallelCallsThatShareAnEmptyIdStillCountAsTwo() {
    // 有些提供方完全省略调用 id；只按 id 匹配会以为一个结果回应了两个调用，从而让会话不合法。
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
    // 同一个会话上的第二个写入者会留下的形状：assistant 请求了一次调用，一条用户消息先于它的结果
    // 落盘，而这个调用如今看起来无人应答，它真正的结果却落在中断之后。按记录原样发出，那就是一条
    // 孤儿 tool 消息 —— 正是 OpenAI 用 "Messages with role 'tool' must be a response to a preceding
    // message with 'tool_calls'" 拒绝的那个请求。
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
    assertEquals(0, repaired.filled(), "这个调用已被回应：不需要凭空造一个");
    assertEquals(1, repaired.moved(), repaired.notice());
    assertTrue(repaired.notice().contains("条工具结果被带回了提出它们的那个回合"), repaired.notice());
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
    // 发出它，就是同一个被拒绝的请求：一条没有任何调用可供它回应的 tool 消息。投影会把它排除在外
    // 并说明原因，因为只有当每个结果都是对某个调用的回应时，请求才发得出去。
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
    assertTrue(repaired.notice().contains("回答不了任何调用的工具结果未放进请求"), repaired.notice());
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
    assertTrue(supplied.content().contains("未运行"), supplied.content());
    assertEquals("are you done?", ((Message.User) out.get(4)).text());
    assertEquals(1, repaired.filled());
    assertEquals(0, repaired.moved(), "本来就在那里的回答没有移动：" + repaired.notice());
  }

  @Test
  void aProjectionIsSendableWhateverASecondWriterDidToTheFile() {
    // 无论结果和中断以什么顺序到达，两种线上格式都会校验的形状必须成立：每个调用后面紧跟一段
    // 格式良好的回答。
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
          "回答按调用顺序排，紧跟在第 " + i + " 位的那个回合之后：" + out);
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
