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
    assertTrue(result.content().contains("替换了 1 处"), result.content());
    assertTrue(result.content().contains("2 行"), result.content());
  }

  @Test
  void severalHunksAreOneApprovalAndOneWrite() throws Exception {
    // 模型一次修五个地方过去要付出什么：五次调用、五次审批、五次写入，以及四次让文件在一个已经展示过
    // 的审批之下被改动的机会。
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
    assertEquals(1, approvals.size(), "整次改动只审批一次");
    assertEquals(
        """
        class F {
          int a = 10;
          int b = 2;
          int c = 30;
        }
        """,
        Files.readString(dir.resolve("f.java")));
    assertTrue(result.content().contains("替换了 2 处（跨 2 个块）"), result.content());
    // 提示把这次改动展示为一个文件的一份差异，而不是每个块一份差异。
    String detail = approvals.get(0);
    assertTrue(detail.contains("2 个块，2 处替换"), detail);
    // 折叠空白之后：差异用符号加一个空格标记行，文件自身的缩进跟在它后面——这个断言要说的是两个块都在
    // 同一份差异里。
    String flat = detail.replaceAll("\\s+", " ");
    assertTrue(flat.contains("- int a = 1;"), flat);
    assertTrue(flat.contains("+ int a = 10;"), flat);
    assertTrue(flat.contains("- int c = 3;"), flat);
    assertTrue(flat.contains("+ int c = 30;"), flat);
  }

  @Test
  void oneHunkThatDoesNotMatchWritesNoneOfThem() throws Exception {
    // 全有或全无。半途生效的补丁会把文件留在一个没人要求过的状态里，而模型的下一次编辑会以它从未
    // 见过的文本为基准来计算。
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
    assertTrue(result.content().startsWith("未写入任何内容："), result.content());
    assertTrue(result.content().contains("第 3/3 个块"), result.content());
    assertTrue(result.content().contains("未找到精确匹配"), result.content());
    assertEquals("one\ntwo\nthree\n", Files.readString(dir.resolve("f.txt")), "一个块都没应用");
    assertEquals(0, approvals.size(), "也没请求任何审批：本来就没有可批准的东西");
  }

  @Test
  void aFailedHunkComesBackWithTheLinesAroundWhereItWasExpected() throws Exception {
    // 模型最常需要从中恢复的那种失败，而它需要的信息这次调用手里本来就有：它以为自己在哪一行，以及
    // 那里实际是什么。
    Files.writeString(
        dir.resolve("f.txt"),
        "alpha\nbeta\ngamma delta\nepsilon\nzeta\n");

    ToolResult result =
        new EditTool()
            .execute(
                "{\"path\":\"f.txt\",\"old_string\":\"gamma   delta\",\"new_string\":\"G\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("折叠空白后有 1 个区域匹配"),
        result.content());
    assertTrue(result.content().contains("第 3 行左右"), result.content());
    assertTrue(result.content().contains(">    3  gamma delta"),
        "那一行本身，带标记和行号: " + result.content());
    assertTrue(result.content().contains("     2  beta"), "以及它的邻居: " + result.content());
  }

  @Test
  void hunksAreAppliedByPositionRatherThanByTheOrderTheyWereWritten() throws Exception {
    // 实测：先写靠下那处改动的模型产出了 `class Notes implint a = 2;` `neable {`——两个块都匹配上了，
    // 而按它们到达的顺序应用，挪动了后一个块据以计算的偏移。按位置排序是唯一正确的顺序。
    Files.writeString(dir.resolve("f.java"), "class Notes {\n  int a = 1;\n}\n");

    ToolResult result =
        new EditTool()
            .execute(
                "{\"path\":\"f.java\",\"edits\":["
                    + "{\"old_string\":\"int a = 1;\",\"new_string\":\"int a = 2;\"},"
                    + "{\"old_string\":\"class Notes {\",\"new_string\":\"class Notes implements Cloneable {\"}]}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertFalse(result.error(), result.content());
    assertEquals(
        "class Notes implements Cloneable {\n  int a = 2;\n}\n",
        Files.readString(dir.resolve("f.java")));
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
    assertTrue(result.content().contains("第 1 个和第 2 个块在它们匹配的文本上重叠"), result.content());
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
    assertTrue(result.content().contains("第 1/2 个块"), result.content());
    assertTrue(result.content().contains("匹配 2 次"), result.content());
    // 而且前一个块用不上后一个块的 replace_all：它们是各自独立的决定。
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
    assertTrue(result.content().contains("两者不要同时传"), result.content());
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
    assertTrue(result.content().contains("匹配 2 次"), result.content());
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
    assertTrue(result.content().contains("替换了 2 处"), result.content());
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
    assertTrue(result.content().contains("未找到精确匹配"), result.content());
    assertTrue(result.content().contains("折叠空白后有 1 个区域匹配"), result.content());
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
    assertTrue(result.content().contains("没有任何区域匹配"), result.content());
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
    assertTrue(result.content().contains("old_string 不能为空"), result.content());
    assertEquals("content\n", Files.readString(dir.resolve("f.txt")));
  }

  @Test
  void aTrailingNewlineChangeIsNotReportedAsNoChange() throws Exception {
    // 行级差异看不到末尾的换行，而对一次确实重写了文件的写入回答「（无变化）」，是审批预览绝不能说的
    // 唯一一件事。
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

    assertTrue(detail.toString().contains("换行"), detail.toString());
    assertFalse(detail.toString().contains("（无变化）"), detail.toString());
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
    assertEquals("被用户拒绝", result.content());
    assertEquals("keep me\n", Files.readString(dir.resolve("f.txt")));
  }

  @Test
  void aFileThatChangesWhileTheApprovalWaitsIsNotOverwritten() throws Exception {
    // 审批是一扇窗口，别人可能在这期间改动该文件——用户在编辑器里、另一个对话的回合、保存时运行的
    // 格式化器。区间是按差异所展示的文本算出来的，把它们套用到磁盘上现在的内容，会写回一个早于那次
    // 改动的版本：别人的编辑没了，而提示里的差异谁也说不出它已经过期。
    Path file = dir.resolve("a.txt");
    Files.writeString(file, "one\ntwo\nthree\n");
    // 批准者在「思考」期间改动这个文件，这正是一个别的写者会做的事。
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

    assertTrue(result.error(), "必须被拒绝: " + result.content());
    assertTrue(result.content().contains("等待审批期间被改动了"), result.content());
    assertEquals(
        "one\nTWO CHANGED BY SOMEBODY ELSE\nthree\n",
        Files.readString(file),
        "活下来的是别人的那处改动");
  }

  @Test
  void anEditAppliesWhenNothingChangedWhileItWaited() throws Exception {
    // 这个检查不能拒绝再普通不过的情况，也就是没人碰过的文件。
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
    // 写入经由旁边一个文件加重命名完成。用户目录里残留的临时文件会是这份原子性看得见的代价，而这个
    // 代价不能付。
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
          "只剩被编辑的那个文件");
    }
  }
}
