package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 提示词预算：一段长对话在发出之前会被投影成什么，以及 —— 更重要的是 —— 什么必须在这次投影中
 * 存活下来，因为两种线上格式都会校验形状。
 */
class ContextBudgetTest {

  private static String blob(int chars) {
    return "x".repeat(chars);
  }

  /** 一个回合：一个用户回合、一个调用了工具的 assistant，以及那个工具的结果。 */
  private static void exchange(List<Message> messages, String prompt, String callId, int resultChars) {
    messages.add(new Message.User(prompt));
    messages.add(
        new Message.Assistant("", List.of(new Message.ToolCall(callId, "read", "{\"path\":\"f\"}"))));
    messages.add(new Message.ToolResult(callId, "read", blob(resultChars), false));
    messages.add(new Message.Assistant("done", List.of()));
  }

  @Test
  void aConversationThatFitsItsBudgetIsNotTouched() {
    List<Message> messages = new ArrayList<>();
    exchange(messages, "first", "call_1", 100);
    exchange(messages, "second", "call_2", 100);

    ContextBudget.Result result = ContextBudget.apply(messages, 100_000);

    assertEquals(messages, result.messages());
    assertFalse(result.trimmed());
    assertEquals(result.beforeTokens(), result.afterTokens());
  }

  @Test
  void noBudgetMeansNoTrimming() {
    List<Message> messages = new ArrayList<>();
    exchange(messages, "first", "call_1", 50_000);

    ContextBudget.Result result = ContextBudget.apply(messages, 0);

    assertEquals(messages, result.messages());
    assertFalse(result.trimmed());
  }

  @Test
  void oldToolOutputIsElidedBeforeAnythingIsDropped() {
    // 工具结果是代理上下文的大头，也是其中价值最低的部分；把它们的正文省略掉，既能保住 API 所
    // 校验的调用/结果配对，又能缩小提示词。
    List<Message> messages = new ArrayList<>();
    exchange(messages, "first", "call_1", 40_000);
    exchange(messages, "second", "call_2", 40_000);
    exchange(messages, "third", "call_3", 200);
    int budget = TokenEstimate.of(messages) - 15_000;

    ContextBudget.Result result = ContextBudget.apply(messages, budget);

    assertTrue(result.elidedResults() > 0, result.notice());
    assertEquals(0, result.droppedTurns(), "没有任何东西需要被丢弃：" + result.notice());
    assertEquals(messages.size(), result.messages().size());
    assertTrue(result.afterTokens() <= budget, result.afterTokens() + " > " + budget);
    Message.ToolResult firstResult = (Message.ToolResult) result.messages().get(2);
    assertTrue(firstResult.content().contains("已略去"), firstResult.content());
    assertEquals("call_1", firstResult.toolCallId(), "省略之后配对仍然完好");
    assertEquals("read", firstResult.toolName());
    assertFalse(((Message.ToolResult) result.messages().get(10)).content().contains("已略去"),
        "最新的那些结果才是正在被推理的内容");
  }

  @Test
  void wholeExchangesAreDroppedOldestFirst() {
    List<Message> messages = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      exchange(messages, "prompt " + i, "call_" + i, 40_000);
    }

    ContextBudget.Result result = ContextBudget.apply(messages, 4_000);

    assertTrue(result.droppedTurns() > 0, result.notice());
    assertTrue(result.afterTokens() <= 4_000, result.afterTokens() + " > 4000");
    List<Message> kept = result.messages();
    assertTrue(
        kept.stream().anyMatch(m -> m instanceof Message.User user && user.text().equals("prompt 5")),
        "最新的那个回合才是模型正在回答的：" + kept);
    assertFalse(
        kept.stream().anyMatch(m -> m instanceof Message.User user && user.text().equals("prompt 0")),
        "最旧的回合最先离开");
  }

  @Test
  void aProjectionNeverLeavesACallWithoutItsResult() {
    // 两种线上格式都会校验的不变量。半对不是更小的请求，而是被拒绝的请求。
    List<Message> messages = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      exchange(messages, "prompt " + i, "call_" + i, 30_000);
    }

    for (int budget : new int[] {500, 2_000, 8_000, 20_000, 60_000}) {
      List<Message> projected = ContextBudget.apply(messages, budget).messages();
      Set<String> calls = new HashSet<>();
      Set<String> results = new HashSet<>();
      for (Message message : projected) {
        if (message instanceof Message.Assistant assistant) {
          assistant.toolCalls().forEach(call -> calls.add(call.id()));
        }
        if (message instanceof Message.ToolResult result) {
          results.add(result.toolCallId());
        }
      }
      assertEquals(
          calls, results, "在预算 " + budget + " 下每个调用都有其结果，且没有多余的");
    }
  }

  @Test
  void theCurrentExchangeSurvivesEvenWhenItAloneIsTooBig() {
    List<Message> messages = new ArrayList<>();
    exchange(messages, "old", "call_old", 20_000);
    messages.add(new Message.User("now"));
    messages.add(new Message.Assistant("", List.of(new Message.ToolCall("call_now", "bash", "{}"))));
    messages.add(new Message.ToolResult("call_now", "bash", blob(200_000), false));

    ContextBudget.Result result = ContextBudget.apply(messages, 1_000);

    assertTrue(result.truncated(), result.notice());
    assertNotNull(result.notice());
    List<Message> kept = result.messages();
    assertTrue(kept.stream().anyMatch(m -> m instanceof Message.User user && user.text().equals("now")));
    Message.ToolResult last = (Message.ToolResult) kept.get(kept.size() - 1);
    assertEquals("call_now", last.toolCallId());
    assertTrue(
        last.content().contains("已截短"),
        "给模型看半份构建日志，就必须告诉它这只是半份日志");
    assertTrue(result.afterTokens() < result.beforeTokens());
  }

  @Test
  void theNoticeSaysWhatHappenedOrNothingAtAll() {
    List<Message> messages = new ArrayList<>();
    exchange(messages, "first", "call_1", 40_000);
    exchange(messages, "second", "call_2", 40_000);

    assertFalse(ContextBudget.apply(messages, 100_000).notice() != null);

    ContextBudget.Result trimmed = ContextBudget.apply(messages, 3_000);
    String notice = trimmed.notice();
    assertNotNull(notice);
    assertTrue(notice.contains("token"), notice);
  }

  @Test
  void anExchangeWithMoreBigResultsThanOneCutCanDrainStillFits() {
    // 只读调用可以重叠之后，单个回合就能装下几十个大结果，而预算必须被真正满足、而不是仅仅接近：
    // 剪固定次数就收手会发出一个超限请求 —— 那正是预算存在的意义所要防止的失败。
    List<Message> messages = new ArrayList<>();
    messages.add(new Message.User("read all of them"));
    List<Message.ToolCall> calls = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      calls.add(new Message.ToolCall("call_" + i, "read", "{\"path\":\"f" + i + "\"}"));
    }
    messages.add(new Message.Assistant("", calls));
    for (Message.ToolCall call : calls) {
      messages.add(new Message.ToolResult(call.id(), call.name(), blob(20_000), false));
    }
    int budget = 100_000;

    ContextBudget.Result result = ContextBudget.apply(messages, budget);

    assertTrue(result.afterTokens() <= budget, result.afterTokens() + " > " + budget);
    assertFalse(result.overBudget(), result.notice());
    assertTrue(result.truncated(), result.notice());
    assertEquals(messages.size(), result.messages().size(), "只裁剪内容，从不删消息");
    assertTrue(
        result.messages().stream()
            .anyMatch(m -> m instanceof Message.Assistant assistant && !assistant.toolCalls().isEmpty()),
        "调用组存活下来：一个没有结果的调用就是被拒绝的请求");
  }

  @Test
  void aRedactedThinkingBlockCountsItsPayload() {
    // 被遮蔽的块不含文本：`data` 就是整个载荷，它会原样回到线上。只统计文本和签名，会把一段
    // 长对话当成这些块是空的一样来计预算。
    Message.Assistant withBlock =
        new Message.Assistant(
            "answer", List.of(), List.of(Message.Thinking.redacted(blob(40_000))));

    assertTrue(
        TokenEstimate.of(withBlock) > 5_000,
        "载荷才是会被发出去的东西：" + TokenEstimate.of(withBlock));
  }

  @Test
  void aBudgetNothingCanBeCutToFitIsReportedRatherThanHidden() {
    // 这里没有任何东西可省略、可丢弃、可裁剪：正在被回答的那个回合永远不会被丢掉，而它里面也
    // 没有工具结果。少发一些是不可能的，所以这次投影会说明自己到底是什么，而不是让调用方以为
    // 预算已经满足。
    List<Message> messages = new ArrayList<>();
    messages.add(new Message.User(blob(400_000)));

    ContextBudget.Result result = ContextBudget.apply(messages, 50_000);

    assertTrue(result.overBudget(), result.notice());
    assertFalse(result.trimmed(), "没有任何东西被拿走：" + result.notice());
    assertTrue(result.afterTokens() > 50_000, "估算值超出了预算：" + result.afterTokens());
    assertTrue(result.notice().contains("仍然超出预算"), result.notice());
    assertEquals(messages, result.messages(), "提问本身不是该剪的东西");
  }

  @Test
  void tokensAreEstimatedByShapeNotByLength() {
    assertEquals(0, TokenEstimate.of(""));
    assertEquals(1, TokenEstimate.of("a"));

    int english = TokenEstimate.of(blob(400));
    assertTrue(english >= 90 && english <= 110, "400 个 ASCII 字符大约 100 token：" + english);

    int chinese = TokenEstimate.of("中".repeat(200));
    assertTrue(
        chinese >= 180 && chinese <= 260, "200 个汉字大约 200 token：" + chinese);
    assertTrue(chinese > english, "同样字符数，中文的花费高得多");

    int message = TokenEstimate.of(new Message.User("hello world"));
    assertTrue(message >= TokenEstimate.of("hello world"), "一条消息的花费是它的内容加上管道开销");
  }
}
