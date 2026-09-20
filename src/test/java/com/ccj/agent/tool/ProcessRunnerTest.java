package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * shell 的选择与参数形状，作为纯函数来测。
 *
 * <p>为什么它们值得单独一组用例：这台机器和 CI 上都没有 Windows，而「没配置时用 `COMSPEC`」「`cmd.exe`
 * 收 `/c`」「PowerShell 收 `-NoProfile -Command`」是这份机制里唯一能在这里被机械检查的部分——真机上这些
 * 参数是否真的让那个程序跑起来，仍然没有证据。
 *
 * <p>{@link BashToolTest} 单独钉住另一半：真的把命令交给一个程序时，它收到的是这些参数。
 */
class ProcessRunnerTest {

  @Test
  void aConfiguredShellIsUsedAsGivenWithSurroundingSpaceStripped() {
    assertEquals("/usr/bin/zsh", ProcessRunner.resolve("  /usr/bin/zsh "));
    assertEquals(
        "C:\\Program Files\\Git\\bin\\bash.exe",
        ProcessRunner.resolve("C:\\Program Files\\Git\\bin\\bash.exe"));
  }

  @Test
  void withoutAConfiguredShellPosixMachinesGetBash() {
    assertEquals(ProcessRunner.POSIX_DEFAULT, ProcessRunner.resolve(null));
    assertEquals(ProcessRunner.POSIX_DEFAULT, ProcessRunner.resolve("   "));
    assertEquals(ProcessRunner.POSIX_DEFAULT, ProcessRunner.platformDefault("Linux", null));
    assertEquals(ProcessRunner.POSIX_DEFAULT, ProcessRunner.platformDefault("Mac OS X", "/bin/zsh"));
  }

  @Test
  void windowsFallsBackToComspecAndThenToCmd() {
    assertEquals(
        "C:\\Windows\\system32\\cmd.exe",
        ProcessRunner.platformDefault("Windows 11", "C:\\Windows\\system32\\cmd.exe"));
    assertEquals("cmd.exe", ProcessRunner.platformDefault("Windows 10", null));
    assertEquals("cmd.exe", ProcessRunner.platformDefault("Windows Server 2022", "  "));
  }

  @Test
  void cmdIsGivenSlashC() {
    assertEquals(List.of("cmd", "/c", "echo hi"), ProcessRunner.argv("cmd", "echo hi"));
    assertEquals(
        List.of("C:\\Windows\\system32\\cmd.exe", "/c", "echo hi"),
        ProcessRunner.argv("C:\\Windows\\system32\\cmd.exe", "echo hi"));
    // 程序字符串原样传给 ProcessBuilder，只有「哪种参数形状」是据文件名判断的。
    assertEquals(List.of("CMD.EXE", "/c", "echo hi"), ProcessRunner.argv("CMD.EXE", "echo hi"));
  }

  @Test
  void powershellIsGivenNoProfileAndCommand() {
    assertEquals(
        List.of("powershell", "-NoProfile", "-Command", "echo hi"),
        ProcessRunner.argv("powershell", "echo hi"));
    assertEquals(
        List.of("pwsh", "-NoProfile", "-Command", "echo hi"),
        ProcessRunner.argv("pwsh", "echo hi"));
  }

  @Test
  void everythingElseIsGivenLcBecauseThatIsTheOnlyGuessLeft() {
    // 未知的 shell 也走这条路：一个不认 -lc 的程序会以「无法识别的选项」失败，那个错误足够具体，
    // 改一下配置就能绕过。
    for (String shell :
        List.of("bash", "/bin/bash", "/usr/bin/zsh", "sh", "/opt/weird/thing", "bash.exe")) {
      assertEquals(List.of(shell, "-lc", "echo hi"), ProcessRunner.argv(shell, "echo hi"), shell);
    }
  }
}
