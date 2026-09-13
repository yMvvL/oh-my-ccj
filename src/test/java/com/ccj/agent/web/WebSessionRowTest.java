package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The sidebar's session row is browser code — it lives in {@code web/app.js}, a classic script with
 * no exports — so its cases are a node script ({@code src/test/js/session-row.test.mjs}) that lifts
 * {@code sessionRow} out of the shipped file and runs it against a small node stub, the same way
 * {@link WebMarkdownTest} runs the markdown cases.
 *
 * <p>What it pins down is the label on a row: a session is shown by the first thing it was asked,
 * not by its timestamp id, and the id is still reachable when two rows would otherwise read the
 * same. This class runs the script when a node is installed and always checks the property that is
 * about the page rather than the row: the server sends the title the row is built from.
 */
class WebSessionRowTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "session-row.test.mjs");
  private static final Path APP = Path.of("src", "main", "resources", "web", "app.js");

  @Test
  void theSessionRowScriptPasses() throws IOException, InterruptedException {
    runNodeCases(SCRIPT, "the session row");
  }

  /** Runs a node case file against the shipped {@code app.js}; prints and skips when node is absent. */
  static void runNodeCases(Path script, String what) throws IOException, InterruptedException {
    if (!Files.exists(script)) {
      return;   // the source tree is not the working directory; nothing to run
    }
    String node = findNode();
    if (node == null) {
      System.out.println("[node cases] no node on PATH — skipping " + what);
      return;
    }

    Path root = script.toAbsolutePath().getParent().getParent().getParent().getParent();
    Process process = new ProcessBuilder(node, script.toAbsolutePath().toString())
        .directory(root.toFile())
        .redirectErrorStream(true)
        .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
    }
    assertTrue(finished, what + " cases must finish");
    assertTrue(process.exitValue() == 0, what + " cases failed:\n" + output);
  }

  static String findNodeStatic() { return findNode(); }

  @Test
  void theRowIsBuiltFromTheTitleTheServerSends() {
    String script = appSource();
    if (script == null) { return; }

    // The row reads `title` (falling back to the shorter `preview` the CLI also
    // uses), and AgentHub puts both on the payload. If either half is renamed the
    // sidebar silently goes back to showing ids, which no server test would catch.
    assertTrue(script.contains("firstLine(str(item.title))"), "the row must read title");
    assertTrue(hubSource().contains("node.put(\"title\", summary.title())"),
        "the server must send title");
  }

  @Test
  void aFinishedTurnReReadsTheSessionOrder() {
    String script = appSource();
    if (script == null) { return; }

    // The server orders sessions by modification time, and a turn writes to the
    // active session's file — so the list the page is holding is stale the moment
    // a turn ends, and the conversation just used has to rise to the top. This is
    // the wiring for that: `done` asks for the order again.
    int done = script.indexOf("function onDone(ev) {");
    int next = script.indexOf("// ------------------------------------------------------------- dispatch");
    assertTrue(done > 0 && next > done, "onDone must be delimited");
    assertTrue(script.substring(done, next).contains("reorderSessionsAfterTurn()"),
        "a finished turn must re-read the order");
    assertTrue(script.contains("request('/api/sessions?workspace='"), script);
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

  private static String findNode() {
    for (String candidate : List.of("node", "nodejs")) {
      try {
        Process process = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
        if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
          return candidate;
        }
      } catch (IOException | InterruptedException err) {
        // try the next name
      }
    }
    return null;
  }
}
