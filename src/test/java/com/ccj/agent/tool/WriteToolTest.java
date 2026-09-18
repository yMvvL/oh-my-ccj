package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.ApprovalAnswer;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteToolTest {

  @TempDir Path dir;

  @Test
  void aFileChangedWhileTheApprovalWaitedIsRefusedRatherThanOverwritten() throws Exception {
    // The gap this closes: `write` refused a file that *appeared* while the prompt was up, and wrote
    // over one that *changed*. Two conversations can write the same path, and so can the user's
    // editor — the diff in the prompt was computed against the file as it was, so writing over what
    // is there now discards a change nobody was shown.
    Path file = dir.resolve("shared.txt");
    Files.writeString(file, "mine\n");

    ToolResult result =
        new WriteTool()
            .execute(
                "{\"path\":\"shared.txt\",\"content\":\"from the agent\\n\"}",
                new ToolContext(
                    dir,
                    request -> {
                      // Somebody else — another conversation, the editor, a formatter — writes while
                      // this one waits for its answer.
                      try {
                        Files.writeString(file, "somebody else's work\n");
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                      return ApprovalAnswer.ALLOW_ONCE;
                    },
                    4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().startsWith("refused:"), result.content());
    assertTrue(result.content().contains("changed while this write was waiting"), result.content());
    assertEquals(
        "somebody else's work\n",
        Files.readString(file),
        "the other change is what survives, not this one");
  }

  @Test
  void aFileThatChangedOnlyInLengthIsStillRefused() throws Exception {
    // A size and a timestamp would miss this one; the hash is what makes the check trustworthy.
    Path file = dir.resolve("same-length.txt");
    Files.writeString(file, "AAAA\n");

    ToolResult result =
        new WriteTool()
            .execute(
                "{\"path\":\"same-length.txt\",\"content\":\"from the agent\\n\"}",
                new ToolContext(
                    dir,
                    request -> {
                      try {
                        Files.writeString(file, "BBBB\n");
                        Files.setLastModifiedTime(file, Files.getLastModifiedTime(file));
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                      return ApprovalAnswer.ALLOW_ONCE;
                    },
                    4096));

    assertTrue(result.error(), result.content());
    assertEquals("BBBB\n", Files.readString(file));
  }

  @Test
  void denialLeavesTheDiskUntouched() throws Exception {
    ToolContext ctx = new ToolContext(dir, Approver.NEVER, 4096);

    ToolResult result =
        new WriteTool().execute("{\"path\":\"nested/new.txt\",\"content\":\"hello\\n\"}", ctx);

    assertTrue(result.error(), result.content());
    assertEquals("rejected by user", result.content());
    assertFalse(Files.exists(dir.resolve("nested")), "denied write must not create directories");
    assertFalse(Files.exists(dir.resolve("nested/new.txt")));
  }

  @Test
  void writesThroughCreatedParentsAndReportsCreation() throws Exception {
    List<String> approvals = new ArrayList<>();
    ToolContext ctx =
        new ToolContext(
            dir,
            request -> {
              approvals.add(request.title() + "|" + request.detail());
              return ApprovalAnswer.ALLOW_ONCE;
            },
            4096);

    ToolResult result =
        new WriteTool().execute("{\"path\":\"a/b/new.txt\",\"content\":\"one\\ntwo\\n\"}", ctx);

    assertFalse(result.error(), result.content());
    assertEquals("one\ntwo\n", Files.readString(dir.resolve("a/b/new.txt")));
    assertTrue(result.content().contains("created new file"), result.content());
    assertEquals(1, approvals.size());
    assertTrue(approvals.get(0).startsWith("write|a/b/new.txt"), approvals.get(0));
    assertTrue(approvals.get(0).contains("creates a new file"), approvals.get(0));
  }

  @Test
  void overwriteApprovalCarriesADiffPreview() throws Exception {
    Files.writeString(dir.resolve("existing.txt"), "one\ntwo\nthree\n");
    List<String> approvals = new ArrayList<>();
    ToolContext ctx =
        new ToolContext(
            dir,
            request -> {
              approvals.add(request.detail());
              return ApprovalAnswer.ALLOW_ONCE;
            },
            4096);

    ToolResult result =
        new WriteTool()
            .execute("{\"path\":\"existing.txt\",\"content\":\"one\\nTWO\\nthree\\n\"}", ctx);

    assertFalse(result.error(), result.content());
    assertEquals("one\nTWO\nthree\n", Files.readString(dir.resolve("existing.txt")));
    assertTrue(result.content().contains("replaced existing file"), result.content());
    assertTrue(approvals.get(0).contains("replaces existing content"), approvals.get(0));
    assertTrue(approvals.get(0).contains("- two"), approvals.get(0));
    assertTrue(approvals.get(0).contains("+ TWO"), approvals.get(0));
  }

  @Test
  void aFileThatAppearsWhileTheApprovalWaitsIsNotOverwritten() throws Exception {
    // The prompt said "creates a new file" and the approver agreed to that. If a file appeared in the
    // meantime, writing now discards work the person who approved never saw — a different act from
    // the one they consented to.
    Path file = dir.resolve("appeared.txt");
    Approver creatingBehindOurBack =
        request -> {
          try {
            Files.writeString(file, "somebody else's work");
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          return ApprovalAnswer.ALLOW_ONCE;
        };

    ToolResult result =
        new WriteTool()
            .execute(
                "{\"path\":\"appeared.txt\",\"content\":\"mine\"}",
                new ToolContext(dir, creatingBehindOurBack, 4096));

    assertTrue(result.error(), "must be refused: " + result.content());
    assertTrue(result.content().contains("did not exist"), result.content());
    assertEquals("somebody else's work", Files.readString(file), "their file is intact");
  }

  @Test
  void overwritingAFileThatWasAlreadyThereIsStillAllowed() throws Exception {
    // A write is deliberately an overwrite, so the check above must not widen into "refuse any change
    // while waiting" — that would break the ordinary case of rewriting a file the agent just read.
    Path file = dir.resolve("existing.txt");
    Files.writeString(file, "old");

    ToolResult result =
        new WriteTool()
            .execute(
                "{\"path\":\"existing.txt\",\"content\":\"new\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertFalse(result.error(), result.content());
    assertEquals("new", Files.readString(file));
  }

  @Test
  void aWriteLeavesNoTemporaryFileBehind() throws Exception {
    new WriteTool()
        .execute(
            "{\"path\":\"solo.txt\",\"content\":\"x\"}",
            new ToolContext(dir, Approver.ALWAYS, 4096));

    try (var entries = Files.list(dir)) {
      assertEquals(
          List.of("solo.txt"),
          entries.map(p -> p.getFileName().toString()).sorted().toList(),
          "only the written file is left");
    }
  }
}
