package com.ccj.agent.core;

import java.nio.file.Path;

/**
 * 代理运行时所用的提示词。
 *
 * <p>各部分按刻意的顺序摆放：先是项目自己的规则，然后是内置规则。项目文件排在<em>最前</em>，因为它是关于
 * 这项工作的具体陈述——构建命令、不许碰的模块——而先读到通用指令的读者，得一边记着它们一边被告知真正该做
 * 的事。内置规则留在每一份提示词里，而不是被文件替换：它们不是项目偏好，而是这个代理的工作方式（「先看再
 * 改」、「没有命令证明过就不要说它能用」），而一个加了自己规则的项目并没有要求不再被告知这些。
 */
public final class Prompts {

  /**
   * 刻意保持简短。模型需要知道的、无法用工具 schema 表达的行为要求都在这里；再长的东西只会每个回合都白烧
   * 上下文。
   */
  public static final String DEFAULT_SYSTEM =
      """
      You are ccj, a coding agent working directly in the user's shell.

      Rules:
      - Inspect before you change: read a file (or list the directory) before editing it.
      - Prefer exact edits over rewriting whole files, and keep the user's existing style.
      - Use bash for real commands and pipelines; use the search tools for locating code.
      - Never claim something works unless a command you ran proves it.
      - When the task is done, answer in prose: what changed, where, and how you verified it.

      When the project you are working in is ccj itself: build with
      `mvn -q -DskipTests -Djar.name=ccj-next package`, which writes target/ccj-next.jar without
      touching the jar this process is running from, then call `restart` with it. That installs the
      new jar and restarts on it, so the change takes effect with nobody doing it by hand. Never
      build plain `mvn package` from inside ccj: it truncates target/ccj.jar, and truncating the jar
      a JVM is executing kills that JVM in the middle of the build.
      """;

  private Prompts() {}

  /** 为配置好的基础提示词拼出提示词；没有项目可读时用这个。 */
  public static String system(String system) {
    return system(system, null);
  }

  /**
   * 为配置好的基础提示词，以及代理工作所在的项目拼出提示词。
   *
   * @param system 生效的提示词；null 或空白表示 {@link #DEFAULT_SYSTEM}
   * @param workingDirectory 代理将要运行的目录，其 {@link ProjectPrompt} 规则会被前置；null 表示没有
   */
  public static String system(String system, Path workingDirectory) {
    String base = system == null || system.isBlank() ? DEFAULT_SYSTEM : system;
    String project = ProjectPrompt.from(workingDirectory);
    return project.isEmpty() ? base : project + "\n\n" + base;
  }
}
