package com.ccj.agent.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 本安装跨多次运行保留的 web token，位于 {@code <home>/web-token}。
 *
 * <p>它存在，是因为整个功能的要点就一个词：{@code ccj}，然后手机就能打开这个页面。每次都要传一个 token
 * 的话，机密就会留在 shell 历史、{@code ps}，以及为了避开前两者而写下的哪个 shell 别名里——所以第一次
 * 需要 token 的那次运行会生成 32 个随机字节，存进一个只有用户能读的文件，并打印带着它的 URL。之后的运行
 * 复用同一个 token，这正是手机的 cookie 能熬过重启的原因。
 *
 * <p>生成而不是向人要：一个人编出来的 token 是 256 位设计里最弱的一环，而且这里没有需要人来选的东西
 * ——这个值从不被输入，只会被点击。
 *
 * <p><strong>一次安装，一个 token，而且两个进程同时开始也只会有一个。</strong> 生成用的是
 * {@link SecureRandom}：32 个字节，也就是 256 位，以 64 个十六进制字符写出。那个数字不是装饰——撞上
 * 同一个值需要大约 2^128 次生成，所以这里不需要、也无法提供「查重」；需要的是另一件事，而它过去是错
 * 的：两次启动（一个重启、两个工作区）在文件还不存在时同时走到这里，会各自生成、各自写入，最后留在
 * 磁盘上的是后写的那个，而先写的那个进程还在用自己内存里的值服务。所以创建是独占的（{@code CREATE_NEW}）
 * ——输掉这一次创建的那个进程读赢家的值，两个进程于是用同一个 token 服务。
 */
public final class WebToken {

  /** 应用 home 里的那个文件；名字说明了它是什么，看目录列表的人一眼就能分辨。 */
  public static final String FILE_NAME = "web-token";

  private static final int BYTES = 32;

  /** 输掉创建竞争之后，等赢家把内容写完的重试次数与间隔：加起来最多 100 毫秒。 */
  private static final int WINNER_ATTEMPTS = 20;

  /** 整个认领流程最多走几圈。正常情况下一圈就结束；上限存在的意义是永不循环。 */
  private static final int CLAIM_ATTEMPTS = 5;

  private static final long WINNER_PAUSE_MILLIS = 5;

  private static final SecureRandom RANDOM = new SecureRandom();

  private WebToken() {}

  /**
   * 本安装的 token；还没有的话就生成一个并存起来。
   *
   * @throws IOException home 目录无法写入时抛出；调用方会把它报出来，而不是带着一个谁也猜不到、谁也读不到
   *     的 token 去服务一次网络绑定
   */
  public static String from(Path home) throws IOException {
    Path file = home.resolve(FILE_NAME);
    Files.createDirectories(home);
    for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
      String existing = read(file);
      if (existing != null) {
        return existing;
      }
      if (Files.exists(file)) {
        // 存在却是空的，两种可能：另一个进程刚把它创建出来、正在写内容，或者这是一份废弃的空文件
        // （上一次运行死在创建与写入之间、一次 `touch`）。等一小会儿就能分开这两者，而分不开的时候
        // 删除是安全的方向——空文件的意思本来就该是「没有 token」，绝不能就这么对外服务。
        String winner = awaitWinner(file);
        if (winner != null) {
          return winner;
        }
        Files.deleteIfExists(file);
        continue;
      }
      String created = generate();
      try {
        Files.writeString(
            file,
            created + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
      } catch (FileAlreadyExistsException raced) {
        // 另一个进程赢了这一瞬间的创建。下一圈读它在几微秒之后写下的值。
        continue;
      }
      restrictToOwner(file);
      return created;
    }
    // 每一圈要么返回、要么让文件消失或出现，所以走到这里意味着有东西在反复删它——报出来，而不是
    // 猜一个 token 继续留着。
    throw new IOException("无法建立 web token：" + file + " 反复出现却又不是可用的值");
  }

  /**
   * 赢得创建的那个进程写下的值，给它一点时间。
   *
   * <p>独占创建和写入内容不是同一个瞬间：输的一方可以看见一个刚被创建、还是空的文件。那不是「一个空
   * token」，那是另一个进程正在写——测试抓住的正是这个窗口，而它比读一次短得多。所以这里短暂重试。
   * 重试用完后返回 {@code null}，把 {@code FileAlreadyExistsException} 交回给调用方：到那时文件真的
   * 是空的，而那是一处必须被报出来的损坏，不是一个可以猜过去的瞬间。
   */
  private static String awaitWinner(Path file) throws IOException {
    for (int attempt = 0; attempt < WINNER_ATTEMPTS; attempt++) {
      String winner = read(file);
      if (winner != null) {
        return winner;
      }
      try {
        Thread.sleep(WINNER_PAUSE_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }

  /** 文件里的 token，或者 {@code null}（不存在、读不出、或者是个空文件）。 */
  private static String read(Path file) {
    try {
      if (!Files.isRegularFile(file)) {
        return null;
      }
      String existing = Files.readString(file, StandardCharsets.UTF_8).strip();
      return existing.isEmpty() ? null : existing;
    } catch (IOException e) {
      // 读不出就当作不存在，交给下面的创建路径：那条路要么成功写出一个，要么把错误报给调用方，
      // 而不是让这里静默地回退成一个「没有 token」的服务器。
      return null;
    }
  }

  private static void write(Path file, String token) throws IOException {
    Files.writeString(
        file,
        token + System.lineSeparator(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    restrictToOwner(file);
  }

  /** 32 个随机字节的十六进制：256 位，里面没有任何需要记住的东西。 */
  static String generate() {
    byte[] bytes = new byte[BYTES];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /** 尽力而为：没有 POSIX 权限的文件系统保持默认，而不是失败。 */
  private static void restrictToOwner(Path file) {
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException | IOException ignored) {
      // token 两种情况下都会被存下；这一步只是收窄谁可以读它。
    }
  }
}
