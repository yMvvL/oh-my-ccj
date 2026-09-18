package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 一次运行在重启之间留着的 token：只生成一次，之后复用，而且从不允许所有人可读。
 *
 * <p>它用来干什么，才是值得钉住的那部分。一个每次启动都变的 token 会让手机在每次 ccj 重启时都
 * 被登出；而一个由人选定的 token 会是一个 256 位设计里的薄弱环节——所以这里的两条性质是「同样的
 * 值会回来」和「新值猜不出来」，而不是写文件的机制。
 */
class WebTokenTest {

  @TempDir Path home;

  @Test
  void theTokenIsGeneratedOnceAndThenReused() throws Exception {
    String first = WebToken.from(home);
    String second = WebToken.from(home);

    assertEquals(first, second, "一次重启绝不能作废手机的 cookie");
    assertEquals(first, Files.readString(home.resolve(WebToken.FILE_NAME)).strip());
    // 32 个随机字节的十六进制：长到「猜」不成其为一种策略，而末尾的换行不算在里面。
    assertEquals(64, first.length());
    assertTrue(first.chars().allMatch(c -> Character.digit(c, 16) >= 0), first);
  }

  @Test
  void twoInstallationsDoNotShareAToken() throws Exception {
    Path other = Files.createDirectories(home.resolve("other-home"));

    assertNotEquals(WebToken.from(home), WebToken.from(other));
    assertNotEquals(WebToken.generate(), WebToken.generate());
  }

  @Test
  void theFileIsNotReadableByAnyoneElse() throws Exception {
    WebToken.from(home);
    Path file = home.resolve(WebToken.FILE_NAME);

    try {
      // 在没有 POSIX 权限的文件系统上跳过而不是失败：文件还是被存下来了，由平台说了算。
      assertEquals(
          "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    } catch (UnsupportedOperationException ignored) {
      assertTrue(Files.isRegularFile(file));
    }
  }

  @Test
  void anEmptyFileIsReplacedRatherThanObeyed() throws Exception {
    // 空文件是一个失误，不是一条策略：用一个空 token 去服务一个网络绑定，等于根本没有 token。
    Files.writeString(home.resolve(WebToken.FILE_NAME), "\n");

    String token = WebToken.from(home);

    assertFalse(token.isBlank());
    assertEquals(token, Files.readString(home.resolve(WebToken.FILE_NAME)).strip());
  }

  @Test
  void anUnwritableHomeIsReportedRatherThanIgnored() throws Exception {
    // 调用方必须能够安全地失败，也就是说这里必须抛异常，而不是返回一个调用方随后会拿去做无
    // token 服务的东西。
    Path file = Files.writeString(home.resolve("not-a-directory"), "x");

    assertTrue(assertThrowsIOException(() -> WebToken.from(file)), "一个文件不是主目录");
  }

  private static boolean assertThrowsIOException(ThrowingRunnable runnable) {
    try {
      runnable.run();
      return false;
    } catch (IOException e) {
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
