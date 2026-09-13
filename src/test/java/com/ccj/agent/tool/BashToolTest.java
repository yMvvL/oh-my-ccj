package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Approver;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BashToolTest {

  @TempDir Path dir;

  @Test
  void mergesStderrIntoStdoutAndReportsSuccess() throws Exception {
    ToolResult result =
        new BashTool()
            .execute(
                "{\"command\":\"echo out; echo err >&2\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().startsWith("exit code 0\n"), result.content());
    assertTrue(result.content().contains("out"), result.content());
    assertTrue(result.content().contains("err"), result.content());
  }

  @Test
  void nonZeroExitIsAnErrorWithTheSameContent() throws Exception {
    ToolResult result =
        new BashTool()
            .execute("{\"command\":\"echo boom; exit 3\"}", new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("exit code 3"), result.content());
    assertTrue(result.content().contains("boom"), result.content());
  }

  @Test
  void timeoutKillsTheProcessAndSaysSo() throws Exception {
    long started = System.nanoTime();
    ToolResult result =
        new BashTool()
            .execute(
                "{\"command\":\"sleep 30\",\"timeout_seconds\":1}",
                new ToolContext(dir, Approver.ALWAYS, 4096));
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("timed out"), result.content());
    assertTrue(elapsedMillis < 15_000, "timeout did not stop the process: " + elapsedMillis + "ms");
  }

  @Test
  void commandsThatReadStdinSeeTheEndOfItInsteadOfHanging() throws Exception {
    // The bug: the child's stdin pipe was never closed, so `cat` with no arguments waited for input
    // that could not come and the call only returned when the timeout killed it.
    long started = System.nanoTime();

    ToolResult result =
        new BashTool()
            .execute(
                "{\"command\":\"cat; echo done\",\"timeout_seconds\":10}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("done"), result.content());
    assertTrue(elapsedMillis < 5_000, "it must not wait for input: " + elapsedMillis + "ms");
  }

  @Test
  void aCharacterCutInHalfByTheOutputBudgetIsDroppedRatherThanMangled() throws Exception {
    // 59 bytes of head, a three-byte character, then enough tail to fill the budget: the character
    // straddles the head's cut, and half of it must not become a replacement glyph.
    String command = "printf 'a%.0s' {1..59}; printf '中'; printf 'b%.0s' {1..200}";

    ToolResult result =
        new BashTool()
            .execute(
                "{\"command\":\"" + command + "\"}",
                new ToolContext(dir, Approver.ALWAYS, 100));

    assertFalse(result.error(), result.content());
    assertFalse(result.content().contains("\uFFFD"), result.content());
    assertTrue(
        result.content().contains("a".repeat(59) + "\n... omitted"),
        "the head keeps every whole byte: " + result.content());
    assertTrue(result.content().endsWith("b".repeat(40)), result.content());
  }

  @Test
  void outputThatFitsIsDecodedAsOneBuffer() throws Exception {
    // Nothing is dropped, so the two buffers are contiguous: a character straddling their boundary
    // must survive intact instead of being lost between two decodes.
    String command = "printf 'a%.0s' {1..59}; printf '中'; printf 'b%.0s' {1..38}";

    ToolResult result =
        new BashTool()
            .execute(
                "{\"command\":\"" + command + "\"}",
                new ToolContext(dir, Approver.ALWAYS, 100));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("中"), result.content());
    assertFalse(result.content().contains("omitted"), result.content());
  }

  @Test
  void truncatesChattyOutputWithAnOmissionMarker() throws Exception {
    ToolResult result =
        new BashTool()
            .execute("{\"command\":\"seq 1 5000\"}", new ToolContext(dir, Approver.ALWAYS, 200));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().startsWith("exit code 0\n"), result.content());
    assertTrue(result.content().contains("... omitted "), result.content());
    assertTrue(result.content().contains(" bytes ..."), result.content());
    assertTrue(result.content().contains("1\n2\n"), result.content());
    assertTrue(result.content().endsWith("5000\n"), result.content());
    assertTrue(result.content().length() < 500, result.content());
  }

  @Test
  void survivesADegenerateTinyOutputLimit() throws Exception {
    ToolResult result =
        new BashTool()
            .execute("{\"command\":\"echo hello\"}", new ToolContext(dir, Approver.ALWAYS, 1));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("exit code 0"), result.content());
  }

  @Test
  void reportsMissingWorkingDirectory() throws Exception {
    ToolResult result =
        new BashTool()
            .execute(
                "{\"command\":\"echo hi\",\"cwd\":\"does/not/exist\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("not a directory"), result.content());
  }

  @Test
  void surfacesBashsOwnCommandNotFoundError() throws Exception {
    ToolResult result =
        new BashTool()
            .execute(
                "{\"command\":\"definitely-not-a-command-ccj\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("exit code 127"), result.content());
    assertTrue(result.content().contains("definitely-not-a-command-ccj"), result.content());
  }

  @Test
  void denialDoesNotRunTheCommand() throws Exception {
    ToolResult result =
        new BashTool()
            .execute("{\"command\":\"echo stamped > ran.txt\"}", new ToolContext(dir, Approver.NEVER, 4096));

    assertTrue(result.error(), result.content());
    assertFalse(java.nio.file.Files.exists(dir.resolve("ran.txt")));
  }
}
