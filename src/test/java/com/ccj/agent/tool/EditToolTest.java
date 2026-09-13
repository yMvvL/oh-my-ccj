package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Approver;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
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
        (title, text) -> {
          detail.append(text);
          return false;
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
}
