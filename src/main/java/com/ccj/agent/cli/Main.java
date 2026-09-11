package com.ccj.agent.cli;

/** Process entry point: the only place in the application that decides the exit status. */
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
