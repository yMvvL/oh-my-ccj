package com.ccj.agent.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * 下一个进程应该打开的会话，在本进程离开之前写下来。
 *
 * <p>重启是刻意换掉进程：{@code restart} 装上刚构建好的 jar，启动器再把它跑起来。新进程会开一个新
 * 会话，因为前端在没人告诉它别的时就是这么做的——于是用户正看着的那段会话，恰好在他们要求的工作落地的
 * 那一刻消失。会话文件在磁盘上、可以恢复，但「可以恢复」不等于「已经在眼前」，让人去找他刚刚看着的那
 * 一行，不如替他记住。
 *
 * <p>这是一张便条，不是记录。它只存一个会话 id，每次重启都被覆盖，被读到只是为了回答「我们刚才在哪」。
 * 因此便条损坏或缺失永远不算错误：最坏的结果也不过是这件事存在之前前端的表现。
 */
public final class ResumePoint {

  private static final String FILE_NAME = "resume";

  private ResumePoint() {}

  /** 便条针对某个 home 目录所在的位置。 */
  public static Path file(Path home) {
    return home.resolve(FILE_NAME);
  }

  /**
   * 为下一个进程记住 {@code sessionId}。
   *
   * <p>一个没法成为会话文件名的 id 会被拒绝而不是存下来：写下它就等于承诺下一个进程能打开它，而
   * {@link FileSession#isValidId} 才是判定这句话真假的依据。
   */
  public static void write(Path home, String sessionId) {
    if (sessionId == null || !FileSession.isValidId(sessionId)) {
      return;
    }
    Path file = file(home);
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          sessionId + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("无法写入 " + file, e);
    }
  }

  /** 要再次打开的会话，没有可打开的时为空。 */
  public static Optional<String> read(Path home) {
    Path file = file(home);
    String text;
    try {
      if (!Files.isRegularFile(file)) {
        return Optional.empty();
      }
      text = Files.readString(file, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      // 读不了和不存在是同一个答案：这张便条从不拦住一次启动。
      return Optional.empty();
    }
    int newline = text.indexOf('\n');
    if (newline >= 0) {
      text = text.substring(0, newline).strip();
    }
    return FileSession.isValidId(text) ? Optional.of(text) : Optional.empty();
  }

  /** 忘掉这张便条，好让下一个进程从头开始。 */
  public static void clear(Path home) {
    try {
      Files.deleteIfExists(file(home));
    } catch (IOException e) {
      throw new UncheckedIOException("无法删除 " + file(home), e);
    }
  }
}
