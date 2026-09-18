package com.ccj.agent.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 页面可以拿来当背景的图片：一个目录，只读。
 *
 * <p>壁纸不是工具，也没有审批关口——页面向它已经在对话的那个服务器要一个文件，服务器就从这一个目录里
 * 交回一张普通图片，或者什么都不给。名字的其他一切情形都被拒绝：带分隔符的、经由链接解析到目录之外的、
 * 绝对路径、不是图片的文件。这和会话存储对 session id 施加的是同一条规则，理由也一样——名字来自浏览器，
 * 所以它是输入，而不是事实。
 *
 * <p>SVG 被有意排除：它是可以携带脚本的文档，而一张背景图不值得开这个口子。只收光栅格式，由字节说了算
 * ——一个名字叫 {@code .jpg} 而实际内容为 PNG 的文件，就按它本来的 PNG 提供。
 */
public final class Wallpapers {

  /** 指向别处的环境变量。 */
  public static final String ENV = "CCJ_WALLPAPERS";

  /** 没人另行指定时它们住在哪儿，相对于 {@code $HOME}。 */
  public static final String DEFAULT_SUBDIR = "Pictures/ccj-backgrounds";

  /** 开头多少个字节就足以认出一种光栅格式。 */
  private static final int SNIFF_BYTES = 16;

  private static final Comparator<String> NATURAL = Wallpapers::natural;

  private final Path dir;

  public Wallpapers(Path dir) {
    this.dir = dir == null ? null : dir.toAbsolutePath().normalize();
  }

  /** 先看旗标，再看环境变量，最后是常规位置：{@code ~/Pictures/ccj-backgrounds}。 */
  public static Wallpapers from(Map<String, String> env, String flag) {
    if (flag != null && !flag.isBlank()) {
      return new Wallpapers(Path.of(flag.strip()));
    }
    String configured = env == null ? null : env.get(ENV);
    if (configured != null && !configured.isBlank()) {
      return new Wallpapers(Path.of(configured.strip()));
    }
    String home = env == null ? null : env.get("HOME");
    Path base =
        home == null || home.isBlank()
            ? Path.of(System.getProperty("user.home", "."))
            : Path.of(home);
    return new Wallpapers(base.resolve(DEFAULT_SUBDIR));
  }

  public Path directory() {
    return dir;
  }

  /** 有可读目录时为 true：目录不存在只意味着还没有人放过图片。 */
  public boolean available() {
    return dir != null && Files.isDirectory(dir);
  }

  /** 目录里的图片；带编号的按人阅读的顺序排：1、2、……10。 */
  public List<String> names() {
    if (!available()) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(dir)) {
      List<String> names = new ArrayList<>();
      for (Path entry : entries.toList()) {
        String name = entry.getFileName().toString();
        // 与页面请求将受到的同一个测试：在这里列出，就意味着在那里可被提供。
        if (resolve(name).isPresent()) {
          names.add(name);
        }
      }
      names.sort(NATURAL);
      return List.copyOf(names);
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取壁纸目录 " + dir, e);
    }
  }

  /** 页面所请求的名字背后的文件；不属于这个目录时为空。 */
  public Optional<Path> resolve(String name) {
    if (!available() || name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
      return Optional.empty();
    }
    Path file = dir.resolve(name).normalize();
    if (!dir.equals(file.getParent())
        || !Files.isRegularFile(file)
        || contentType(file).isEmpty()) {
      return Optional.empty();
    }
    try {
      // 位于本目录却指向目录之外的链接，不是本目录的图片。
      if (!file.toRealPath().startsWith(dir.toRealPath())) {
        return Optional.empty();
      }
    } catch (IOException e) {
      return Optional.empty();
    }
    return Optional.of(file);
  }

  /** 一张图片的媒体类型，读它自己的开头字节得出。 */
  public Optional<String> contentType(Path file) {
    byte[] head;
    try (InputStream in = Files.newInputStream(file)) {
      head = in.readNBytes(SNIFF_BYTES);
    } catch (IOException e) {
      return Optional.empty();
    }
    return Optional.ofNullable(sniff(head));
  }

  private static String sniff(byte[] b) {
    if (at(b, 0, 0x89, 'P', 'N', 'G')) {
      return "image/png";
    }
    if (at(b, 0, 0xFF, 0xD8, 0xFF)) {
      return "image/jpeg";
    }
    if (at(b, 0, 'G', 'I', 'F', '8')) {
      return "image/gif";
    }
    // RIFF 是与其他格式共用的容器；说出它是 WebP 的是里面那个形式标识。
    if (at(b, 0, 'R', 'I', 'F', 'F') && at(b, 8, "WEBP")) {
      return "image/webp";
    }
    if (at(b, 0, 'B', 'M')) {
      return "image/bmp";
    }
    // ISO 基础媒体文件都以 ftyp box 开头；品牌说明它是不是 AVIF。
    if (at(b, 4, "ftyp") && (at(b, 8, "avif") || at(b, 8, "avis"))) {
      return "image/avif";
    }
    return null;
  }

  private static boolean at(byte[] bytes, int offset, int... values) {
    if (bytes.length < offset + values.length) {
      return false;
    }
    for (int i = 0; i < values.length; i++) {
      if (bytes[offset + i] != (byte) values[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean at(byte[] bytes, int offset, String text) {
    if (bytes.length < offset + text.length()) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      if (bytes[offset + i] != (byte) text.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /** {@code 2.png} 排在 {@code 10.jpg} 之前：带编号的一组图是按数字读的。 */
  private static int natural(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      char ca = a.charAt(i);
      char cb = b.charAt(j);
      if (Character.isDigit(ca) && Character.isDigit(cb)) {
        int startA = i;
        int startB = j;
        while (i < a.length() && Character.isDigit(a.charAt(i))) {
          i++;
        }
        while (j < b.length() && Character.isDigit(b.charAt(j))) {
          j++;
        }
        int compared = compareDigits(a.substring(startA, i), b.substring(startB, j));
        if (compared != 0) {
          return compared;
        }
        continue;
      }
      int compared = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
      if (compared != 0) {
        return compared;
      }
      i++;
      j++;
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  /** 两个数字串的数值序，不做解析：前导零不是量级。 */
  private static int compareDigits(String a, String b) {
    String x = stripLeadingZeros(a);
    String y = stripLeadingZeros(b);
    return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
  }

  private static String stripLeadingZeros(String digits) {
    int i = 0;
    while (i < digits.length() - 1 && digits.charAt(i) == '0') {
      i++;
    }
    return digits.substring(i);
  }
}
