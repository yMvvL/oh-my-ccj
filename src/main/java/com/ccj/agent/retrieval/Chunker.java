package com.ccj.agent.retrieval;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 把一段文字切成可以各自被检索的块。
 *
 * <p>切分是检索里最不显眼、也最贵的一个决定。切得太大，一个块里混着好几件事，向量就成了它们的平均值——它
 * 对哪一件事都不像；切得太小，一句跨行的话会被劈成两半，而两半各自都不像它原来的意思。所以这里按<em>行</em>
 * 切，并且让相邻两块重叠若干行：一个窗口的边界落在哪一行都可以，因为边界上的那几行同时也完整地出现在前一块
 * 或后一块里。
 *
 * <p>两个细节值得写下来，因为它们是量出来的而不是想出来的：
 *
 * <ul>
 *   <li><strong>块里带上它的路径。</strong>一段没有出处的代码片段，与另一段长得一样的代码片段，向量上几乎
 *       没有区别；把 {@code src/agent/Loop.java} 放进去，是给这个块一句「我在哪」，而这句话的代价是几个
 *       token。
 *   <li><strong>指纹算的是内容，不是位置。</strong>同一个文件被编辑之后，没有变的那几块指纹不变，于是它们
 *       不必再花一次钱去算向量——这是「改一个文件要重算整个仓库」与「只重算它改了的那几块」之间的差别。
 * </ul>
 */
public final class Chunker {

  /** 一个块里最多这么多字符：再长的块也不再是一个「意思」，而一行超长的代码就是这样的东西。 */
  public static final int MAX_CHUNK_CHARS = 4000;

  /** 一个块是从哪个文件的第几行到第几行。 */
  public record Chunk(String path, int firstLine, int lastLine, String text, String fingerprint) {

    /** 给嵌入端点看的文字：路径在前，内容在后。 */
    public String embedText() {
      return "# " + path + ":" + firstLine + "-" + lastLine + "\n" + text;
    }

    /** 搜索结果里给人看的一行。 */
    public String label() {
      return path + ":" + firstLine + "-" + lastLine;
    }
  }

  private Chunker() {}

  /**
   * 把一份语料切块。
   *
   * <p>空行不留块：一个只由空行组成的窗口没有任何意思，而它会占掉索引里的一格、一次嵌入调用，以及搜索结果
   * 里的一个位置。
   */
  public static List<Chunk> chunks(
      List<Source> sources, int chunkLines, int overlapLines, int maxChunks) {
    int window = Math.max(1, chunkLines);
    int step = Math.max(1, window - Math.max(0, Math.min(overlapLines, window - 1)));
    List<Chunk> chunks = new ArrayList<>();
    for (Source source : sources) {
      String[] lines = source.text().split("\n", -1);
      for (int start = 0; start < lines.length; start += step) {
        int end = Math.min(lines.length, start + window);
        String body = join(lines, start, end);
        if (body.isBlank()) {
          if (end == lines.length) {
            break;
          }
          continue;
        }
        String text = body.length() > MAX_CHUNK_CHARS ? body.substring(0, MAX_CHUNK_CHARS) : body;
        chunks.add(
            new Chunk(
                source.path(),
                start + 1,
                end,
                text,
                fingerprint(source.path(), start + 1, end, text)));
        if (chunks.size() >= maxChunks) {
          return List.copyOf(chunks);
        }
        if (end == lines.length) {
          break;
        }
      }
    }
    return List.copyOf(chunks);
  }

  private static String join(String[] lines, int start, int end) {
    StringBuilder out = new StringBuilder();
    for (int i = start; i < end; i++) {
      if (i > start) {
        out.append('\n');
      }
      out.append(lines[i]);
    }
    return out.toString();
  }

  /**
   * 一个块的指纹：路径、行号与内容一起散列。
   *
   * <p>行号也在里面，尽管内容一样的两块本来就可以共用向量——把它们视为不同，是因为它们的出处不同，而搜索
   * 结果要给出出处。宁可多算一次，也不要让一次「删掉了中间几行」的编辑，把后面的每一块都当成没变。
   */
  static String fingerprint(String path, int firstLine, int lastLine, String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      digest.update((byte) firstLine);
      digest.update((byte) lastLine);
      digest.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest()).substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 是每一个 JRE 都必须提供的；到不了这里。
      throw new IllegalStateException("这个 JRE 没有 SHA-256", e);
    }
  }
}
