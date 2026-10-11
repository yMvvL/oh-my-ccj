package com.ccj.agent.ui;

/**
 * 一个二维码编码器——字节模式、纠错级别 M、版本 1 到 {@link #MAX_VERSION}。
 *
 * <p>它存在的理由只有一条：**让手机扫一下就进得来**。web 控制台默认只在环回和你自己的 tailnet 上服务，所以
 * 「把地址敲进手机」这句话的意思是敲一个长 IP 加一个 64 位的 token。终端里画一个码，这一整步就消失了。
 *
 * <p>手写而不是引一个库，理由与这个仓库里其它自研的东西一样：它只有几百行、没有依赖、而它的正确性**可以被
 * 独立验证**。这里用的验证方式是：把矩阵渲染成 PNG，交给 {@code zbarimg}（zbar，另一个实现）去解，解出来的
 * 字符串必须与原样一字不差——见 {@code QrCodeTest}。一个只对着自己的解码器自证的编码器，证明的是它自洽。
 *
 * <p>版本上界是 10（字节模式、级别 M 下约 213 字节），因为要编码的东西是一个本机 URL：留出余量之后 10 已经
 * 远远够用，而完整实现 40 个版本意味着 40 行 RS 分块表——那些行每一行都得靠记忆抄，抄错的那一行只会表现为
 * 「某个长度的内容刚好扫不出来」。超过上界时 {@link #fits} 返回 false，调用方照旧打印 URL 文本。
 */
public final class QrCode {

  /** 支持到第几版。见类注释里那个取舍。 */
  public static final int MAX_VERSION = 10;

  /** 纠错级别 M：约 15% 的冗余。终端上放大着看不需要 H，而 L 在手机上容易被拍歪。 */
  private static final int EC_LEVEL_BITS = 0b00; // 级别 M 的线上编码就是 00

  private static final int BYTE_MODE = 0b0100;

  /**
   * 版本 → 每个 RS 块的纠错码字数，以及两组数据块（第二组每块比第一组多一个数据码字）。
   *
   * <p>这些数字是标准里的表（级别 M）。它们不是推导出来的，是抄下来的——所以这里只有 1 到 10，而每一行都由
   * 「zbarimg 能不能解出来」覆盖着：抄错一行，某个版本的内容就扫不出来。
   */
  private static final int[][] BLOCKS_M = {
    // 版本, 每块纠错码字, 第一组块数, 第一组每块数据码字, 第二组块数, 第二组每块数据码字
    {1, 10, 1, 16, 0, 0},
    {2, 16, 1, 28, 0, 0},
    {3, 26, 1, 44, 0, 0},
    {4, 18, 2, 32, 0, 0},
    {5, 24, 2, 43, 0, 0},
    {6, 16, 4, 27, 0, 0},
    {7, 18, 4, 31, 0, 0},
    {8, 22, 2, 38, 2, 39},
    {9, 22, 3, 36, 2, 37},
    {10, 26, 4, 43, 1, 44},
  };

  /** 版本 → 对齐图形的中心坐标。版本 1 没有对齐图形。 */
  private static final int[][] ALIGNMENT = {
    {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34}, {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50},
  };

  /** 版本 → 符号里一共有多少码字（数据 + 纠错）。 */
  private static final int[] TOTAL_CODEWORDS = {26, 44, 70, 100, 134, 172, 196, 242, 292, 346};

  private final int version;
  private final int size;
  private final boolean[][] modules;
  private final boolean[][] isFunction;

  private QrCode(int version) {
    this.version = version;
    this.size = 17 + 4 * version;
    this.modules = new boolean[size][size];
    this.isFunction = new boolean[size][size];
  }

  /** 这段文字放得进本编码器支持的最大版本吗。 */
  public static boolean fits(String text) {
    return capacityBytes(text) >= 0 && versionFor(text) > 0;
  }

  /** 编码之后的模块矩阵：{@code true} 是黑。测试与渲染都从这里取。 */
  public static boolean[][] matrix(String text) {
    return build(text, 0, -1).modules;
  }

  /**
   * 钉住版本与掩码的编码；两个参数都是给测试用的（{-1} 表示按规范自己选）。
   *
   * <p>钉住掩码那一个是为了与另一个实现对比矩阵：掩码是「同样的数据可以有八种画法」的那一步，只有两边都钉住
   * 它，逐个模块的比较才是在比较编码，而不是在比较两个实现各自的掩码打分。
   */
  static boolean[][] matrix(String text, int version, int mask) {
    return build(text, version, mask).modules;
  }

  /** 这个版本号与掩码下的矩阵。 */
  private static QrCode build(String text, int version, int mask) {
    int chosen = version > 0 ? version : versionFor(text);
    if (chosen <= 0 || chosen > MAX_VERSION) {
      throw new IllegalArgumentException(
          "这段文字放不进 " + MAX_VERSION + " 版（级别 M 下约 " + MAX_VERSION_DATA_BYTES + " 字节），实际 "
              + text.length() + " 字节：请把地址缩短，或者照旧打印文本");
    }
    QrCode code = new QrCode(chosen);
    code.placeFunctionPatterns();
    code.placeData(payload(text, chosen));
    int applied = mask >= 0 ? mask : code.chooseMask();
    code.applyMask(applied);
    code.placeFormatInfo(applied);
    if (chosen >= 7) {
      code.placeVersionInfo();
    }
    return code;
  }

  /** 10 版在级别 M 下能装多少字节：给报错信息用的数字。 */
  private static final int MAX_VERSION_DATA_BYTES = 213;

  /** 这段文字需要第几版；放不下时为 0。 */
  private static int versionFor(String text) {
    int bytes = capacityBytes(text);
    if (bytes < 0) {
      return 0;
    }
    for (int candidate = 1; candidate <= MAX_VERSION; candidate++) {
      if (bytes <= dataCodewords(candidate)) {
        return candidate;
      }
    }
    return 0;
  }

  /** 字节模式下一共需要多少个数据码字（含模式指示符、长度、以及一个可能多出来的填充字节）。 */
  private static int capacityBytes(String text) {
    int length = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    if (length == 0) {
      return -1;
    }
    int header = 4 + (length < 256 ? 8 : 16); // 模式指示符 + 字符计数（版本 10 以内是 8 位）
    return (header + length * 8 + 7) / 8;
  }

  private static int dataCodewords(int version) {
    int[] block = BLOCKS_M[version - 1];
    return block[2] * block[3] + block[4] * block[5];
  }

  // ------------------------------------------------------------------ 数据

  /** 模式指示符、长度、数据、终止符、补齐到字节、再补齐那些规定的填充码字。 */
  private static boolean[] payload(String text, int version) {
    byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    int dataCodewords = dataCodewords(version);
    boolean[] bits = new boolean[dataCodewords * 8];
    int at = 0;
    at = write(bits, at, BYTE_MODE, 4);
    // 字符计数在版本 1–9 是 8 位，10–26 是 16 位。
    at = write(bits, at, bytes.length, version <= 9 ? 8 : 16);
    for (byte value : bytes) {
      at = write(bits, at, value & 0xff, 8);
    }
    // 终止符最多四位，之后补零到字节边界——两者都不可以越过数据容量。
    at = Math.min(bits.length, at + 4);
    while (at < bits.length && at % 8 != 0) {
      at++;
    }
    // 规定的填充码字 0xEC / 0x11 交替，直到装满。
    for (int pad = 0; at < bits.length; pad++) {
      at = write(bits, at, pad % 2 == 0 ? 0xEC : 0x11, 8);
    }
    return interleaveWithErrorCorrection(bits, version);
  }

  private static int write(boolean[] bits, int at, int value, int width) {
    for (int i = width - 1; i >= 0 && at < bits.length; i--, at++) {
      bits[at] = ((value >>> i) & 1) != 0;
    }
    return at;
  }

  /**
   * 分块、各自算纠错、再交错。
   *
   * <p>交错不是可选的花样：一个块里的码字必须散布到整个符号上，否则一道划痕只会打穿一个块，而一个块能被修
   * 好的错误数量是有限的。
   */
  private static boolean[] interleaveWithErrorCorrection(boolean[] data, int version) {
    int[] block = BLOCKS_M[version - 1];
    int ecPerBlock = block[1];
    int[] blockSizes = new int[block[2] + block[4]];
    for (int i = 0; i < blockSizes.length; i++) {
      blockSizes[i] = i < block[2] ? block[3] : block[5];
    }
    byte[][] dataBlocks = new byte[blockSizes.length][];
    byte[][] ecBlocks = new byte[blockSizes.length][];
    int cursor = 0;
    for (int i = 0; i < blockSizes.length; i++) {
      dataBlocks[i] = new byte[blockSizes[i]];
      for (int j = 0; j < blockSizes[i]; j++, cursor += 8) {
        dataBlocks[i][j] = (byte) readBits(data, cursor, 8);
      }
      ecBlocks[i] = ReedSolomon.encode(dataBlocks[i], ecPerBlock);
    }
    boolean[] out = new boolean[TOTAL_CODEWORDS[version - 1] * 8];
    int at = 0;
    int longest = 0;
    for (int length : blockSizes) {
      longest = Math.max(longest, length);
    }
    for (int j = 0; j < longest; j++) {
      for (byte[] dataBlock : dataBlocks) {
        if (j < dataBlock.length) {
          at = write(out, at, dataBlock[j] & 0xff, 8);
        }
      }
    }
    for (int j = 0; j < ecPerBlock; j++) {
      for (byte[] ecBlock : ecBlocks) {
        at = write(out, at, ecBlock[j] & 0xff, 8);
      }
    }
    return out;
  }

  private static int readBits(boolean[] bits, int at, int width) {
    int value = 0;
    for (int i = 0; i < width; i++) {
      value = (value << 1) | (bits[at + i] ? 1 : 0);
    }
    return value;
  }

  // ------------------------------------------------------------------ 画矩阵

  /** 定位图形、分隔符、时序图形、对齐图形、以及固定黑模块。 */
  private void placeFunctionPatterns() {
    for (int[] corner : new int[][] {{0, 0}, {0, size - 7}, {size - 7, 0}}) {
      for (int dy = -1; dy <= 7; dy++) {
        for (int dx = -1; dx <= 7; dx++) {
          int row = corner[0] + dy;
          int col = corner[1] + dx;
          if (row < 0 || row >= size || col < 0 || col >= size) {
            continue;
          }
          boolean separator = dy == -1 || dy == 7 || dx == -1 || dx == 7;
          boolean ring = dy == 0 || dy == 6 || dx == 0 || dx == 6;
          boolean core = dy >= 2 && dy <= 4 && dx >= 2 && dx <= 4;
          set(row, col, !separator && (ring || core));
        }
      }
    }
    // 时序图形：第六行与第六列，交替深浅，两端由定位图形占着。
    for (int i = 8; i < size - 8; i++) {
      boolean dark = i % 2 == 0;
      set(6, i, dark);
      set(i, 6, dark);
    }
    // 对齐图形：位置表的每一种组合，除了三个角（那里是定位图形）。
    int[] centers = ALIGNMENT[version - 1];
    for (int row : centers) {
      for (int col : centers) {
        boolean corner =
            (row == 6 && col == 6)
                || (row == 6 && col == size - 7)
                || (row == size - 7 && col == 6);
        if (corner) {
          continue;
        }
        for (int dy = -2; dy <= 2; dy++) {
          for (int dx = -2; dx <= 2; dx++) {
            boolean dark = Math.max(Math.abs(dy), Math.abs(dx)) != 1;
            set(row + dy, col + dx, dark);
          }
        }
      }
    }
    // 固定黑模块（格式信息的一部分）。
    set(size - 8, 8, true);
    // 格式信息与版本信息的位置先占住，免得数据被放进被它们占用的格子里。
    for (int i = 0; i <= 8; i++) {
      reserve(8, i);
      reserve(i, 8);
    }
    for (int i = 0; i < 8; i++) {
      reserve(8, size - 1 - i);
      reserve(size - 1 - i, 8);
    }
    if (version >= 7) {
      for (int i = 0; i < 6; i++) {
        for (int j = 0; j < 3; j++) {
          reserve(size - 11 + j, i);
          reserve(i, size - 11 + j);
        }
      }
    }
  }

  private void set(int row, int col, boolean dark) {
    modules[row][col] = dark;
    isFunction[row][col] = true;
  }

  private void reserve(int row, int col) {
    isFunction[row][col] = true;
  }

  /** 数据从右下角开始，两列一组左移，跳过第六列，组内上下交替。 */
  private void placeData(boolean[] data) {
    int at = 0;
    int row = size - 1;
    int direction = -1;
    for (int col = size - 1; col > 0; col -= 2) {
      if (col == 6) {
        col--; // 垂直时序图形那一列不参与
      }
      while (true) {
        for (int pair = 0; pair < 2; pair++) {
          int x = col - pair;
          if (!isFunction[row][x]) {
            modules[row][x] = at < data.length && data[at];
            at++;
          }
        }
        row += direction;
        if (row < 0 || row >= size) {
          row -= direction;
          direction = -direction;
          break;
        }
      }
    }
  }

  private void applyMask(int mask) {
    for (int row = 0; row < size; row++) {
      for (int col = 0; col < size; col++) {
        if (!isFunction[row][col] && masked(mask, row, col)) {
          modules[row][col] = !modules[row][col];
        }
      }
    }
  }

  /** 八种掩码，按规范定义。 */
  private static boolean masked(int mask, int row, int col) {
    return switch (mask) {
      case 0 -> (row + col) % 2 == 0;
      case 1 -> row % 2 == 0;
      case 2 -> col % 3 == 0;
      case 3 -> (row + col) % 3 == 0;
      case 4 -> (row / 2 + col / 3) % 2 == 0;
      case 5 -> (row * col) % 2 + (row * col) % 3 == 0;
      case 6 -> ((row * col) % 2 + (row * col) % 3) % 2 == 0;
      default -> ((row + col) % 2 + (row * col) % 3) % 2 == 0;
    };
  }

  /** 按规范那四条罚分规则挑一个损失最小的掩码。 */
  private int chooseMask() {
    int best = 0;
    int bestPenalty = Integer.MAX_VALUE;
    for (int mask = 0; mask < 8; mask++) {
      for (int row = 0; row < size; row++) {
        for (int col = 0; col < size; col++) {
          if (!isFunction[row][col] && masked(mask, row, col)) {
            modules[row][col] = !modules[row][col];
          }
        }
      }
      placeFormatInfo(mask);
      int penalty = penalty();
      if (penalty < bestPenalty) {
        bestPenalty = penalty;
        best = mask;
      }
      // 还原，好让下一个候选从同一份数据出发。
      for (int row = 0; row < size; row++) {
        for (int col = 0; col < size; col++) {
          if (!isFunction[row][col] && masked(mask, row, col)) {
            modules[row][col] = !modules[row][col];
          }
        }
      }
    }
    return best;
  }

  /** 四条罚分规则合起来，越小越好。 */
  private int penalty() {
    int penalty = 0;
    // 规则一：同色的连续五个以上。
    for (int i = 0; i < size; i++) {
      penalty += runs(modules[i]);
      boolean[] column = new boolean[size];
      for (int j = 0; j < size; j++) {
        column[j] = modules[j][i];
      }
      penalty += runs(column);
    }
    // 规则二：同色的 2×2 方块。
    for (int row = 0; row + 1 < size; row++) {
      for (int col = 0; col + 1 < size; col++) {
        boolean first = modules[row][col];
        if (first == modules[row][col + 1] && first == modules[row + 1][col] && first == modules[row + 1][col + 1]) {
          penalty += 3;
        }
      }
    }
    // 规则三：看起来像定位图形的那两种花样。
    for (int i = 0; i < size; i++) {
      boolean[] column = columnOf(i);
      for (int j = 0; j + 7 <= size; j++) {
        penalty += finderLike(modules[i], j);
        penalty += finderLike(column, j);
      }
    }
    // 规则四：黑模块的比例偏离一半多少。
    int dark = 0;
    for (boolean[] line : modules) {
      for (boolean module : line) {
        if (module) {
          dark++;
        }
      }
    }
    int percent = dark * 100 / (size * size);
    penalty += Math.abs(percent - 50) / 5 * 10;
    return penalty;
  }

  private boolean[] columnOf(int col) {
    boolean[] column = new boolean[size];
    for (int row = 0; row < size; row++) {
      column[row] = modules[row][col];
    }
    return column;
  }

  private static int runs(boolean[] line) {
    int penalty = 0;
    int length = 1;
    for (int i = 1; i < line.length; i++) {
      if (line[i] == line[i - 1]) {
        length++;
      } else {
        if (length >= 5) {
          penalty += 3 + (length - 5);
        }
        length = 1;
      }
    }
    if (length >= 5) {
      penalty += 3 + (length - 5);
    }
    return penalty;
  }

  /** 规则三要抓的那个核：1:1:3:1:1（也就是定位图形中间那七格的比例）。 */
  private static final boolean[] FINDER_CORE = {true, false, true, true, true, false, true};

  /**
   * 规则三：一个定位核，**左边或右边**有四格亮区，罚 40。
   *
   * <p>「或」是这里最容易读错的一个字，而它错了整整一倍的分数：把两种写法（亮区在前、亮区在后）当成两个
   * 独立的花样去数，一个两侧都亮的核会被数上两次——而两侧都亮恰恰是常见情形（大片空白里的核）。实测：
   * 那条把「或」读成「与」的实现，在这台机器的样例上给八个掩码打出的分是 597/628…，于是它挑了另一个掩码
   * （六个里挑中 6），而 qrencode 挑 3；改回「或」之后两者一致。
   */
  private static int finderLike(boolean[] line, int at) {
    if (!matches(line, at, FINDER_CORE)) {
      return 0;
    }
    boolean lightBefore = at >= 4 && light(line, at - 4, at);
    boolean lightAfter = at + 11 <= line.length && light(line, at + 7, at + 11);
    return lightBefore || lightAfter ? 40 : 0;
  }

  private static boolean light(boolean[] line, int from, int to) {
    for (int i = from; i < to; i++) {
      if (line[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean matches(boolean[] line, int at, boolean[] pattern) {
    for (int i = 0; i < pattern.length; i++) {
      if (line[at + i] != pattern[i]) {
        return false;
      }
    }
    return true;
  }

  /** 十五位格式信息，两处副本各放一次。 */
  private void placeFormatInfo(int mask) {
    int data = (EC_LEVEL_BITS << 3) | mask;
    int encoded = data << 10;
    for (int i = 14; i >= 10; i--) {
      if (((encoded >>> (i)) & 1) != 0) {
        encoded ^= 0x537 << (i - 10);
      }
    }
    int format = ((data << 10) | encoded) ^ 0x5412;
    int[][] first = {
      {8, 0}, {8, 1}, {8, 2}, {8, 3}, {8, 4}, {8, 5}, {8, 7}, {8, 8},
      {7, 8}, {5, 8}, {4, 8}, {3, 8}, {2, 8}, {1, 8}, {0, 8},
    };
    int[][] second = {
      {size - 1, 8}, {size - 2, 8}, {size - 3, 8}, {size - 4, 8}, {size - 5, 8}, {size - 6, 8}, {size - 7, 8},
      {8, size - 8}, {8, size - 7}, {8, size - 6}, {8, size - 5}, {8, size - 4}, {8, size - 3}, {8, size - 2}, {8, size - 1},
    };
    for (int bit = 0; bit < 15; bit++) {
      // **高位在前**：十五位的格式信息从最高位开始放进那两串位置。写成低位在前，读起来完全合理——而两个
      // 副本会一致地错，所以「两处副本相同」这种检查抓不到它。抓到它的是 zbar：扫描器从格式位里学掩码，
      // 读反了就用错的掩码去解数据，于是整张图一个字符都解不出来。
      boolean dark = ((format >>> (14 - bit)) & 1) != 0;
      modules[first[bit][0]][first[bit][1]] = dark;
      modules[second[bit][0]][second[bit][1]] = dark;
    }
  }

  /** 十八位版本信息，只出现在第七版及以上。 */
  private void placeVersionInfo() {
    int encoded = version << 12;
    for (int i = 17; i >= 12; i--) {
      if (((encoded >>> i) & 1) != 0) {
        encoded ^= 0x1F25 << (i - 12);
      }
    }
    int versionInfo = (version << 12) | encoded;
    for (int i = 0; i < 18; i++) {
      boolean dark = ((versionInfo >>> i) & 1) != 0;
      int row = i / 3;
      int col = size - 11 + i % 3;
      modules[col][row] = dark;
      modules[row][col] = dark;
    }
  }

  // ------------------------------------------------------------------ 输出

  /**
   * 终端里的画法：一行两个模块（用上下半块字符），四周留两格白边。
   *
   * <p>白边不是装饰：没有它，扫描器找不到码的边界。半块字符是这一层唯一的技巧——一个字符格放两行模块，
   * 于是 21 版的码只占 11 行终端。
   */
  public static String forTerminal(String text, int quietZone) {
    boolean[][] matrix = matrix(text);
    int size = matrix.length;
    int margin = Math.max(0, quietZone);
    StringBuilder out = new StringBuilder();
    for (int row = -margin; row < size + margin; row += 2) {
      for (int col = -margin; col < size + margin; col++) {
        boolean top = row >= 0 && row < size && col >= 0 && col < size && matrix[row][col];
        boolean bottom = row + 1 >= 0 && row + 1 < size && col >= 0 && col < size && matrix[row + 1][col];
        out.append(top ? (bottom ? '\u2588' : '\u2580') : (bottom ? '\u2584' : ' '));
      }
      out.append('\n');
    }
    return out.toString();
  }
}
