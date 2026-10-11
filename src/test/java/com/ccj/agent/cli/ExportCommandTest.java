package com.ccj.agent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
import com.ccj.agent.session.FileSession;
import com.ccj.agent.session.SessionStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code ccj --export}:一份 HTML 写到 stdout，除此之外什么都不做。
 *
 * <p>它测的是那个子命令的三件小事：退出码（一个不存在的会话是 1，不是 0）、写到哪（stdout，于是
 * {@code > 文件} 就是全部的重定向逻辑）、以及它不需要别的东西——没有模型、没有工具、没有审批。
 */
class ExportCommandTest {

  @TempDir Path tmp;

  private String sessionWith(String text) throws IOException {
    Path sessions = Files.createDirectories(tmp.resolve("home").resolve("sessions"));
    FileSession session = SessionStore.create(sessions);
    session.append(new Message.User(text));
    session.append(Message.Assistant.text("好。"));
    session.close();
    return session.id();
  }

  /** 跑一次 CLI，把三个流都收下来。 */
  private record Run(int code, String out, String err) {}

  private Run run(String... args) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code =
        new Cli()
            .run(
                args,
                new java.io.ByteArrayInputStream(new byte[0]),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Run(
        code,
        out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }

  private String[] base(String... extra) {
    String[] args = new String[extra.length + 4];
    args[0] = "--home";
    args[1] = tmp.resolve("home").toString();
    args[2] = "-C";
    args[3] = tmp.toString();
    System.arraycopy(extra, 0, args, 4, extra.length);
    return args;
  }

  @Test
  void theExportGoesToStdoutAndExitsZero() throws Exception {
    String id = sessionWith("把这句话导出去");

    Run run = run(base("--export", id));

    assertEquals(0, run.code(), run.err());
    assertTrue(run.out().startsWith("<!doctype html>"), run.out().substring(0, 40));
    assertTrue(run.out().contains("把这句话导出去"), "会话的内容在里面");
    assertTrue(run.out().contains(id), "会话 id 在标题里");
    assertTrue(run.err().isEmpty(), "没有模型、没有工具，所以 stderr 什么都不该有：" + run.err());
  }

  @Test
  void anUnknownSessionIsRefusedWithAReason() throws Exception {
    sessionWith("随便一条");

    Run run = run(base("--export", "20200101-000000-nope"));

    assertEquals(1, run.code());
    assertTrue(run.out().isEmpty(), "什么都没导出来");
    assertTrue(run.err().contains("没有会话"), run.err());
  }

  @Test
  void aSessionIdThatTriesToWalkOutIsRefused() throws Exception {
    sessionWith("随便一条");

    Run escaped = run(base("--export", "../escape"));
    Run absolute = run(base("--export", "/etc/passwd"));

    assertEquals(1, escaped.code());
    assertEquals(1, absolute.code());
    assertTrue(escaped.err().contains("无效的会话 id"), escaped.err());
    assertTrue(absolute.err().contains("无效的会话 id"), absolute.err());
  }

  @Test
  void listingAndExportingDoNotNeedAModel() throws Exception {
    // 这两个子命令是「读一读磁盘上的东西」，所以它们不该因为这台机器上没有配模型而失败。
    String id = sessionWith("看得到我吗");

    assertEquals(0, run(base("--list-sessions")).code());
    assertTrue(run(base("--list-sessions")).out().contains(id));
    assertEquals(0, run(base("--export", id)).code());
  }
}

