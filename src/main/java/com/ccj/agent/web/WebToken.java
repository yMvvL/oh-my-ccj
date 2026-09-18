package com.ccj.agent.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 */
public final class WebToken {

  /** 应用 home 里的那个文件；名字说明了它是什么，看目录列表的人一眼就能分辨。 */
  public static final String FILE_NAME = "web-token";

  private static final int BYTES = 32;

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
    if (Files.isRegularFile(file)) {
      String existing = Files.readString(file, StandardCharsets.UTF_8).strip();
      if (!existing.isEmpty()) {
        return existing;
      }
      // 空文件是失误，不是策略：重写它，而不是不带 token 地对外服务。
    }
    String created = generate();
    Files.createDirectories(home);
    Files.writeString(
        file,
        created + System.lineSeparator(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    restrictToOwner(file);
    return created;
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
