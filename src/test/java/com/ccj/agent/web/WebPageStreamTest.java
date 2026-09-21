package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 页面与流：静态资源、壁纸、状态快照，以及一条 SSE 流是怎样把一个回合讲完的。
 *
 * <p>单独成类，是因为这一组断言的是线协议本身（帧的顺序、keep-alive、回到页面的重放），
 * 而不是某个功能端点的语义。
 */
class WebPageStreamTest extends WebHarness {

  @Test
  void servesThePageAndItsAssetsFromTheClasspath() throws Exception {
    String page = body("/");
    assertTrue(page.contains("<html"), page);
    assertTrue(page.contains("/app.js"), "页面必须引用它自己的脚本");
    assertTrue(page.contains("/style.css"), "页面必须引用它自己的样式表");

    String script = body("/app.js");
    assertTrue(script.contains("EventSource"), "页面必须真的监听事件流");
    assertTrue(script.contains("/api/message"), "页面必须能发送消息");
    assertFalse(body("/style.css").isBlank());
  }

  @Test
  void theWallpaperEndpointsListAndServeWhatTheDirectoryHolds() throws Exception {
    Path pictures = Files.createDirectories(tmp.resolve("pictures"));
    byte[] png =
        new byte[] {
          (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0
        };
    Files.write(pictures.resolve("2.png"), png);
    Files.write(pictures.resolve("1.png"), png);
    start(null, new Wallpapers(pictures));

    String listed = body("/api/wallpapers");
    assertTrue(listed.contains("\"1.png\""), listed);
    assertTrue(listed.indexOf("1.png") < listed.indexOf("2.png"), "按阅读顺序列出：" + listed);

    HttpResponse<byte[]> image =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/wallpaper/1.png")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, image.statusCode());
    assertEquals("image/png", image.headers().firstValue("content-type").orElse(""));
    assertArrayEquals(png, image.body(), "这些字节就是文件本身的字节");

    assertEquals(404, get("/wallpaper/../config.json").statusCode(), "目录穿越不是一个名字");
    assertEquals(404, get("/wallpaper/nope.png").statusCode(), "不存在的文件也不是");
  }

  @Test
  void aServerWithNoWallpaperDirectoryOffersNone() throws Exception {
    // 列表为空时页面会把自己的控件藏起来，所以「没有目录」必须是一个空列表，而不是一个它
    // 还得去解读的错误。
    start(null, new Wallpapers(tmp.resolve("nothing-here")));

    assertEquals("{\"wallpapers\":[]}", body("/api/wallpapers"));
    assertEquals(404, get("/wallpaper/1.png").statusCode());
  }

  @Test
  void statusDescribesTheModelSessionAndTools() throws Exception {
    JsonNode status = json("/api/status");

    assertEquals("openai", status.path("provider").asText());
    assertEquals("mock-model", status.path("model").asText());
    assertEquals("http://mock.invalid/v1", status.path("baseUrl").asText());
    assertEquals(cwd.toString(), status.path("cwd").asText());
    assertFalse(status.path("sessionId").asText().isBlank());
    assertFalse(status.path("busy").asBoolean(), "此刻还不该有任何东西在运行");
    assertEquals(8, status.path("tools").size(), "每个标准工具都必须被公布出来");
    assertEquals("read", status.path("tools").get(0).path("name").asText());
  }

  @Test
  void aFreshConnectionImmediatelyReceivesTheStatus() throws Exception {
    try (Sse sse = watch()) {
      JsonNode status = sse.await("status", 3000);

      assertEquals("openai", status.path("provider").asText());
      assertEquals(8, status.path("tools").size());
      assertFalse(status.path("busy").asBoolean());
    }
  }

  @Test
  void aTurnStreamsProseAndFinishes() throws Exception {
    provider.reply(Message.Assistant.text("hello from the mock"));
    try (Sse sse = watch()) {
      assertEquals(202, post("/api/message", "{\"text\":\"hi\"}").statusCode());
      JsonNode done = sse.await("done", 5000);
      assertEquals("hello from the mock", done.path("finalText").asText());
      assertFalse(done.path("aborted").asBoolean());
      assertEquals("hi", sse.await("user", 1000).path("text").asText());
      assertEquals("hello from the mock", sse.text());
    }
  }

  @Test
  void theStreamSendsARealKeepAliveRatherThanAComment() throws Exception {
    // 这里钉住的 bug，是在一个等待审批的会话上测出来的：保活曾经是 `: ping`，一个 SSE 的
    // *注释*。注释不会投递给任何人——EventSource 只派发带 data 字段的帧——所以页面的
    // `lastEventAt` 从不更新，它那个 20 秒的「流已死」计时器在一条完全健康的连接上开火，页面
    // 就在一个仍然开着的提示底下重连了。对用户来说，那就是一个闪一下然后消失的提示。
    //
    // 断言针对的是服务器真正写出的那个帧，因为这次失败对双方都是不可见的：服务器以为自己在
    // 保活，页面以为连接已经没了。
    try (Sse sse = watch()) {
      JsonNode ping = sse.awaitRaw("event", "ping", 25_000);
      assertNotNull(ping, "保活必须在心跳间隔内到达");
    }
  }

  @Test
  void thePageListensForTheKeepAlive() throws Exception {
    // 另一半：具名事件不会到达 `onmessage`，所以服务器的帧只有在页面为它注册了监听器时才会
    // 被投递。只有一半没有另一半，就是同一个 bug。
    String app = Files.readString(Path.of("src", "main", "resources", "web", "app.js"));

    assertTrue(
        app.contains("addEventListener('ping'"),
        "页面必须监听服务器发出的保活");
    int listener = app.indexOf("addEventListener('ping'");
    int body = app.indexOf("lastEventAt = Date.now()", listener);
    assertTrue(
        body > listener && body - listener < 400,
        "而且它必须刷新那个陈旧度时钟，这正是发送它的全部意义");
  }

  @Test
  void everyEventSaysWhichSessionItBelongsTo() throws Exception {
    // 一条流承载所有对话，所以一个不点名自己会话的事件，就是一个页面会渲染进另一个对话转录里
    // 的事件。
    provider.reply(Message.Assistant.text("ok"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      sse.await("done", 5000);
      assertFalse(
          sse.anyEventWithoutSession(),
          "每个事件都必须带上 sessionId：" + sse.snapshot());
      assertEquals(1, sse.ofType("user").size(), sse.snapshot().toString());
      assertFalse(sse.ofType("user").get(0).path("sessionId").asText().isEmpty());
    }
  }

  @Test
  void aTurnIsOverBeforeTheDoneEventAnnouncesIt() throws Exception {
    // 页面收到 `done` 就会让用户再次发送。如果服务器在那一刻还忙着，下一条消息就会被 409 拒
    // ——而输入框会一直禁用，直到有别的事发生、发布出一个状态。所以客户端在 `done` 之前一瞬间
    // 看到的状态，必须已经说回合结束了。
    List<EventStream.Event> seen = new CopyOnWriteArrayList<>();
    hub.subscribe(seen::add);
    provider.reply(new Message.Assistant("all done", List.of()));

    assertEquals(AgentHub.Submit.STARTED, hub.submit("hello"));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline
        && seen.stream().noneMatch(event -> event.type().equals("done"))) {
      Thread.sleep(10);
    }

    int done = -1;
    for (int i = 0; i < seen.size(); i++) {
      if (seen.get(i).type().equals("done")) {
        done = i;
        break;
      }
    }
    assertTrue(done > 0, "回合必须结束：" + seen.stream().map(EventStream.Event::type).toList());
    EventStream.Event before = seen.get(done - 1);
    assertEquals("status", before.type(), "状态在回合宣布之前就已经定下来了");
    assertFalse(before.payload().path("busy").asBoolean(true), before.payload().toString());
  }
}
