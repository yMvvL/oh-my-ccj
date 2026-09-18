package com.ccj.agent.ui;

/**
 * ANSI 颜色与样式辅助方法；当颜色只会成为噪音时，它们退化为恒等函数。
 *
 * <p>检测是进程级的，并且刻意保持简单：只有当 stdout 是真实终端且 {@code NO_COLOR} 未设置时，
 * 颜色才开启。被重定向的输出、CI 日志和 {@code NO_COLOR} 约定都会落到纯文本上，因此调用方可以
 * 随意外包，不必做任何检查。
 */
public final class Ansi {

  private static final String ESC = "\u001b[";
  private static volatile Boolean cached;

  private Ansi() {}

  /** 当 stdin/stdout 挂在终端上时为 true。 */
  public static boolean tty() {
    return System.console() != null;
  }

  public static boolean enabled() {
    Boolean value = cached;
    if (value == null) {
      value = tty() && System.getenv("NO_COLOR") == null;
      cached = value;
    }
    return value;
  }

  public static String style(String code, String text) {
    return style(code, text, enabled());
  }

  public static String style(String code, String text, boolean enabled) {
    return enabled ? ESC + code + "m" + text + ESC + "0m" : text;
  }

  public static String bold(String text) {
    return style("1", text);
  }

  public static String dim(String text) {
    return style("2", text);
  }

  public static String italic(String text) {
    return style("3", text);
  }

  public static String underline(String text) {
    return style("4", text);
  }

  public static String red(String text) {
    return style("31", text);
  }

  public static String green(String text) {
    return style("32", text);
  }

  public static String yellow(String text) {
    return style("33", text);
  }

  public static String blue(String text) {
    return style("34", text);
  }

  public static String magenta(String text) {
    return style("35", text);
  }

  public static String cyan(String text) {
    return style("36", text);
  }

  public static String gray(String text) {
    return style("90", text);
  }
}
