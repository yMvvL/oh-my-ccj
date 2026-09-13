package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrepToolTest {

  @TempDir Path dir;

  @Test
  void findsRegexMatchesWithLineNumbers() throws Exception {
    Files.writeString(dir.resolve("app.log"), "foo1\nbar\nfoo22\n");

    ToolResult result =
        new GrepTool()
            .execute("{\"pattern\":\"foo\\\\d+\",\"path\":\"app.log\"}", ToolContext.of(dir));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("app.log:1:foo1"), result.content());
    assertTrue(result.content().contains("app.log:3:foo22"), result.content());
    assertFalse(result.content().contains("bar"), result.content());
  }

  @Test
  void honoursIgnoreCase() throws Exception {
    Files.writeString(dir.resolve("a.txt"), "Hello World\n");

    ToolResult insensitive =
        new GrepTool().execute("{\"pattern\":\"hello\",\"ignore_case\":true}", ToolContext.of(dir));
    ToolResult sensitive = new GrepTool().execute("{\"pattern\":\"hello\"}", ToolContext.of(dir));

    assertTrue(insensitive.content().contains("a.txt:1:Hello World"), insensitive.content());
    assertTrue(sensitive.content().contains("no matches"), sensitive.content());
  }

  @Test
  void reportsInvalidRegexWithItsReason() throws Exception {
    ToolResult result = new GrepTool().execute("{\"pattern\":\"[\"}", ToolContext.of(dir));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().startsWith("invalid regex"), result.content());
    assertTrue(result.content().contains("index"), result.content());
  }

  @Test
  void anUnreadableFileIsNamedInsteadOfLosingTheWholeSearch() throws Exception {
    Files.writeString(dir.resolve("readable.txt"), "needle here\n");
    Path locked = dir.resolve("locked.txt");
    Files.writeString(locked, "needle but unreadable\n");
    // A file the process cannot open: the search must still answer with what it did find.
    Files.setPosixFilePermissions(locked, java.util.Set.of());

    ToolResult result = new GrepTool().execute("{\"pattern\":\"needle\"}", ToolContext.of(dir));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("readable.txt:1"), result.content());
    assertTrue(result.content().contains("locked.txt"), result.content());
    assertTrue(result.content().contains("could not be read"), result.content());
  }

  @Test
  void aNulBytePastTheHeadProbeStillMakesTheFileBinary() throws Exception {
    // The cheap probe reads the first 8 KiB; a NUL further in is still not text, and quoting the
    // line around it would put raw bytes in the conversation.
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      text.append("filler line ").append(i).append(" needle\n");
    }
    text.append('\0').append("needle after the NUL\n");
    Files.writeString(dir.resolve("late-nul.bin"), text.toString());
    Files.writeString(dir.resolve("clean.txt"), "needle\n");

    ToolResult result = new GrepTool().execute("{\"pattern\":\"needle\"}", ToolContext.of(dir));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("clean.txt:1"), result.content());
    assertFalse(result.content().contains("late-nul.bin"), result.content());
  }

  @Test
  void skipsBinaryFilesAndCapsResults() throws Exception {
    Files.write(dir.resolve("bin.dat"), new byte[] {'m', 'a', 't', 'c', 'h', 0, 'h', 'i'});
    Files.writeString(dir.resolve("text.txt"), "match 1\nmatch 2\nmatch 3\n");

    ToolResult result =
        new GrepTool().execute("{\"pattern\":\"match\",\"max_results\":2}", ToolContext.of(dir));

    assertFalse(result.content().contains("bin.dat"), result.content());
    assertTrue(result.content().contains("text.txt:1:match 1"), result.content());
    assertTrue(result.content().contains("1 more matches omitted"), result.content());
  }

  @Test
  void globFilterExcludesFilesBeforeCounting() throws Exception {
    Files.writeString(dir.resolve("A.java"), "needle\n");
    Files.writeString(dir.resolve("B.txt"), "needle\n");

    ToolResult result =
        new GrepTool().execute("{\"pattern\":\"needle\",\"glob\":\"*.java\"}", ToolContext.of(dir));

    assertTrue(result.content().contains("A.java:1:needle"), result.content());
    assertFalse(result.content().contains("B.txt"), result.content());
    assertFalse(result.content().contains("omitted"), result.content());
  }
}
