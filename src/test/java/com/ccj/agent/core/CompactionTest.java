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
 * Compaction: which part of a conversation a summary replaces, and what it is told about the rest.
 *
 * <p>The rule that matters most is the one it shares with {@link ContextBudget} and
 * {@link SessionRepair} — a tool result never loses the assistant turn that asked for it, because both
 * wire formats reject that shape — plus the one that is its own: the tail a model is about to act on is
 * quoted, not compressed.
 */
class CompactionTest {

  /**
   * {@code exchanges} user turns, each with an answer and (for the first ones) a tool round.
   *
   * <p>Messages carry realistic bulk: a compaction is a trade between a summary and the text it
   * replaces, and a conversation of two-word messages has nothing to trade. The filler makes each
   * exchange cost what a real file read and a real answer cost.
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
   * A summary of a plausible length — long enough to be useful, short enough to be a saving.
   *
   * <p>Already stripped, because {@code apply} strips what the model wrote: a constant with a trailing
   * space would make every {@code contains} assertion below fail against text that does contain it.
   */
  private static final String SUMMARY =
      "goal: do the thing. files: f0. open: nothing. ".repeat(10).strip();

  @Test
  void theNewestExchangesAreKeptVerbatimAndTheRestIsSummarised() {
    List<Message> before = conversation(8, false);

    Compaction.Result result = Compaction.apply(before, SUMMARY, "/sessions/s.jsonl", null);

    // 8 exchanges of two messages; the newest 5 exchanges are kept, so 3 exchanges — 6 messages — go.
    assertEquals(6, result.summarised(), "the three oldest exchanges are replaced");
    assertEquals(10, result.kept(), "five exchanges of two messages each stay");
    assertEquals(before.size(), result.summarised() + result.kept(), "nothing is lost or invented");

    Message.Summary head = (Message.Summary) result.messages().get(0);
    assertTrue(head.text().contains(SUMMARY), head.text());
    assertEquals(6, head.covers(), "the count is messages, which is what the transcript reports");
    assertEquals("/sessions/s.jsonl", head.source());

    // The tail is the original messages, unchanged and in order — quoted, never rewritten.
    assertEquals(
        before.subList(6, before.size()),
        result.messages().subList(1, result.messages().size()),
        "the kept part must be the original messages themselves");
  }

  @Test
  void aCutNeverSeparatesAToolCallFromItsResult() {
    // Six exchanges with tool rounds. Whichever way the count lands, the tail has to start at a user
    // message: a tool_result whose assistant turn was summarised away is a request both APIs reject.
    List<Message> before = conversation(6, true);

    Compaction.Result result = Compaction.apply(before, SUMMARY, "s.jsonl", null);

    assertTrue(result.messages().get(1) instanceof Message.User, result.messages().get(1).toString());
    // And every kept call still has its result, immediately after it — the shape both wire formats
    // validate, checked on the projection rather than on the rule that produced it.
    List<Message> kept = result.messages();
    for (int i = 1; i < kept.size(); i++) {
      if (kept.get(i) instanceof Message.ToolResult result0) {
        assertTrue(
            kept.get(i - 1) instanceof Message.Assistant assistant
                && assistant.toolCalls().stream().anyMatch(call -> call.id().equals(result0.toolCallId())),
            "a tool result at " + i + " answers no call right before it: " + kept);
      }
    }
    long calls = kept.stream().filter(m -> m instanceof Message.Assistant a && a.hasToolCalls()).count();
    long results = kept.stream().filter(m -> m instanceof Message.ToolResult).count();
    assertEquals(calls, results, "every kept call has its result: " + kept);
  }

  @Test
  void aConversationWithNothingToCutCannotBeCompacted() {
    // Fewer exchanges than the tail keeps: compacting would replace nothing with a summary and lose
    // the detail for no saving at all.
    assertFalse(Compaction.possible(conversation(Compaction.KEEP_EXCHANGES, false)));
    assertFalse(Compaction.possible(List.of()));
    assertEquals(0, Compaction.cutPoint(conversation(Compaction.KEEP_EXCHANGES, false)));
    assertThrows(
        IllegalArgumentException.class,
        () -> Compaction.apply(conversation(2, false), SUMMARY, "src", null));
  }

  @Test
  void compactingTwiceDoesNotCompressAnEarlierCompression() {
    // The second pass must treat the first summary as the start of an exchange, so it summarises the
    // work that came after it rather than feeding its own output back through itself.
    List<Message> first = Compaction.apply(conversation(8, false), SUMMARY, "s.jsonl", null).messages();

    assertTrue(Compaction.possible(first));
    Compaction.Result second = Compaction.apply(conversation(6, false), SUMMARY, "s.jsonl", null);
    assertTrue(second.summarised() > 0);

    int cut = Compaction.cutPoint(first);
    assertTrue(cut > 0);
    assertTrue(
        first.get(cut) instanceof Message.User || first.get(cut) instanceof Message.Summary,
        first.get(cut).toString());

    // The earlier summary is part of what gets summarised again (it is old work now), but it is inside
    // the transcript the model reads rather than silently dropped — which is what keeps a second pass
    // from losing what the first one preserved.
    String transcript = Compaction.transcript(first);
    assertTrue(transcript.contains(SUMMARY), transcript);
    assertTrue(transcript.contains("summary of still earlier work"), transcript);
  }

  @Test
  void aSummaryThatSavesNothingIsRefusedRatherThanWritten() {
    // The failure this exists for: a conversation whose early exchanges were mostly tool-call plumbing
    // summarises into something *longer* than the text it removed — measured, on a real session, at
    // 4239 → 4236 tokens. A compaction that frees nothing is a cost with no benefit, so the result is
    // refused and the caller can say the conversation has not grown enough yet.
    List<Message> thin = conversation(8, false);
    String enormous = "detail ".repeat(4000);

    Compaction.NotWorthIt refused =
        assertThrows(
            Compaction.NotWorthIt.class, () -> Compaction.apply(thin, enormous, "s.jsonl", null));

    assertTrue(refused.summaryTokens() >= refused.replacedTokens(), refused.getMessage());
    assertTrue(refused.getMessage().contains("would not free anything"), refused.getMessage());
  }

  @Test
  void aSummaryThatSavesSomethingReportsHowMuch() {
    List<Message> before = conversation(8, false);

    Compaction.Result result = Compaction.apply(before, SUMMARY, "s.jsonl", null);

    assertTrue(result.savedPercent() > 0, "a useful summary saves a measurable amount");
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
    // The summary is marked as a summary, so a model does not read it as its own earlier words.
    assertTrue(head.text().contains("written by the model when the conversation was compacted"), head.text());
  }

  @Test
  void aCompactionShrinksWhatTheNextRequestCarries() {
    // Sized like a real session rather than a toy one: the point of the feature is that the *next*
    // turn is cheaper, and a summary carries a preamble of its own, so a conversation of two-word
    // messages would save nothing and prove nothing.
    List<Message> before = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      before.add(new Message.User("question " + i + " " + "context ".repeat(200)));
      before.add(Message.Assistant.text("answer " + i + " " + "detail ".repeat(200)));
    }

    Compaction.Result result =
        Compaction.apply(before, "a summary of the earlier work, of a plausible length ".repeat(20), "x", null);

    assertTrue(
        TokenEstimate.of(result.messages()) < TokenEstimate.of(before),
        "a compaction must leave a smaller request: "
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
    // Only the part being replaced: the tail is not in what is sent to be summarised.
    assertFalse(transcript.contains("question 7"), transcript);
  }
}
