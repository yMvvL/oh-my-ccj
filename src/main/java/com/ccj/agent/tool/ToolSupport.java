package com.ccj.agent.tool;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * 内置工具共用的参数解析、路径显示与文本检查。
 *
 * <p>刻意设为包内可见：harness 其余部分与之对话的工具 ABI 是 {@link com.ccj.agent.core.Tool}
 * 接口，把这些细节在每个工具里各写一遍，只会让它们彼此漂移（一个工具接受空路径，另一个拒绝，诸如此类）。
 */
final class ToolSupport {

  /** 编码会话不希望被灌进输出的目录；glob 与 grep 会跳过它们。 */
  static final Set<String> IGNORED_DIRS = Set.of("target", ".git", "node_modules", ".idea");

  /** 判断一个文件是否为二进制文本时检查的字节数。 */
  static final int BINARY_PROBE_BYTES = 8 * 1024;

  private ToolSupport() {}

  static JsonNode args(String argumentsJson) {
    return Json.parse(argumentsJson);
  }

  static String requireText(JsonNode args, String field) {
    JsonNode node = args.get(field);
    if (node == null || node.isNull()) {
      throw new IllegalArgumentException("缺少必需参数 '" + field + "'");
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException("参数 '" + field + "' 必须是字符串");
    }
    return node.asText();
  }

  static String requireNonBlank(JsonNode args, String field) {
    String value = requireText(args, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException("参数 '" + field + "' 不能为空");
    }
    return value;
  }

  /** 文本参数，缺席时为 {@code null}；对路径类选项来说，空串也算缺席。 */
  static String optionalText(JsonNode args, String field) {
    JsonNode node = args.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException("参数 '" + field + "' 必须是字符串");
    }
    return node.asText();
  }

  static boolean optionalBool(JsonNode args, String field, boolean fallback) {
    JsonNode node = args.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (!node.isBoolean()) {
      throw new IllegalArgumentException("参数 '" + field + "' 必须是布尔值");
    }
    return node.asBoolean();
  }

  static int optionalInt(JsonNode args, String field, int fallback, int min, int max) {
    JsonNode node = args.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      throw new IllegalArgumentException("参数 '" + field + "' 必须是整数");
    }
    int value = node.asInt();
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          "参数 '" + field + "' 必须在 " + min + " 与 " + max + " 之间，实际是 " + value);
    }
    return value;
  }

  /**
   * 模型应当看到的路径形式：在工作目录之内时相对它，否则用绝对路径，这样输出既短又不会有歧义。
   */
  static String display(ToolContext ctx, Path path) {
    Path absolute = path.toAbsolutePath().normalize();
    if (absolute.startsWith(ctx.cwd())) {
      Path relative = ctx.cwd().relativize(absolute);
      return relative.toString().isEmpty() ? "." : slashed(relative);
    }
    return absolute.toString();
  }

  /** 与文件系统无关的形式，用于 glob 匹配，以及需要能被 grep 到的输出。 */
  static String slashed(Path path) {
    return path.toString().replace('\\', '/');
  }

  /** UTF-8 字节长度，且不分配编码后的数组。 */
  static int utf8Length(String text) {
    int bytes = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < 0x80) {
        bytes++;
      } else if (c < 0x800) {
        bytes += 2;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        bytes += 4;
        i++;
      } else {
        bytes += 3;
      }
    }
    return bytes;
  }

  /**
   * {@code text} 中能放进 {@code maxBytes} 个 UTF-8 字节的最长前缀，只在码点边界上截断，因此永远不会
   * 交回半个字符。
   */
  static String truncateUtf8(String text, int maxBytes) {
    int bytes = 0;
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      int width;
      int chars;
      if (c < 0x80) {
        width = 1;
        chars = 1;
      } else if (c < 0x800) {
        width = 2;
        chars = 1;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        width = 4;
        chars = 2;
      } else {
        width = 3;
        chars = 1;
      }
      if (bytes + width > maxBytes) {
        break;
      }
      bytes += width;
      i += chars;
    }
    return text.substring(0, i);
  }

  /** 与 {@code wc -l} 会数得不一样的行数：末尾的换行不额外算作一行。 */
  static int lineCount(String text) {
    if (text.isEmpty()) {
      return 0;
    }
    int lines = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        lines++;
      }
    }
    return text.charAt(text.length() - 1) == '\n' ? lines : lines + 1;
  }

  /** {@code needle} 全部不重叠的出现位置，以递增的 {@code [start, end)} 区间表示。 */
  static List<int[]> findAll(String text, String needle) {
    List<int[]> ranges = new ArrayList<>();
    if (needle.isEmpty()) {
      return ranges;
    }
    int from = 0;
    while (true) {
      int at = text.indexOf(needle, from);
      if (at < 0) {
        return ranges;
      }
      ranges.add(new int[] {at, at + needle.length()});
      from = at + needle.length();
    }
  }

  /**
   * 把连续空白折叠之后，{@code needle} 会匹配多少次。只用于组织「no exact match」这条错误消息，其
   * 常见成因是模型猜错了缩进；一个计数是「这段文本确实存在」的便宜提示。
   */
  static int countNormalisedMatches(String text, String needle) {
    String target = normaliseWhitespace(needle);
    if (target.isEmpty()) {
      return 0;
    }
    String haystack = normaliseWhitespace(text);
    int count = 0;
    int from = 0;
    while (true) {
      int at = haystack.indexOf(target, from);
      if (at < 0) {
        return count;
      }
      count++;
      from = at + target.length();
    }
  }

  /**
   * 匹配失败时，预期出现某个块的位置附近那几行，带行号。
   *
   * <p>失败的 `edit` 是模型最常需要从中恢复的事情，而它过去得到的错误只说了一个计数，别的什么都没有：
   * 「no exact match」，或者「1 region matches once whitespace is normalised」。两句都是真的，而两句
   * 都无法让模型不必重读文件就把问题修好——对这次调用手里已经有的信息来说，那是一整个来回。
   */
  static String excerpt(String text, int aroundLine, int contextLines) {
    List<String> lines = List.of(text.split("\n", -1));
    if (lines.isEmpty()) {
      return "(文件为空)\n";
    }
    int centre =
        aroundLine < 1 ? 1 : Math.min(aroundLine, lines.size());
    int from = Math.max(1, centre - contextLines);
    int to = Math.min(lines.size(), centre + contextLines);
    StringBuilder rendered = new StringBuilder();
    for (int line = from; line <= to; line++) {
      rendered
          .append(line == centre ? "  > " : "    ")
          .append(String.format("%4d", line))
          .append("  ")
          .append(lines.get(line - 1))
          .append('\n');
    }
    return rendered.toString();
  }

  /**
   * needle 起始的行号（从 1 计），没有可供锚定的东西时为 -1。
   *
   * <p>锚在 needle 的第一行上，因为那通常是模型猜对的部分，而它下面的缩进才是猜错的地方。比较时容忍
   * 一开始就导致失败的那种差异：连续空白按宽松方式匹配；如果整行找不到——模型可能引用它时每处的空格都
   * 不一样——就先试第一个词再放弃。一段位置略有偏差但就在附近的摘录，比一句「no exact match」外加
   * 什么都没有更有价值。
   */
  static int lineOfFirstLine(String text, String needle) {
    String first = null;
    for (String line : List.of(needle.split("\n", -1))) {
      if (!line.isBlank()) {
        first = line;
        break;
      }
    }
    if (first == null) {
      return -1;
    }
    List<String> words = List.of(first.strip().split("\\s+"));
    for (int length = words.size(); length >= 1; length--) {
      int at = indexOfWords(text, words.subList(0, length));
      if (at >= 0) {
        int lineNumber = 1;
        for (int i = 0; i < at; i++) {
          if (text.charAt(i) == '\n') {
            lineNumber++;
          }
        }
        return lineNumber;
      }
    }
    return -1;
  }

  /** 头 {@code words} 个词按顺序出现、其间允许任意空白的位置，找不到时为 -1。 */
  private static int indexOfWords(String text, List<String> words) {
    StringBuilder pattern = new StringBuilder();
    for (int i = 0; i < words.size(); i++) {
      if (i > 0) {
        pattern.append("\\s+");
      }
      pattern.append(Pattern.quote(words.get(i)));
    }
    Matcher matcher = Pattern.compile(pattern.toString()).matcher(text);
    return matcher.find() ? matcher.start() : -1;
  }

  private static String normaliseWhitespace(String text) {
    return text.strip().replaceAll("\\s+", " ");
  }

  /**
   * 对已经读入的字节做严格的 UTF-8 解码。
   *
   * @return 文本；这些字节不是文本时返回 {@code null}：出现 NUL 字节，或者存在不是合法 UTF-8 的序列
   */
  static String decodeText(byte[] bytes) {
    if (looksBinary(bytes)) {
      return null;
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      return null;
    }
  }

  static boolean looksBinary(byte[] bytes) {
    for (byte b : bytes) {
      if (b == 0) {
        return true;
      }
    }
    return false;
  }

  /** 文件开头 {@link #BINARY_PROBE_BYTES} 个字节中任意位置出现 NUL 字节。 */
  static boolean isBinaryFile(Path file) throws IOException {
    int probe = (int) Math.min(BINARY_PROBE_BYTES, Files.size(file));
    byte[] head = new byte[probe];
    try (var in = Files.newInputStream(file)) {
      int read = 0;
      while (read < probe) {
        int n = in.read(head, read, probe - read);
        if (n < 0) {
          break;
        }
        read += n;
      }
    }
    return looksBinary(head);
  }

  /** {@link #walkFiles} 的回调。 */
  @FunctionalInterface
  interface FileSink {
    void accept(Path file, Path relative) throws IOException;
  }

  /**
   * 以未指定的顺序访问 {@code base} 下的每个普通文件，跳过 {@link #IGNORED_DIRS}，并且从不进入指向
   * 目录的符号链接——一个走进链接农场的 glob 可能永远跑不完。
   */
  static void walkFiles(Path base, FileSink sink) throws IOException {
    IOException[] failure = new IOException[1];
    Files.walkFileTree(
        base,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (dir.equals(base)) {
              return FileVisitResult.CONTINUE;
            }
            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (IGNORED_DIRS.contains(name) || Files.isSymbolicLink(dir)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile()) {
              try {
                sink.accept(file, base.relativize(file));
              } catch (IOException e) {
                failure[0] = e;
                return FileVisitResult.TERMINATE;
              }
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
          }
        });
    if (failure[0] != null) {
      throw failure[0];
    }
  }
}
