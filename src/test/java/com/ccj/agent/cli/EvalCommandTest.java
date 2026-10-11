package com.ccj.agent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --eval-json} 与 {@code --eval-compare} 在真的命令行上：跑两次，第二次与第一次比。
 *
 * <p>它钉住的是这两个子命令之间的那个接口——一个存下来的报告文件。第一次运行写它，第二次运行读它，而第二次
 * 的输出里必须出现「哪几个任务不一样」。
 */
class EvalCommandTest {

  @TempDir Path tmp;

  private record Run(int code, String out, String err) {}

  private Run run(String... extra) throws Exception {
    String[] args = new String[extra.length + 4];
    args[0] = "--home";
    args[1] = tmp.resolve("home").toString();
    args[2] = "-C";
    args[3] = tmp.toString();
    System.arraycopy(extra, 0, args, 4, extra.length);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code =
        new Cli()
            .run(
                args,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aRunCanBeStoredAndThenComparedAgainstInASecondRun() throws Exception {
    Path stored = tmp.resolve("first.json");
    String suite = Path.of("evals").toAbsolutePath().toString();

    Run first = run("--demo", "--eval", suite, "--eval-json", stored.toString());
    assertEquals(0, first.code(), first.err());
    assertTrue(first.out().contains("报告已写到"), first.out());
    assertTrue(Files.isRegularFile(stored), "报告要真的写下来");

    Run second =
        run("--demo", "--eval", suite, "--eval-compare", stored.toString());
    assertEquals(0, second.code(), second.err());
    assertTrue(second.out().contains("对比："), second.out());
    assertTrue(second.out().contains("个不一样"), second.out());
    // 同一个 demo 模型跑同一套任务两次，逐任务应当一模一样。
    assertTrue(second.out().contains("0 个不一样"), second.out());
    assertTrue(second.out().contains("一样"), second.out());
  }

  @Test
  void aMissingOrUnreadableReportIsReportedRatherThanIgnored() throws Exception {
    String suite = Path.of("evals").toAbsolutePath().toString();

    Run missing = run("--demo", "--eval", suite, "--eval-compare", tmp.resolve("nope.json").toString());
    assertEquals(1, missing.code());
    assertTrue(missing.err().contains("读不出上一份报告"), missing.err());

    Path broken = tmp.resolve("broken.json");
    Files.writeString(broken, "not json");
    Run malformed = run("--demo", "--eval", suite, "--eval-compare", broken.toString());
    assertTrue(malformed.code() != 0, "一份读不出来的报告不该被当成「没什么不一样」");
  }
}
