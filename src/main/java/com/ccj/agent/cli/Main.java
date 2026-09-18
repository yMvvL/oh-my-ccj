package com.ccj.agent.cli;

/** 进程入口：应用里唯一决定退出状态的地方。 */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    int code;
    try {
      code = new Cli().run(args);
    } catch (RuntimeException e) {
      String message = e.getMessage();
      System.err.println(
          "error: "
              + (message == null || message.isBlank() ? e.getClass().getSimpleName() : message));
      code = 1;
    }
    System.exit(code);
  }
}
