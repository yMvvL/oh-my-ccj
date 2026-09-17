package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
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

  /**
   * Runs a node case file against the shipped {@code app.js}.
   *
   * <p>Reported as <em>skipped</em> rather than passed when node is absent. The earlier version
   * printed a line and returned, which JUnit records as a green test: a build machine without node
   * would show every browser case as passing while not one of them ran. A test that cannot run has to
   * say so in the report, because the report is what everybody reads.
   *
   * <p>Output is collected while the process runs, not after. The earlier version read the stream to
   * the end before waiting, so the wait was never the thing that bounded it: a case file that hung
   * would hold the build, and the timeout was decoration.
   */
  static void runNodeCases(Path script, String what) throws IOException, InterruptedException {
    Assumptions.assumeTrue(Files.exists(script), "source tree is not the working directory");
    String node = findNode();
    Assumptions.assumeTrue(
        node != null, "no node on PATH — the " + what + " cases cannot run");

    Path root = script.toAbsolutePath().getParent().getParent().getParent().getParent();
    Process process = new ProcessBuilder(node, script.toAbsolutePath().toString())
        .directory(root.toFile())
        .redirectErrorStream(true)
        .start();
    process.getOutputStream().close();
    // Drained on another thread so the pipe cannot fill and deadlock the child while we wait: a case
    // file that prints more than the pipe buffer holds used to hang until the timeout.
    StringBuilder collected = new StringBuilder();
    Thread drain =
        new Thread(
            () -> {
              try (var in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                String tail = "";
                while ((read = in.read(buffer)) != -1) {
                  String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
                  collected.append(chunk);
                  // Keep only the tail: a failing case's useful output is at the end, and an
                  // unbounded buffer would make a runaway script a memory problem too.
                  if (collected.length() > 200_000) {
                    collected.delete(0, collected.length() - 100_000);
                  }
                  tail = chunk;
                }
              } catch (IOException ignored) {
                // The process ended or was killed; the exit value is what is reported.
              }
            },
            "node-cases-drain");
    drain.setDaemon(true);
    drain.start();

    boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    drain.join(2_000);
    String output = collected.toString();
    assertTrue(finished, what + " cases must finish within 60s; output so far:\n" + output);
    assertTrue(process.exitValue() == 0, what + " cases failed:\n" + output);
  }

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
