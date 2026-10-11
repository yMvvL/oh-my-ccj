package com.ccj.agent.session;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个文本文件概括成几句话：多少行、多大、开头长什么样。
 *
 * <p>它是"一个文件进对话"那条路上的**本地描述者**。日志、一段堆栈、一张 CSV 都不需要一个模型来读——需要
 * 模型的从来只有没有文字的东西。所以它住在这里，而不在 web 那一层：它只读字节，既不知道 HTTP，也不知道
 * 谁在问。
 *
 * <p>它只给开头。行数与字节数告诉模型这份东西有多大，而完整的文件就在会话旁边（描述后面那句点名了路径），
 * 被落下的细节可以用 {@code read} 读回来——这条规矩压缩那条路已经在用了。
 */
public final class TextPreview {

  /** 开头给多少行。 */
  public static final int HEAD_LINES = 40;

  /** 开头最多给多少字符：一行压缩过的 JSON 可能有一兆，而描述是摘要，不是搬运。 */
  public static final int HEAD_CHARS = 4000;

  /** 一行最多留多少字符。 */
  private static final int LINE_CHARS = 500;

  private TextPreview() {}

  /**
   * {@code bytes} 里那段文本的概括。
   *
   * <p>调用方已经确认过它是文本（{@link AttachmentStore#isText}）；这里只负责说清楚它长什么样。
   */
  public static String describe(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.UTF_8);
    List<String> lines = lines(text);
    StringBuilder out = new StringBuilder();
    out.append("文本文件：").append(lines.size()).append(" 行 / ").append(size(bytes)).append("。");
    out.append(
        lines.size() <= HEAD_LINES
            ? "全文如下："
            : "开头 " + HEAD_LINES + " 行如下：");
    out.append("\n\n");
    int chars = 0;
    int shown = 0;
    for (String line : lines) {
      if (shown >= HEAD_LINES || chars >= HEAD_CHARS) {
        break;
      }
      String one =
          line.length() > LINE_CHARS ? line.substring(0, LINE_CHARS) + " …（这一行还有更多）" : line;
      if (chars + one.length() > HEAD_CHARS) {
        break;
      }
      out.append(one).append('\n');
      chars += one.length() + 1;
      shown++;
    }
    if (shown < lines.size()) {
      out.append("\n（后面还有 ").append(lines.size() - shown).append(" 行没在这里）");
    }
    return out.toString().stripTrailing();
  }

  /** 行：按 {@code \n} 切开，去掉 {@code \r}，末尾那个换行不算一行。 */
  private static List<String> lines(String text) {
    List<String> lines = new ArrayList<>();
    if (text.isEmpty()) {
      return lines;
    }
    for (String line : text.split("\n", -1)) {
      lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
    }
    if (text.endsWith("\n")) {
      lines.remove(lines.size() - 1);
    }
    return lines;
  }

  private static String size(byte[] bytes) {
    if (bytes.length < 1024) {
      return bytes.length + " 字节";
    }
    return String.format(java.util.Locale.ROOT, "%.1f KB", bytes.length / 1024.0);
  }
}
