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
    assertTrue(result.content().contains("用 offset=13 继续"), result.content());
  }

  @Test
  void aLineLongerThanTheWholeBudgetIsCutInsteadOfBlockingThePage() throws Exception {
    // 曾经的 bug：这行因为放不下而被跳过，于是每次给它的回答都是同一句「用 offset=1 继续」——
    // 一页读者永远翻不过去的页。
    Files.writeString(dir.resolve("wide.txt"), "中".repeat(2000) + "\nsecond\n");

    ToolResult first =
        new ReadTool()
            .execute("{\"path\":\"wide.txt\"}", new ToolContext(dir, Approver.ALWAYS, 1024));

    assertFalse(first.error(), first.content());
    assertTrue(first.content().contains("已被截短"), first.content());
    assertTrue(first.content().contains("用 offset=2 继续"), first.content());
    assertFalse(
        first.content().contains("\uFFFD"),
        "截断必须落在字符边界上，绝不能切在码点中间");

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
    assertTrue(result.content().contains("越过了"), result.content());
  }

  @Test
  void rejectsNulBytesAndInvalidUtf8() throws Exception {
    Files.write(dir.resolve("nul.bin"), new byte[] {0x41, 0x00, 0x42});
    Files.write(dir.resolve("bad.bin"), new byte[] {(byte) 0xC3, 0x28, 0x41});

    ToolResult nul = new ReadTool().execute("{\"path\":\"nul.bin\"}", ToolContext.of(dir));
    ToolResult bad = new ReadTool().execute("{\"path\":\"bad.bin\"}", ToolContext.of(dir));

    assertTrue(nul.error(), nul.content());
    assertTrue(nul.content().contains("二进制"), nul.content());
    assertTrue(bad.error(), bad.content());
    assertTrue(bad.content().contains("二进制"), bad.content());
  }

  @Test
  void rejectsMissingFilesAndDirectories() throws Exception {
    Files.createDirectories(dir.resolve("sub"));

    ToolResult missing = new ReadTool().execute("{\"path\":\"nope.txt\"}", ToolContext.of(dir));
    ToolResult directory = new ReadTool().execute("{\"path\":\"sub\"}", ToolContext.of(dir));

    assertTrue(missing.error(), missing.content());
    assertTrue(missing.content().contains("找不到文件"), missing.content());
    assertTrue(directory.error(), directory.content());
    assertTrue(directory.content().contains("是目录"), directory.content());
  }
}
