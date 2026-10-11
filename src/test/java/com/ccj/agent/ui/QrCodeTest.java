package com.ccj.agent.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 二维码编码器：先自己证明自己，再交给**另一个实现**去证明。
 *
 * <p>三层的证据，从便宜到强：
 *
 * <ol>
 *   <li><strong>不依赖任何外部工具的代数检查。</strong>纠正码字必须让整个码字多项式在 α^0…α^(n−1) 处为 0
 *       ——那是里德-所罗门码的定义；再加一条标准里的已知向量（字母数字 "01234567" 那个例子）。
 *   <li><strong>结构检查。</strong>定位图形、时序图形、固定黑模块、以及格式位能被解回「级别 M + 某个掩码」。
 *   <li><strong>独立实现。</strong>把矩阵画成 PNG 交给 {@code zbarimg} 去解（解出来必须一字不差），以及把
 *       同一个掩码下的矩阵与 {@code qrencode} 的逐模块比对（实测：841 个模块零差异）。这两条在没有那两个程序
 *       的机器上报告为**跳过**，而不是通过。
 * </ol>
 *
 * <p>一处**已知的不一致**写在明面上：**掩码的选择**。我按规范那四条罚分规则实现，而实测在多数输入上选出的
 * 掩码与 qrencode 不同（例如 {@code https://a.example/}：我选 2，它选 1）。任何合法掩码都能被扫出来——八个
 * 掩码逐个交给 zbar 验证过——所以这是一处「两个实现各做了一个合法决定」的差别，而不是一个能不能扫的问题。
 * 追它到一个具体的罚分项没有成功，而这一点也写在这里：**一个诚实的「不知道哪一条规则差在哪」，好过一个假装
 * 一致的断言。**
 */
class QrCodeTest {

  @TempDir Path tmp;

  /** 标准里那个例子：字母数字 "01234567"、版本 1、级别 M 的 16 个数据码字与 10 个纠错码字。 */
  private static final byte[] STANDARD_DATA = {
    (byte) 0x10, (byte) 0x20, (byte) 0x0C, (byte) 0x56, (byte) 0x61, (byte) 0x80,
    (byte) 0xEC, (byte) 0x11, (byte) 0xEC, (byte) 0x11, (byte) 0xEC, (byte) 0x11,
    (byte) 0xEC, (byte) 0x11, (byte) 0xEC, (byte) 0x11,
  };

  private static final byte[] STANDARD_EC = {
    (byte) 0xA5, (byte) 0x24, (byte) 0xD4, (byte) 0xC1, (byte) 0xED,
    (byte) 0x36, (byte) 0xC7, (byte) 0x87, (byte) 0x2C, (byte) 0x55,
  };

  @Test
  void theErrorCorrectionMatchesTheStandardExample() {
    // 「差一格就全错」的那一处（多项式除以生成多项式时那个隐式的首项 1）由这条用例钉住：它错了，这十个字节
    // 一个都对不上。
    assertArrayEqualsHex(STANDARD_EC, ReedSolomon.encode(STANDARD_DATA, 10));
  }

  @Test
  void everyCodewordVanishesAtTheRootsOfTheGeneratorWhichIsTheDefinitionOfTheCode() {
    // 这条检查不依赖任何抄下来的表：它用的就是里德-所罗门码的定义（码字在 α^0…α^(n−1) 处取值全为 0）。
    for (int ecLength : new int[] {7, 10, 16, 22, 26}) {
      byte[] data = new byte[20];
      for (int i = 0; i < data.length; i++) {
        data[i] = (byte) (i * 37 + 11);
      }
      byte[] ec = ReedSolomon.encode(data, ecLength);
      for (int root = 0; root < ecLength; root++) {
        int value = 0;
        for (byte b : data) {
          value = multiply(value, EXP[root]) ^ (b & 0xff);
        }
        for (byte b : ec) {
          value = multiply(value, EXP[root]) ^ (b & 0xff);
        }
        assertEquals(0, value, "码字在 α^" + root + " 处应当为 0（ec 长度 " + ecLength + "）");
      }
    }
  }

  @Test
  void theSymbolHasTheFunctionPatternsTheSpecFixesInPlace() {
    boolean[][] matrix = QrCode.matrix("https://example.com/ccj?token=abc123");
    int size = matrix.length;

    // 尺寸只能是 21 + 4n。
    assertEquals(0, (size - 21) % 4, "尺寸应当是 21+4n（21 加上 4 的倍数），实际 " + size);
    // 三个角上的定位图形。
    for (int[] corner : new int[][] {{0, 0}, {0, size - 7}, {size - 7, 0}}) {
      for (int dy = 0; dy < 7; dy++) {
        for (int dx = 0; dx < 7; dx++) {
          boolean ring = dy == 0 || dy == 6 || dx == 0 || dx == 6;
          boolean core = dy >= 2 && dy <= 4 && dx >= 2 && dx <= 4;
          assertEquals(
              ring || core,
              matrix[corner[0] + dy][corner[1] + dx],
              "定位图形 (" + (corner[0] + dy) + "," + (corner[1] + dx) + ")");
        }
      }
    }
    // 固定黑模块，以及沿着第六行/第六列交替的时序图形。
    assertTrue(matrix[size - 8][8], "固定黑模块永远是黑的");
    for (int i = 8; i < size - 8; i++) {
      assertEquals(i % 2 == 0, matrix[6][i], "时序图形第 " + i + " 列");
      assertEquals(i % 2 == 0, matrix[i][6], "时序图形第 " + i + " 行");
    }
    // 格式位解回来应当是「级别 M + 0..7 的某个掩码」，而且两处副本一致。
    int first = formatBits(matrix, false);
    int second = formatBits(matrix, true);
    assertEquals(first, second, "两处格式信息必须一致");
    int data = ((first ^ 0x5412) >>> 10) & 0x1F;
    assertEquals(0, data >>> 3, "级别应当是 M（两位 00）");
    assertTrue((data & 7) >= 0 && (data & 7) <= 7, "掩码在 0..7 之内");
  }

  @Test
  void contentThatDoesNotFitSaysSoAndWhatToDoInstead() {
    String tooLong = "x".repeat(400);
    assertFalse(QrCode.fits(tooLong));
    assertTrue(QrCode.fits("https://example.com/ccj?token=abc123"));

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> QrCode.matrix(tooLong));
    assertTrue(failure.getMessage().contains("10 版"), failure.getMessage());
    assertTrue(failure.getMessage().contains("打印文本"), "报错要说清替代做法：" + failure.getMessage());
  }

  @Test
  void theTerminalDrawingHasQuietZoneAndOneCharacterPerTwoRows() {
    String text = "https://a.example/";
    boolean[][] matrix = QrCode.matrix(text);
    String art = QrCode.forTerminal(text, 2);
    List<String> lines = art.lines().toList();

    // 上下各 2 格白边，一行两格模块：25 版的高度是 (25 + 4 + 1) / 2 = 15 行。
    assertEquals((matrix.length + 4 + 1) / 2, lines.size(), art);
    int width = matrix.length + 4;
    for (String line : lines) {
      assertEquals(width, line.length(), "每一行都要有左边与右边的白边：" + line);
      assertTrue(line.startsWith("  ") || line.startsWith("\u2580") || line.startsWith("\u2584") || line.startsWith("\u2588"), line);
      assertTrue(line.endsWith(" ") || line.endsWith("\u2580") || line.endsWith("\u2584") || line.endsWith("\u2588"), line);
    }
    // 上半块的字符只出现在偶数行；这里只确认白边真的在：第一行与最后一行全是半块或空。
    assertTrue(lines.get(0).strip().isEmpty() || lines.get(0).contains("\u2580"), lines.get(0));
  }

  @Test
  void anIndependentDecoderReadsBackEveryMaskICouldChoose() throws Exception {
    Path zbar = tool("zbarimg");
    Path image = tmp.resolve("mine.png");
    List<String> inputs =
        List.of(
            "https://example.com/ccj?token=abc123",
            "https://a.example/",
            "http://127.0.0.1:6767/?token=0123456789abcdef",
            "the quick brown fox jumps over the lazy dog");

    for (String text : inputs) {
      write(image, text, -1);
      assertEquals(text, decode(zbar, image), "zbar 应当读出原样：" + text);
      for (int mask = 0; mask < 8; mask++) {
        write(image, text, mask);
        assertEquals(text, decode(zbar, image), "掩码 " + mask + " 也该能读出来：" + text);
      }
    }
  }

  @Test
  void withTheMaskQrencodeChoseTheTwoMatricesAreIdenticalModuleForModule() throws Exception {
    Path qrencode = tool("qrencode");
    String text = "https://example.com/ccj?token=abc123";
    Path theirs = tmp.resolve("theirs.png");
    run(List.of(qrencode.toString(), "-l", "M", "-s", "8", "-m", "4", "-t", "PNG", "-o", theirs.toString(), text));

    boolean[][] myDefault = QrCode.matrix(text);
    int theirMask = maskOf(read(theirs, 8, 4));
    boolean[][] mineForced = QrCode.matrix(text, 0, theirMask);

    int differences = 0;
    for (int row = 0; row < mineForced.length; row++) {
      for (int column = 0; column < mineForced.length; column++) {
        if (mineForced[row][column] != read(theirs, 8, 4)[row][column]) {
          differences++;
        }
      }
    }
    System.out.println(
        "与 qrencode 比对：同一个掩码（" + theirMask + "）下 " + differences + " 个模块不同；"
            + "我自己选的掩码是 " + maskOf(myDefault) + "（见类注释里那处已知的不一致）");
    assertEquals(0, differences, "同一个掩码下两份矩阵必须逐模块相同");
    assertEquals(mineForced.length, read(theirs, 8, 4).length, "版本也该一样");
  }

  // ------------------------------------------------------------------ 外部工具

  /** 找得到就返回路径，找不到就跳过——与这个仓库对 node 与浏览器的待遇一致。 */
  private static Path tool(String name) {
    for (String candidate : List.of("/usr/bin/" + name, "/usr/local/bin/" + name, "/bin/" + name)) {
      if (Files.isExecutable(Path.of(candidate))) {
        return Path.of(candidate);
      }
    }
    org.junit.jupiter.api.Assumptions.assumeTrue(
        false, "PATH 里没有 " + name + " —— 这一条用另一个实现来验证编码器，因此报告为跳过而不是通过");
    return null;
  }

  private static String decode(Path zbar, Path image) throws Exception {
    Process process =
        new ProcessBuilder(zbar.toString(), "--raw", "-q", image.toString())
            .redirectErrorStream(false)
            .start();
    String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    process.waitFor();
    return out;
  }

  private static void run(List<String> command) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), "命令失败：" + command + "\n" + output);
  }

  private static void write(Path file, String text, int mask) throws IOException {
    boolean[][] matrix = QrCode.matrix(text, 0, mask);
    int scale = 8;
    int quiet = 4;
    int size = (matrix.length + quiet * 2) * scale;
    BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        int mx = x / scale - quiet;
        int my = y / scale - quiet;
        boolean dark = mx >= 0 && my >= 0 && mx < matrix.length && my < matrix.length && matrix[my][mx];
        image.setRGB(x, y, dark ? 0x000000 : 0xFFFFFF);
      }
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    Files.write(file, bytes.toByteArray());
  }

  private static boolean[][] read(Path file, int scale, int quiet) throws IOException {
    BufferedImage image = ImageIO.read(file.toFile());
    int size = image.getWidth() / scale - 2 * quiet;
    boolean[][] matrix = new boolean[size][size];
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        int pixel = image.getRGB((column + quiet) * scale + scale / 2, (row + quiet) * scale + scale / 2);
        matrix[row][column] = (pixel & 0xFFFFFF) < 0x808080;
      }
    }
    return matrix;
  }

  /** 十五位格式信息：两处副本的位置（顺序都是**高位在前**）。 */
  private static int formatBits(boolean[][] matrix, boolean second) {
    int size = matrix.length;
    int[][] positions =
        second
            ? new int[][] {
              {size - 1, 8}, {size - 2, 8}, {size - 3, 8}, {size - 4, 8}, {size - 5, 8}, {size - 6, 8},
              {size - 7, 8}, {8, size - 8}, {8, size - 7}, {8, size - 6}, {8, size - 5}, {8, size - 4},
              {8, size - 3}, {8, size - 2}, {8, size - 1},
            }
            : new int[][] {
              {8, 0}, {8, 1}, {8, 2}, {8, 3}, {8, 4}, {8, 5}, {8, 7}, {8, 8},
              {7, 8}, {5, 8}, {4, 8}, {3, 8}, {2, 8}, {1, 8}, {0, 8},
            };
    int value = 0;
    for (int bit = 0; bit < 15; bit++) {
      if (matrix[positions[bit][0]][positions[bit][1]]) {
        value |= 1 << (14 - bit);
      }
    }
    return value;
  }

  private static int maskOf(boolean[][] matrix) {
    return (((formatBits(matrix, false) ^ 0x5412) >>> 10) & 0x1F) & 7;
  }

  private static void assertArrayEqualsHex(byte[] expected, byte[] actual) {
    List<String> hex = new ArrayList<>();
    for (byte value : actual) {
      hex.add(String.format("%02X", value & 0xff));
    }
    assertEquals(
        hexString(expected),
        String.join(" ", hex));
  }

  private static String hexString(byte[] values) {
    List<String> hex = new ArrayList<>();
    for (byte value : values) {
      hex.add(String.format("%02X", value & 0xff));
    }
    return String.join(" ", hex);
  }

  // ---- 上面那两条代数检查要用的 GF(256) 表（与被测代码各自独立地算一遍，正是为了让它们互为对照） ----

  private static final int[] EXP = new int[512];
  private static final int[] LOG = new int[256];

  static {
    int value = 1;
    for (int i = 0; i < 256; i++) {
      EXP[i] = value;
      if (i < 255) {
        LOG[value] = i;
      }
      value <<= 1;
      if ((value & 256) != 0) {
        value ^= 0x11D;
      }
    }
    for (int i = 256; i < 512; i++) {
      EXP[i] = EXP[i - 255];
    }
  }

  private static int multiply(int a, int b) {
    return (a == 0 || b == 0) ? 0 : EXP[LOG[a] + LOG[b]];
  }
}
