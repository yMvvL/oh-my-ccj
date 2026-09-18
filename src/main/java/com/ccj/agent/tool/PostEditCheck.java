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

  PostEditCheck(Checks checks) {
    this.checks = checks == null ? Checks.none() : checks;
  }

  /**
   * 运行适用于 {@code edited} 的那个检查（如果有），并渲染要追加到工具结果里的内容——没有检查适用
   * 时返回空串，这是常见情况。
   *
   * <p>读取配置失败会被报出来而不是吞掉：否则一段有拼写错误的检查配置看起来会和一个没有任何检查的项目
   * 一模一样。
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
