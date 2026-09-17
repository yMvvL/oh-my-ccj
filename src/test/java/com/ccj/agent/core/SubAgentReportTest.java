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
 * The report a sub-agent hands back, and the one part of it that is machine-read.
 *
 * <p>The file list is what lets the main agent decide what to promote without opening anything. That
 * matters more than it looks: if it had to read each file to judge it, the context a sub-agent exists
 * to save would be spent on the judgement instead — the feature would cancel itself out. So the
 * parsing is pinned here in both directions: the shape the prompt asks for, and the shapes a model
 * actually writes when it half-follows it.
 *
 * <p>The rule underneath all of it is that a report is never refused. A run that cost real tokens and
 * came back with three headings instead of four has still done the work, and throwing its answer away
 * over formatting is the expensive kind of correctness.
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
    assertFalse(report.artifacts().get(0).disposable(), "the first is finished work");
    assertEquals("the file that does the parsing", report.artifacts().get(0).note());

    assertEquals("brute.cpp", report.artifacts().get(1).path());
    assertTrue(report.artifacts().get(1).disposable(), "the second is an intermediate");

    // The split the caller actually acts on.
    assertEquals(1, report.keepable().size());
    assertEquals("src/Parser.java", report.keepable().get(0).path());
    assertEquals(1, report.disposable().size());
    assertEquals("brute.cpp", report.disposable().get(0).path());
  }

  @Test
  void markdownHeadingsAndSynonymsAreAccepted() {
    // A model told to use headings uses one convention or the other and rarely both. Accepting
    // either costs a few lines and saves a run.
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
    // The asymmetry is deliberate: promoting a file that turns out to be an intermediate is a
    // smaller mistake than deleting one that turns out to be the answer.
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
    assertFalse(report.artifacts().get(0).disposable(), "kept, not discarded, when it is ambiguous");
    assertEquals(1, report.keepable().size());
  }

  @Test
  void theDisposableMarkIsFoundWhereverItAppearsInTheLine() {
    // "intermediate" and "temp" are what a model writes when it is not reading the format closely.
    SubAgentReport a = SubAgentReport.parse("FILES:\n  gen.py  temporary generator\n");
    SubAgentReport b = SubAgentReport.parse("FILES:\n  gen.py  intermediate, kept for the run\n");
    SubAgentReport c = SubAgentReport.parse("FILES:\n  gen.py\n");

    assertTrue(a.artifacts().get(0).disposable(), "temporary");
    assertTrue(b.artifacts().get(0).disposable(), "intermediate");
    assertFalse(c.artifacts().get(0).disposable(), "nothing said, so keep");
  }

  @Test
  void theMarkIsStrippedFromTheNote() {
    // Leaving "final" in the description would read as a claim about the file rather than as the flag
    // it already is.
    SubAgentReport report = SubAgentReport.parse("FILES:\n  a.py  final  the solution\n");

    assertEquals("the solution", report.artifacts().get(0).note());
  }

  @Test
  void proseWithNoHeadingsIsStillAnAnswer() {
    // A model that ignored the format produced the findings anyway. That is what the main agent
    // needs, and a parser that threw would turn a useful run into a lost one.
    SubAgentReport report =
        SubAgentReport.parse("The bug is in Parser.java line 142.\nThe null check is misplaced.");

    assertTrue(report.findings().contains("Parser.java line 142"), report.findings());
    assertEquals("The bug is in Parser.java line 142.", report.summary(), "first line as the summary");
    assertFalse(report.status().isEmpty(), "a report that came back is a run that finished");
    assertTrue(report.artifacts().isEmpty());
  }

  @Test
  void anEmptyReportIsAFailureNotASuccess() {
    // Nothing at all is a run that produced nothing: reporting that as "done" would have the main
    // agent proceed on an answer that does not exist.
    SubAgentReport report = SubAgentReport.parse("");

    assertEquals("failed", report.status());
  }

  @Test
  void aLongReportIsCutAndSaysSo() {
    // A sub-agent exists to keep one conversation from filling with another one's reading; a verbose
    // report is that failure arriving by a different door. The marker is the honesty: a main agent
    // reading a partial answer must know it is partial.
    String findings = "detail ".repeat(SubAgentReport.LIMIT_CHARS);
    SubAgentReport report = SubAgentReport.parse("STATUS: done\nFINDINGS:\n" + findings);

    String rendered = report.render("/tmp/work");

    assertTrue(rendered.length() <= SubAgentReport.LIMIT_CHARS + 200, "bounded: " + rendered.length());
    assertTrue(rendered.contains("cut short"), "and says the report is incomplete");
  }

  @Test
  void theRenderedReportNamesWhereTheFilesAre() {
    // Without the workspace path the paths in the file list mean nothing to the main agent: it would
    // have to guess where to promote from.
    SubAgentReport report =
        SubAgentReport.parse("STATUS: done\nFILES:\n  std.cpp  final  the solution\nFINDINGS:\nok");

    String rendered = report.render("/home/me/proj/.ccj-work/r2");

    assertTrue(rendered.contains("/home/me/proj/.ccj-work/r2"), rendered);
    assertTrue(rendered.contains("std.cpp"), rendered);
    assertTrue(rendered.contains("[final]"), "the mark is visible to the reader: " + rendered);
    assertTrue(rendered.startsWith("STATUS: done"), rendered);
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
    // A model on a Windows-shaped habit, or a relay that rewrites line endings.
    SubAgentReport report = SubAgentReport.parse("STATUS: done\r\nFINDINGS:\r\nfound it\r\n");

    assertEquals("done", report.status());
    assertTrue(report.findings().contains("found it"), report.findings());
  }
}
