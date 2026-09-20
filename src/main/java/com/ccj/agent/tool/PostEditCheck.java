package com.ccj.agent.tool;

import com.ccj.agent.core.Checks;
import com.ccj.agent.core.ToolContext;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 编辑之后自行运行的检查，以及它写进工具自身结果的那一行。
 *
 * <p>关键在于它*何时*运行：就在引发它的那次 `edit` 或 `write` 调用之内，于是编译器的裁决与改动属于同一步。
 * 弄坏了东西的模型在宣称自己做完之前就会知道，而弱模型——这里对它最要紧——会一趟收敛，而不是三趟。
 *
 * <p>有三件事让它便宜到可以这么做。每次编辑最多运行一个检查：第一个 glob 匹配上的。它报告的是一段有界的
 * 摘录，而不是整份构建日志，因为工具结果是一段用户此后每个回合都要为之付费的提示。而通过的检查只用一行说
 * 明——模型应当知道脚下的地是实的，盯着工具卡片的用户也应当看到确实有东西跑过。
 */
final class PostEditCheck {

  /** 失败的检查有多少输出能到达模型——在上下文自身的上限之前。 */
  private static final int REPORT_BYTES = 4 * 1024;

  private final Checks checks;

  /** 跑检查命令的那个程序：配置里的那个，或者没有配置时这个平台的默认。 */
  private final String shell;

  /**
   * @param shell 跑检查命令的程序（`config.json` 的 `"shell"`）；null 或空白表示按平台默认。检查和
   *     `bash` 工具用同一个程序，因为它们跑的是同一类命令，而一个能跑 `bash` 却跑不了检查的安装，只会
   *     让人以为坏掉的是检查本身。见 {@link ProcessRunner#resolve}
   */
  PostEditCheck(Checks checks, String shell) {
    this.checks = checks == null ? Checks.none() : checks;
    this.shell = ProcessRunner.resolve(shell);
  }

  /**
   * 运行适用于 {@code edited} 的那个检查（如果有），并渲染要追加到工具结果里的内容——没有检查适用
   * 时返回空串，这是常见情况。
   *
   * <p>读取配置失败会被报出来而不是吞掉：否则一段有拼写错误的检查配置看起来会和一个没有任何检查的项目
   * 一模一样。
   *
   * <p><strong>这个检查不经审批运行，而这是一次写明的放宽</strong>（见 SECURITY.md）。2.6.10 把这个
   * 悬着的问题定下来了：保持放宽。理由是它没有让任何新的东西变成可能——那条命令是用户自己写进配置文件里
   * 的，这和把它写进 CCJ.md 或直接在终端里敲出来是同一件事；而让它走审批的代价是每次编辑弹一次提示，也
   * 就是把一个「编辑之后就告诉你结果」的功能，变成一个「先点两次再说」的功能。走规则那条路也不行：一条
   * 允许规则会让它**永远**不问，那和一个写明的放宽是同一件事，只是多了一层假装。它不能做的，是变成一条
   * 通用逃生口：这里只运行配置里那条命名的检查命令，在会话的工作目录里，仅此而已。
   */
  String afterEditing(Path edited, ToolContext ctx) {
    Optional<Checks.Check> applicable;
    try {
      applicable = checks.forPath(edited, ctx.cwd());
    } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
      return "\n\n[check] 配置的检查读不出来: " + e.getMessage();
    }
    if (applicable.isEmpty()) {
      return "";
    }
    Checks.Check check = applicable.get();
    // 回合一旦被中止就不再启动任何东西：构建是活儿，而中止存在的意义正是停掉没人再等的活儿。
    if (ctx.isCancelled()) {
      return "";
    }
    ProcessRunner.Result result;
    try {
      result =
          ProcessRunner.run(
              shell,
              check.command(),
              ctx.cwd(),
              check.timeoutSeconds(),
              Math.min(REPORT_BYTES, ctx.outputLimitBytes()),
              ctx);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "\n\n[check] " + check.command() + " — 已中断";
    }
    return "\n\n[check] " + check.command() + " — " + verdict(result, check);
  }

  private static String verdict(ProcessRunner.Result result, Checks.Check check) {
    if (!result.finished()) {
      return result.cancelled()
          ? "已停止：回合被中止了"
          : "运行 " + check.timeoutSeconds() + "s 后超时；整个进程树已被杀掉";
    }
    if (result.exitCode() == 0) {
      // 说「exit 0」而不说「clean」：一条命令自身的覆盖范围可能比决定运行它的那个 glob 更窄——实测，
      // `mvn -q -o -DskipTests compile` 对仓库根目录下一个坏掉的文件仍然退出 0，因为 Maven 只编译
      // `src/main/java`——而「clean」这样的词会把这件事变成该命令从未做出的断言。
      return "exit 0 (" + result.millis() + "ms)";
    }
    String output = result.output().strip();
    return "exit code "
        + result.exitCode()
        + " ("
        + result.millis()
        + "ms) — 继续之前先修好它:\n"
        + (output.isEmpty() ? "(no output)" : output);
  }
}
