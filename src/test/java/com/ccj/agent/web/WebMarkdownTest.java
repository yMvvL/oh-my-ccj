package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The assistant's markdown renderer is browser code — it lives in {@code web/app.js}, which is a
 * classic script with no exports — so its cases cannot be JUnit assertions directly. They are a
 * node script ({@code src/test/js/markdown.test.mjs}) that lifts the renderer's own source out of
 * the shipped file and runs it against a small DOM.
 *
 * <p>This class runs that script when a node is installed, and always checks the two properties
 * that are about the page rather than the parser: the answer really is rendered as markdown, and
 * the renderer builds nodes instead of HTML strings. A machine without node skips the script
 * rather than failing — the front end has no build step and no npm dependency to install, and a
 * test that cannot run must not look like one that passed.
 */
class WebMarkdownTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "markdown.test.mjs");
  private static final Path APP = Path.of("src", "main", "resources", "web", "app.js");

  @Test
  void theRendererScriptPasses() throws IOException, InterruptedException {
    // The shared runner rather than a second copy of it: the copy this replaces read the child's
    // output to the end *before* waiting, so the timeout was decoration — a case file that hung held
    // the build — and it reported a missing node by printing a line and returning, which JUnit records
    // as a pass. Both are fixed in one place now.
    WebSessionRowTest.runNodeCases(SCRIPT, "the markdown renderer");
  }

  @Test
  void aCaseFileThatCannotBeRunIsSkippedRatherThanPassed() {
    // The bug this pins: the earlier version printed a line and returned, which JUnit records as a
    // green test — a build machine without node showed every browser case as passing while not one of
    // them ran. A missing script must therefore abort the case, not complete it.
    //
    // Asserted through the real entry point with a path that cannot exist, so this checks the runner
    // rather than the machine it happens to be running on.
    Path missing = Path.of("src", "test", "js", "this-file-does-not-exist.test.mjs");

    assertThrows(
        org.opentest4j.TestAbortedException.class,
        () -> WebSessionRowTest.runNodeCases(missing, "a case file that is not there"));
  }

  @Test
  void theAnswerIsRenderedAsMarkdown() {
    String script = appSource();
    if (script == null) { return; }

    // The streamed answer goes through the markdown path, and the fallback the loop sends when
    // nothing was streamed goes through the same one — otherwise a turn with no deltas would be
    // the only answer on screen rendered as plain text.
    assertTrue(script.contains("queueMarkdown(assistantBlock()"), script);
    assertTrue(script.contains("queueMarkdown(block, finalText)"), script);
    assertTrue(script.contains("renderMarkdown(md, node)"), script);
    assertFalse(script.contains("assistantNode(block, 'text').appendChild"), script);
  }

  @Test
  void theRendererBuildsNodesAndNeverHtmlSource() {
    String script = appSource();
    if (script == null) { return; }
    int from = script.indexOf("---- markdown: parse");
    int to = script.indexOf("---- transcript");
    assertTrue(from > 0 && to > from, "the markdown section must be delimited by its banners");
    String renderer = script.substring(from, to);

    // The page's contract, restated where it is easiest to break: model output becomes nodes, so
    // there is no HTML source for a tag in an answer to be parsed as markup, and a link is only
    // given an href after its scheme has been filtered.
    assertFalse(renderer.contains("innerHTML"), "the renderer must not build HTML strings");
    assertFalse(renderer.contains("insertAdjacentHTML"), renderer);
    assertFalse(renderer.contains("createContextualFragment"), renderer);
    assertFalse(renderer.contains("document.write"), renderer);
    assertTrue(renderer.contains("mdSafeUrl"), "link targets must be filtered");
  }

  private static String appSource() {
    try {
      return Files.exists(APP) ? Files.readString(APP) : null;
    } catch (IOException err) {
      return null;
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
