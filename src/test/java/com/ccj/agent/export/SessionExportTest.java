package com.ccj.agent.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
import com.ccj.agent.core.UsageTotals;
import com.ccj.agent.session.AttachmentStore;
import com.ccj.agent.session.FileSession;
import com.ccj.agent.session.SessionStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 导出：自包含、可重复、且**不动原文件**。
 *
 * <p>三条主张各自有一条用例，因为它们的失败方式都是安静的：一个偷偷引用外部资源的导出，在作者的机器上一直
 * 打得开；一个每次都带新时间戳的导出，看起来只是「有点不一样」；而一个在导出时顺手改动了原文件的实现，
 * 要等到有人去 diff 才会被发现——所以这一条用（路径、大小、修改时间）的快照比对来钉。
 */
class SessionExportTest {

  @TempDir Path tmp;

  private Path sessions;

  private FileSession session() throws IOException {
    sessions = Files.createDirectories(tmp.resolve("sessions"));
    return SessionStore.create(sessions);
  }

  @Test
  void thePageCarriesTheTranscriptTheToolCallsAndTheLedger() throws IOException {
    FileSession session = session();
    session.append(new Message.User("把笔记写下来"));
    session.append(
        new Message.Assistant(
            "好。",
            List.of(
                new Message.ToolCall(
                    "c1",
                    "write",
                    "{\"path\": \"notes.txt\", \"content\": \"hi\\n\"}")),
            List.of()));
    session.append(new Message.ToolResult("c1", "write", "wrote 3 bytes", false));
    session.append(Message.Assistant.text("写好了。"));
    session.totals(new UsageTotals(1200, 40, 900, 1, 2, 1, 0, 500, 0, true));

    String html = SessionExport.toHtml(session, Instant.parse("2026-10-11T12:00:00Z"));

    assertTrue(html.startsWith("<!doctype html>"), html.substring(0, 40));
    assertTrue(html.contains("把笔记写下来"), "用户说了什么");
    assertTrue(html.contains("写好了。"), "助手说了什么");
    assertTrue(html.contains("write"), "调了哪个工具");
    assertTrue(html.contains("notes.txt"), "参数在页面里");
    assertTrue(html.contains("wrote 3 bytes"), "结果在页面里");
    assertTrue(html.contains("1200 输入 / 40 输出 token"), "账本在页眉里");
    assertTrue(html.contains("缓存命中 75%"), html.substring(html.indexOf("回合与用量"), html.indexOf("回合与用量") + 200));
    assertTrue(html.contains(session.id()), "会话 id 在标题里");
    assertFalse(html.contains("<script"), "页面里没有一行脚本");
    assertTrue(html.contains("原文件与它的图片没有被改动"), "它自己说清这是一份副本");
  }

  @Test
  void modelTextIsEscapedRatherThanRendered() throws IOException {
    // 模型写的东西是**文本**：这条规矩在页面上和在转录里是同一条，而导出最容易忘掉它——一份生成的 HTML
    // 看起来「本来就是 HTML」，于是插值处就少了一个转义。
    FileSession session = session();
    session.append(new Message.User("</pre><script>alert(1)</script>"));
    session.append(
        new Message.Assistant(
            "<b>粗体</b>",
            List.of(new Message.ToolCall("c1", "read", "{\"path\": \"<img src=x>\"}")),
            List.of()));
    session.append(new Message.ToolResult("c1", "read", "<script>也在这里</script>", true));

    String html = SessionExport.toHtml(session, Instant.parse("2026-10-11T12:00:00Z"));

    assertFalse(html.contains("<script"), "脚本标签必须被转义");
    assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), html);
    assertTrue(html.contains("&lt;b&gt;粗体&lt;/b&gt;"), "助手的话也一样");
    assertTrue(html.contains("&lt;img src=x&gt;"), "工具参数也一样");
    assertTrue(html.contains("&lt;script&gt;也在这里&lt;/script&gt;"), "工具结果也一样");
  }

  @Test
  void aPictureIsInlinedAsADataUrlSoTheFileWorksOffline() throws IOException {
    FileSession session = session();
    AttachmentStore store = AttachmentStore.forSession(session.file());
    AttachmentStore.Saved saved = store.save("board.png", png(240, 160));
    session.append(
        new Message.User(
            "[picture "
                + saved.path().getFileName()
                + "] 白板上写着今天要做的事\n\n(The picture this describes is saved at "
                + saved.path()
                + ")"));

    String html = SessionExport.toHtml(session, Instant.parse("2026-10-11T12:00:00Z"));

    assertTrue(html.contains("src=\"data:image/png;base64,"), "图片内联，页面断网也打得开");
    assertTrue(html.contains("白板上写着今天要做的事"), "描述照常显示");
    assertTrue(html.contains("<figcaption>"), "文件名在图下面");
  }

  @Test
  void aPictureNameThatPointsOutsideTheAttachmentDirectoryIsJustText() throws IOException {
    // 导出的输入是一段不可信的文本，而它唯一会碰到文件系统的地方就是解析这个名字。
    FileSession session = session();
    AttachmentStore.forSession(session.file()).save("real.png", png(60, 60));
    session.append(new Message.User("[picture ../../../../etc/passwd] 试试看"));

    String html = SessionExport.toHtml(session, Instant.parse("2026-10-11T12:00:00Z"));

    assertFalse(html.contains("data:"), "目录之外的任何文件都不该被读进来");
    assertTrue(html.contains("试试看"), "那段文字照常显示，什么都不猜");
    assertEquals("real.png", SessionExport.pictureName("[picture real.png] 描述"));
    assertNull(SessionExport.pictureName("这根本不是一条图片消息"));
    assertNull(SessionExport.pictureName("[picture ] 没有名字"));
    assertNull(SessionExport.pictureName("[picture 缺一个方括号"));
  }

  @Test
  void exportingTwiceAtTheSameMomentProducesTheSameBytes() throws IOException {
    // 一份不能被 diff 的导出，就没法用来回答「上次和这次差在哪」。导出时间是唯一的外部输入，所以它由
    // 调用方传进来；其余一切来自会话本身。
    FileSession session = session();
    session.append(new Message.User("你好"));
    session.append(Message.Assistant.text("你好。"));
    Instant at = Instant.parse("2026-10-11T12:00:00Z");

    byte[] first = SessionExport.toHtml(session, at).getBytes(StandardCharsets.UTF_8);
    byte[] second = SessionExport.toHtml(session, at).getBytes(StandardCharsets.UTF_8);

    assertEquals(
        java.util.Arrays.toString(first), java.util.Arrays.toString(second), "同一个时刻、同一个会话");
  }

  @Test
  void nothingOfTheOriginalChanges() throws IOException {
    FileSession session = session();
    AttachmentStore store = AttachmentStore.forSession(session.file());
    AttachmentStore.Saved saved = store.save("board.png", png(80, 80));
    session.append(new Message.User("[picture " + saved.path().getFileName() + "] 一张图"));
    session.append(Message.Assistant.text("看到了。"));

    List<String> before = snapshot(session.file(), store.directory());
    SessionExport.toHtml(session, Instant.parse("2026-10-11T12:00:00Z"));
    List<String> after = snapshot(session.file(), store.directory());

    assertEquals(before, after, "导出是只读的：原文件与它的图片逐字节没变");
  }

  @Test
  void aSummarySaysWhereTheOriginalIsStillReadable() throws IOException {
    FileSession session = session();
    session.append(new Message.Summary("更早的对话讲了三件事。", 7, "sessions/x.jsonl"));

    String html = SessionExport.toHtml(session, Instant.parse("2026-10-11T12:00:00Z"));

    assertTrue(html.contains("它替换掉了 7 条更早的消息"), html);
    assertTrue(html.contains("sessions/x.jsonl"), "被摘要掉的部分仍读得回来，读哪里也写清楚了");
  }

  /** 每个文件的（相对路径、大小、修改时间）。 */
  private static List<String> snapshot(Path... roots) throws IOException {
    List<String> entries = new ArrayList<>();
    for (Path root : roots) {
      if (root == null || !Files.exists(root)) {
        entries.add(root + " 不在");
        continue;
      }
      entries.add(root.getFileName() + " " + Files.size(root) + " " + modified(root));
      if (Files.isDirectory(root)) {
        try (var walk = Files.walk(root)) {
          for (Path file : walk.sorted().toList()) {
            entries.add(
                root.relativize(file)
                    + " "
                    + (Files.isRegularFile(file) ? Files.size(file) : "-")
                    + " "
                    + modified(file));
          }
        }
      }
    }
    return entries;
  }

  private static FileTime modified(Path path) throws IOException {
    return Files.getLastModifiedTime(path);
  }

  private static byte[] png(int width, int height) throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        image.setRGB(x, y, (x / 8 + y / 8) % 2 == 0 ? 0x3366CC : 0xFFFFFF);
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "png", out)) {
      throw new IllegalStateException("这个 JDK 没有 PNG 写出器，因此没法造出测试用的图");
    }
    return out.toByteArray();
  }
}
