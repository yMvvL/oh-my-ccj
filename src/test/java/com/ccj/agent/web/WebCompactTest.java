package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * What the page does when a compaction lands is browser code — it lives in {@code web/app.js}, a
 * classic script with no exports — so the two functions that matter are a node script
 * ({@code src/test/js/compaction.test.mjs}) that lifts them out of the shipped file and runs them
 * against a small stub, the same way {@link WebReplayTest} runs the replay cases.
 *
 * <p>Both bugs it pins down got past a full {@code mvn test} pass and a real end-to-end session driven
 * over HTTP, because curl cannot see a transcript:
 *
 * <ol>
 *   <li>{@code loadHistory} <em>appends</em>, since it is written for a session switch where the id
 *       changed and the pane was cleared for it. A compaction keeps the same id, so the kept exchanges
 *       were drawn a second time underneath the ones already on screen.
 *   <li>The summary was never drawn at all — {@code historyJson} had no branch for it and fell through
 *       to the "not part of a rendered conversation" default.
 * </ol>
 *
 * <p>The cases below are what a node cannot check on its own: that the server produces the event and
 * the page consumes it, by name, on both sides.
 */
class WebCompactTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "compaction.test.mjs");
  private static final Path APP = Path.of("src", "main", "resources", "web", "app.js");
  private static final Path HUB =
      Path.of("src", "main", "java", "com", "ccj", "agent", "web", "AgentHub.java");

  @Test
  void theCompactionScriptPasses() throws IOException, InterruptedException {
    WebSessionRowTest.runNodeCases(SCRIPT, "compaction");
  }

  @Test
  void theTranscriptIsClearedBeforeTheNewHistoryArrives() throws IOException {
    String script = source(APP);
    assertTrue(script != null, "app.js must be readable");

    int at = script.indexOf("async function onCompacted(");
    assertTrue(at >= 0, "the page handles the compacted event");
    // The clear has to come *before* the fetch: after it, the append has already happened and the
    // conversation on screen is doubled.
    int clear = script.indexOf("clearTranscript();", at);
    int load = script.indexOf("loadHistory(", at);
    assertTrue(clear > at && load > clear,
        "onCompacted must clear the transcript before it re-reads the history");
  }

  @Test
  void theSummaryEventIsRendered() throws IOException {
    String script = source(APP);
    assertTrue(script != null, "app.js must be readable");

    assertTrue(script.contains("case 'summary':"), "the page dispatches the summary event");
    assertTrue(script.contains("function appendSummary("), "and has a renderer for it");
    // The card says where the full conversation still is: that is what makes a summary that dropped a
    // detail recoverable, so it is not optional decoration.
    assertTrue(script.contains("read it back"),
        "the card tells the reader the detail can be read back from the file");
  }

  @Test
  void theServerSendsTheSummaryRatherThanDroppingIt() throws IOException {
    String hub = source(HUB);
    assertTrue(hub != null, "AgentHub.java must be readable");

    // The bug was here: `Message.Summary` fell through to the default branch whose comment says system
    // messages are not rendered content, so a page loaded after a compaction showed the kept exchanges
    // with no sign that a compaction had ever happened.
    int at = hub.indexOf("public ObjectNode historyJson()");
    assertTrue(at >= 0, "the history is built by one method");
    int end = hub.indexOf("private static ObjectNode replay(", at);
    String history = hub.substring(at, end > at ? end : hub.length());

    assertTrue(history.contains("case Message.Summary summary ->"),
        "historyJson must have a branch for a summary");
    assertTrue(history.contains("replay(\"summary\")"),
        "and send it as a summary event, which is what the page dispatches");
    assertTrue(history.contains(".put(\"covers\""),
        "carrying the count the card displays");
  }

  @Test
  void theCompactedEventIsStillAnnouncedToThePage() throws IOException {
    String hub = source(HUB);
    assertTrue(hub != null, "AgentHub.java must be readable");

    // The event is what tells the page to rebuild at all; the summary event alone only arrives on a
    // history fetch, which the event is what triggers.
    assertTrue(hub.contains("\"compacted\""), "the hub publishes a compacted event");
    assertTrue(hub.contains(".put(\"savedPercent\""),
        "and reports what it saved, which is the number the notice shows");
  }

  private static String source(Path path) {
    try {
      return Files.exists(path) ? Files.readString(path) : null;
    } catch (IOException err) {
      return null;
    }
  }
}
