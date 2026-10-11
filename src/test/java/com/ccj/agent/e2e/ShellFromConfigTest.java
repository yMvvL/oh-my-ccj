package com.ccj.agent.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.cli.Cli;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 一个回合真的跑起来了，而它执行命令用的那个程序**来自配置**。
 *
 * <p>它是 4.6（"Windows 的真相"）里那份证据。这个类**不绑定 POSIX**：CI 的 windows 作业会跑它，而它打印
 * 的那两行就是那份证据——不是"应该能跑"，而是"跑过了，而且是配置里那个程序执行的"。跑的是真实 CLI：
 * {@code --demo} 之下没有任何模型后端，但回合、工具、shell 全是真的。
 *
 * <p>两个用例合起来才说明问题。第一个证明**一个回合能跑完、并且拿到命令的输出**；第二个在配置里写一个
 * **根本不存在**的程序，然后证明那个回合因此失败——如果平台默认被偷偷用上了，第二条会通过，而"配置能决定
 * shell"这句话就成了一句没人验证过的主张。这正是 2.5 当时留下的那个缺口。
 */
class ShellFromConfigTest {

  @TempDir Path tmp;

  @Test
  void aTurnRunsAndRunsACommand() throws IOException {
    Run run = turn(null, "run echo CI-SHELL-EVIDENCE");

    // 一次成功的工具卡，外加命令真的印出来的那行——注意断言的是工具卡与它的输出，不是提示里那串字：
    // 演示提供方会把用户说的话原样背一遍，所以"输出里出现过这个记号"本身什么都不能证明。
    assertTrue(run.out().contains("✔ bash"), run.out());
    assertTrue(run.out().contains("CI-SHELL-EVIDENCE"), run.out());
    System.out.println("CI 证据：一个回合跑起来了，命令的输出回来了（shell 用平台默认）");
  }

  @Test
  void theShellIsTheOneInTheConfigEvenWhenThatProgramDoesNotExist() throws IOException {
    Run run = turn("definitely-not-a-shell-xyz", "run echo CI-SHELL-EVIDENCE");

    // 配置里那个程序根本不存在，所以这一回合必须**失败**，并且说出它试图启动的是哪一个程序。
    // 如果平台默认被偷偷用上了，这里会看到一次成功的执行——而"配置能决定 shell"就成了一句没人验证过的
    // 主张。这正是 2.5 当时留下的那个缺口。
    assertTrue(run.out().contains("✖ bash"), run.out());
    assertTrue(run.out().contains("definitely-not-a-shell-xyz"), run.out());
    assertFalse(run.out().contains("✔ bash"), "不该有成功的执行：\n" + run.out());
    System.out.println("CI 证据：配置里那个不存在的 shell 被真的用上了（不是平台默认）");
  }

  private record Run(int exitCode, String out) {}

  /** 一次真实的 CLI 运行：{@code --demo} 只关掉模型，回合、工具、shell 都不是替身。 */
  private Run turn(String shell, String prompt) throws IOException {
    Path home = Files.createDirectories(tmp.resolve(shell == null ? "default" : "bogus"));
    Files.writeString(
        home.resolve("config.json"),
        shell == null ? "{}\n" : "{\"shell\": \"" + shell + "\"}\n");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int exit;
    try (PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8)) {
      exit =
          new Cli()
              .run(
                  new String[] {
                    "--demo",
                    // 自动批准：这一条要证明的是命令**跑起来**，而不是审批台挡住了它。
                    "--yolo",
                    "-p",
                    prompt,
                    "--home",
                    home.toString(),
                    "--config",
                    home.resolve("config.json").toString(),
                    "-C",
                    home.toString()
                  },
                  new ByteArrayInputStream(new byte[0]),
                  stream,
                  stream);
    }
    return new Run(exit, out.toString(StandardCharsets.UTF_8));
  }
}
