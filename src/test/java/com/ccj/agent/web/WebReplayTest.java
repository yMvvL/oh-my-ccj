package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Replaying a long conversation is browser code — it lives in {@code web/app.js}, a classic script
 * with no exports — so the function that splits a history into what is shown now and what is filled
 * in later is a node script ({@code src/test/js/replay.test.mjs}) that lifts it out of the shipped
 * file and runs it against a small node stub, the same way {@link WebSessionRowTest} runs the
 * session-row cases.
 *
 * <p>What it pins down is the reason the split exists: switching back to a long conversation used to
 * rebuild every tool card at once, blocking the thread for long enough that a turn running behind the
 * switch looked frozen. This class runs the script when a node is installed, and always checks the
 * properties of the shipped sources that make the two passes correct: the cut lands on a boundary the
 * renderer already treats as one, the earlier part is drawn off-screen and inserted above the reader,
 * and the reader's scroll position is held while it is.
 */
class WebReplayTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "replay.test.mjs");
  private static final Path APP = Path.of("src", "main", "resources", "web", "app.js");

  @Test
  void theReplayScriptPasses() throws IOException, InterruptedException {
    WebSessionRowTest.runNodeCases(SCRIPT, "long-conversation replay");
  }

  @Test
  void theEarlierPartIsDrawnInIdleTimeAndInsertedAbove() throws IOException {
    String script = appSource();
    assertTrue(script != null, "app.js must be readable");

    assertTrue(script.contains("function splitReplay("),
        "the history is split by one function, so its boundary rule can be tested");
    assertTrue(script.contains("function loadOlderReplay("),
        "and the earlier part has its own loader");
    assertTrue(script.contains("requestIdleCallback"),
        "which runs when the browser is idle: a turn streaming behind the switch keeps painting");
    assertTrue(script.contains("document.createDocumentFragment()"),
        "and builds the exchange off-screen before attaching it, so nothing is half-drawn");
    // The reader's place is held by the height that was added above them; without this the text
    // being read slides down the screen every time a chunk lands.
    assertTrue(script.contains("dom.transcript.scrollTop = before + grew"),
        "and the scroll position is corrected by the height added above it");
  }

  @Test
  void aSupersededReplayStops() throws IOException {
    String script = appSource();
    assertTrue(script != null, "app.js must be readable");

    // Switching again while the earlier part is still loading must not append the old conversation
    // into the new transcript.
    assertTrue(script.contains("if (seq !== replaySeq) { return; }"),
        "a superseded replay stops rather than writing into a transcript that is gone");
  }

  @Test
  void theBudgetIsBoundedAndModest() throws IOException {
    String script = appSource();
    assertTrue(script != null, "app.js must be readable");

    int at = script.indexOf("REPLAY_TAIL_EVENTS = ");
    assertTrue(at >= 0, "the first pass has a named budget");
    String rest = script.substring(at + "REPLAY_TAIL_EVENTS = ".length());
    int limit = Integer.parseInt(rest.substring(0, rest.indexOf(';')).strip());
    assertTrue(limit > 0 && limit <= 1000,
        "the first pass must be cheap: " + limit + " events is not a bounded draw");
    // A conversation shorter than the budget is rendered in one pass, which is the common case and
    // must not change at all.
    assertTrue(script.contains("if (clean.length <= REPLAY_TAIL_EVENTS)"),
        "and a history that fits the budget is left alone");
  }

  private static String appSource() {
    try {
      return Files.exists(APP) ? Files.readString(APP) : null;
    } catch (IOException err) {
      return null;
    }
  }
}
