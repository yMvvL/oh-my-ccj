package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Approver;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadToolTest {

  @TempDir Path dir;

  @Test
  void returnsNumberedWindowFromOneBasedOffset() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= 50; i++) {
      text.append("line ").append(i).append('\n');
    }
    Files.writeString(dir.resolve("lines.txt"), text.toString());

    ToolResult result =
        new ReadTool()
            .execute("{\"path\":\"lines.txt\",\"offset\":10,\"limit\":3}", ToolContext.of(dir));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().startsWith("   10\tline 10\n"), result.content());
    assertTrue(result.content().contains("   12\tline 12\n"), result.content());
    assertFalse(result.content().contains("   13\tline 13"), result.content());
    assertTrue(result.content().contains("resume with offset=13"), result.content());
  }

  @Test
  void aLineLongerThanTheWholeBudgetIsCutInsteadOfBlockingThePage() throws Exception {
    // The bug: the line was skipped because it did not fit, so the answer was the same "resume with
    // offset=1" for it every time — a page the reader could never turn.
    Files.writeString(dir.resolve("wide.txt"), "中".repeat(2000) + "\nsecond\n");

    ToolResult first =
        new ReadTool()
            .execute("{\"path\":\"wide.txt\"}", new ToolContext(dir, Approver.ALWAYS, 1024));

    assertFalse(first.error(), first.content());
    assertTrue(first.content().contains("cut short"), first.content());
    assertTrue(first.content().contains("resume with offset=2"), first.content());
    assertFalse(
        first.content().contains("\uFFFD"),
        "a cut must land on a character boundary, never mid-codepoint");

    ToolResult second =
        new ReadTool()
            .execute(
                "{\"path\":\"wide.txt\",\"offset\":2}", new ToolContext(dir, Approver.ALWAYS, 1024));
    assertTrue(second.content().contains("second"), second.content());
  }

  @Test
  void reportsOffsetPastEndOfFile() throws Exception {
    Files.writeString(dir.resolve("short.txt"), "only\n");

    ToolResult result =
        new ReadTool().execute("{\"path\":\"short.txt\",\"offset\":9}", ToolContext.of(dir));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("past the end"), result.content());
  }

  @Test
  void rejectsNulBytesAndInvalidUtf8() throws Exception {
    Files.write(dir.resolve("nul.bin"), new byte[] {0x41, 0x00, 0x42});
    Files.write(dir.resolve("bad.bin"), new byte[] {(byte) 0xC3, 0x28, 0x41});

    ToolResult nul = new ReadTool().execute("{\"path\":\"nul.bin\"}", ToolContext.of(dir));
    ToolResult bad = new ReadTool().execute("{\"path\":\"bad.bin\"}", ToolContext.of(dir));

    assertTrue(nul.error(), nul.content());
    assertTrue(nul.content().contains("binary"), nul.content());
    assertTrue(bad.error(), bad.content());
    assertTrue(bad.content().contains("binary"), bad.content());
  }

  @Test
  void rejectsMissingFilesAndDirectories() throws Exception {
    Files.createDirectories(dir.resolve("sub"));

    ToolResult missing = new ReadTool().execute("{\"path\":\"nope.txt\"}", ToolContext.of(dir));
    ToolResult directory = new ReadTool().execute("{\"path\":\"sub\"}", ToolContext.of(dir));

    assertTrue(missing.error(), missing.content());
    assertTrue(missing.content().contains("file not found"), missing.content());
    assertTrue(directory.error(), directory.content());
    assertTrue(directory.content().contains("directory"), directory.content());
  }
}
