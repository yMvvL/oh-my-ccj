package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 子代理交回来的报告，以及其中唯一被机器读取的那部分。
 *
 * <p>文件清单让主代理无需打开任何东西就能决定要提升什么。这比看上去更重要：如果它必须逐个读
 * 文件才能判断，那么子代理本应省下的上下文，反倒会花在这次判断上 —— 这个特性就会自我抵消。所以
 * 解析在两个方向上都被钉住：提示词要求的形状，以及模型半照着做时实际写出的形状。
 *
 * <p>这一切之下的规则是：报告永远不会被拒绝。一次花了真金白银的 token、回来时给出三个标题而不是
 * 四个的运行，活还是干了；为了格式就把它的答案扔掉，是代价高昂的那种正确。
 */
class SubAgentReportTest {

  @Test
  void theAskleShapeIsReadBackExactly() {
    String text =
        """
        STATUS: done
        SUMMARY: Found the parser.
        FILES:
          src/Parser.java  final  the file that does the parsing
          brute.cpp  disposable  kept for comparison only
        FINDINGS:
        The null check is outside the try block, at src/Parser.java:142.
        """;

    SubAgentReport report = SubAgentReport.parse(text);

    assertEquals("done", report.status());
    assertEquals("Found the parser.", report.summary());
    assertTrue(report.findings().contains("Parser.java:142"), report.findings());
    assertEquals(2, report.artifacts().size(), report.artifacts().toString());

    assertEquals("src/Parser.java", report.artifacts().get(0).path());
    assertFalse(report.artifacts().get(0).disposable(), "第一个是完成的工作");
    assertEquals("the file that does the parsing", report.artifacts().get(0).note());

    assertEquals("brute.cpp", report.artifacts().get(1).path());
    assertTrue(report.artifacts().get(1).disposable(), "第二个是中间产物");

    // 调用方真正据以行动的那个划分。
    assertEquals(1, report.keepable().size());
    assertEquals("src/Parser.java", report.keepable().get(0).path());
    assertEquals(1, report.disposable().size());
    assertEquals("brute.cpp", report.disposable().get(0).path());
  }

  @Test
  void markdownHeadingsAndSynonymsAreAccepted() {
    // 被告知使用标题的模型，会采用两种惯例中的一种，很少两者都用。两种都接受只需几行代码，却能
    // 救回一次运行。
    String text =
        """
        ## Status
        blocked
        ## Summary
        Could not reach the second module.
        ## Artifacts
        - out/report.md final notes
        ## Results
        module B has no tests at all
        """;

    SubAgentReport report = SubAgentReport.parse(text);

    assertEquals("blocked", report.status());
    assertEquals("Could not reach the second module.", report.summary());
    assertEquals(1, report.artifacts().size());
    assertEquals("out/report.md", report.artifacts().get(0).path());
    assertTrue(report.findings().contains("module B"), report.findings());
  }

  @Test
  void aFileWithNoMarkIsTreatedAsFinishedWork() {
    // 这种不对称是有意的：把一个其实是中间产物的文件提升，比删掉一个其实就是答案的文件，错误更小。
    String text =
        """
        STATUS: done
        FILES:
          solution.py
        FINDINGS:
        written
        """;

    SubAgentReport report = SubAgentReport.parse(text);

    assertEquals(1, report.artifacts().size());
    assertFalse(report.artifacts().get(0).disposable(), "含糊不清时选择保留，而不是丢弃");
    assertEquals(1, report.keepable().size());
  }

  @Test
  void theDisposableMarkIsFoundWhereverItAppearsInTheLine() {
    // 模型没有仔细读格式时，写出来的就是 "intermediate" 和 "temp"。
    SubAgentReport a = SubAgentReport.parse("FILES:\n  gen.py  temporary generator\n");
    SubAgentReport b = SubAgentReport.parse("FILES:\n  gen.py  intermediate, kept for the run\n");
    SubAgentReport c = SubAgentReport.parse("FILES:\n  gen.py\n");

    assertTrue(a.artifacts().get(0).disposable(), "temporary");
    assertTrue(b.artifacts().get(0).disposable(), "intermediate");
    assertFalse(c.artifacts().get(0).disposable(), "什么都没说，那就保留");
  }

  @Test
  void theMarkIsStrippedFromTheNote() {
    // 把 "final" 留在描述里，读起来会像是对该文件的一句断言，而不是它本来就已是的那面旗标。
    SubAgentReport report = SubAgentReport.parse("FILES:\n  a.py  final  the solution\n");

    assertEquals("the solution", report.artifacts().get(0).note());
  }

  @Test
  void proseWithNoHeadingsIsStillAnAnswer() {
    // 一个无视格式的模型也照样产出了发现。那正是主代理需要的，而一个抛异常的解析器会把一次有用的
    // 运行变成一次丢失的运行。
    SubAgentReport report =
        SubAgentReport.parse("The bug is in Parser.java line 142.\nThe null check is misplaced.");

    assertTrue(report.findings().contains("Parser.java line 142"), report.findings());
    assertEquals("The bug is in Parser.java line 142.", report.summary(), "首行作为摘要");
    assertFalse(report.status().isEmpty(), "回来了的报告，就是一次跑完了的运行");
    assertTrue(report.artifacts().isEmpty());
  }

  @Test
  void anEmptyReportIsAFailureNotASuccess() {
    // 什么都没有，就是一次什么都没产出的运行：把它报告成 "done"，会让主代理基于一个并不存在的
    // 答案继续往下走。
    SubAgentReport report = SubAgentReport.parse("");

    assertEquals("failed", report.status());
  }

  @Test
  void aLongReportIsCutAndSaysSo() {
    // 子代理的存在，就是为了不让一段对话被另一段对话的阅读塞满；一份啰嗦的报告，是同一个失败从
    // 另一扇门进来。标记就是那份诚实：读着部分答案的主代理，必须知道它只是部分。
    String findings = "detail ".repeat(SubAgentReport.LIMIT_CHARS);
    SubAgentReport report = SubAgentReport.parse("STATUS: done\nFINDINGS:\n" + findings);

    String rendered = report.render("/tmp/work");

    assertTrue(rendered.length() <= SubAgentReport.LIMIT_CHARS + 200, "有界：" + rendered.length());
    assertTrue(rendered.contains("已截短"), "并且说明这份报告不完整");
  }

  @Test
  void theRenderedReportNamesWhereTheFilesAre() {
    // 没有工作区路径，文件清单里的那些路径对主代理毫无意义：它得去猜从哪里提升。
    SubAgentReport report =
        SubAgentReport.parse("STATUS: done\nFILES:\n  std.cpp  final  the solution\nFINDINGS:\nok");

    String rendered = report.render("/home/me/proj/.ccj-work/r2");

    assertTrue(rendered.contains("/home/me/proj/.ccj-work/r2"), rendered);
    assertTrue(rendered.contains("std.cpp"), rendered);
    assertTrue(rendered.contains("[final]"), "这个标记对读者是可见的：" + rendered);
    assertTrue(rendered.startsWith("STATUS: done"), rendered);
  }

  @Test
  void theRenderedReportSaysWhatTheRunSpent() {
    // 报告是模型读的，所以这一行和 STATUS、FILES 一样是光秃标签加短字段；它留在标题块里，因为被截断的应该
    // 是发现，不是账单。
    SubAgentReport report =
        SubAgentReport.parse(
                "STATUS: done\nSUMMARY: Found the parser."
                    + "\nFINDINGS:\nThe bug is at Parser.java:142.")
            .withUsage(new UsageTotals(120, 30, 100, 1, 2, 0, 0, 0, true));

    String rendered = report.render("/tmp/work");

    assertTrue(rendered.contains("USAGE: 120 in / 30 out / 100 cached / 2 model turns"), rendered);
    assertTrue(
        rendered.indexOf("USAGE:") < rendered.indexOf("Parser.java:142"),
        "它留在标题块里，在发现之前：" + rendered);
  }

  @Test
  void aPinnedModelIsNamedInTheSpendingLine() {
    SubAgentReport pinned =
        SubAgentReport.parse("STATUS: done\nFINDINGS:\nfound it")
            .withUsage(new UsageTotals(10, 2, 0, 1, 1, 0, 0, 0, true))
            .withModel("cheap-model");
    // 空白与缺席同义：没人点名的运行，不该因为一个空串被点上一个名字。
    SubAgentReport unnamed =
        SubAgentReport.parse("STATUS: done\nFINDINGS:\nfound it")
            .withUsage(new UsageTotals(10, 2, 0, 1, 1, 0, 0, 0, true))
            .withModel("  ");

    assertTrue(
        pinned.render("/tmp/work").contains(", model cheap-model"), pinned.render("/tmp/work"));
    assertFalse(unnamed.render("/tmp/work").contains(", model"), unnamed.render("/tmp/work"));
  }

  @Test
  void aRunWithNoReportedNumbersGetsNoSpendingLine() {
    // 没有数字就没有账：凭空写一行零比不写更糟。任务文本本身算一个用户回合，所以「不空」并不等于「花过
    // 钱」——这一行问的是后者。
    SubAgentReport spentNothing =
        SubAgentReport.parse("STATUS: done\nFINDINGS:\nok")
            .withUsage(new UsageTotals(0, 0, 0, 1, 0, 0, 0, 0, false));
    SubAgentReport neverRan = SubAgentReport.failed("被停止了");

    assertFalse(
        spentNothing.render("/tmp/work").contains("USAGE:"), spentNothing.render("/tmp/work"));
    assertFalse(neverRan.render("/tmp/work").contains("USAGE:"), neverRan.render("/tmp/work"));
  }

  @Test
  void aFailedRunCarriesItsReason() {
    SubAgentReport report = SubAgentReport.failed("the sub-agent ran past its 10 minute deadline");

    assertEquals("failed", report.status());
    assertTrue(report.summary().contains("deadline"), report.summary());
    assertTrue(report.render("/tmp").contains("failed"), report.render("/tmp"));
  }

  @Test
  void aCarriageReturnDoesNotHideAHeading() {
    // 一个带着 Windows 式习惯的模型，或者一个会重写行尾的中转服务。
    SubAgentReport report = SubAgentReport.parse("STATUS: done\r\nFINDINGS:\r\nfound it\r\n");

    assertEquals("done", report.status());
    assertTrue(report.findings().contains("found it"), report.findings());
  }
}
