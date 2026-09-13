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
 * The prompt budget: what a long conversation is projected onto before it is sent, and — more
 * importantly — what must survive that projection, because both wire formats validate the shape.
 */
class ContextBudgetTest {

  private static String blob(int chars) {
    return "x".repeat(chars);
  }

  /** One exchange: a user turn, an assistant that called a tool, and that tool's result. */
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
    // Tool results are the bulk of an agent's context and the least valuable part of it; eliding
    // their contents keeps the call/result pairing the APIs check while shrinking the prompt.
    List<Message> messages = new ArrayList<>();
    exchange(messages, "first", "call_1", 40_000);
    exchange(messages, "second", "call_2", 40_000);
    exchange(messages, "third", "call_3", 200);
    int budget = TokenEstimate.of(messages) - 15_000;

    ContextBudget.Result result = ContextBudget.apply(messages, budget);

    assertTrue(result.elidedResults() > 0, result.notice());
    assertEquals(0, result.droppedTurns(), "nothing had to be dropped: " + result.notice());
    assertEquals(messages.size(), result.messages().size());
    assertTrue(result.afterTokens() <= budget, result.afterTokens() + " > " + budget);
    Message.ToolResult firstResult = (Message.ToolResult) result.messages().get(2);
    assertTrue(firstResult.content().contains("elided"), firstResult.content());
    assertEquals("call_1", firstResult.toolCallId(), "the pairing survives elision");
    assertEquals("read", firstResult.toolName());
    assertFalse(((Message.ToolResult) result.messages().get(10)).content().contains("elided"),
        "the newest results are the ones being reasoned about");
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
        "the newest exchange is what the model is answering: " + kept);
    assertFalse(
        kept.stream().anyMatch(m -> m instanceof Message.User user && user.text().equals("prompt 0")),
        "the oldest exchange goes first");
  }

  @Test
  void aProjectionNeverLeavesACallWithoutItsResult() {
    // The invariant both wire formats validate. Half a pair is a rejected request, not a smaller one.
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
          calls, results, "at budget " + budget + " every call has its result and nothing more");
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
        last.content().contains("cut short"),
        "a model shown half a build log must be told it is half a log");
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
    assertTrue(notice.contains("tokens"), notice);
  }

  @Test
  void anExchangeWithMoreBigResultsThanOneCutCanDrainStillFits() {
    // A single turn can hold dozens of big results now that read-only calls overlap, and the budget
    // has to be met rather than approached: stopping after a fixed number of cuts would send an
    // oversize request — the one failure the budget exists to prevent.
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
    assertEquals(messages.size(), result.messages().size(), "only contents are cut, never messages");
    assertTrue(
        result.messages().stream()
            .anyMatch(m -> m instanceof Message.Assistant assistant && !assistant.toolCalls().isEmpty()),
        "the call group survives: a call without its results is a rejected request");
  }

  @Test
  void aRedactedThinkingBlockCountsItsPayload() {
    // A redacted block carries no text: `data` is the whole payload, and it goes back on the wire
    // exactly as it arrived. Counting only text and signature budgeted a long conversation as if the
    // blocks were empty.
    Message.Assistant withBlock =
        new Message.Assistant(
            "answer", List.of(), List.of(Message.Thinking.redacted(blob(40_000))));

    assertTrue(
        TokenEstimate.of(withBlock) > 5_000,
        "the payload is what gets sent: " + TokenEstimate.of(withBlock));
  }

  @Test
  void aBudgetNothingCanBeCutToFitIsReportedRatherThanHidden() {
    // Nothing here can be elided, dropped or cut: the exchange being answered is never dropped and
    // there is no tool result in it. Sending less is impossible, so the projection says what it is
    // instead of letting the caller believe the budget was met.
    List<Message> messages = new ArrayList<>();
    messages.add(new Message.User(blob(400_000)));

    ContextBudget.Result result = ContextBudget.apply(messages, 50_000);

    assertTrue(result.overBudget(), result.notice());
    assertFalse(result.trimmed(), "nothing was carried away: " + result.notice());
    assertTrue(result.afterTokens() > 50_000, "the estimate is over the budget: " + result.afterTokens());
    assertTrue(result.notice().contains("over the budget"), result.notice());
    assertEquals(messages, result.messages(), "the question is not the thing to cut");
  }

  @Test
  void tokensAreEstimatedByShapeNotByLength() {
    assertEquals(0, TokenEstimate.of(""));
    assertEquals(1, TokenEstimate.of("a"));

    int english = TokenEstimate.of(blob(400));
    assertTrue(english >= 90 && english <= 110, "400 ASCII characters is about 100 tokens: " + english);

    int chinese = TokenEstimate.of("中".repeat(200));
    assertTrue(
        chinese >= 180 && chinese <= 260, "200 ideographs are about 200 tokens: " + chinese);
    assertTrue(chinese > english, "the same character count costs far more in Chinese");

    int message = TokenEstimate.of(new Message.User("hello world"));
    assertTrue(message >= TokenEstimate.of("hello world"), "a message costs its content plus plumbing");
  }
}
