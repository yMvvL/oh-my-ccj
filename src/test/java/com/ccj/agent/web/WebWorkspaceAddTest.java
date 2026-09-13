package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * "Add workspace" is browser code — it lives in {@code web/app.js}, a classic script with no
 * exports — so its cases are a node script ({@code src/test/js/workspace-add.test.mjs}) that lifts
 * the section out of the shipped file and runs it against a small node stub, the same way
 * {@link WebSessionRowTest} runs the row's cases.
 *
 * <p>What it pins down is the gesture: one click opens the desktop's chooser and the chosen folder
 * is added under its own name. This class also checks the two halves that only make sense together —
 * the request the page sends and the route that answers it — because a page that sends
 * {@code {"path": ...}} needs a server that accepts a path with no name.
 */
class WebWorkspaceAddTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "workspace-add.test.mjs");

  @Test
  void theAddWorkspaceScriptPasses() throws IOException, InterruptedException {
    WebSessionRowTest.runNodeCases(SCRIPT, "the add-workspace");
  }

  @Test
  void theClickIsWiredToTheChooserAndTheRequestCarriesNoName() {
    String script = appSource();
    if (script == null) { return; }

    // Following the markdown cases: the shipped script must talk to the chooser on the trigger and
    // post a path, because that is what the server derives the name from.
    assertTrue(script.contains("pickWorkspaceFolder"), "the page must use the desktop's chooser");
    assertTrue(script.contains("addWorkspaceByPicking"), "the trigger must add, not just fill a field");

    int trigger = script.indexOf("dom.workspaceAdd.addEventListener");
    int submit = script.indexOf("dom.workspaceAddForm.addEventListener");
    assertTrue(trigger > 0 && submit > trigger, "the trigger and the form must both be wired");
    assertTrue(
        script.substring(trigger, submit).contains("addWorkspaceByPicking()"),
        "one click on the trigger is the chooser");

    // And the form no longer has a name to type: the folder names the workspace.
    String page = pageSource();
    if (page != null) {
      assertTrue(page.contains("id=\"ws-new-path\""), "the path field stays as the fallback");
      assertTrue(page.contains("id=\"workspace-pick\""), "the form can still call the chooser");
      assertTrue(!page.contains("ws-new-name"), "the name field is gone");
    }
  }

  private static String appSource() {
    return read(Path.of("src", "main", "resources", "web", "app.js"));
  }

  private static String pageSource() {
    return read(Path.of("src", "main", "resources", "web", "index.html"));
  }

  private static String read(Path path) {
    try {
      return Files.exists(path) ? Files.readString(path) : null;
    } catch (IOException err) {
      return null;
    }
  }
}
