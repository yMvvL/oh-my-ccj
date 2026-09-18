package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.ApprovalAnswer;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.Checks;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The check that runs by itself after an edit, driven through the real `edit` and `write` tools.
 *
 * <p>What is under test is the moment the compiler gets to speak: in the same step as the edit, or
 * not at all. Everything else here — which command, how much output — exists to keep that moment
 * cheap enough to have.
 */
class PostEditCheckTest {

  @TempDir Path tmp;

  private Path config;
  private Path cwd;

  private void givenConfig(String json) throws IOException {
    config = tmp.resolve("config.json");
    Files.writeString(config, json);
    cwd = Files.createDirectories(tmp.resolve("project"));
  }

  private ToolContext context() {
    return new ToolContext(cwd, request -> ApprovalAnswer.ALLOW_ONCE, 32 * 1024);
  }

  private static String editArgs(String path, String from, String to) {
    ObjectNode args = Json.object();
    args.put("path", path);
    args.put("old_string", from);
    args.put("new_string", to);
    return Json.write(args);
  }

  private String edit(String path, String from, String to) throws Exception {
    return new EditTool(Checks.from(config)).execute(editArgs(path, from, to), context()).content();
  }

  @Test
  void aBrokenEditComesBackWithWhatTheCheckSaid() throws Exception {
    // The whole point: the model is told what it broke in the same step, so the fix is the next
    // thing it does rather than something it has to decide to go and look for.
    givenConfig(
        """
        {"checks": [{"glob": "**/*.java", "command": "echo 'Foo.java:3: error: cannot find symbol'; exit 1"}]}
        """);
    Path file = cwd.resolve("Foo.java");
    Files.writeString(file, "class Foo {}\n");

    String result = edit("Foo.java", "class Foo {}", "class Foo { int x = missing; }");

    assertTrue(result.startsWith("replaced 1 occurrence"), result);
    assertTrue(result.contains("[check] echo 'Foo.java:3: error: cannot find symbol'; exit 1"), result);
    assertTrue(result.contains("cannot find symbol"), result);
    assertTrue(result.contains("exit code 1"), result);
    assertTrue(result.contains("fix this before going on"), result);
  }

  @Test
  void aCheckThatPassesSaysSoInOneLine() throws Exception {
    givenConfig("{\"checks\": [{\"glob\": \"**/*.java\", \"command\": \"true\"}]}");
    Files.writeString(cwd.resolve("Foo.java"), "class Foo {}\n");

    String result = edit("Foo.java", "class Foo {}", "class Foo { int x; }");

    assertTrue(result.contains("[check] true — exit 0 ("), result);
    assertTrue(result.lines().count() <= 3, "a passing check is a line, not a log: " + result);
  }

  @Test
  void nothingRunsForAFileNoCheckIsAbout() throws Exception {
    givenConfig("{\"checks\": [{\"glob\": \"**/*.java\", \"command\": \"echo ran\"}]}");
    Files.writeString(cwd.resolve("notes.md"), "old\n");

    String result = edit("notes.md", "old", "new");

    assertFalse(result.contains("[check]"), result);
    assertFalse(result.contains("ran"), result);
  }

  @Test
  void aFailedEditStartsNothing() throws Exception {
    // The command runs after a write that happened. An edit that matched nothing changed nothing,
    // and building the project to be told about a change that was never made is work with no reader.
    givenConfig("{\"checks\": [{\"glob\": \"**\", \"command\": \"echo should-not-run > marker.txt\"}]}");
    Files.writeString(cwd.resolve("Foo.java"), "class Foo {}\n");

    String result = edit("Foo.java", "not in the file", "whatever");

    assertTrue(result.startsWith("nothing was written: the edit found no exact match"), result);
    assertFalse(Files.exists(cwd.resolve("marker.txt")), "the check ran on a failed edit");
  }

  @Test
  void writeRunsTheCheckToo() throws Exception {
    givenConfig("{\"checks\": [{\"glob\": \"**/*.txt\", \"command\": \"echo checked $PWD\"}]}");

    ObjectNode args = Json.object();
    args.put("path", "notes.txt");
    args.put("content", "hello\n");
    ToolResult result =
        new WriteTool(Checks.from(config)).execute(Json.write(args), context());

    assertTrue(result.content().contains("[check] echo checked $PWD — exit 0 ("), result.content());
  }

  @Test
  void theCheckRunsWhereTheSessionRunsAndNowhereElse() throws Exception {
    // The command is the user's, but the model chooses the path. A check must not become a way to
    // make the build run in somebody else's directory: the working directory is the session's.
    givenConfig("{\"checks\": [{\"glob\": \"**\", \"command\": \"pwd\"}]}");
    Path outside = Files.createDirectories(tmp.resolve("elsewhere"));
    Files.writeString(outside.resolve("Foo.java"), "class Foo {}\n");

    String result =
        new EditTool(Checks.from(config))
            .execute(editArgs(outside.resolve("Foo.java").toString(), "class Foo {}", "class Foo { }"), context())
            .content();

    assertFalse(result.contains("[check]"), "a file outside the session cwd runs nothing: " + result);
  }

  @Test
  void aCheckThatHangsIsKilledAtItsTimeout() throws Exception {
    givenConfig("{\"checks\": [{\"glob\": \"**\", \"command\": \"sleep 30\", \"timeoutSeconds\": 1}]}");
    Files.writeString(cwd.resolve("Foo.java"), "class Foo {}\n");

    long started = System.nanoTime();
    String result = edit("Foo.java", "class Foo {}", "class Foo { int x; }");
    long seconds = (System.nanoTime() - started) / 1_000_000_000L;

    assertTrue(result.contains("timed out after 1s"), result);
    assertTrue(seconds < 10, "the deadline has to bound the turn, and it took " + seconds + "s");
  }

  @Test
  void aMalformedChecksBlockIsSaidOutLoud() throws Exception {
    givenConfig("{\"checks\": \"mvn compile\"}");
    Files.writeString(cwd.resolve("Foo.java"), "class Foo {}\n");

    String result = edit("Foo.java", "class Foo {}", "class Foo { int x; }");

    assertTrue(result.contains("[check] the configured checks could not be read"), result);
    assertTrue(result.contains("checks"), result);
  }

  @Test
  void aChattyCheckIsReportedBounded() throws Exception {
    // The result is a prompt the user pays for on every later turn, so a build log is capped rather
    // than pasted: head, tail, and an exact count of what was left out.
    givenConfig(
        "{\"checks\": [{\"glob\": \"**\", \"command\": \"yes 'noise from the compiler' | head -2000; exit 1\"}]}");
    Files.writeString(cwd.resolve("Foo.java"), "class Foo {}\n");

    String result = edit("Foo.java", "class Foo {}", "class Foo { int x; }");

    assertTrue(result.contains("omitted"), result);
    assertTrue(result.length() < 20_000, "the report must stay small, was " + result.length());
  }

  @Test
  void withNothingConfiguredTheToolsBehaveExactlyAsBefore() throws Exception {
    givenConfig("{\"provider\": \"openai\"}");
    Files.writeString(cwd.resolve("Foo.java"), "class Foo {}\n");

    String result = edit("Foo.java", "class Foo {}", "class Foo { int x; }");

    assertEquals(
        "replaced 1 occurrence in Foo.java; file now has 1 lines",
        result,
        "no config file, no checks, no change to the result the model already knew how to read");
  }

  @Test
  void theEditToolStillWorksWithNoChecksAtAll() throws Exception {
    // Every existing caller constructs the tool with no arguments, and the default has to stay the
    // old behaviour rather than becoming an error.
    Files.writeString(tmp.resolve("Loose.java"), "class Loose {}\n");
    ToolContext direct = new ToolContext(tmp, Approver.ALWAYS, 4096);

    String result =
        new EditTool().execute(editArgs("Loose.java", "class Loose {}", "class Loose { }"), direct).content();

    assertTrue(result.startsWith("replaced 1 occurrence"), result);
  }
}
