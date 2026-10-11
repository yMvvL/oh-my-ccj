package com.ccj.agent.ui;

/**
 * 二维码用的那套里德-所罗门纠错：GF(256)、本原多项式 0x11D、生成元 2。
 *
 * <p>它的形状是固定的（这个域是标准的一部分，不是可以挑的），所以这里的两个表在类初始化时算出来而不是抄：
 * 一张 256 项的反对数表与一张对数表，各自一次循环。抄一张 256 个数字的表，抄错一位只会表现为「某些内容
 * 刚好扫不出来」——而那正是那种要花一晚上才能找到的 bug。
 *
 * <p>它只做编码那一半：算出纠错码字。解码（也就是「从一堆可能有错的模块里把数据捞回来」）不在这个程序的
 * 职责里——那是扫描器的事，而验证的方式正是把码交给另一个实现去解（见 {@link QrCode} 的类注释）。
 */
final class ReedSolomon {

  /** 域的大小：GF(256)。 */
  private static final int FIELD = 256;

  /** 本原多项式 x^8 + x^4 + x^3 + x^2 + 1。 */
  private static final int PRIMITIVE = 0x11D;

  private static final int[] EXP = new int[FIELD * 2];
  private static final int[] LOG = new int[FIELD];

  static {
    int value = 1;
    for (int i = 0; i < FIELD; i++) {
      EXP[i] = value;
      if (i < FIELD - 1) {
        LOG[value] = i;
      }
      value <<= 1;
      if ((value & FIELD) != 0) {
        value ^= PRIMITIVE;
      }
    }
    // 把指数表接长一倍：乘法的两个指数相加最大到 508，接长之后就不必再取模。
    for (int i = FIELD; i < EXP.length; i++) {
      EXP[i] = EXP[i - (FIELD - 1)];
    }
  }

  private ReedSolomon() {}

  /**
   * {@code data} 的纠错码字，共 {@code ecLength} 个。
   *
   * <p>做法是标准那一步：把数据当成一个多项式，左移 {@code ecLength} 位，再对生成多项式取余。生成多项式是
   * (x − α^0)(x − α^1)…(x − α^{n−1})——它每一项都是首一的，所以最高位那一项不必存。
   */
  static byte[] encode(byte[] data, int ecLength) {
    int[] generator = generator(ecLength);
    int[] remainder = new int[ecLength];
    for (byte value : data) {
      int factor = (value & 0xff) ^ remainder[0];
      System.arraycopy(remainder, 1, remainder, 0, remainder.length - 1);
      remainder[remainder.length - 1] = 0;
      if (factor != 0) {
        for (int i = 0; i < ecLength; i++) {
          remainder[i] ^= multiply(generator[i], factor);
        }
      }
    }
    byte[] out = new byte[ecLength];
    for (int i = 0; i < ecLength; i++) {
      out[i] = (byte) remainder[i];
    }
    return out;
  }

  private static int[] generator(int degree) {
    int[] polynomial = {1};
    for (int i = 0; i < degree; i++) {
      int[] next = new int[polynomial.length + 1];
      for (int j = 0; j < polynomial.length; j++) {
        next[j] ^= polynomial[j];
        next[j + 1] ^= multiply(polynomial[j], EXP[i]);
      }
      polynomial = next;
    }
    // **去掉首项**（它一定是 1），剩下的按降幂排列。
    //
    // 这一处跳过一位，是这段代码唯一一处「差一格」就全错的地方：多项式除法里那个隐式的首项 1 已经在
    // 「factor = 数据字节 ^ remainder[0]」那一步里用掉了，把它再异或回 remainder[0] 会让每一项都错位。
    // 抓到它的不是读代码，是自检——合法的码字在所有 α^0…α^(n−1) 处取值必须为 0，而错位的版本处处不为 0。
    int[] out = new int[degree];
    System.arraycopy(polynomial, 1, out, 0, degree);
    return out;
  }

  private static int multiply(int a, int b) {
    if (a == 0 || b == 0) {
      return 0;
    }
    return EXP[LOG[a] + LOG[b]];
  }
}
