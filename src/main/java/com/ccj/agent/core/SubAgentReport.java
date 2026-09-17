package com.ccj.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a sub-agent hands back: a conclusion, not its conversation.
 *
 * <p>The whole point of a sub-agent is that its reading does not reach the main conversation, so its
 * report is a fixed shape rather than free prose. Two parts of it earn their place:
 *
 * <ul>
 *   <li><b>The findings.</b> The answer, with the paths and line numbers it lives at — enough to act
 *       on without going back to look.
 *   <li><b>The file list, each marked final or disposable.</b> This is what lets the main agent decide
 *       what to keep <em>without reading anything</em>. If it had to open each file to judge it, the
 *       context this feature exists to save would be spent on the judgement instead.
 * </ul>
 *
 * <p>Parsing is forgiving by design. A model that writes three of the four headings, or spells one
 * differently, has still done the work — and refusing its report over a formatting slip would throw
 * away a run that cost real tokens. Anything unrecognised becomes part of the findings, which is the
 * part a human reads anyway.
 */
public final class SubAgentReport {

  /** How the report starts a section. Matched case-insensitively, with or without the colon. */
  private static final String STATUS = "status";
  private static final String SUMMARY = "summary";
  private static final String FILES = "files";
  private static final String FINDINGS = "findings";

  /**
   * Cap on the report as it reaches the main conversation.
   *
   * <p>A sub-agent exists to keep one conversation from filling with another one's reading, and a
   * verbose report is that failure arriving by a different door. Over the cap the text is cut with a
   * marker — the same honesty {@link ContextBudget} uses — so the main agent knows it is reading a
   * partial answer rather than a complete short one.
   */
  public static final int LIMIT_CHARS = 8_000;

  /** What the cut says. */
  static final String CUT_MARKER =
      "\n... (cut short: this report did not fit; ask for a narrower follow-up) ...";

  /**
   * One file a run produced.
   *
   * @param path where it is, relative to the working directory the sub-agent was given
   * @param disposable true for an intermediate kept only to work with, false for finished work
   * @param note one line on what it is, so the decision to keep it does not need the file opened
   */
  public record Artifact(String path, boolean disposable, String note) {

    /** True when this is finished work rather than a by-product. */
    public boolean keepable() {
      return !disposable;
    }
  }

  private final String status;
  private final String summary;
  private final String findings;
  private final List<Artifact> artifacts;
  private final UsageTotals usage;
  private final String directory;

  private SubAgentReport(
      String status,
      String summary,
      String findings,
      List<Artifact> artifacts,
      UsageTotals usage,
      String directory) {
    this.status = status;
    this.summary = summary;
    this.findings = findings;
    this.artifacts = List.copyOf(artifacts);
    this.usage = usage == null ? UsageTotals.empty() : usage;
    this.directory = directory == null ? "" : directory;
  }

  /**
   * What this run cost.
   *
   * <p>Carried back so the conversation that delegated the work can count it. Hiding the sub-agent's
   * transcript is the point; hiding what it spent would be a different thing — a token count that
   * quietly omits the delegated half is a number the user cannot trust.
   */
  public UsageTotals usage() {
    return usage;
  }

  /** The same report with the run's cost attached. */
  public SubAgentReport withUsage(UsageTotals spent) {
    return new SubAgentReport(status, summary, findings, artifacts, spent, directory);
  }

  /**
   * The directory the reported paths are relative to.
   *
   * <p>Set by the run that produced the report, because the paths are only meaningful next to it: a
   * writing role works in its own staging directory, and a report that named the project as the
   * location would send the main agent to a place the files are not.
   */
  public String directory() {
    return directory;
  }

  /** The same report, saying where its paths are relative to. */
  public SubAgentReport in(String where) {
    return new SubAgentReport(status, summary, findings, artifacts, usage, where);
  }

  public String status() {
    return status;
  }

  public String summary() {
    return summary;
  }

  public String findings() {
    return findings;
  }

  public List<Artifact> artifacts() {
    return artifacts;
  }

  /** The files worth promoting, in the order they were reported. */
  public List<Artifact> keepable() {
    List<Artifact> keep = new ArrayList<>();
    for (Artifact artifact : artifacts) {
      if (artifact.keepable()) {
        keep.add(artifact);
      }
    }
    return List.copyOf(keep);
  }

  /** The by-products: kept to work with, not the answer. */
  public List<Artifact> disposable() {
    List<Artifact> out = new ArrayList<>();
    for (Artifact artifact : artifacts) {
      if (artifact.disposable()) {
        out.add(artifact);
      }
    }
    return List.copyOf(out);
  }

  /**
   * Reads a report out of what a sub-agent said.
   *
   * <p>Never throws and never returns null: text with no headings at all is a report whose findings
   * are the whole of it, because that is what a model that ignored the format actually produced, and
   * it is still the answer the main agent needs.
   */
  public static SubAgentReport parse(String text) {
    String body = text == null ? "" : text.strip();
    Map<String, StringBuilder> sections = new LinkedHashMap<>();
    String current = FINDINGS;
    sections.put(current, new StringBuilder());
    for (String line : body.split("\n", -1)) {
      // Only the four known words end a section. A path like `src/Parser.java  final  ...` contains a
      // colon, so a rule of "a colon means a heading" would swallow the file list it is meant to
      // read — the words are the whole test, and there are only four of them.
      String heading = headingOf(line);
      if (heading != null) {
        current = heading;
        sections.computeIfAbsent(current, key -> new StringBuilder());
        // What followed the colon on a heading line is that section's first content: "SUMMARY: found
        // it" is one line and one statement, and dropping the half after the colon would lose the
        // summary of every model that writes it that way.
        String rest = afterHeading(line);
        if (!rest.isEmpty()) {
          sections.get(current).append(rest).append('\n');
        }
        continue;
      }
      sections.get(current).append(line).append('\n');
    }
    String status = clean(sections.get(STATUS));
    String summary = clean(sections.get(SUMMARY));
    String findings = clean(sections.get(FINDINGS));
    List<Artifact> artifacts = parseArtifacts(sections.get(FILES));
    if (status.isEmpty()) {
      // No STATUS heading: a report that came back at all is a run that finished. Saying so is more
      // useful than reporting an empty status the caller has to special-case.
      status = body.isEmpty() ? "failed" : "done";
    }
    if (summary.isEmpty()) {
      // Fall back to the first line of the body, which is what a model that skipped the format
      // writes first: the sentence that says what happened.
      summary = firstLine(body);
    }
    if (findings.isEmpty() && !summary.isEmpty() && !summary.equals(firstLine(body))) {
      findings = summary;
    }
    return new SubAgentReport(status, summary, findings, artifacts, null, null);
  }

  /** What a heading line carries after its colon, which is the section's first line of content. */
  private static String afterHeading(String line) {
    String bare = line.strip().startsWith("#")
        ? line.strip().replaceAll("^#+\\s*", "")
        : line.strip();
    int colon = bare.indexOf(':');
    return colon < 0 ? "" : bare.substring(colon + 1).strip();
  }

  /** The heading a line is, or null when it is ordinary text. */
  private static String headingOf(String line) {
    String trimmed = line.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    // "STATUS: done" and "## Findings" both count. A model told to use headings uses one convention
    // or the other and rarely both; accepting either costs a line and saves a run.
    String bare = trimmed.startsWith("#") ? trimmed.replaceAll("^#+\\s*", "") : trimmed;
    int colon = bare.indexOf(':');
    if (colon < 0) {
      // A bare word is only a heading when it is short enough to be one: "STATUS" is, and a sentence
      // that happens to end in a full stop after one word is not worth the risk of guessing.
      String whole = bare.strip().toLowerCase(java.util.Locale.ROOT);
      if (whole.contains(" ")) {
        return null;
      }
    }
    String word = (colon >= 0 ? bare.substring(0, colon) : bare).strip().toLowerCase(java.util.Locale.ROOT);
    return switch (word) {
      case STATUS -> STATUS;
      case SUMMARY -> SUMMARY;
      case FILES, "file", "artifacts", "artefacts" -> FILES;
      case FINDINGS, "finding", "result", "results", "answer" -> FINDINGS;
      default -> null;
    };
  }

  /**
   * The file list.
   *
   * <p>Accepts the shape the prompt asks for — {@code path  final|disposable  note} — and the shapes
   * a model reaches for instead: a leading dash, a colon after the path, the mark anywhere in the
   * line. A line with no mark is treated as finished work, because promoting a file that turns out to
   * be an intermediate is a smaller mistake than deleting one that turns out to be the answer.
   */
  private static List<Artifact> parseArtifacts(StringBuilder section) {
    if (section == null) {
      return List.of();
    }
    List<Artifact> found = new ArrayList<>();
    for (String raw : section.toString().split("\n")) {
      String line = raw.strip();
      if (line.isEmpty()) {
        continue;
      }
      line = line.replaceAll("^[-*+]\\s*", "");
      if (line.isEmpty()) {
        continue;
      }
      int mark = markIndex(line);
      boolean disposable = mark >= 0 && !line.substring(mark).toLowerCase(java.util.Locale.ROOT)
          .startsWith("final");
      String path;
      String note = "";
      int colon = line.indexOf(':');
      if (colon > 0 && !line.substring(0, colon).contains(" ")) {
        path = line.substring(0, colon).strip();
        note = line.substring(colon + 1).strip();
      } else {
        // Column-aligned (two or more spaces, or a tab) is what the prompt asks for, but a model
        // that puts single spaces between the three fields is common enough that reading the whole
        // line as one path would lose the file it names. So the mark is what the split is anchored
        // on: everything before it is the path, everything after is the note.
        if (mark >= 0) {
          path = line.substring(0, mark).strip();
          note = line.substring(mark).strip();
        } else {
          String[] parts = line.split("\\s{2,}|\\t", 2);
          path = parts[0].strip();
          note = parts.length > 1 ? parts[1].strip() : "";
        }
      }
      // The mark may be the note itself; leaving "final" in the note would read as a description.
      note = note.replaceAll("(?i)^(final|disposable|intermediate)\\s*[:,-]?\\s*", "").strip();
      if (!path.isEmpty()) {
        found.add(new Artifact(path, disposable, note));
      }
    }
    return List.copyOf(found);
  }

  /**
   * Where the final/disposable mark starts on a line, or -1 when the line carries none.
   *
   * <p>Found by looking for the word as a whole, so a directory called {@code temporary-output/} does
   * not mark a file disposable and a note that merely mentions the word later does not either.
   */
  private static int markIndex(String line) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile(
                "(?i)(?<![\\w.-])(final|disposable|intermediate|temporary|temp)(?![\\w.-])")
            .matcher(line);
    return matcher.find() ? matcher.start() : -1;
  }

  private static String clean(StringBuilder section) {
    return section == null ? "" : section.toString().strip();
  }

  private static String firstLine(String body) {
    for (String line : body.split("\n")) {
      String trimmed = line.strip();
      if (!trimmed.isEmpty() && headingOf(trimmed) == null) {
        return trimmed;
      }
    }
    return "";
  }

  /**
   * The report as it goes back to the main conversation, bounded and labelled.
   *
   * @param workspace the directory the reported paths are relative to, used only when the run did not
   *     name one of its own. It matters that this is right: a report naming a directory the files are
   *     not in sends the main agent looking in the wrong place.
   */
  public String render(String workspace) {
    String where = directory.isEmpty() ? workspace : directory;
    StringBuilder out = new StringBuilder();
    out.append("STATUS: ").append(status).append('\n');
    if (!summary.isEmpty()) {
      out.append("SUMMARY: ").append(summary).append('\n');
    }
    if (!artifacts.isEmpty()) {
      out.append("FILES (in ").append(where).append("):\n");
      for (Artifact artifact : artifacts) {
        out.append("  ").append(artifact.path())
            .append(artifact.disposable() ? "  [disposable]" : "  [final]");
        if (!artifact.note().isEmpty()) {
          out.append(" — ").append(artifact.note());
        }
        out.append('\n');
      }
    }
    if (!findings.isEmpty()) {
      out.append('\n').append(findings).append('\n');
    }
    String text = out.toString().strip();
    if (text.length() > LIMIT_CHARS) {
      text = text.substring(0, Math.max(0, LIMIT_CHARS - CUT_MARKER.length())) + CUT_MARKER;
    }
    return text;
  }

  /** A report for a run that never produced one: cancelled, timed out, or the model refused. */
  public static SubAgentReport failed(String reason) {
    return new SubAgentReport("failed", reason == null ? "" : reason, "", List.of(), null, null);
  }

  /** A run that finished but wrote nothing and said nothing usable. */
  public static SubAgentReport empty() {
    return new SubAgentReport(
        "done", "(the sub-agent produced no report)", "", List.of(), null, null);
  }
}
