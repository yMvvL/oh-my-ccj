package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Approver;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteToolTest {

  @TempDir Path dir;

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
            (title, detail) -> {
              approvals.add(title + "|" + detail);
              return true;
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
            (title, detail) -> {
              approvals.add(detail);
              return true;
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
}
