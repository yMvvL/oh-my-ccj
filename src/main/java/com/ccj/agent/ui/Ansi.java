package com.ccj.agent.ui;

/**
 * ANSI colour and style helpers that turn into identity functions when colour would be noise.
 *
 * <p>Detection is process-wide and deliberately simple: colour is on only when stdout is a real
 * terminal and {@code NO_COLOR} is unset. Redirected output, CI logs and the {@code NO_COLOR}
 * convention all land on plain text, so callers can wrap freely without checking anything.
 */
public final class Ansi {

  private static final String ESC = "\u001b[";
  private static volatile Boolean cached;

  private Ansi() {}

  /** True when stdin/stdout are attached to a terminal. */
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
