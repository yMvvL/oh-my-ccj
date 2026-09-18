package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.ApprovalAnswer;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditToolTest {

  @TempDir Path dir;

  @Test
  void replacesTheSingleOccurrence() throws Exception {
    Files.writeString(dir.resolve("f.txt"), "hello world\nsecond line\n");

    ToolResult result =
        new EditTool()
            .execute(
                "{\"path\":\"f.txt\",\"old_string\":\"world\",\"new_string\":\"there\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertFalse(result.error(), result.content());
    assertEquals("hello there\nsecond line\n", Files.readString(dir.resolve("f.txt")));
    assertTrue(result.content().contains("1 occurrence"), result.content());
    assertTrue(result.content().contains("2 lines"), result.content());
  }

  @Test
  void severalHunksAreOneApprovalAndOneWrite() throws Exception {
    // What a model fixing five places used to cost: five calls, five approvals, five writes, and four
    // chances for the file to change under an approval that had already been shown.
    Files.writeString(
        dir.resolve("f.java"),
        """
        class F {
          int a = 1;
          int b = 2;
          int c = 3;
        }
        """);
    List<String> approvals = new java.util.ArrayList<>();
    ToolContext ctx =
        new ToolContext(
            dir,
            request -> {
              approvals.add(request.detail());
              return ApprovalAnswer.ALLOW_ONCE;
            },
            4096);

    ToolResult result =
        new EditTool()
            .execute(
                """
                {"path":"f.java","edits":[
                  {"old_string":"int a = 1;","new_string":"int a = 10;"},
                  {"old_string":"int c = 3;","new_string":"int c = 30;"}]}
                """,
                ctx);

    assertFalse(result.error(), result.content());
    assertEquals(1, approvals.size(), "one approval for the whole change");
    assertEquals(
        """
        class F {
          int a = 10;
          int b = 2;
          int c = 30;
        }
        """,
        Files.readString(dir.resolve("f.java")));
    assertTrue(result.content().contains("2 occurrences across 2 hunks"), result.content());
    // The prompt shows the change as one diff of one file, not one diff per hunk.
    String detail = approvals.get(0);
    assertTrue(detail.contains("2 hunks, 2 replacements"), detail);
    // Whitespace collapsed: the diff marks lines with a sign and a space, and the file's own
    // indentation follows it — what the assertion is about is that both hunks are in the one diff.
    String flat = detail.replaceAll("\\s+", " ");
    assertTrue(flat.contains("- int a = 1;"), flat);
    assertTrue(flat.contains("+ int a = 10;"), flat);
    assertTrue(flat.contains("- int c = 3;"), flat);
    assertTrue(flat.contains("+ int c = 30;"), flat);
  }

  @Test
  void oneHunkThatDoesNotMatchWritesNoneOfThem() throws Exception {
    // All or nothing. A patch that half-applies leaves a file in a state nobody asked for and the
    // model's next edit is computed against text it never saw.
    Files.writeString(dir.resolve("f.txt"), "one\ntwo\nthree\n");
    List<String> approvals = new java.util.ArrayList<>();
    ToolContext ctx =
        new ToolContext(
            dir,
            request -> {
              approvals.add(request.detail());
              return ApprovalAnswer.ALLOW_ONCE;
            },
            4096);

    ToolResult result =
        new EditTool()
            .execute(
                """
                {"path":"f.txt","edits":[
                  {"old_string":"one","new_string":"1"},
                  {"old_string":"two","new_string":"2"},
                  {"old_string":"threee","new_string":"3"}]}
                """,
                ctx);

    assertTrue(result.error(), result.content());
    assertTrue(result.content().startsWith("nothing was written:"), result.content());
    assertTrue(result.content().contains("hunk 3 of 3"), result.content());
    assertTrue(result.content().contains("no exact match"), result.content());
    assertEquals("one\ntwo\nthree\n", Files.readString(dir.resolve("f.txt")), "not one hunk applied");
    assertEquals(0, approvals.size(), "nothing was asked for either: there was nothing to approve");
  }

  @Test
  void aFailedHunkComesBackWithTheLinesAroundWhereItWasExpected() throws Exception {
    // The failure a model has to recover from most often, and the information it needs is in this
    // call's hands already: which line it thought it was on, and what is actually there.
    Files.writeString(
        dir.resolve("f.txt"),
        "alpha\nbeta\ngamma delta\nepsilon\nzeta\n");

    ToolResult result =
        new EditTool()
            .execute(
                "{\"path\":\"f.txt\",\"old_string\":\"gamma   delta\",\"new_string\":\"G\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("region(s) match once whitespace is normalised"),
        result.content());
    assertTrue(result.content().contains("around line 3"), result.content());
    assertTrue(result.content().contains(">    3  gamma delta"),
        "the line itself, marked, with its number: " + result.content());
    assertTrue(result.content().contains("     2  beta"), "and its neighbours: " + result.content());
  }

  @Test
  void overlappingHunksAreRefusedRatherThanGuessedAt() throws Exception {
    Files.writeString(dir.resolve("f.txt"), "alpha\nbeta\ngamma\n");

    ToolResult result =
        new EditTool()
            .execute(
                """
                {"path":"f.txt","edits":[
                  {"old_string":"alpha\\nbeta","new_string":"A"},
                  {"old_string":"beta\\ngamma","new_string":"B"}]}
                """,
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("hunks 1 and 2 overlap"), result.content());
    assertEquals("alpha\nbeta\ngamma\n", Files.readString(dir.resolve("f.txt")));
  }

  @Test
  void hunkIndexesAreNamedSoARetryKnowsWhichOne() throws Exception {
    Files.writeString(dir.resolve("f.txt"), "alpha\nalpha\ngamma\n");

    ToolResult result =
        new EditTool()
            .execute(
                """
                {"path":"f.txt","edits":[
                  {"old_string":"alpha","new_string":"A"},
                  {"old_string":"gamma","new_string":"G"}]}
                """,
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("hunk 1 of 2"), result.content());
    assertTrue(result.content().contains("matches 2 times"), result.content());
    // And the second hunk's replace_all is not needed for the first: they are separate decisions.
    ToolResult fixed =
        new EditTool()
            .execute(
                """
                {"path":"f.txt","edits":[
                  {"old_string":"alpha","new_string":"A","replace_all":true},
                  {"old_string":"gamma","new_string":"G"}]}
                """,
                new ToolContext(dir, Approver.ALWAYS, 4096));
    assertFalse(fixed.error(), fixed.content());
    assertEquals("A\nA\nG\n", Files.readString(dir.resolve("f.txt")));
  }

  @Test
  void bothShapesAtOnceIsARefusalRatherThanAGuess() throws Exception {
    Files.writeString(dir.resolve("f.txt"), "alpha\n");

    ToolResult result =
        new EditTool()
            .execute(
                """
                {"path":"f.txt","old_string":"alpha","new_string":"A","edits":[
                  {"old_string":"alpha","new_string":"A"}]}
                """,
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("not both"), result.content());
    assertEquals("alpha\n", Files.readString(dir.resolve("f.txt")));
  }

  @Test
  void refusesAmbiguousMatchWithoutReplaceAll() throws Exception {
    Files.writeString(dir.resolve("f.txt"), "alpha\nbeta\nalpha\n");

    ToolResult result =
        new EditTool()
            .execute(
                "{\"path\":\"f.txt\",\"old_string\":\"alpha\",\"new_string\":\"gamma\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("2 times"), result.content());
    assertTrue(result.content().contains("replace_all"), result.content());
    assertEquals("alpha\nbeta\nalpha\n", Files.readString(dir.resolve("f.txt")));
  }

  @Test
  void replaceAllChangesEveryOccurrence() throws Exception {
    Files.writeString(dir.resolve("f.txt"), "alpha\nbeta\nalpha\n");

    ToolResult result =
        new EditTool()
            .execute(
                "{\"path\":\"f.txt\",\"old_string\":\"alpha\",\"new_string\":\"gamma\",\"replace_all\":true}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertFalse(result.error(), result.content());
    assertEquals("gamma\nbeta\ngamma\n", Files.readString(dir.resolve("f.txt")));
    assertTrue(result.content().contains("2 occurrences"), result.content());
  }

  @Test
  void absentMatchReportsTheWhitespaceNormalisedCount() throws Exception {
    Files.writeString(dir.resolve("f.txt"), "if (x) {\n    return y;\n}\n");

    ToolResult result =
        new EditTool()
            .execute(
                "{\"path\":\"f.txt\",\"old_string\":\"if (x) { return y; }\",\"new_string\":\"z\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("no exact match"), result.content());
    assertTrue(result.content().contains("1 region(s) match"), result.content());
  }

  @Test
  void absentMatchSaysSoWhenNothingIsClose() throws Exception {
    Files.writeString(dir.resolve("f.txt"), "if (x) {\n}\n");

    ToolResult result =
        new EditTool()
            .execute(
                "{\"path\":\"f.txt\",\"old_string\":\"while (q) {}\",\"new_string\":\"z\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("no region matches"), result.content());
  }

  @Test
  void rejectsEmptyOldString() throws Exception {
    Files.writeString(dir.resolve("f.txt"), "content\n");

    ToolResult result =
        new EditTool()
            .execute(
                "{\"path\":\"f.txt\",\"old_string\":\"\",\"new_string\":\"z\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("must not be empty"), result.content());
    assertEquals("content\n", Files.readString(dir.resolve("f.txt")));
  }

  @Test
  void aTrailingNewlineChangeIsNotReportedAsNoChange() throws Exception {
    // The line diff cannot see a trailing newline, and answering "(no change)" for a write that
    // does rewrite the file is the one thing an approval preview must never say.
    Files.writeString(dir.resolve("f.txt"), "a\nb");
    StringBuilder detail = new StringBuilder();
    Approver capture =
        request -> {
          detail.append(request.detail());
          return ApprovalAnswer.DENY;
        };

    new EditTool()
        .execute(
            "{\"path\":\"f.txt\",\"old_string\":\"b\",\"new_string\":\"b\\n\"}",
            new ToolContext(dir, capture, 4096));

    assertTrue(detail.toString().contains("newline"), detail.toString());
    assertFalse(detail.toString().contains("(no change)"), detail.toString());
  }

  @Test
  void denialKeepsTheOriginalContent() throws Exception {
    Files.writeString(dir.resolve("f.txt"), "keep me\n");

    ToolResult result =
        new EditTool()
            .execute(
                "{\"path\":\"f.txt\",\"old_string\":\"keep\",\"new_string\":\"drop\"}",
                new ToolContext(dir, Approver.NEVER, 4096));

    assertTrue(result.error(), result.content());
    assertEquals("rejected by user", result.content());
    assertEquals("keep me\n", Files.readString(dir.resolve("f.txt")));
  }

  @Test
  void aFileThatChangesWhileTheApprovalWaitsIsNotOverwritten() throws Exception {
    // The approval is a window in which somebody else can change the file — the user in their editor,
    // another conversation's turn, a formatter on save. The ranges were computed against the text the
    // diff showed, so applying them to whatever is on disk now would write back a version that
    // predates the other change: their edit gone, with a prompt whose diff nobody could tell was
    // stale.
    Path file = dir.resolve("a.txt");
    Files.writeString(file, "one\ntwo\nthree\n");
    // The approver changes the file while "thinking", which is exactly what another writer does.
    Approver editingBehindOurBack =
        request -> {
          try {
            Files.writeString(file, "one\nTWO CHANGED BY SOMEBODY ELSE\nthree\n");
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          return ApprovalAnswer.ALLOW_ONCE;
        };

    ToolResult result =
        new EditTool()
            .execute(
                "{\"path\":\"a.txt\",\"old_string\":\"two\",\"new_string\":\"TWO\"}",
                new ToolContext(dir, editingBehindOurBack, 4096));

    assertTrue(result.error(), "must be refused: " + result.content());
    assertTrue(result.content().contains("changed while"), result.content());
    assertEquals(
        "one\nTWO CHANGED BY SOMEBODY ELSE\nthree\n",
        Files.readString(file),
        "the other change survives");
  }

  @Test
  void anEditAppliesWhenNothingChangedWhileItWaited() throws Exception {
    // The check must not refuse the ordinary case, which is a file nobody touched.
    Path file = dir.resolve("b.txt");
    Files.writeString(file, "one\ntwo\nthree\n");

    ToolResult result =
        new EditTool()
            .execute(
                "{\"path\":\"b.txt\",\"old_string\":\"two\",\"new_string\":\"TWO\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertFalse(result.error(), result.content());
    assertEquals("one\nTWO\nthree\n", Files.readString(file));
  }

  @Test
  void anEditLeavesNoTemporaryFileBehind() throws Exception {
    // The write goes through a sibling and a rename. A leftover temp file in the user's directory
    // would be the visible price of the atomicity, and it must not be paid.
    Path file = dir.resolve("c.txt");
    Files.writeString(file, "hello\n");

    new EditTool()
        .execute(
            "{\"path\":\"c.txt\",\"old_string\":\"hello\",\"new_string\":\"goodbye\"}",
            new ToolContext(dir, Approver.ALWAYS, 4096));

    try (var entries = Files.list(dir)) {
      assertEquals(
          List.of("c.txt"),
          entries.map(p -> p.getFileName().toString()).sorted().toList(),
          "only the edited file is left");
    }
  }
}
