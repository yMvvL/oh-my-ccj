package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * An outstanding approval is browser code — it lives in {@code web/app.js}, a classic script with no
 * exports — so its cases are a node script ({@code src/test/js/approval.test.mjs}) that lifts the two
 * functions that own it out of the shipped file and runs them against a small node stub, the same way
 * {@link WebSessionRowTest} runs the session-row cases.
 *
 * <p>What it pins down is the bug this exists for: a turn waiting for approval lost its prompt as soon
 * as the user looked at another conversation, leaving abort as the only way out. The request is
 * blocked in memory on the server rather than written to the conversation, so a replay cannot restore
 * it — the status carries it and the page draws it again. This class runs the script when a node is
 * installed, and always checks the two ends of that contract in the shipped sources: the server
 * reports an outstanding request with everything the prompt needs, and the page reads that field.
 */
class WebApprovalTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "approval.test.mjs");
  private static final Path APP = Path.of("src", "main", "resources", "web", "app.js");

  @Test
  void theApprovalScriptPasses() throws IOException, InterruptedException {
    WebSessionRowTest.runNodeCases(SCRIPT, "outstanding approvals");
  }

  @Test
  void theStatusCarriesEverythingAPromptNeeds() throws IOException {
    String hub = hubSource();

    assertTrue(
        hub.contains("private record Pending("),
        "an approval has to remember what it is asking about, not only the future it waits on");
    assertTrue(
        hub.contains("pending.title()") && hub.contains("pending.detail()"),
        "and the status reports it, or a page that looks away cannot draw the prompt again:\n" + hub);
    assertTrue(
        hub.contains("node.putArray(\"approvals\")"),
        "under a field of its own, so one conversation's request is not offered to another");
    // Only the conversation that is waiting sees its own requests.
    assertTrue(
        hub.contains("if (!pending.sessionId().equals(shownId))"),
        "filtered by session: answering a prompt on screen must never resolve another's");
  }

  @Test
  void thePageRebuildsAPromptFromTheStatus() throws IOException {
    String script = appSource();
    assertTrue(script != null, "app.js must be readable");

    assertTrue(script.contains("function renderApproval("),
        "the prompt is drawn by one function, so the live event and the status rebuild it the same way");
    assertTrue(script.contains("function syncApprovals("),
        "and a status brings back what is still outstanding");
    assertTrue(script.contains("syncApprovals(status.approvals)"),
        "which is what the page calls with what the server reported");
    // A prompt drawn into a transcript that is about to be rebuilt is the very loss this fixes, so
    // the rebuild waits for the replay to finish.
    assertTrue(script.contains("state.pendingApprovalsSync"),
        "a request reported mid-replay is held until the transcript stops being rebuilt");
    assertTrue(script.contains("No longer waiting"),
        "and a request the server no longer knows about is closed rather than left unanswerable");
  }

  private static String appSource() {
    try {
      return Files.exists(APP) ? Files.readString(APP) : null;
    } catch (IOException err) {
      return null;
    }
  }

  private static String hubSource() {
    Path hub = Path.of("src", "main", "java", "com", "ccj", "agent", "web", "AgentHub.java");
    try {
      return Files.exists(hub) ? Files.readString(hub) : "";
    } catch (IOException err) {
      return "";
    }
  }
}
