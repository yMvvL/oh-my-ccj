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
import java.util.Set;

/**
 * Argument parsing, path display and text inspection shared by the bundled tools.
 *
 * <p>Package-private on purpose: the tool ABI the rest of the harness talks to is the {@link
 * com.ccj.agent.core.Tool} interface, and duplicating these details in each tool would let them
 * drift apart (one tool accepting a blank path, another rejecting it, and so on).
 */
final class ToolSupport {

  /** Directories no coding session wants flooded into output; skipped by glob and grep. */
  static final Set<String> IGNORED_DIRS = Set.of("target", ".git", "node_modules", ".idea");

  /** Bytes inspected when deciding whether a file is binary text. */
  static final int BINARY_PROBE_BYTES = 8 * 1024;

  private ToolSupport() {}

  static JsonNode args(String argumentsJson) {
    return Json.parse(argumentsJson);
  }

  static String requireText(JsonNode args, String field) {
    JsonNode node = args.get(field);
    if (node == null || node.isNull()) {
      throw new IllegalArgumentException("missing required argument '" + field + "'");
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException("argument '" + field + "' must be a string");
    }
    return node.asText();
  }

  static String requireNonBlank(JsonNode args, String field) {
    String value = requireText(args, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException("argument '" + field + "' must not be blank");
    }
    return value;
  }

  /** Text argument or {@code null} when absent; blank counts as absent for path-like options. */
  static String optionalText(JsonNode args, String field) {
    JsonNode node = args.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException("argument '" + field + "' must be a string");
    }
    return node.asText();
  }

  static boolean optionalBool(JsonNode args, String field, boolean fallback) {
    JsonNode node = args.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (!node.isBoolean()) {
      throw new IllegalArgumentException("argument '" + field + "' must be a boolean");
    }
    return node.asBoolean();
  }

  static int optionalInt(JsonNode args, String field, int fallback, int min, int max) {
    JsonNode node = args.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      throw new IllegalArgumentException("argument '" + field + "' must be an integer");
    }
    int value = node.asInt();
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          "argument '" + field + "' must be between " + min + " and " + max + ", got " + value);
    }
    return value;
  }

  /**
   * Path as the model should see it: relative to the session cwd when it is inside, absolute
   * otherwise, so output stays short but never ambiguous.
   */
  static String display(ToolContext ctx, Path path) {
    Path absolute = path.toAbsolutePath().normalize();
    if (absolute.startsWith(ctx.cwd())) {
      Path relative = ctx.cwd().relativize(absolute);
      return relative.toString().isEmpty() ? "." : slashed(relative);
    }
    return absolute.toString();
  }

  /** Filesystem-independent form used for glob matching and for output that must be greppable. */
  static String slashed(Path path) {
    return path.toString().replace('\\', '/');
  }

  /** UTF-8 byte length without allocating the encoded array. */
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
   * The longest prefix of {@code text} that fits in {@code maxBytes} UTF-8 bytes, cut only on a code
   * point boundary so no half character is ever handed back.
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

  /** Lines as {@code wc -l} would disagree with: a trailing newline does not add a line. */
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

  /** All non-overlapping occurrences of {@code needle}, as ascending {@code [start, end)} ranges. */
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
   * How many times {@code needle} would match if runs of whitespace were collapsed. Used only to
   * phrase the "no exact match" error, where the usual cause is indentation the model guessed
   * wrong; a count is a cheap hint that the text exists at all.
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

  private static String normaliseWhitespace(String text) {
    return text.strip().replaceAll("\\s+", " ");
  }

  /**
   * Strict UTF-8 decode of already-read bytes.
   *
   * @return the text, or {@code null} when the bytes are not text: a NUL byte or a sequence that is
   *     not valid UTF-8
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

  /** NUL byte anywhere in the first {@link #BINARY_PROBE_BYTES} of the file. */
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

  /** Callback for {@link #walkFiles}. */
  @FunctionalInterface
  interface FileSink {
    void accept(Path file, Path relative) throws IOException;
  }

  /**
   * Visits every regular file under {@code base} in unspecified order, skipping {@link
   * #IGNORED_DIRS} and never descending into symbolic links to directories - a glob that walks a
   * link farm can run forever.
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
