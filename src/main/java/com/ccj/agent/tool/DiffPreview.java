package com.ccj.agent.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 待定文件改动的紧凑行预览，展示给批准 {@code write} 与 {@code edit} 的人。
 *
 * <p>预览刻意是有界的：每处改动区域周围几行上下文，用 `...` 表示被略去的片段，再加一个硬性的行数
 * 上限。做差异之前会先从两端裁掉未变的文本，因此在一个巨大文件里改一行，只花掉真正要紧的那几行。
 */
public final class DiffPreview {

  /** LCS 矩阵的天花板，超过它预览就退化成只画两端的草图。 */
  private static final int MAX_DP_CELLS = 250_000;

  private static final int COARSE_EDGE_LINES = 3;

  private static final char SAME = ' ';
  private static final char REMOVED = '-';
  private static final char ADDED = '+';
  private static final char MARKER = '~';

  private DiffPreview() {}

  /**
   * 把 {@code oldText} 的每个 {@code [start, end)} 区间都替换成 {@code newString} 之后的预览。
   * 区间必须递增且互不重叠，{@link EditTool} 就是这样收集它们的。
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

  /** 从 {@code oldText} 到 {@code newText} 的行级改动预览。 */
  public static String unified(String oldText, String newText, int contextLines, int maxLines) {
    if (oldText.equals(newText)) {
      return "（无变化）";
    }
    List<String> before = lines(oldText);
    List<String> after = lines(newText);
    if (before.equals(after)) {
      // 行相同、字节不同：这次编辑增加或删除了末尾的换行。行级差异看不到它，而对一次确实重写了文件
      // 的写入回答「（无变化）」，是审批预览绝不能说的唯一一件事。
      return newText.length() > oldText.length()
          ? "（文件末尾新增了一个换行）"
          : "（文件末尾的换行被删除了）";
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

  /** 对裁过两端的中间部分做 LCS 差异；只在中间小到负担得起时使用。 */
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

  /** 两侧都整块替换：只给首尾几行，外加一个明确的计数。 */
  private static void coarse(
      List<Op> ops, List<String> a, List<String> b, int offset, int n, int m) {
    int headOld = Math.min(COARSE_EDGE_LINES, n);
    int tailOld = Math.min(COARSE_EDGE_LINES, n - headOld);
    for (int i = 0; i < headOld; i++) {
      ops.add(new Op(REMOVED, a.get(offset + i)));
    }
    if (n - headOld - tailOld > 0) {
      ops.add(new Op(MARKER, "（另有 " + (n - headOld - tailOld) + " 行被删除）"));
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
      ops.add(new Op(MARKER, "（另有 " + (m - headNew - tailNew) + " 行被新增）"));
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
      return "（无变化）";
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
      out.append("  ... 另有 ").append(suppressed).append(" 行差异被省略 ...\n");
    }
    return out.toString();
  }

  /** 文本按行切开且不带行终止符；末尾的换行结束最后一行，不额外增加一行。 */
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
