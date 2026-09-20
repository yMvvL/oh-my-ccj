package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Approver;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BashToolTest {

  @TempDir Path dir;

  /**
   * 一个真的能被执行的「shell」：它把自己拿到的参数原样印出来，于是一个程序收到的调用约定是可观察的，
   * 而不是被推断的。
   *
   * @param name 文件名，argv 里可据以判断调用约定的那部分
   */
  private Path executableShell(String name) throws IOException {
    Path script = dir.resolve(name);
    Files.writeString(script, "#!/bin/sh\nprintf '%s|' \"$@\"\n");
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    return script;
  }

  @Test
  void mergesStderrIntoStdoutAndReportsSuccess() throws Exception {
    ToolResult result =
        new BashTool()
            .execute(
                "{\"command\":\"echo out; echo err >&2\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().startsWith("exit code 0\n"), result.content());
    assertTrue(result.content().contains("out"), result.content());
    assertTrue(result.content().contains("err"), result.content());
  }

  @Test
  void nonZeroExitIsAnErrorWithTheSameContent() throws Exception {
    ToolResult result =
        new BashTool()
            .execute("{\"command\":\"echo boom; exit 3\"}", new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("exit code 3"), result.content());
    assertTrue(result.content().contains("boom"), result.content());
  }

  @Test
  void timeoutKillsTheProcessAndSaysSo() throws Exception {
    long started = System.nanoTime();
    ToolResult result =
        new BashTool()
            .execute(
                "{\"command\":\"sleep 30\",\"timeout_seconds\":1}",
                new ToolContext(dir, Approver.ALWAYS, 4096));
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("超时"), result.content());
    assertTrue(elapsedMillis < 15_000, "超时没有停住这个进程: " + elapsedMillis + "ms");
  }

  @Test
  void commandsThatReadStdinSeeTheEndOfItInsteadOfHanging() throws Exception {
    // 曾经的 bug：子进程的 stdin 管道从未关闭，于是无参数的 `cat` 一直等一个不可能到来的输入，
    // 这次调用直到超时把它杀掉才返回。
    long started = System.nanoTime();

    ToolResult result =
        new BashTool()
            .execute(
                "{\"command\":\"cat; echo done\",\"timeout_seconds\":10}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("done"), result.content());
    assertTrue(elapsedMillis < 5_000, "它绝不能等待输入: " + elapsedMillis + "ms");
  }

  @Test
  void aCharacterCutInHalfByTheOutputBudgetIsDroppedRatherThanMangled() throws Exception {
    // 59 字节的头部，一个三字节字符，然后填满预算的尾部：这个字符横跨头部的切口，它的一半绝不能
    // 变成替换字形。
    String command = "printf 'a%.0s' {1..59}; printf '中'; printf 'b%.0s' {1..200}";

    ToolResult result =
        new BashTool()
            .execute(
                "{\"command\":\"" + command + "\"}",
                new ToolContext(dir, Approver.ALWAYS, 100));

    assertFalse(result.error(), result.content());
    assertFalse(result.content().contains("\uFFFD"), result.content());
    assertTrue(
        result.content().contains("a".repeat(59) + "\n... 省略了"),
        "头部保留每一个完整的字节: " + result.content());
    assertTrue(result.content().endsWith("b".repeat(40)), result.content());
  }

  @Test
  void outputThatFitsIsDecodedAsOneBuffer() throws Exception {
    // 什么都没被丢掉，所以两块缓冲是连续的：跨在它们边界上的字符必须完整存活，而不是在两次解码之间
    // 丢失。
    String command = "printf 'a%.0s' {1..59}; printf '中'; printf 'b%.0s' {1..38}";

    ToolResult result =
        new BashTool()
            .execute(
                "{\"command\":\"" + command + "\"}",
                new ToolContext(dir, Approver.ALWAYS, 100));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("中"), result.content());
    assertFalse(result.content().contains("省略了"), result.content());
  }

  @Test
  void truncatesChattyOutputWithAnOmissionMarker() throws Exception {
    ToolResult result =
        new BashTool()
            .execute("{\"command\":\"seq 1 5000\"}", new ToolContext(dir, Approver.ALWAYS, 200));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().startsWith("exit code 0\n"), result.content());
    assertTrue(result.content().contains("... 省略了 "), result.content());
    assertTrue(result.content().contains(" 字节 ..."), result.content());
    assertTrue(result.content().contains("1\n2\n"), result.content());
    assertTrue(result.content().endsWith("5000\n"), result.content());
    assertTrue(result.content().length() < 500, result.content());
  }

  @Test
  void survivesADegenerateTinyOutputLimit() throws Exception {
    ToolResult result =
        new BashTool()
            .execute("{\"command\":\"echo hello\"}", new ToolContext(dir, Approver.ALWAYS, 1));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("exit code 0"), result.content());
  }

  @Test
  void reportsMissingWorkingDirectory() throws Exception {
    ToolResult result =
        new BashTool()
            .execute(
                "{\"command\":\"echo hi\",\"cwd\":\"does/not/exist\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("不是目录"), result.content());
  }

  @Test
  void surfacesBashsOwnCommandNotFoundError() throws Exception {
    ToolResult result =
        new BashTool()
            .execute(
                "{\"command\":\"definitely-not-a-command-ccj\"}",
                new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("exit code 127"), result.content());
    assertTrue(result.content().contains("definitely-not-a-command-ccj"), result.content());
  }

  @Test
  void denialDoesNotRunTheCommand() throws Exception {
    ToolResult result =
        new BashTool()
            .execute("{\"command\":\"echo stamped > ran.txt\"}", new ToolContext(dir, Approver.NEVER, 4096));

    assertTrue(result.error(), result.content());
    assertFalse(java.nio.file.Files.exists(dir.resolve("ran.txt")));
  }

  @Test
  void aConfiguredShellGetsTheCommandAsALoginShellString() throws Exception {
    // 命令交给的是配置里的那个程序，不是 /bin/bash：假的 shell 印出来的是它自己收到的参数，
    // 所以「交给了谁、按哪种形状」两件事都在这条断言里被看到了。
    Path shell = executableShell("my-shell");

    ToolResult result =
        new BashTool(shell.toString())
            .execute("{\"command\":\"echo hi\"}", new ToolContext(dir, Approver.ALWAYS, 4096));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("-lc|echo hi|"), result.content());
  }

  @Test
  void aProgramNamedCmdGetsSlashC() throws Exception {
    // cmd.exe 不认 -lc，而配置里写的会是一个完整路径（C:\Windows\system32\cmd.exe），所以能据以
    // 判断的只有文件名。
    Path cmd = executableShell("cmd");

    ToolResult result =
        new BashTool(cmd.toString())
            .execute("{\"command\":\"echo hi\"}", new ToolContext(dir, Approver.ALWAYS, 4096));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("/c|echo hi|"), result.content());
  }

  @Test
  void aShellThatCannotStartNamesThePathAndTheThreePlacesToChangeIt() throws Exception {
    Path missing = dir.resolve("no/such/shell");

    ToolResult result =
        new BashTool(missing.toString())
            .execute("{\"command\":\"echo hi\"}", new ToolContext(dir, Approver.ALWAYS, 4096));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains(missing.toString()), result.content());
    assertTrue(result.content().contains("\"shell\""), result.content());
    assertTrue(result.content().contains("--shell"), result.content());
    assertTrue(result.content().contains("CCJ_SHELL"), result.content());
    // 没起来不是超时，报成一个超时会把读它的人送去等一个永远不会开跑的进程。
    assertFalse(result.content().contains("超时"), result.content());
  }

  @Test
  void withNoShellConfiguredTheCommandGoesToPosixBash() throws Exception {
    // $0 是 shell 自己报出的名字，于是「没配置时跑的是谁」是被观察到的，而不是被声明的。
    ToolResult result =
        new BashTool()
            .execute("{\"command\":\"echo $0\"}", new ToolContext(dir, Approver.ALWAYS, 4096));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("exit code 0\n"), result.content());
    assertTrue(result.content().contains(ProcessRunner.POSIX_DEFAULT), result.content());
    assertEquals(
        ProcessRunner.POSIX_DEFAULT, ProcessRunner.resolve(null), "没有配置时是 POSIX 默认");
  }
}
