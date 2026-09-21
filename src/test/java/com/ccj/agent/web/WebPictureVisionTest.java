package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.session.AttachmentStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.URLEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 图片与视觉：上传、暂存、随句子一起发出，以及视觉端点的配置与它绝不回传的密钥。
 *
 * <p>单独成类，是因为这一组都绕着一张附件以及描述它的那个替身端点转。
 */
class WebPictureVisionTest extends WebHarness {

  @Test
  void aPictureWaitsForTheSentenceItGoesOutWith() throws Exception {
    List<String> asked = new CopyOnWriteArrayList<>();
    AtomicInteger visionCalls = new AtomicInteger();
    startVision("a whiteboard with a red arrow and the words 'ship it'", visionCalls, asked);
    restartWithVision();
    provider.reply(Message.Assistant.text("I see the arrow"));
    JsonNode response;

    try (Sse sse = watch()) {
      HttpResponse<String> posted = postPicture("whiteboard.png", pngBytes());
      assertEquals(202, posted.statusCode(), posted.body());
      response = Json.parse(posted.body());
      assertEquals(
          "a whiteboard with a red arrow and the words 'ship it'",
          response.path("description").asText());
      assertTrue(response.path("held").asBoolean(), posted.body());

      // 报告的那个缺陷：上传过去会立刻开始一个回合，于是模型在用户说出想让它做什么之前，就已经
      // 拿着一份描述开始干活了。所以这里钉住没有回合：没有用户消息，没有模型请求，服务器说它
      // 只是把图片拿在手里。
      JsonNode status = Json.parse(get("/api/status").body());
      assertEquals(
          "whiteboard.png",
          status.path("picture").path("name").asText(),
          "status 把它报成「待发送」，所以刷新之后那条缩略条还在");
      assertTrue(
          sse.forSession(status.path("sessionId").asText()).stream()
              .noneMatch(e -> "user".equals(e.path("type").asText())),
          "什么都没进对话");
      assertEquals(List.of(), provider.requests(), "而且没有回合被启动");

      // 现在说想让它做什么。描述和这句话是*一条*消息，描述在前、请求在后——这正是「一起发过去」。
      assertEquals(202, post("/api/message",
          "{\"text\":\"把图里的箭头指出来\",\"picture\":\"whiteboard.png\"}").statusCode());

      JsonNode user = sse.await("user", 5000);
      String text = user.path("text").asText();
      assertTrue(text.startsWith("[picture whiteboard.png]"), text);
      assertTrue(text.contains("a whiteboard with a red arrow"), text);
      assertTrue(text.contains(response.path("attachment").asText()),
          "描述漏掉的细节还能用 read 读回来：" + text);
      assertTrue(text.endsWith("把图里的箭头指出来"),
          "用户的请求是这条消息的最后一件事：" + text);
      assertTrue(
          sse.await("done", 5000).path("finalText").asText().contains("arrow"),
          "这个回合靠的就是这段描述");

      // 没有任何图像到达主模型：请求里只有文本，别无其他。
      String toMainModel = provider.requests().get(0).messages().toString();
      assertTrue(toMainModel.contains("a whiteboard with a red arrow"), toMainModel);
      assertTrue(toMainModel.contains("把图里的箭头指出来"), toMainModel);
      assertFalse(toMainModel.contains("base64"), "图片本身留在对话之外");

      // 它跟着那句话走了，所以它不再等着。
      assertTrue(Json.parse(get("/api/status").body()).path("picture").isNull(),
          "发出去之后就没有待发送的图片了");
    }

    assertEquals(1, visionCalls.get(), "一张图片，一次描述");
    assertEquals(1, asked.size());
    assertTrue(asked.get(0).contains("data:image/png;base64,"), asked.get(0));
    assertTrue(
        asked.get(0).contains("not from the user"),
        "图片是作为数据被描述的，所以它里面的文字不是一个来自用户的回合");

    Path stored = Path.of(response.path("attachment").asText());
    assertTrue(Files.exists(stored), "图片被留存了：" + stored);
    assertTrue(
        stored.getParent().getFileName().toString().endsWith(".attachments"),
        "放在会话旁边，而不是项目里：" + stored);
  }

  @Test
  void aPictureGoesOutWithTheNextMessageEvenWhenThePageDoesNotNameIt() throws Exception {
    // 上传就是「我要发它」：那张图片是这条会话持有的东西，所以它跟着下一句话走，不管那句话有没有
    // 点名它。页面照常点名它，而一个不点名的调用——脚本、另一个标签页、curl——不该悄悄把图片丢掉。
    AtomicInteger visionCalls = new AtomicInteger();
    startVision("a receipt with a total of 42", visionCalls, new CopyOnWriteArrayList<>());
    restartWithVision();

    try (Sse sse = watch()) {
      assertEquals(202, postPicture("receipt.png", pngBytes()).statusCode());
      assertEquals(202, post("/api/message", "{\"text\":\"这个多少钱\"}").statusCode());
      String text = sse.await("user", 5000).path("text").asText();
      assertTrue(text.contains("a receipt with a total of 42"), text);
      assertTrue(text.endsWith("这个多少钱"), text);
    }
  }

  @Test
  void aPictureNobodyUploadedIsRefusedRatherThanSentAsATextOnlyTurn() throws Exception {
    // 页面点名了一张服务器并不持有的图片：服务器和页面不一致了，而猜哪一边对，要么发出一个没有
    // 图片的回合，要么发出一个用户在另一台设备上已经丢掉的回合。
    AtomicInteger visionCalls = new AtomicInteger();
    startVision("never used", visionCalls, new CopyOnWriteArrayList<>());
    restartWithVision();

    HttpResponse<String> posted =
        post("/api/message", "{\"text\":\"看图\",\"picture\":\"other.png\"}");

    assertEquals(400, posted.statusCode(), posted.body());
    assertTrue(posted.body().contains("请重新选一张"), posted.body());
    assertEquals(List.of(), provider.requests(), "两端不一致时什么都不发");
  }

  @Test
  void aHeldPictureIsDroppedWithoutBeingSent() throws Exception {
    AtomicInteger visionCalls = new AtomicInteger();
    startVision("a cat on a keyboard", visionCalls, new CopyOnWriteArrayList<>());
    restartWithVision();

    try (Sse sse = watch()) {
      assertEquals(202, postPicture("cat.png", pngBytes()).statusCode());

      HttpResponse<String> dropped = delete("/api/attachment");
      assertEquals(200, dropped.statusCode(), dropped.body());
      assertEquals("cat.png", Json.parse(dropped.body()).path("discarded").asText());
      assertTrue(Json.parse(get("/api/status").body()).path("picture").isNull());

      assertEquals(202, post("/api/message", "{\"text\":\"刚才那张图呢\"}").statusCode());
      String text = sse.await("user", 5000).path("text").asText();
      assertEquals("刚才那张图呢", text, "被丢掉的那张图不会跟着这句话走");
    }
  }

  @Test
  void aSecondPictureReplacesTheFirstWhileItIsStillWaiting() throws Exception {
    // 一次只有一张图片会跟着消息走，所以第二次上传取代第一次，而不是把两张都留在那里等着。
    List<String> asked = new CopyOnWriteArrayList<>();
    AtomicInteger visionCalls = new AtomicInteger();
    startVision("a first description", visionCalls, asked);
    restartWithVision();

    try (Sse sse = watch()) {
      assertEquals(202, postPicture("first.png", pngBytes()).statusCode());
      JsonNode second = Json.parse(postPicture("second.png", pngBytes()).body());
      assertTrue(second.path("replaced").asBoolean(), "第二次上传说的是它取代了什么");
      assertEquals("second.png",
          Json.parse(get("/api/status").body()).path("picture").path("name").asText(),
          "拿着的是第二次上传的那一张");

      assertEquals(202, post("/api/message", "{\"text\":\"这一张\"}").statusCode());
      String text = sse.await("user", 5000).path("text").asText();
      // 标记里带着文件名，所以即使两段描述是同一段桩文本，也能看出走的是哪一张。
      assertTrue(text.startsWith("[picture second.png]"), text);
      assertTrue(text.endsWith("这一张"), text);
      assertTrue(Json.parse(get("/api/status").body()).path("picture").isNull(),
          "发出去之后就没有待发送的图片了");
    }
    assertEquals(2, visionCalls.get(), "两张图片各描述一次：上传即描述，没有别的时机");
  }

  @Test
  void aPngThatIsNotAPngIsRefusedWithoutAskingTheVisionModel() throws Exception {
    AtomicInteger visionCalls = new AtomicInteger();
    startVision("never asked", visionCalls, new CopyOnWriteArrayList<>());
    restartWithVision();

    HttpResponse<String> posted =
        postPicture("receipt.png", "this is not a png".getBytes(StandardCharsets.UTF_8));

    assertEquals(400, posted.statusCode(), posted.body());
    assertTrue(posted.body().contains("magic number"), posted.body());
    assertEquals(0, visionCalls.get(), "是字节说了算，而且是在问视觉端点之前");
    assertEquals(List.of(), attachmentDirectories(), "什么都没写");
  }

  @Test
  void aPictureOverTheLimitIsRefusedWithoutBeingHeld() throws Exception {
    AtomicInteger visionCalls = new AtomicInteger();
    startVision("never asked", visionCalls, new CopyOnWriteArrayList<>());
    restartWithVision();

    // 九兆字节，端点会按公布的长度拒掉它。这里钉住的是：这个拒绝是一次拒绝而不是一次崩溃，而且
    // 在走到它的路上什么都没写、什么都没描述；至于上限作用在「读」上而不是作用在已经缓冲下来的
    // 东西上，是 AttachmentStoreTest 钉住的——在那里，一条只要被读就会让测试失败的流让这件事
    // 变得可检查。
    byte[] big = new byte[(int) AttachmentStore.MAX_BYTES + 1];
    System.arraycopy(pngBytes(), 0, big, 0, 8);

    HttpResponse<String> posted = postPicture("huge.png", big);

    assertEquals(413, posted.statusCode(), posted.body());
    assertTrue(posted.body().contains("8 MB"), posted.body());
    assertEquals(0, visionCalls.get());
    assertEquals(List.of(), attachmentDirectories(), "而且什么都没写");
  }

  @Test
  void aPictureCanBeDescribedWhileATurnIsRunning() throws Exception {
    // 这条规矩随着上传不再开始回合而变了。描述一张图片不是这个对话的工作，而结果就在这里等着，
    // 所以「还有回合在跑；请先中止它」是把一次上传变成一条死路——你在等一个长回合时拍的那张照片，
    // 正是你接下来想用的那一张。
    AtomicInteger visionCalls = new AtomicInteger();
    startVision("a sticky note", visionCalls, new CopyOnWriteArrayList<>());
    restartWithVision();
    provider.reply(Message.Assistant.text("slow answer"));
    provider.gate(new CountDownLatch(1));

    try (Sse sse = watch()) {
      assertEquals(202, post("/api/message", "{\"text\":\"first\"}").statusCode());
      HttpResponse<String> posted = postPicture("photo.png", pngBytes());
      assertEquals(202, posted.statusCode(), posted.body());
      assertEquals("photo.png",
          Json.parse(get("/api/status").body()).path("picture").path("name").asText(),
          "它就在那里等着那个回合结束");

      provider.release();
      sse.await("done", 5000);
    }
    assertEquals(1, visionCalls.get(), "描述发生了，因为描述不需要那个回合腾出位置");
    // 在跑的那个回合不受影响：它只发出过一次请求，而里面没有这张图片。
    assertEquals(1, provider.requests().size());
    assertFalse(provider.requests().get(0).messages().toString().contains("a sticky note"),
        "正在跑的回合不会中途长出这张图片");
  }

  @Test
  void withoutAVisionModelAPictureIsRefusedWithWhatToSet() throws Exception {
    // 默认的测试配置没有 vision 块：这个功能报告自己是关的，而拒绝会点名那个块和打开它的那些
    // flag，而不是含混地失败。
    HttpResponse<String> posted = postPicture("photo.png", pngBytes());

    assertEquals(409, posted.statusCode(), posted.body());
    assertTrue(posted.body().contains("没有配置视觉模型，所以图片没法被描述——请设置 " + configFile + " 里的 \\\"vision\\\" 块（baseUrl、model 和一个密钥），或者传入 --vision-base-url 和 --vision-model"), posted.body());
    assertTrue(posted.body().contains("--vision-base-url"), posted.body());
    assertEquals(List.of(), attachmentDirectories(), "而且什么都没写");
  }

  @Test
  void theVisionBlockIsConfiguredFromTheSettingsFormAndUsed() throws Exception {
    // 整条面板路径：表单提交什么文件就留什么，而随后那张图片会去刚录入的那个模型——不去提供方，
    // 也不去一个过时的块。
    AtomicInteger visionCalls = new AtomicInteger();
    startVision("described by the model the form saved", visionCalls, new CopyOnWriteArrayList<>());
    String port = String.valueOf(vision.getAddress().getPort());

    JsonNode saved =
        postJson(
            "/api/config",
            "{\"provider\":\"openai\",\"model\":\"mock-model\","
                + "\"visionBaseUrl\":\"http://127.0.0.1:"
                + port
                + "/v1\",\"visionModel\":\"mock-vision\","
                + "\"visionApiKey\":\"sk-vision\",\"visionMaxTokens\":4096}");

    // 保存用状态作答，所以表单会读回来的就是 config 端点。
    assertEquals("mock-model", saved.path("model").asText());
    JsonNode vision = json("/api/config").path("vision");
    assertTrue(vision.path("on").asBoolean(), vision.toString());
    assertEquals("mock-vision", vision.path("model").asText());
    assertEquals(4096, vision.path("maxTokens").asInt());
    assertEquals(
        "mock-vision",
        Json.parse(Files.readString(configFile)).path("vision").path("model").asText(),
        "而且它在文件里，不只在内存里");

    assertEquals(202, postPicture("whiteboard.png", pngBytes()).statusCode());
    assertEquals(1, visionCalls.get(), "图片去了表单保存的那个模型");
  }

  @Test
  void theVisionKeyIsNeverReturnedToTheBrowser() throws Exception {
    // 和提供方密钥同一条规则：表单只会被告知有没有这么一个东西、它从哪里来，绝不被告知它是
    // 什么。
    Files.writeString(
        configFile,
        "{\"vision\":{\"baseUrl\":\"http://127.0.0.1:1/v1\",\"model\":\"m\","
            + "\"apiKey\":\"sk-vision-secret\"}}");
    restartFromConfigFile();

    String payload = json("/api/config").toString();
    JsonNode vision = Json.parse(payload).path("vision");

    assertFalse(payload.contains("sk-vision-secret"), payload);
    assertEquals("config", vision.path("apiKeySource").asText(), vision.toString());
    assertTrue(vision.path("on").asBoolean(), vision.toString());
  }

  @Test
  void clearingTheVisionKeyKeepsTheEndpointAndTurningPicturesOffRemovesTheBlock() throws Exception {
    Files.writeString(
        configFile,
        "{\"vision\":{\"baseUrl\":\"http://127.0.0.1:1/v1\",\"model\":\"m\","
            + "\"apiKey\":\"sk-vision-secret\",\"maxTokens\":4096}}");
    // 运行时的配置在启动时从文件构建，所以文件必须在 hub 之前就存在——CLI 也是这么做的。
    restartFromConfigFile();

    // 忘掉密钥不等于忘掉用户查出来的那个端点。
    postJson("/api/config", "{\"clearVisionApiKey\":true}");
    assertFalse(Files.readString(configFile).contains("sk-vision-secret"));
    JsonNode cleared = json("/api/config").path("vision");
    assertEquals("http://127.0.0.1:1/v1", cleared.path("baseUrl").asText());
    assertEquals(4096, cleared.path("maxTokens").asInt());
    assertEquals("none", cleared.path("apiKeySource").asText());

    // 而关掉它也不是「清空一个字段」：空字段意味着「别动它」，所以表单为这一项发的是一个
    // flag。
    postJson("/api/config", "{\"clearVision\":true}");
    assertFalse(json("/api/config").path("vision").path("configured").asBoolean());
    assertFalse(Files.readString(configFile).contains("\"vision\""), "那个块从文件里没了");

    HttpResponse<String> posted = postPicture("photo.png", pngBytes());
    assertEquals(409, posted.statusCode(), posted.body());
    assertTrue(posted.body().contains("没有配置视觉模型，所以图片没法被描述——请设置 " + configFile + " 里的 \\\"vision\\\" 块（baseUrl、model 和一个密钥），或者传入 --vision-base-url 和 --vision-model"), posted.body());
  }

  /** 一个 loopback 上的替身视觉端点：一句备好的描述，外加调用计数。 */
  private void startVision(String description, AtomicInteger calls, List<String> asked)
      throws IOException {
    if (vision != null) {
      vision.stop(0);
    }
    vision = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    vision.createContext(
        "/v1/chat/completions",
        exchange -> {
          asked.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          calls.incrementAndGet();
          ObjectNode reply = Json.object();
          reply.putArray("choices").addObject().putObject("message").put("content", description);
          byte[] body = Json.write(reply).getBytes(StandardCharsets.UTF_8);
          try {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
              out.write(body);
            }
          } catch (IOException ignored) {
            // 客户端把连接挂了；这个桩存在的意义就是那个计数。
          }
        });
    vision.start();
  }

  /** 再次建起 hub，用一份带 vision 块的配置，就像配置文件会写的那样。 */
  private void restartWithVision() throws IOException {
    Files.writeString(
        configFile,
        """
        {"provider":"openai","model":"mock-model","baseUrl":"http://mock.invalid/v1",
         "apiKey":"sk-test",
         "vision":{"baseUrl":"http://127.0.0.1:%d/v1","model":"mock-vision","apiKey":"sk-vision"}}
        """
            .formatted(vision.getAddress().getPort()));

    Config layered = Config.layered(configFile, Map.of(), null);
    api.close();
    hub.close();
    hub = hub(provider, layered);
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();
  }

  @Test
  void anOversizeUploadIsDrainedSoTheClientsAnswerIsNotTruncated() throws Exception {
    // 上一条覆盖「服务器在读取途中发现超限」；这一条覆盖 CI 上真正咬人的那一条：**公布的长度就超了**，
    // 于是服务器一个字节都不读就拒掉、然后把连接关掉——而客户端还在往里灌八兆。它看到的不是这条 413，
    // 而是 `fixed content-length: 72, bytes received: 0`（ubuntu 的 CI 上实测；macOS 上碰巧没撞上，本机
    // 一直是绿的）。所以拒绝之前要把请求体读掉（有界），让对方把话说完再收下这个答案。
    //
    // 手写一个 socket 而不是用 HttpClient：这里的要点正是「客户端还在写的时候服务器答了」，而那种交错
    // 自己控制才做得出来。
    long declared = AttachmentStore.MAX_BYTES + 1L;
    int chunk = 64 * 1024;
    long sent = 0;
    String head =
        "POST /api/attachment?name=huge.png HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + api.port() + "\r\n"
            + "Content-Type: application/octet-stream\r\n"
            + "Content-Length: " + declared + "\r\n"
            + "Connection: close\r\n\r\n";
    byte[] first = new byte[chunk];
    System.arraycopy(pngBytes(), 0, first, 0, 8);

    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), api.port())) {
      java.io.OutputStream out = socket.getOutputStream();
      out.write(head.getBytes(StandardCharsets.UTF_8));
      out.write(first);
      out.flush();
      sent += chunk;
      // 一边发一边看：拒答会在这中间到。全部发完之后再读，读到的必须是完整的那条 413。
      String raw = null;
      try {
        while (sent < declared) {
          int n = (int) Math.min(chunk, declared - sent);
          out.write(first, 0, n);
          out.flush();
          sent += n;
          Thread.sleep(1);
        }
        raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        // 服务器在我们还在写的时候就关了连接——这正是修复前那个 bug 的样子，所以把它当作断言失败报告，
        // 而不是当成一个错误异常。
        assertEquals(
            declared,
            sent,
            "服务器在请求体发完之前就把连接关了（发了 " + sent + " 字节），客户端读不到那条 413：" + e);
        return;
      }
      assertTrue(raw.startsWith("HTTP/1.1 413"), raw.substring(0, Math.min(200, raw.length())));
      assertTrue(raw.contains("上限"), raw);
    }
  }

  private HttpResponse<String> postPicture(String name, byte[] bytes) throws Exception {
    return client.send(
        HttpRequest.newBuilder(
                URI.create(
                    origin
                        + "/api/attachment?name="
                        + URLEncoder.encode(name, StandardCharsets.UTF_8)))
            .header("Content-Type", "image/png")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** 会话目录下每一个 {@code <id>.attachments} 目录的名字。 */
  private List<String> attachmentDirectories() throws IOException {
    if (!Files.isDirectory(sessions)) {
      return List.of();
    }
    try (var entries = Files.list(sessions)) {
      return entries
          .map(entry -> entry.getFileName().toString())
          .filter(name -> name.endsWith(AttachmentStore.DIRECTORY_SUFFIX))
          .sorted()
          .toList();
    }
  }

  private static byte[] pngBytes() throws IOException {
    java.awt.image.BufferedImage image =
        new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, 0xFF0000);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    assertTrue(javax.imageio.ImageIO.write(image, "png", out), "这个测试需要一张真正的 PNG");
    return out.toByteArray();
  }
}
