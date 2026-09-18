package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 压缩：摘要替换掉对话的哪一部分，以及关于其余部分它被告知了什么。
 *
 * <p>最重要的一条规则，是它和 {@link ContextBudget}、{@link SessionRepair} 共有的那条 —— 工具结果
 * 永远不会失去请求它的那个 assistant 回合，因为两种线上格式都拒绝那种形状 —— 外加它自己的那条：
 * 模型即将据以行动的那段尾部是被原样引用，而不是被压缩的。
 */
class CompactionTest {

  /**
   * {@code exchanges} 个用户回合，每个回合带一个回答，前几个还带一轮工具调用。
   *
   * <p>消息带有接近真实的体量：压缩是摘要在与它所替换的文本之间做的一笔交易，而一段只有两个词的消息
   * 组成的对话没有东西可交易。填充内容让每个回合的花费等同于一次真实的文件读取和一次回答。
   */
  private static List<Message> conversation(int exchanges, boolean withTools) {
    List<Message> messages = new ArrayList<>();
    for (int i = 0; i < exchanges; i++) {
      messages.add(new Message.User("question " + i + " " + "context ".repeat(120)));
      if (withTools) {
        messages.add(
            new Message.Assistant(
                "", List.of(new Message.ToolCall("call_" + i, "read", "{\"path\":\"f" + i + "\"}"))));
        messages.add(
            new Message.ToolResult(
                "call_" + i, "read", "contents of f" + i + " " + "line".repeat(120), false));
      }
      messages.add(Message.Assistant.text("answer " + i + " " + "detail ".repeat(120)));
    }
    return messages;
  }

  /**
   * 一份长度可信的摘要 —— 长到有用，又短到确实省下东西。
   *
   * <p>已经 strip 过，因为 {@code apply} 会去掉模型写出的首尾空白：一个带尾随空格的常量会让下面
   * 每个 {@code contains} 断言，在明明包含它的文本上失败。
   */
  private static final String SUMMARY =
      "goal: do the thing. files: f0. open: nothing. ".repeat(10).strip();

  @Test
  void theNewestExchangesAreKeptVerbatimAndTheRestIsSummarised() {
    List<Message> before = conversation(8, false);

    Compaction.Result result = Compaction.apply(before, SUMMARY, "/sessions/s.jsonl", null);

    // 8 个回合、每回合两条消息；最新的 5 个回合保留，所以有 3 个回合 —— 6 条消息 —— 被替换掉。
    assertEquals(6, result.summarised(), "最旧的三个回合被替换");
    assertEquals(10, result.kept(), "五个回合、每回合两条消息留下");
    assertEquals(before.size(), result.summarised() + result.kept(), "没有丢失，也没有凭空多出");

    Message.Summary head = (Message.Summary) result.messages().get(0);
    assertTrue(head.text().contains(SUMMARY), head.text());
    assertEquals(6, head.covers(), "这个数目是消息数，也就是转录所报告的");
    assertEquals("/sessions/s.jsonl", head.source());

    // 尾部就是原始消息，未作改动、顺序不变 —— 被引用，从不被改写。
    assertEquals(
        before.subList(6, before.size()),
        result.messages().subList(1, result.messages().size()),
        "保留的那部分必须就是原始消息本身");
  }

  @Test
  void aCutNeverSeparatesAToolCallFromItsResult() {
    // 六个带工具轮次的回合。无论计数落在哪里，尾部都必须从一个用户消息开始：一个其 assistant
    // 回合已被摘要掉的 tool_result，是两个 API 都会拒绝的请求。
    List<Message> before = conversation(6, true);

    Compaction.Result result = Compaction.apply(before, SUMMARY, "s.jsonl", null);

    assertTrue(result.messages().get(1) instanceof Message.User, result.messages().get(1).toString());
    // 而且每个保留下来的调用仍紧接在它后面带着自己的结果 —— 两种线上格式都会校验的形状，
    // 检查的是投影本身，而不是产生它的那条规则。
    List<Message> kept = result.messages();
    for (int i = 1; i < kept.size(); i++) {
      if (kept.get(i) instanceof Message.ToolResult result0) {
        assertTrue(
            kept.get(i - 1) instanceof Message.Assistant assistant
                && assistant.toolCalls().stream().anyMatch(call -> call.id().equals(result0.toolCallId())),
            "第 " + i + " 位的工具结果没有回应它紧前面的任何调用：" + kept);
      }
    }
    long calls = kept.stream().filter(m -> m instanceof Message.Assistant a && a.hasToolCalls()).count();
    long results = kept.stream().filter(m -> m instanceof Message.ToolResult).count();
    assertEquals(calls, results, "每个保留下来的调用都有它的结果：" + kept);
  }

  @Test
  void aConversationWithNothingToCutCannotBeCompacted() {
    // 回合数比尾部所要保留的还少：压缩会拿一份摘要去替换「什么都没有」，白白丢掉细节而毫无节省。
    assertFalse(Compaction.possible(conversation(Compaction.KEEP_EXCHANGES, false)));
    assertFalse(Compaction.possible(List.of()));
    assertEquals(0, Compaction.cutPoint(conversation(Compaction.KEEP_EXCHANGES, false)));
    assertThrows(
        IllegalArgumentException.class,
        () -> Compaction.apply(conversation(2, false), SUMMARY, "src", null));
  }

  @Test
  void compactingTwiceDoesNotCompressAnEarlierCompression() {
    // 第二遍必须把第一份摘要当作一个回合的开头，这样它总结的是它之后的工作，而不是把自己的输出
    // 又喂回给自己。
    List<Message> first = Compaction.apply(conversation(8, false), SUMMARY, "s.jsonl", null).messages();

    assertTrue(Compaction.possible(first));
    Compaction.Result second = Compaction.apply(conversation(6, false), SUMMARY, "s.jsonl", null);
    assertTrue(second.summarised() > 0);

    int cut = Compaction.cutPoint(first);
    assertTrue(cut > 0);
    assertTrue(
        first.get(cut) instanceof Message.User || first.get(cut) instanceof Message.Summary,
        first.get(cut).toString());

    // 先前那份摘要也是会被再次总结的内容之一（它如今是旧工作了），但它在模型读到的转录里，
    // 而不是被悄悄丢掉 —— 这正是第二遍不会丢掉第一遍所保全之物的原因。
    String transcript = Compaction.transcript(first);
    assertTrue(transcript.contains(SUMMARY), transcript);
    assertTrue(transcript.contains("summary of still earlier work"), transcript);
  }

  @Test
  void aSummaryThatSavesNothingIsRefusedRatherThanWritten() {
    // 这个测试所针对的失败：一段早期回合大多是工具调用管道的对话，总结出来的东西比它移除的文本
    // *更长* —— 在真实会话上实测为 4239 → 4236 tokens。什么都腾不出来的压缩是有成本无收益的，
    // 所以结果会被拒绝，调用方也就能说这段对话还没长到值得压缩。
    List<Message> thin = conversation(8, false);
    String enormous = "detail ".repeat(4000);

    Compaction.NotWorthIt refused =
        assertThrows(
            Compaction.NotWorthIt.class, () -> Compaction.apply(thin, enormous, "s.jsonl", null));

    assertTrue(refused.summaryTokens() >= refused.replacedTokens(), refused.getMessage());
    assertTrue(refused.getMessage().contains("压缩腾不出任何空间"), refused.getMessage());
  }

  @Test
  void aSummaryThatSavesSomethingReportsHowMuch() {
    List<Message> before = conversation(8, false);

    Compaction.Result result = Compaction.apply(before, SUMMARY, "s.jsonl", null);

    assertTrue(result.savedPercent() > 0, "一份有用的摘要能省下可测量的量");
    assertTrue(result.savedPercent() <= 100, String.valueOf(result.savedPercent()));
    assertTrue(result.summaryTokens() < result.replacedTokens());
  }

  @Test
  void theSummaryTellsTheModelWhereTheRealConversationIs() {
    List<Message> before = conversation(8, false);

    Compaction.Result result =
        Compaction.apply(before, SUMMARY, "/home/me/.oh-my-ccj/sessions/abc.jsonl", null);
    Message.Summary head = (Message.Summary) result.messages().get(0);

    assertTrue(head.text().contains("/home/me/.oh-my-ccj/sessions/abc.jsonl"), head.text());
    assertTrue(head.text().contains("read it if you need a detail"), head.text());
    // 摘要被标记为摘要，这样模型不会把它读成自己先前说过的话。
    assertTrue(head.text().contains("written by the model when the conversation was compacted"), head.text());
  }

  @Test
  void aCompactionShrinksWhatTheNextRequestCarries() {
    // 体量按真实会话来，而不是玩具会话：这个特性要的是*下一个*回合更便宜，而摘要本身也带一段
    // 前言，所以一段只有两个词的消息组成的对话什么也省不下，什么也证明不了。
    List<Message> before = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      before.add(new Message.User("question " + i + " " + "context ".repeat(200)));
      before.add(Message.Assistant.text("answer " + i + " " + "detail ".repeat(200)));
    }

    Compaction.Result result =
        Compaction.apply(before, "a summary of the earlier work, of a plausible length ".repeat(20), "x", null);

    assertTrue(
        TokenEstimate.of(result.messages()) < TokenEstimate.of(before),
        "一次压缩必须留下更小的请求："
            + TokenEstimate.of(before)
            + " -> "
            + TokenEstimate.of(result.messages()));
  }

  @Test
  void theTranscriptHandedToTheModelNamesRolesAndToolResults() {
    List<Message> before = conversation(8, true);

    String transcript = Compaction.transcript(before);

    assertTrue(transcript.contains("question 0"), transcript);
    assertTrue(transcript.contains("called read"), transcript);
    assertTrue(transcript.contains("tool result"), transcript);
    assertTrue(transcript.contains("answer 0"), transcript);
    // 只包含被替换的那部分：尾部不在送去总结的内容里。
    assertFalse(transcript.contains("question 7"), transcript);
  }
}
