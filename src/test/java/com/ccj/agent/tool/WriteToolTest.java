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
    // 这里补上的缺口：`write` 会拒绝一个在提示弹出期间*冒出来*的文件，却会覆盖一个*被改过*的
    // 文件。两个对话可以写同一条路径，用户的编辑器也可以——提示里的差异是按文件当时的样子算出来的，
    // 所以覆盖现在的内容会丢掉一个谁都没看到过的改动。
    Path file = dir.resolve("shared.txt");
    Files.writeString(file, "mine\n");

    ToolResult result =
        new WriteTool()
            .execute(
                "{\"path\":\"shared.txt\",\"content\":\"from the agent\\n\"}",
                new ToolContext(
                    dir,
                    request -> {
                      // 别人——另一个对话、编辑器、格式化器——在这一次等待答复期间写了这个文件。
                      try {
                        Files.writeString(file, "somebody else's work\n");
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                      return ApprovalAnswer.ALLOW_ONCE;
                    },
                    4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().startsWith("已拒绝："), result.content());
    assertTrue(result.content().contains("在这次写入等待审批期间被改动了"), result.content());
    assertEquals(
        "somebody else's work\n",
        Files.readString(file),
        "活下来的是别人的那处改动，不是这一处");
  }

  @Test
  void aFileThatChangedOnlyInLengthIsStillRefused() throws Exception {
    // 只看大小和时间戳会漏掉这一种；哈希才让这个检查可信。
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
    assertEquals("被用户拒绝", result.content());
    assertFalse(Files.exists(dir.resolve("nested")), "被拒绝的写入不能创建目录");
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
    assertTrue(result.content().contains("新建了文件"), result.content());
    assertEquals(1, approvals.size());
    assertTrue(approvals.get(0).startsWith("write|a/b/new.txt"), approvals.get(0));
    assertTrue(approvals.get(0).contains("新建文件"), approvals.get(0));
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
    assertTrue(result.content().contains("替换了已有文件"), result.content());
    assertTrue(approvals.get(0).contains("覆盖现有内容"), approvals.get(0));
    assertTrue(approvals.get(0).contains("- two"), approvals.get(0));
    assertTrue(approvals.get(0).contains("+ TWO"), approvals.get(0));
  }

  @Test
  void aFileThatAppearsWhileTheApprovalWaitsIsNotOverwritten() throws Exception {
    // 提示里说的是「新建文件」，批准者也同意了那件事。如果这期间冒出一个文件，现在写下去就会丢掉
    // 批准的人从未看到过的工作——那是与他们同意的那件事不同的另一个行为。
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

    assertTrue(result.error(), "必须被拒绝: " + result.content());
    assertTrue(result.content().contains("还不存在"), result.content());
    assertEquals("somebody else's work", Files.readString(file), "他们的文件完好无损");
  }

  @Test
  void overwritingAFileThatWasAlreadyThereIsStillAllowed() throws Exception {
    // 写入本来就是一次刻意的覆盖，所以上面那个检查不能扩张成「等待期间拒绝任何变化」——那会破坏
    // 「重写代理刚读过的文件」这种再普通不过的情况。
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
          "只剩被写入的那个文件");
    }
  }
}
