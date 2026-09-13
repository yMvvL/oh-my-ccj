package com.ccj.agent.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact line previews of a pending file mutation, shown to the human who approves {@code write}
 * and {@code edit}.
 *
 * <p>The preview is deliberately bounded: a few lines of context around each changed region, `...`
 * for elided stretches, and a hard line cap. Unchanged text is trimmed from both ends before
 * diffing, so a one-line edit in a huge file costs only the lines that matter.
 */
public final class DiffPreview {

  /** Ceiling on the LCS matrix, above which the preview degrades to an edge-only sketch. */
  private static final int MAX_DP_CELLS = 250_000;

  private static final int COARSE_EDGE_LINES = 3;

  private static final char SAME = ' ';
  private static final char REMOVED = '-';
  private static final char ADDED = '+';
  private static final char MARKER = '~';

  private DiffPreview() {}

  /**
   * Preview of replacing each {@code [start, end)} offset of {@code oldText} with {@code newString}.
   * Ranges must be ascending and non-overlapping, which is how {@link EditTool} collects them.
   */
  public static String replacements(
      String oldText, List<int[]> ranges, String newString, int contextLines, int maxLines) {
    StringBuilder updated = new StringBuilder(oldText);
    for (int i = ranges.size() - 1; i >= 0; i--) {
      int[] range = ranges.get(i);
      updated.replace(range[0], range[1], newString);
    }
    return unified(oldText, updated.toString(), contextLines, maxLines);
  }

  /** Preview of the line-level change from {@code oldText} to {@code newText}. */
  public static String unified(String oldText, String newText, int contextLines, int maxLines) {
    if (oldText.equals(newText)) {
      return "(no change)";
    }
    List<String> before = lines(oldText);
    List<String> after = lines(newText);
    if (before.equals(after)) {
      // Identical lines, different bytes: the edit adds or removes a trailing newline. The line
      // diff cannot see it, and answering "(no change)" for a write that does rewrite the file is
      // the one thing an approval preview must never say.
      return newText.length() > oldText.length()
          ? "(a newline is added at the end of the file)"
          : "(the newline at the end of the file is removed)";
    }
    return render(diff(before, after), Math.max(0, contextLines), Math.max(1, maxLines));
  }

  private record Op(char sign, String text) {}

  private static List<Op> diff(List<String> oldLines, List<String> newLines) {
    int oldSize = oldLines.size();
    int newSize = newLines.size();
    int prefix = 0;
    while (prefix < oldSize
        && prefix < newSize
        && oldLines.get(prefix).equals(newLines.get(prefix))) {
      prefix++;
    }
    int suffix = 0;
    while (suffix < oldSize - prefix
        && suffix < newSize - prefix
        && oldLines.get(oldSize - 1 - suffix).equals(newLines.get(newSize - 1 - suffix))) {
      suffix++;
    }
    int midOld = oldSize - prefix - suffix;
    int midNew = newSize - prefix - suffix;

    List<Op> ops = new ArrayList<>();
    for (int i = 0; i < prefix; i++) {
      ops.add(new Op(SAME, oldLines.get(i)));
    }
    if ((long) midOld * midNew > MAX_DP_CELLS) {
      coarse(ops, oldLines, newLines, prefix, midOld, midNew);
    } else {
      precise(ops, oldLines, newLines, prefix, midOld, midNew);
    }
    for (int i = 0; i < suffix; i++) {
      ops.add(new Op(SAME, oldLines.get(oldSize - suffix + i)));
    }
    return ops;
  }

  /** LCS diff of the trimmed middle; only used while the middle is small enough to afford it. */
  private static void precise(
      List<Op> ops, List<String> a, List<String> b, int offset, int n, int m) {
    int[][] lcs = new int[n + 1][m + 1];
    for (int i = n - 1; i >= 0; i--) {
      for (int j = m - 1; j >= 0; j--) {
        lcs[i][j] =
            a.get(offset + i).equals(b.get(offset + j))
                ? lcs[i + 1][j + 1] + 1
                : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }
    int i = 0;
    int j = 0;
    while (i < n && j < m) {
      if (a.get(offset + i).equals(b.get(offset + j))) {
        ops.add(new Op(SAME, a.get(offset + i)));
        i++;
        j++;
      } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
        ops.add(new Op(REMOVED, a.get(offset + i++)));
      } else {
        ops.add(new Op(ADDED, b.get(offset + j++)));
      }
    }
    while (i < n) {
      ops.add(new Op(REMOVED, a.get(offset + i++)));
    }
    while (j < m) {
      ops.add(new Op(ADDED, b.get(offset + j++)));
    }
  }

  /** Whole-block replacement of both sides: first and last lines only, with an explicit count. */
  private static void coarse(
      List<Op> ops, List<String> a, List<String> b, int offset, int n, int m) {
    int headOld = Math.min(COARSE_EDGE_LINES, n);
    int tailOld = Math.min(COARSE_EDGE_LINES, n - headOld);
    for (int i = 0; i < headOld; i++) {
      ops.add(new Op(REMOVED, a.get(offset + i)));
    }
    if (n - headOld - tailOld > 0) {
      ops.add(new Op(MARKER, "(" + (n - headOld - tailOld) + " more removed lines)"));
    }
    for (int i = n - tailOld; i < n; i++) {
      ops.add(new Op(REMOVED, a.get(offset + i)));
    }
    int headNew = Math.min(COARSE_EDGE_LINES, m);
    int tailNew = Math.min(COARSE_EDGE_LINES, m - headNew);
    for (int i = 0; i < headNew; i++) {
      ops.add(new Op(ADDED, b.get(offset + i)));
    }
    if (m - headNew - tailNew > 0) {
      ops.add(new Op(MARKER, "(" + (m - headNew - tailNew) + " more added lines)"));
    }
    for (int i = m - tailNew; i < m; i++) {
      ops.add(new Op(ADDED, b.get(offset + i)));
    }
  }

  private static String render(List<Op> ops, int contextLines, int maxLines) {
    boolean[] keep = new boolean[ops.size()];
    boolean changed = false;
    for (int i = 0; i < ops.size(); i++) {
      if (ops.get(i).sign() == SAME) {
        continue;
      }
      changed = true;
      int from = Math.max(0, i - contextLines);
      int to = Math.min(ops.size() - 1, i + contextLines);
      for (int j = from; j <= to; j++) {
        keep[j] = true;
      }
    }
    if (!changed) {
      return "(no change)";
    }

    StringBuilder out = new StringBuilder();
    int emitted = 0;
    int suppressed = 0;
    boolean gap = false;
    boolean capped = false;
    for (int i = 0; i < ops.size(); i++) {
      Op op = ops.get(i);
      if (op.sign() != MARKER && !keep[i]) {
        if (!capped) {
          gap = true;
        }
        continue;
      }
      if (emitted >= maxLines) {
        capped = true;
        suppressed++;
        continue;
      }
      if (gap) {
        out.append("  ...\n");
        gap = false;
      }
      if (op.sign() == MARKER) {
        out.append("  ").append(op.text()).append('\n');
      } else {
        out.append(op.sign()).append(' ').append(op.text()).append('\n');
      }
      emitted++;
    }
    if (capped) {
      out.append("  ... ").append(suppressed).append(" more diff lines omitted ...\n");
    }
    return out.toString();
  }

  /** Text as lines without terminators; a trailing newline ends the last line, it does not add one. */
  private static List<String> lines(String text) {
    List<String> out = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        out.add(text.substring(start, i));
        start = i + 1;
      }
    }
    if (start < text.length()) {
      out.add(text.substring(start));
    }
    return out;
  }
}
