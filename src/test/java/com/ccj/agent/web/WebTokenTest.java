package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
  void processesStartingAtTheSameMomentConvergeOnOneToken() throws Exception {
    // 重启（旧进程以 75 退出、启动器起新进程）和同一个 home 下的第二个工作区，都可能撞上「文件还不
    // 存在」的这一瞬。过去两边各自生成、各自写入：磁盘上留下后写的那个，而先写的那个进程还在用内存
    // 里的值服务，于是它打印过的 URL 和文件里的 token 不再是同一个。现在创建是独占的，输的那一方
    // 读赢家的值——八个同时启动全部拿到同一个 token，文件里也是那个。
    Path fresh = home.resolve("concurrent");
    Files.createDirectories(fresh);
    int starters = 8;
    CountDownLatch go = new CountDownLatch(1);
    List<String> tokens = Collections.synchronizedList(new ArrayList<>());
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < starters; i++) {
      Thread thread =
          new Thread(
              () -> {
                try {
                  go.await();
                  tokens.add(WebToken.from(fresh));
                } catch (Throwable t) {
                  failures.add(t);
                }
              },
              "token-starter-" + i);
      thread.start();
      threads.add(thread);
    }
    go.countDown();
    for (Thread thread : threads) {
      thread.join();
    }

    assertEquals(List.of(), failures, failures.toString());
    assertEquals(starters, tokens.size());
    assertEquals(1, new HashSet<>(tokens).size(), "八次启动，一个 token：" + tokens);
    assertEquals(
        tokens.get(0),
        Files.readString(fresh.resolve(WebToken.FILE_NAME)).strip(),
        "磁盘上的那个就是所有进程在用的那个");
  }

  @Test
  void theGeneratedTokenIsA256BitValueWrittenAsHex() throws Exception {
    // 文档里写着「32 个随机字节，也就是 256 位」，而这里钉的是那句声明的可核对部分：长度。有人把
    // BYTES 改小、或者把编码换成更短的，不该只是让一句文档悄悄变成假的。
    String token = WebToken.generate();
    assertEquals(64, token.length(), token);
    assertTrue(token.matches("[0-9a-f]{64}"), token);
    assertNotEquals(WebToken.generate(), token, "两次生成不是同一个值");
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
