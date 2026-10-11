package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 导出跨过真的 HTTP：它是一份下载，它可重复，它什么都不改。
 *
 * <p>这里比的是**去掉时间戳之后的字节**：服务器每次导出都用当下的时刻（那是一份给人看的文件该有的东西），
 * 所以「两次导出字节相同」这条主张在这里的正确读法是「除时间戳外完全相同」——而要做到这一点，导出里就不能
 * 有任何别的会漂的东西：不能有随机数、不能有 map 的遍历顺序、不能有随机的文件名。
 */
class WebExportTest extends WebHarness {

  private String exported(String id) throws Exception {
    HttpResponse<String> response = get("/api/export?id=" + id);
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(
        "attachment",
        response.headers().firstValue("content-disposition").orElse("").contains("attachment")
            ? "attachment"
            : "（不是一次下载）",
        "导出是一次下载，不是一次页面访问");
    return response.body();
  }

  /** 导出时间：它是唯一被允许在两次导出之间变化的东西。 */
  private static String withoutTimestamp(String html) {
    return Pattern.compile("<dd>[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}</dd>")
        .matcher(html)
        .replaceAll("<dd>（时间戳）</dd>");
  }

  @Test
  void theDownloadIsASelfContainedPageWithTheConversationInIt() throws Exception {
    provider.reply(Message.Assistant.text("导出我。"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"把这段话导出去\"}");
      sse.await("done", 5000);
    }

    String id = json("/api/status").path("sessionId").asText();
    String html = exported(id);

    assertTrue(html.startsWith("<!doctype html>"), html.substring(0, 40));
    assertTrue(html.contains("把这段话导出去"), "用户的话在里面");
    assertTrue(html.contains("导出我。"), "助手的话在里面");
    assertTrue(html.contains(id), "会话 id 在标题里");
    assertFalse(html.contains("<script"), "自包含：没有脚本、没有外链");
    assertFalse(html.contains("http://") || html.contains("https://"), "一个外部地址都不该有");
  }

  @Test
  void twoExportsDifferOnlyByTheirTimestamp() throws Exception {
    provider.reply(Message.Assistant.text("一次就好。"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"再来一次\"}");
      sse.await("done", 5000);
    }

    String id = json("/api/status").path("sessionId").asText();
    String first = withoutTimestamp(exported(id));
    String second = withoutTimestamp(exported(id));

    assertEquals(
        first.length(), second.length(), "长度都该一样，否则说明还有别的东西在漂");
    assertEquals(first, second, "除时间戳外逐字节相同");
  }

  @Test
  void exportingChangesNothingOnDisk() throws Exception {
    provider.reply(Message.Assistant.text("我不会改任何东西。"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"导吧\"}");
      sse.await("done", 5000);
    }

    Path sessionFile =
        sessions.resolve(json("/api/status").path("sessionId").asText() + ".jsonl");
    List<String> before = snapshot(sessions);
    assertTrue(Files.isRegularFile(sessionFile), "会话文件本来就该在");

    exported(json("/api/status").path("sessionId").asText());

    assertEquals(before, snapshot(sessions), "导出是只读的：一个字节都不该变");
  }

  @Test
  void anUnknownOrUnsafeIdIsRefused() throws Exception {
    assertEquals(400, get("/api/export").statusCode(), "没有 id 是一次坏的请求");
    assertEquals(400, get("/api/export?id=").statusCode());
    assertEquals(400, get("/api/export?id=..%2Fescape").statusCode(), "会话 id 的白名单先挡下来");
    assertEquals(400, get("/api/export?id=../escape").statusCode());
    assertEquals(400, get("/api/export?id=20200101-000000-nope").statusCode(), "不存在的会话");
    assertEquals(405, post("/api/export?id=x", "{}").statusCode());
  }

  /** 目录里每个文件的（相对路径、大小、修改时间）。 */
  private static List<String> snapshot(Path root) throws Exception {
    List<String> entries = new ArrayList<>();
    try (var walk = Files.walk(root)) {
      for (Path file : walk.sorted().toList()) {
        entries.add(
            root.relativize(file)
                + " "
                + (Files.isRegularFile(file) ? Files.size(file) : "-")
                + " "
                + Files.getLastModifiedTime(file));
      }
    }
    return entries;
  }
}
