package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ccj.agent.core.ApprovalRules;
import com.ccj.agent.core.Compaction;
import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.ProjectPrompt;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.UsageTotals;
import com.ccj.agent.session.AttachmentStore;
import com.ccj.agent.session.FileSession;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.URLEncoder;
import java.util.Optional;
import com.ccj.agent.session.SessionStore;
import com.ccj.agent.workspace.WorkspaceStore;
import com.ccj.agent.tool.Tools;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 像浏览器那样驱动 web API：真实的 HTTP、真实的 SSE、真实的工具执行。
 *
 * <p>模型是脚本化的，所以被测的就是 web 层本身——事件顺序、审批握手、忙碌拒绝、会话切换和
 * token 关卡。
 */
class WebApiTest {

  @TempDir Path tmp;

  private final HttpClient client = HttpClient.newHttpClient();

  private Path cwd;
  private Path sessions;
  private Path configFile;
  private final AtomicInteger factoryCalls = new AtomicInteger();
  /** 测试会改动的行为；hub 持有 {@link #chooser}，由它委托到这里。 */
  private com.ccj.agent.provider.ProviderStore providerStore;
  private volatile FolderChooser chooserBehaviour = title -> Optional.empty();
  private final FolderChooser chooser = title -> chooserBehaviour.choose(title);
  private volatile MockProvider lastBuilt;
  private MockProvider provider;
  private AgentHub hub;
  private HttpApi api;
  private String origin;
  /** 替身的视觉端点，仅在某个测试启动了它时存在。 */
  private HttpServer vision;

  @BeforeEach
  void setUp() throws IOException {
    cwd = Files.createDirectories(tmp.resolve("ws"));
    sessions = tmp.resolve("sessions");
    configFile = tmp.resolve("config.json");
    providerStore = com.ccj.agent.provider.ProviderStore.open(tmp);
    provider = new MockProvider("mock");
    start(null);
  }

  @AfterEach
  void tearDown() {
    if (api != null) {
      api.close();
    }
    if (hub != null) {
      hub.close();
    }
    if (vision != null) {
      vision.stop(0);
    }
  }

  private void start(String token) throws IOException {
    start(token, new Wallpapers(null));
  }

  /** 启动一个服务器，其壁纸目录由测试自己选定。 */
  private void start(String token, Wallpapers wallpapers) throws IOException {
    if (api != null) {
      api.close();
    }
    hub = hub(provider, testConfig());
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), token, wallpapers);
    origin = "http://127.0.0.1:" + api.port();
  }


  /** 一个注册表，其默认工作区是测试的工作目录。 */
  private WorkspaceStore store() {
    return WorkspaceStore.open(tmp, cwd);
  }

  private static Config testConfig() {
    return new Config(
            "openai", "mock-model", "http://mock.invalid/v1", "sk-test", null, null, null, null,
            0, null, null)
        .resolved();
  }

  /**
   * 镜像 CLI 注入的东西：一个像真货那样做校验的工厂，返回以模型命名的提供方，这样运行时的
   * 切换从外部就观察得到。
   */
  private AgentHub hub(Provider initial, Config config) {
    return hub(initial, config, SessionStore.create(sessions));
  }

  /** 这些测试写入规则的审批文件；决策链在每次判定时都会读它。 */
  private Path approvalsFile() {
    return tmp.resolve("approvals.json");
  }

  private AgentHub hub(Provider initial, Config config, FileSession session) {
    AgentHub built = hubWithoutRules(initial, config, session);
    // 严格按 CLI 的接法接线：本项目的规则，放在应用主目录里。
    built.setApprovalRules(ApprovalRules.open(approvalsFile(), cwd));
    return built;
  }

  private AgentHub hubWithoutRules(Provider initial, Config config, FileSession session) {
    return new AgentHub(
        initial,
        config,
        Tools.standard(),
        new AgentHub.Settings(
            "test",
            store(),
            null,
            configFile,
            Map.of(),
            (candidate, env) -> {
              factoryCalls.incrementAndGet();
              // 镜像真实的工厂：内置提供方，加上用户自定义的那些。
              String requested = candidate.provider();
              if (requested == null
                  || (!List.of("openai", "anthropic").contains(requested)
                      && providerStore.find(requested).isEmpty())) {
                throw new IllegalArgumentException("未知的提供方 '" + requested + "'");
              }
              lastBuilt =
                  new MockProvider(candidate.model() == null ? "mock-model" : candidate.model());
              return lastBuilt;
            },
            new com.ccj.agent.provider.ConfigModelCatalog(providerStore),
            providerStore,
            chooser,
            false),
        session);
  }

  // ------------------------------------------------------------------ 测试

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
  void aToolCallBlocksOnApprovalAndThenReallyRuns() throws Exception {
    provider.reply(bashCall("printf hi > made.txt"));
    provider.reply(Message.Assistant.text("done"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"create the file\"}");

      JsonNode approval = sse.await("approval", 5000);
      assertEquals("bash", approval.path("title").asText());
      assertTrue(approval.path("detail").asText().contains("made.txt"), approval.toString());
      assertFalse(
          Files.exists(cwd.resolve("made.txt")), "审批还挂着的时候什么都不能发生");

      JsonNode start = sse.await("tool", 1000);
      assertEquals("start", start.path("state").asText());
      assertEquals("printf hi > made.txt", start.path("summary").asText());

      HttpResponse<String> resolved =
          post(
              "/api/approval",
              "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      assertEquals(200, resolved.statusCode(), resolved.body());
      assertEquals("hi", Files.readString(cwd.resolve("made.txt")));

      JsonNode done = sse.await("done", 5000);
      assertEquals("done", done.path("finalText").asText());
      JsonNode ended = lastOf(sse, "tool");
      assertTrue(ended.path("ok").asBoolean(), ended.toString());
      assertEquals("end", ended.path("state").asText());
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
  void anUnansweredApprovalWaitsRatherThanExpiring() throws Exception {
    // 这里钉住的行为：向人提出的问题不会过期。旧的 120 秒上限会终结回合，并让页面显示一个
    // 早已被撤回的提示——活儿被丢下了，而用户说不出为什么。
    //
    // 故意等过了旧的超时时间。两分钟会让整个测试套件没法用，所以断言的是：在*页面*对一条沉默
    // 的流放弃（20 秒）很久之后，回合仍然在等；旧设计失败的窗口正是这一段：提示没能及时到达
    // 浏览器。这里仍在等，就说明请求撑过来了。
    provider.reply(bashCall("printf hi > waiting.txt"));
    provider.reply(Message.Assistant.text("done"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"do it\"}");
      JsonNode approval = sse.await("approval", 5000);
      assertFalse(approval.path("id").asText().isEmpty());

      Thread.sleep(21_000); // past the page's stale-stream window

      // 仍然挂着，并没有替用户作答。
      JsonNode status = json("/api/status");
      assertEquals(1, status.path("approvals").size(), status.toString());
      assertFalse(Files.exists(cwd.resolve("waiting.txt")), "没人作答时什么也没跑");

      // 两条出路仍然有效：作答。
      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      sse.await("done", 5000);
      assertEquals("hi", Files.readString(cwd.resolve("waiting.txt")));
    }
  }

  @Test
  void abortingAnswersAPendingApproval() throws Exception {
    // 一条不依赖计时器的出路：等一个活人的回合以前要靠超时来终结，没有超时之后，中止就得是
    // 那个作答的东西。
    provider.reply(bashCall("printf hi > never.txt"));
    provider.reply(Message.Assistant.text("stopped"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"do it\"}");
      sse.await("approval", 5000);

      assertEquals(200, post("/api/abort", "{}").statusCode());

      // 回合结束了，而不是永远等下去；而且什么也没跑。
      sse.await("done", 5000);
      assertFalse(Files.exists(cwd.resolve("never.txt")));
      assertEquals(0, json("/api/status").path("approvals").size(), "问题已被撤回");
    }
  }

  @Test
  void aDeniedApprovalLeavesTheWorkspaceUntouched() throws Exception {
    provider.reply(bashCall("printf hi > nope.txt"));
    provider.reply(Message.Assistant.text("stopped"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"do it\"}");
      JsonNode approval = sse.await("approval", 5000);
      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":false}");

      sse.await("done", 5000);
      assertFalse(Files.exists(cwd.resolve("nope.txt")), "被拒绝的调用绝不能碰磁盘");
      assertFalse(lastOf(sse, "tool").path("ok").asBoolean(), "工具必须报告失败");
      assertFalse(hub.autoApprove(), "拒绝不能把闸门关掉");
    }
  }

  @Test
  void allowingForTheSessionStopsAskingAboutThatCommandAndNothingElse() throws Exception {
    // 「记住」现在意味着什么，而区别正是重点。它过去会把整个会话切到自动批准——于是替一条
    // 命令作答就决定了此后每一个问题——所以干这事的那个开关成了谁也不敢碰的那一个。现在作答
    // 的范围和用户按下的按钮一样窄：这条命令，本会话，其他所有东西的闸门照旧开着。
    // 回复是按回合入队的，而不是一次全排好：这些回合是不同的命令，而一次排满的队列会把第三个
    // 回合的答案递给第二个回合。
    provider.reply(bashCall("printf a > one.txt"));
    provider.reply(Message.Assistant.text("first"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"one\"}");
      JsonNode approval = sse.await("approval", 5000);
      post(
          "/api/approval",
          "{\"id\":\"" + approval.path("id").asText() + "\",\"answer\":\"session\"}");
      sse.await("done", 5000);
      assertFalse(hub.autoApprove(), "这个问题问的是一条命令，不是每一条命令");

      // 同一条命令再来一次：由记住的东西作答，所以不会有第二次提示。
      provider.reply(bashCall("printf a > one.txt"));
      provider.reply(Message.Assistant.text("same command"));
      post("/api/message", "{\"text\":\"same again\"}");
      sse.awaitAtLeast("done", 2, 5000);
      assertEquals(1, sse.ofType("approval").size(), "同一条命令不得问两次");

      // 不同的命令就是不同的问题，它仍然会被问到。
      provider.reply(bashCall("printf c > three.txt"));
      provider.reply(Message.Assistant.text("different command"));
      post("/api/message", "{\"text\":\"something else\"}");
      JsonNode second = sse.awaitAtLeast("approval", 2, 5000);
      assertNotNull(second, "没人放行过的命令仍然必须问一次");
      assertEquals("bash", second.path("tool").asText());
      assertEquals("printf c > three.txt", second.path("command").asText(),
          "而且提示把命令作为一个字段带上，这样才能被规则匹配");
      post("/api/approval", "{\"id\":\"" + second.path("id").asText() + "\",\"answer\":\"deny\"}");
      sse.awaitAtLeast("done", 3, 5000);
      assertFalse(Files.exists(cwd.resolve("three.txt")), "被拒绝的命令绝不能跑过");
    }
  }

  @Test
  void alwaysAllowWritesARuleAndAProjectPicksItUp() throws Exception {
    // 「总是允许」这个答案是一次文件写入，所以正是要小心的那一个：写进去的东西不能比被问到的
    // 范围更宽，而且事后必须真的管用——包括对后来才启动、重新读这个文件的进程。
    provider.reply(bashCall("printf a > one.txt"));
    provider.reply(Message.Assistant.text("first"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"one\"}");
      JsonNode approval = sse.await("approval", 5000);
      post(
          "/api/approval",
          "{\"id\":\"" + approval.path("id").asText() + "\",\"answer\":\"always\"}");
      sse.await("done", 5000);
    }

    String written = Files.readString(approvalsFile());
    assertTrue(written.contains("printf a > one.txt"), written);
    assertTrue(written.contains(cwd.toString()), "归档在它被授予的那个项目下：" + written);
    assertTrue(
        written.contains("one.txt"),
        "规则就是被批准的那条命令，逐字照录：" + written);
    assertFalse(
        written.contains("\"command\" : \"*\"") || written.contains("\"tool\" : \"bash\"\n    }"),
        "并且没有任何会放行所有命令的东西：" + written);

    // 同一个文件上重建一个全新的 hub，如下一个进程会做的那样：这条命令不会再被问到。
    api.close();
    hub.close();
    hub = hub(provider, testConfig());
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();

    provider.reply(bashCall("printf a > one.txt"));
    provider.reply(Message.Assistant.text("again"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"again\"}");
      sse.await("done", 5000);
      assertEquals(0, sse.ofType("approval").size(), "文件里的规则现在就替它作答");
      assertTrue(Files.exists(cwd.resolve("one.txt")));
    }
  }

  @Test
  void aRuleThatForbidsIsRefusedWithoutAskingAnybody() throws Exception {
    // 拒绝优先，而且它绝不能变成一次提示：写规则的意义就在于它自己说了算。
    Files.createDirectories(approvalsFile().getParent());
    Files.writeString(
        approvalsFile(),
        "{\"projects\": {\""
            + cwd
            + "\": {\"deny\": [{\"tool\": \"bash\", \"command\": \"printf a > one.txt\"}]}}}");
    provider.reply(bashCall("printf a > one.txt"));
    provider.reply(Message.Assistant.text("tried"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"one\"}");
      JsonNode done = sse.await("done", 5000);
      assertEquals(0, sse.ofType("approval").size(), "规则作答了，所以不用问任何人");
      assertFalse(Files.exists(cwd.resolve("one.txt")), "而且这条命令没有跑");
      assertTrue(done.toString().contains("tried"), done.toString());
    }
    // 转录里说的是规则说了不行，而不是报告某个人的拒绝：在一个没人看着的会话里，这是两种
    // 不同的事件。
    assertTrue(
        sseText().contains("被审批文件中的某条规则拒绝（可运行 ccj --help、查看 SECURITY.md 了解如何改规则）"),
        "规则的拒绝读起来必须就是规则的拒绝：" + sseText());
  }

  private String sseText() throws Exception {
    // 本次会话的转录里有什么就返回什么，如同页面会渲染出的样子。
    return json("/api/history").toString();
  }

  @Test
  void aMessageSentWhileBusyWaitsAndThenRuns() throws Exception {
    // 它以前会被 409 拒绝，这让一次思考中的停顿变成了彻底卡死：输入框一直禁用，直到回合结束，
    // 而你在等待时想到的东西全都丢了。现在它会被排队，并且作为自己的一个回合运行——有自己的
    // user 事件和自己的 done。
    provider.reply(Message.Assistant.text("slow answer"));
    provider.reply(Message.Assistant.text("the queued answer"));
    provider.gate(new CountDownLatch(1));
    try (Sse sse = watch()) {
      assertEquals(202, post("/api/message", "{\"text\":\"first\"}").statusCode());

      HttpResponse<String> second = post("/api/message", "{\"text\":\"second\"}");
      assertEquals(202, second.statusCode(), second.body());
      assertTrue(Json.parse(second.body()).path("queued").asBoolean(), second.body());
      assertEquals(
          List.of("second"),
          queuedTexts(),
          "状态说明了什么在排队，这样输入框才能把它显示出来");

      provider.release();
      sse.await("done", 5000);
      // 排队的消息会自己开始，它开始后队列就空了。
      assertEquals("second", sse.await("user", 5000).path("text").asText());
      assertEquals("the queued answer", sse.awaitAtLeast("done", 2, 5000).path("finalText").asText());
      assertEquals(List.of(), queuedTexts());
    }
  }

  @Test
  void abortDropsWhatWasQueuedBehindTheTurn() throws Exception {
    // 中止是出了岔子时按下的按钮。让四条消息等着在被中止的回合松手的那一刻开始，是「停下」的
    // 反面；而那个计数会被公布出来，这样被丢弃的消息是看得见的，而不是悄无声息的。
    provider.reply(Message.Assistant.text("slow answer"));
    provider.gate(new CountDownLatch(1));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"first\"}");
      post("/api/message", "{\"text\":\"second\"}");
      post("/api/message", "{\"text\":\"third\"}");
      assertEquals(List.of("second", "third"), queuedTexts());

      post("/api/abort", "{}");

      provider.release();
      JsonNode notice = sse.await("notice", 5000);
      assertTrue(notice.path("text").asText().contains("已中止；丢掉了 2 条排队的消息"), notice.toString());
      assertEquals(List.of(), queuedTexts());
      assertFalse(
          sse.ofType("user").stream().anyMatch(event -> event.path("text").asText().equals("second")),
          "被丢弃的消息不得之后再启动");
    }
  }

  @Test
  void aQueueHasABoundAndSaysSo() throws Exception {
    // 没有上限的队列，是一种对已经在跑东西的会话失去控制的方式。
    provider.reply(Message.Assistant.text("slow answer"));
    provider.gate(new CountDownLatch(1));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"first\"}");
      HttpResponse<String> last = null;
      for (int i = 0; i < 16; i++) {
        last = post("/api/message", "{\"text\":\"waiting " + i + "\"}");
        assertEquals(202, last.statusCode(), last.body());
      }
      HttpResponse<String> over = post("/api/message", "{\"text\":\"one too many\"}");

      assertEquals(409, over.statusCode(), over.body());
      assertTrue(over.body().contains("这个对话已经有 16 条消息在等了；请等回合结束，或者中止它"), over.body());
      assertEquals(16, queuedTexts().size());
      provider.release();
    }
  }

  private List<String> queuedTexts() throws Exception {
    List<String> texts = new java.util.ArrayList<>();
    json("/api/status").path("queued").forEach(node -> texts.add(node.asText()));
    return texts;
  }

  @Test
  void anotherSessionRunsWhileOneIsStillBusy() throws Exception {
    // 这次改动的全部意义：b 会话里的一个回合不得锁住服务器，否则「在另一个对话里开个任务」就是
    // 一句空话。*同一个*会话里的*第二个*回合仍然被拒——每个对话一个写入者，才让转录不会变成两
    // 份。b 被扣在一次审批上，这是回合长时间占住服务器的最真实方式。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_b", "bash", "{\"command\":\"echo b\"}"))));
    provider.reply(Message.Assistant.text("a finished"));
    provider.reply(Message.Assistant.text("b finished"));
    provider.reply(Message.Assistant.text("b's queued answer"));
    try (Sse sse = watch()) {
      assertEquals(202, post("/api/message", "{\"text\":\"b: first\"}").statusCode());
      String b = json("/api/status").path("sessionId").asText();
      assertFalse(b.isEmpty(), "正在运行的会话必须可辨认");
      JsonNode approval = sse.awaitInSession(b, "approval", 1, 5000);

      assertTrue(json("/api/status").path("busy").asBoolean(), "b 正在运行");
      // 每个对话同一时刻只有一个回合，这一点没变——变的是第二条消息会等它，而不是被拒。
      HttpResponse<String> queuedBehindB = post("/api/message", "{\"text\":\"b: second\"}");
      assertEquals(202, queuedBehindB.statusCode(), queuedBehindB.body());
      assertTrue(Json.parse(queuedBehindB.body()).path("queued").asBoolean(), queuedBehindB.body());

      // 另开一个对话并在里面派一个任务，而 b 还在等人作答。
      String a = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(b, a);
      assertFalse(
          json("/api/status").path("busy").asBoolean(),
          "正在看的会话是空闲的，尽管 b 在运行");
      assertTrue(
          json("/api/status").path("running").toString().contains(b),
          "而且状态里点名了正在运行的那个会话");

      assertEquals(
          202,
          post("/api/message", "{\"text\":\"a: hello\"}").statusCode(),
          "另一个会话里的回合必须被接受");
      assertEquals("a finished", sse.awaitInSession(a, "done", 1, 5000).path("finalText").asText());

      // b 从未被打扰：它的审批仍然挂着，作答就结束了 b。
      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      // b 的两个回合按顺序：先是那个等人作答的，然后是排在它后面的消息。按列表来读，而不是
      // 调两次 `awaitInSession`——后者返回最新匹配的事件，会和排队回合自己的 `done` 抢。
      sse.awaitInSession(b, "done", 2, 5000);
      assertEquals(
          List.of("b finished", "b's queued answer"),
          sse.forSession(b).stream()
              .filter(event -> "done".equals(event.path("type").asText()))
              .map(event -> event.path("finalText").asText())
              .toList());
    }
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
  void anApprovalInAnotherSessionIsVisibleAndAnswerableFromHere() throws Exception {
    // 一个需要活人的后台回合，不能因为用户正在看另一个对话就永远等下去：请求会带着它的会话一起
    // 发布，而在哪儿都能作答。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("ran it"));
    try (Sse sse = watch()) {
      assertEquals(202, post("/api/message", "{\"text\":\"start the long job\"}").statusCode());
      String b = json("/api/status").path("sessionId").asText();

      String a = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(b, a);

      JsonNode approval = sse.awaitInSession(b, "approval", 1, 5000);
      assertEquals(b, approval.path("sessionId").asText(), "请求里点名了它属于哪个会话");
      assertEquals("bash", approval.path("title").asText());

      post(
          "/api/approval",
          "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      assertEquals("ran it", sse.awaitInSession(b, "done", 1, 5000).path("finalText").asText());
    }
  }

  @Test
  void whatTouchesOneConversationIsRefusedOnlyWhileThatOneRuns() throws Exception {
    // 哪些操作在意并发是一个决定，而不是习惯。显示一个对话什么都不改变，永远允许——包括正在
    // 工作的那个，让回合继续跑的全部意义就在这。会把回合脚下的地抽走的操作——删它的文件、改
    // 模型——要等它。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"b: long job\"}");
      String b = json("/api/status").path("sessionId").asText();
      JsonNode approval = sse.awaitInSession(b, "approval", 1, 5000);

      // 看着那个忙碌的对话没问题，反复地看也没问题。
      assertEquals(
          200,
          post("/api/session", "{\"action\":\"resume\",\"id\":\"" + b + "\"}").statusCode(),
          "正在运行的对话可以被显示");
      // 但它的文件不能被删：那会把转录从回合脚底下抽走。
      assertEquals(
          409,
          client
              .send(
                  HttpRequest.newBuilder(URI.create(origin + "/api/session?id=" + b))
                      .DELETE()
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode(),
          "也不能在它正被写入时删除");

      // 设置是每个对话脚下的地，所以它们继续被拒。
      assertEquals(
          409,
          post("/api/config", "{\"model\":\"other-model\"}").statusCode(),
          "在回合运行时改模型，正是必须等待的那种操作");

      // *切走*是用户继续干活的方式，所以那是允许的。
      String a = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(b, a, "可以在正在运行的对话旁边新开一个对话");

      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      sse.awaitInSession(b, "done", 1, 5000);
    }
  }

  @Test
  void anAbortStopsTheSessionOnScreenAndLeavesTheOtherAlone() throws Exception {
    // 中止是按对话来的：停下你正在看的那个任务，绝不能把另一个也停下。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"b: long job\"}");
      String b = json("/api/status").path("sessionId").asText();
      sse.awaitInSession(b, "approval", 1, 5000);

      String a = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(b, a);

      // 屏幕上这个会话是 a，而 a 没有在运行：这里没有什么可中止的。
      assertFalse(
          postJson("/api/abort", "{}").path("aborted").asBoolean(),
          "中止作用于屏幕上那个对话，而它是空闲的");

      JsonNode aborted = postJson("/api/abort?id=" + b, "{}");
      assertTrue(aborted.path("aborted").asBoolean(), "正在运行的会话仍然可以被停下");
      JsonNode stopped = sse.awaitInSession(b, "done", 1, 5000);
      assertTrue(stopped.path("aborted").asBoolean(), "而且它以被中止结束：" + stopped);
    }
  }

  @Test
  void theSidebarCanTellWhichSessionsAreRunning() throws Exception {
    // 没有这个，页面就无法标记用户启动后又切走的那一行。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"b: long job\"}");
      String b = json("/api/status").path("sessionId").asText();
      sse.awaitInSession(b, "approval", 1, 5000);

      JsonNode list = json("/api/sessions").path("sessions");
      assertEquals(1, list.size(), list.toString());
      assertEquals(b, list.get(0).path("id").asText());
      assertTrue(list.get(0).path("running").asBoolean(), list.toString());

      String a = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(b, a);
      assertTrue(
          json("/api/sessions").path("sessions").get(0).path("running").asBoolean(),
          "切走并不会把它停下");

      postJson("/api/abort?id=" + b, "{}");
      sse.awaitInSession(b, "done", 1, 5000);
      assertFalse(
          json("/api/sessions").path("sessions").get(0).path("running").asBoolean(),
          "回合结束后就不再是运行中");
    }
  }

  @Test
  void sessionsCanBeListedAndSwitched() throws Exception {
    provider.reply(Message.Assistant.text("ok"));
    String first;
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"remember me\"}");
      sse.await("done", 5000);
      first = json("/api/status").path("sessionId").asText();
    }

    JsonNode list = json("/api/sessions").path("sessions");
    assertEquals(1, list.size(), list.toString());
    assertEquals("remember me", list.get(0).path("preview").asText());
    assertEquals("remember me", list.get(0).path("title").asText(),
        "侧边栏用被问的第一句话给会话打标签，而不是用它的时间戳 id");
    assertEquals(first, list.get(0).path("id").asText());

    JsonNode created = postJson("/api/session", "{\"action\":\"new\"}");
    assertNotEquals(first, created.path("sessionId").asText());

    JsonNode resumed = postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + first + "\"}");
    assertEquals(first, resumed.path("sessionId").asText());
    assertEquals(2, resumed.path("messageCount").asInt(), "历史必须从磁盘回放");
  }

  @Test
  void aRunningConversationCanStillBeOpened() throws Exception {
    // 有人报的 bug：a 里有回合在跑时，在侧边栏点 a 会被弹回来。切换*显示*哪个对话，对正在运行
    // 的回合什么都没改变——让任务继续跑的意义就在于你还能看着它。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"a: long job\"}");
      String a = json("/api/status").path("sessionId").asText();
      sse.awaitInSession(a, "approval", 1, 5000);

      // 把目光移开……
      String b = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(a, b);
      // ……再回到正在工作的那个。
      JsonNode back = postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + a + "\"}");
      assertEquals(a, back.path("sessionId").asText(), "正在运行的会话就是该看的那个");
      assertTrue(back.path("busy").asBoolean(), "而且它仍然显示为运行中");

      postJson("/api/abort?id=" + a, "{}");
      sse.awaitInSession(a, "done", 1, 5000);
      // 回合的结果落进了 a 自己的文件，且只落了一次：再次打开 a 绝不能在那上面又起一个写入者。
      List<String> lines = Files.readAllLines(sessions.resolve(a + ".jsonl"));
      assertTrue(lines.stream().anyMatch(line -> line.contains("tool_result")), lines.toString());
    }
  }

  @Test
  void openingARunningSessionAgainDoesNotOpenASecondWriter() throws Exception {
    // 旧检查存在的理由。它必须靠复用回合正在写的那个文件来回答，而不是靠拒绝显示对话：两个
    // FileSession 往同一个 JSONL 里追加，正是「每会话一条」规则要防的那种损坏。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("a finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"a: long job\"}");
      String a = json("/api/status").path("sessionId").asText();
      sse.awaitInSession(a, "approval", 1, 5000);

      // 磁盘上存在的第二个对话，用来切过去再切回来。
      String b = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(a, b);
      assertEquals(202, post("/api/message", "{\"text\":\"b: hello\"}").statusCode());
      sse.awaitInSession(b, "done", 1, 5000);

      // 来回切换是用户在等待时会做的事，而每次回到 a 都必须回到它那个回合正在用的同一个文件，
      // 而不是在它上面多一个写入者。
      for (int i = 0; i < 3; i++) {
        assertEquals(
            a,
            postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + a + "\"}")
                .path("sessionId")
                .asText());
        assertEquals(
            b,
            postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + b + "\"}")
                .path("sessionId")
                .asText());
      }

      postJson("/api/abort?id=" + a, "{}");
      sse.awaitInSession(a, "done", 1, 5000);
      // 每一行仍然是一个完整的 JSON 对象：多一个写入者会把字节交错进去。
      for (String line : Files.readAllLines(sessions.resolve(a + ".jsonl"))) {
        assertFalse(line.isBlank(), "没有撕裂的行");
        Json.parse(line);
      }
    }
  }

  @Test
  void anotherWorkspacesConversationCanBeOpenedWhileATurnRuns() throws Exception {
    // 有人报的 bug：一个工作区里的回合挡住了打开另一个工作区的对话。回合从启动那一刻起就拥有
    // 自己的工作目录和自己的会话文件，所以在它运行时看别的工作区不可能打扰到它。
    Path other = Files.createDirectories(tmp.resolve("other-ws"));

    provider.reply(
        new Message.Assistant(
            "",
            List.of(
                new Message.ToolCall(
                    "call_1", "bash", "{\"command\":\"touch made-by-a.txt\"}"))));
    provider.reply(Message.Assistant.text("c finished"));
    provider.reply(Message.Assistant.text("a finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"a: long job\"}");
      String a = json("/api/status").path("sessionId").asText();
      JsonNode approval = sse.awaitInSession(a, "approval", 1, 5000);

      // 注册工作区只是加一条注册表条目，而且必须在 a 运行时也能做——否则第二个工作区恰好在用户
      // 想去的时候够不着。
      postJson("/api/workspaces", "{\"name\":\"other\",\"path\":\"" + other + "\"}");

      // 切过去：换的是命名空间，不是正在运行的东西。
      JsonNode switched = postJson("/api/workspace", "{\"name\":\"other\"}");
      assertEquals("other", switched.path("workspace").path("name").asText());
      String c = switched.path("sessionId").asText();
      assertNotEquals(a, c, "那边是个新会话");

      // ……而且在第一个工作区的回合继续跑时它就能用。
      assertEquals(202, post("/api/message", "{\"text\":\"c: in the other workspace\"}").statusCode());
      assertEquals("c finished", sse.awaitInSession(c, "done", 1, 5000).path("finalText").asText());
      assertTrue(
          json("/api/status").path("running").toString().contains(a),
          "第一个工作区里的回合未受影响：" + json("/api/status"));

      // a 里的回合仍然在它启动时所在的目录里运行，而不是现在屏幕上那个：命令会写一个文件，
      // 而这个文件必须落在第一个工作区。
      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      assertEquals("a finished", sse.awaitInSession(a, "done", 1, 5000).path("finalText").asText());
      assertTrue(
          Files.exists(cwd.resolve("made-by-a.txt")),
          "a 的工具在 a 自己的工作区里运行，也就是它启动时所在的地方：" + cwd);
      assertFalse(
          Files.exists(other.resolve("made-by-a.txt")),
          "而不是当前只是显示在屏幕上的那个工作区");
    }
  }


  @Test
  void eachConversationsOwnRulesFollowItsWorkingDirectory() throws Exception {
    // 规则来自对话的工具所运行的目录，在 web UI 里这是*会话*的属性，不是服务器的属性：一个
    // 页面可以让一个对话在一个项目里、另一个在它的兄弟目录里，而每个请求都必须带上自己项目的
    // 规则。
    Files.writeString(
        cwd.resolve(ProjectPrompt.FILE_NAME), "Rule for the first workspace: run `make check`.\n");
    Path other = Files.createDirectories(tmp.resolve("second-ws"));
    Files.writeString(
        other.resolve(ProjectPrompt.FILE_NAME), "Rule for the second workspace: use tabs.\n");

    provider.reply(Message.Assistant.text("first answer"));
    provider.reply(Message.Assistant.text("second answer"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello in the first\"}");
      sse.await("done", 5000);
      String firstPrompt = lastSystemPrompt(0);
      assertTrue(firstPrompt.contains("run `make check`"), firstPrompt);
      assertFalse(firstPrompt.contains("use tabs"), firstPrompt);

      postJson("/api/workspaces", "{\"name\":\"second\",\"path\":\"" + other + "\"}");
      JsonNode switched = postJson("/api/workspace", "{\"name\":\"second\"}");
      post("/api/message", "{\"text\":\"hello in the second\"}");
      sse.awaitInSession(switched.path("sessionId").asText(), "done", 1, 5000);

      String secondPrompt = lastSystemPrompt(1);
      assertTrue(secondPrompt.contains("use tabs"), secondPrompt);
      assertFalse(
          secondPrompt.contains("run `make check`"),
          "而不是另一个工作区的规则：" + secondPrompt);
    }
  }

  /** 模拟提供方收到的第 n 个请求的系统提示词。 */
  private String lastSystemPrompt(int index) {
    List<Provider.Request> seen = provider.requests();
    assertTrue(seen.size() > index, "only " + seen.size() + " requests so far");
    String system = seen.get(index).system();
    assertTrue(system != null, "每个请求都必须带上系统提示词");
    return system;
  }

  @Test
  void aReplayedConversationKeepsItsReasoning() throws Exception {
    // 有人报的 bug：在一个对话思考时切走，再切回来，推理内容就没了——因为 historyJson 只回放
    // 散文和工具调用。推理内容就在会话文件里，所以丢掉它的回放是在回放另一个对话。
    provider.reply(Message.Assistant.text("answered"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"think about it\"}");
      sse.await("done", 5000);
      String session = json("/api/status").path("sessionId").asText();

      // 推理内容从流上到达，并随助手回合一起被持久化。
      provider.emitReasoning("considering the problem");
      post("/api/message", "{\"text\":\"and again\"}");
      sse.awaitAtLeast("done", 2, 5000);
      String file = Files.readString(sessions.resolve(session + ".jsonl"));
      assertTrue(file.contains("considering the problem"), "推理内容就在磁盘上：\n" + file);

      // 所以回放那个对话时必须把它再显示出来。
      JsonNode history = json("/api/history");
      assertEquals(session, history.path("sessionId").asText());
      List<String> reasoning = new ArrayList<>();
      history.path("events").forEach(event -> {
        if ("reasoning".equals(event.path("type").asText())) {
          reasoning.add(event.path("delta").asText());
        }
      });
      assertEquals(List.of("considering the problem"), reasoning,
          "回放必须带上推理内容，否则切走再切回来就丢了："
              + history.path("events"));
    }
  }

  @Test
  void aRedactedReasoningBlockIsNotReplayedAsText() throws Exception {
    // 被遮蔽块的载荷是不透明的，必须原封不动地送回*模型*；把它当散文显示会在转录里堆一堵
    // base64 的墙，那比什么都不显示更糟。
    provider.reply(Message.Assistant.text("done"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"go\"}");
      sse.await("done", 5000);
      String session = json("/api/status").path("sessionId").asText();
      // 手写一个：只有 Anthropic 会产生这种东西，而这个测试针对的是那层投影。
      Files.writeString(
          sessions.resolve(session + ".jsonl"),
          Files.readString(sessions.resolve(session + ".jsonl"))
              + com.ccj.agent.session.MessageCodec.toJson(
                  new Message.Assistant(
                      "",
                      List.of(),
                      List.of(Message.Thinking.redacted("b3BhcXVlLXBheWxvYWQ="))))
              + "\n");

      JsonNode history = json("/api/history");
      history.path("events").forEach(event ->
          assertFalse(
              event.path("delta").asText().contains("b3BhcXVl"),
              "不透明的载荷绝不能被渲染成散文：" + event));
    }
  }

  @Test
  void aPendingApprovalSurvivesLeavingAndComingBack() throws Exception {
    // 有人报的 bug：一个等待审批的回合，在用户一看别的对话时就把提示丢了，只剩中止。审批是
    // 阻塞在内存里的请求，不是一条消息，所以回放历史并不能把它带回来——它必须在等待中的那个
    // 对话的状态里可见，而页面在进入时必须把它重新渲染出来。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("ran it"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"a: run it\"}");
      String a = json("/api/status").path("sessionId").asText();
      JsonNode approval = sse.awaitInSession(a, "approval", 1, 5000);
      String approvalId = approval.path("id").asText();

      // 看向别处再回来：请求仍然未决，所以必须把这件事告诉页面。
      String b = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(a, b);
      JsonNode back = postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + a + "\"}");
      assertEquals(a, back.path("sessionId").asText());

      // 等待中的那个对话的状态点名了未决的请求，并带上把提示重新画出来所需的一切。
      JsonNode pending = back.path("approvals");
      assertTrue(pending.isArray(), back.toString());
      assertEquals(1, pending.size(), "未决的请求被报告了出来：" + back);
      assertEquals(approvalId, pending.get(0).path("id").asText());
      assertEquals("bash", pending.get(0).path("title").asText());
      assertTrue(pending.get(0).path("detail").asText().contains("echo hi"), pending.toString());

      // 而且从另一个对话里作答仍然有效。
      post("/api/approval", "{\"id\":\"" + approvalId + "\",\"allow\":true}");
      assertEquals("ran it", sse.awaitInSession(a, "done", 1, 5000).path("finalText").asText());
      assertEquals(
          0,
          json("/api/status").path("approvals").size(),
          "已解决的请求不再挂着");
    }
  }

  @Test
  void anApprovalBelongsToOneConversationOnly() throws Exception {
    // 可以有两个回合同时等待。每个页面只能被提供它自己那个对话的请求，否则作答屏幕上那个就会
    // 解决掉另一个的。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("a ran it"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"a: run it\"}");
      String a = json("/api/status").path("sessionId").asText();
      JsonNode approval = sse.awaitInSession(a, "approval", 1, 5000);

      // 一个全新的对话没有属于它自己的未决请求。
      String b = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertEquals(
          0,
          json("/api/status").path("approvals").size(),
          "b 没有任何未决请求；a 的请求不是 b 的：" + json("/api/status"));
      // a 仍在等待，而且仍然这么说着。
      postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + a + "\"}");
      assertEquals(1, json("/api/status").path("approvals").size());

      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      sse.awaitInSession(a, "done", 1, 5000);
    }
  }

  @Test
  void theTokenGateProtectsTheNetworkAndRemembersTheBrowser() throws Exception {
    api.close();
    start("s3cret");

    // token 是为了从别处够到这台服务器，而一个点名了非 loopback 主机的请求就属于这种情况，
    // 哪怕它是在本机上发出的——这正是回答「自己域名解析到 127.0.0.1 的页面」的那一半。
    String denied = rawResponse("rebind.example", "/api/status");
    assertTrue(denied.startsWith("HTTP/1.1 401"), denied);
    assertTrue(denied.contains("token"), denied);

    String refused = rawResponse("rebind.example", "/api/status?token=wrong");
    assertTrue(refused.startsWith("HTTP/1.1 401"), refused);

    // 本机不算「别处」：在 127.0.0.1 上打开页面，就是坐在键盘前的用户使用 ccj 的方式，在那里
    // 还要输一个秘密，等于在人家自己的命令行上加了个密码。
    assertEquals(200, get("/api/status").statusCode(), "本机自己被放行");

    HttpResponse<String> allowed = get("/api/status?token=s3cret");
    assertEquals(200, allowed.statusCode());

    HttpResponse<String> page = get("/?token=s3cret");
    assertEquals(200, page.statusCode());
    String cookie = page.headers().firstValue("Set-Cookie").orElse("");
    assertTrue(cookie.startsWith("ccj_token=s3cret"), "页面加载必须记住 token");
    assertTrue(cookie.contains("HttpOnly"), cookie);
    assertTrue(page.body().contains("<html"), "页面本身仍然必须被提供");

    HttpResponse<String> withCookie =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/status"))
                .header("Cookie", "ccj_token=s3cret")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, withCookie.statusCode(), "浏览器不必再要一次 token");

    HttpResponse<String> withHeader =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/status"))
                .header("Authorization", "Bearer s3cret")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, withHeader.statusCode());
  }

  // ------------------------------------------------------------------ 设置

  @Test
  void anUnconfiguredServerStillServesAndExplainsItself() throws Exception {
    api.close();
    hub.close();
    hub = hub(null, Config.empty().resolved());
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();

    JsonNode status = json("/api/status");
    assertFalse(status.path("configured").asBoolean());
    assertTrue(status.path("provider").asText().isEmpty());
    assertTrue(status.path("cwd").asText().endsWith("ws"), "UI 仍然需要它的上下文");

    HttpResponse<String> refused = post("/api/message", "{\"text\":\"hi\"}");
    assertEquals(409, refused.statusCode());
    assertTrue(refused.body().contains("没有配置模型——打开「设置」添加一个"), refused.body());

    JsonNode config = json("/api/config");
    assertFalse(config.path("configured").asBoolean());
    assertTrue(config.path("providers").size() >= 2, config.toString());
    assertTrue(config.path("configFile").asText().endsWith("config.json"));
    assertEquals("none", config.path("apiKeySource").asText());

    try (Sse sse = watch()) {
      assertFalse(sse.await("status", 3000).path("configured").asBoolean());
    }
  }

  @Test
  void savingSettingsSwitchesTheModelAndPersistsTheFile() throws Exception {
    Files.writeString(
        configFile,
        "{\"systemPrompt\": \"keep me\", \"outputLimitBytes\": 4096, \"model\": \"old\"}");

    JsonNode saved =
        postJson(
            "/api/config",
            "{\"provider\":\"anthropic\",\"model\":\"claude-test\",\"baseUrl\":\"http://relay.invalid\",\"apiKey\":\"sk-written\",\"maxTokens\":900}");

    assertEquals("claude-test", saved.path("model").asText());
    assertEquals("anthropic", saved.path("provider").asText());
    assertTrue(saved.path("configured").asBoolean());
    assertEquals("claude-test", lastBuilt.name(), "新提供方必须是正在使用的那个");

    JsonNode file = Json.parse(Files.readString(configFile));
    assertEquals("anthropic", file.path("provider").asText());
    assertEquals("claude-test", file.path("model").asText());
    assertEquals("sk-written", file.path("apiKey").asText());
    assertEquals(900, file.path("maxTokens").asInt());
    assertEquals("keep me", file.path("systemPrompt").asText(), "未接管的键必须留得住");
    assertEquals(4096, file.path("outputLimitBytes").asInt());
    assertEquals(
        "rw-------",
        java.nio.file.attribute.PosixFilePermissions.toString(
            Files.getPosixFilePermissions(configFile)),
        "这个文件可能存有密钥");

    JsonNode config = json("/api/config");
    assertEquals("config", config.path("apiKeySource").asText());
    assertFalse(config.toString().contains("sk-written"), "密钥绝不能离开服务器");

    // 而且下一个回合真的会走重建后的提供方
    lastBuilt.reply(Message.Assistant.text("answered by the new model"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      assertEquals("answered by the new model", sse.await("done", 5000).path("finalText").asText());
    }
  }

  @Test
  void aHandWrittenConfigCanBeSavedFromTheForm() throws Exception {
    // 有人报的缺陷：一份手敲出来的配置文件——一个端点加一把密钥，没有 `settingsFor` 标记，手写
    // 文件就是这样——根本没法从设置表单里保存。表单总会提交它的密钥变量字段，并预填了提供方的
    // 默认值，而这一点被读成了「这次改动自己指定了凭据」：文件里那对没有标记的值被丢掉，随后
    // 构建提供方就失败于 `no API key for provider 'openai'`。
    Files.writeString(
        configFile,
        "{\"provider\":\"openai\",\"model\":\"hand-written\",\"apiKey\":\"sk-hand-written\"}");
    restartFromConfigFile();

    JsonNode saved = postJson("/api/config", "{\"provider\":\"openai\",\"model\":\"typed-model\","
        + "\"apiKeyEnv\":\"OPENAI_API_KEY\",\"language\":\"auto\"}");

    assertEquals("typed-model", saved.path("model").asText(), saved.toString());
    assertEquals(
        "sk-hand-written",
        Config.fromFile(configFile).apiKey(),
        "原本就在那儿的密钥必须挺过一次没有替换它的保存");
  }

  @Test
  void namingANonDefaultKeyVariableStillCountsAsNamingACredential() throws Exception {
    // 同一条规则的另一半：一次说清了密钥来自哪里的表单保存仍然是一个刻意的动作，它替换掉的那
    // 一对会被丢弃，而不是被继承。
    Files.writeString(
        configFile,
        "{\"provider\":\"openai\",\"model\":\"hand-written\",\"apiKey\":\"sk-hand-written\"}");
    restartFromConfigFile();

    postJson("/api/config", "{\"provider\":\"openai\",\"model\":\"m\","
        + "\"apiKeyEnv\":\"MY_OWN_KEY_VARIABLE\"}");

    assertEquals("MY_OWN_KEY_VARIABLE", Config.fromFile(configFile).apiKeyEnv());
    assertNull(
        Config.fromFile(configFile).apiKey(),
        "一次自己指定了凭据的保存，不会继承原本在那儿的那个");
  }

  @Test
  void aRejectedSettingChangesNothingOnDiskOrInMemory() throws Exception {
    JsonNode before = json("/api/config");

    HttpResponse<String> rejected = post("/api/config", "{\"provider\":\"gemini\"}");

    assertEquals(400, rejected.statusCode(), rejected.body());
    assertTrue(rejected.body().contains("gemini"), rejected.body());
    assertFalse(Files.exists(configFile), "被拒的改动绝不能创建配置文件");
    assertEquals(before.path("provider").asText(), json("/api/config").path("provider").asText());
    assertEquals("openai", json("/api/status").path("provider").asText());
  }

  @Test
  void outOfRangeValuesAreRejected() throws Exception {
    assertEquals(400, post("/api/config", "{\"temperature\":9}").statusCode());
    assertEquals(400, post("/api/config", "{\"temperature\":-1}").statusCode());
    assertEquals(400, post("/api/config", "{\"temperature\":\"warm\"}").statusCode());
    assertEquals(400, post("/api/config", "{\"maxTokens\":\"lots\"}").statusCode());
    assertFalse(Files.exists(configFile));
  }

  @Test
  void clearingAStoredKeyFallsBackToTheEnvironment() throws Exception {
    postJson("/api/config", "{\"apiKey\":\"sk-temp\"}");
    assertEquals("config", json("/api/config").path("apiKeySource").asText());

    JsonNode cleared = postJson("/api/config", "{\"clearApiKey\":true}");

    assertTrue(cleared.path("configured").asBoolean(), cleared.toString());
    assertFalse(Files.readString(configFile).contains("sk-temp"), "密钥必须没了");
    assertEquals("none", json("/api/config").path("apiKeySource").asText());
  }

  @Test
  void testingSettingsDoesNotSaveThem() throws Exception {
    factoryCalls.set(0);

    JsonNode result = postJson("/api/config/test", "{\"provider\":\"anthropic\",\"model\":\"probe\"}");

    assertTrue(result.path("ok").asBoolean(), result.toString());
    assertTrue(result.path("elapsedMs").asInt() >= 0);
    assertEquals(1, factoryCalls.get(), "探测必须构建一个用完就丢的提供方");
    assertFalse(Files.exists(configFile), "测试绝不能持久化任何东西");
    assertEquals("openai", json("/api/config").path("provider").asText(), "内存中的配置未变");
    assertEquals("openai", json("/api/status").path("provider").asText());
  }

  @Test
  void aFailingProbeIsReportedAsARejection() throws Exception {
    HttpResponse<String> failure =
        post("/api/config/test", "{\"provider\":\"gemini\",\"model\":\"x\"}");

    assertEquals(400, failure.statusCode(), failure.body());
    assertTrue(failure.body().contains("gemini"), failure.body());
  }

  // ------------------------------------------------------------------ 历史与用量

  @Test
  void usageTotalsAndCacheHitRateAccumulateAcrossATurn() throws Exception {
    // 替身每个回合都报告同一笔账，所以两个回合就把两边都翻倍。
    provider.usage(100, 5, 40).reply(Message.Assistant.text("one"));
    provider.reply(Message.Assistant.text("two"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"first\"}");
      sse.await("done", 5000);
      post("/api/message", "{\"text\":\"second\"}");
      sse.awaitAtLeast("done", 2, 5000);
    }

    JsonNode usage = json("/api/status").path("usage");
    assertEquals(2, usage.path("turns").asInt());
    assertEquals(2, usage.path("steps").asInt());
    assertEquals(200, usage.path("inputTokens").asInt());
    assertEquals(10, usage.path("outputTokens").asInt());
    assertEquals(80, usage.path("cachedInputTokens").asInt());
    assertEquals(0.4, usage.path("cacheHitRate").asDouble(), 0.001);
    assertTrue(usage.path("elapsedMs").asLong() >= 0);
  }

  @Test
  void aProviderThatReportsNoCacheLeavesTheRateUnknown() throws Exception {
    provider.reply(Message.Assistant.text("hi"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      sse.await("done", 5000);
    }

    JsonNode usage = json("/api/status").path("usage");
    assertTrue(usage.path("cachedInputTokens").isNull(), usage.toString());
    assertTrue(usage.path("cacheHitRate").isNull(), "未知绝不能渲染成 0%");
    assertEquals(1, usage.path("turns").asInt());
  }

  @Test
  void historyReplaysTheConversationAsRenderEvents() throws Exception {
    Files.writeString(cwd.resolve("note.txt"), "on disk\n");
    provider.reply(call("read", "path", "note.txt"));
    provider.reply(Message.Assistant.text("done"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"make the file\"}");
      sse.await("done", 5000);
    }

    JsonNode history = json("/api/history");
    assertEquals(json("/api/status").path("sessionId").asText(), history.path("sessionId").asText());

    List<String> types = new ArrayList<>();
    history.path("events").forEach(event -> types.add(event.path("type").asText()));
    assertEquals(List.of("user", "tool", "tool", "text"), types, history.toString());

    JsonNode start = history.path("events").get(1);
    JsonNode end = history.path("events").get(2);
    assertEquals("start", start.path("state").asText());
    assertEquals("read", start.path("name").asText());
    assertEquals("note.txt", start.path("summary").asText());
    assertEquals(start.path("id").asText(), end.path("id").asText(), "卡片按调用 id 配对");
    assertTrue(end.path("ok").asBoolean());
    assertTrue(end.path("output").asText().contains("on disk"), end.toString());
    assertTrue(end.path("elapsedMs").isNull(), "历史不存计时，所以也不编造计时");
    history
        .path("events")
        .forEach(event -> assertTrue(event.path("replay").asBoolean(), event.toString()));
    assertEquals(1, history.path("usage").path("turns").asInt(), "一个用户回合");
    assertEquals(2, history.path("usage").path("steps").asInt(), "先工具调用，再答案");
  }

  @Test
  void anEmptyConversationReplaysAsNothing() throws Exception {
    JsonNode history = json("/api/history");

    assertEquals(0, history.path("events").size());
    assertEquals(json("/api/status").path("sessionId").asText(), history.path("sessionId").asText());
  }

  @Test
  void newSessionOnAnEmptySessionIsRefusedInsteadOfMintingAnotherId() throws Exception {
    String before = json("/api/status").path("sessionId").asText();

    try (Sse sse = watch()) {
      JsonNode response = postJson("/api/session", "{\"action\":\"new\"}");
      assertEquals(before, response.path("sessionId").asText(), "id 不得改变");
      assertTrue(sse.await("notice", 3000).path("text").asText().contains("这个会话已经是空的——先随便说点什么"));
    }
    assertTrue(SessionStore.list(sessions).isEmpty(), "空会话不得冒出任何会话文件");
  }

  @Test
  void newSessionAfterARealTurnDoesStartAFreshOne() throws Exception {
    provider.reply(Message.Assistant.text("hi"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      sse.await("done", 5000);
    }
    String before = json("/api/status").path("sessionId").asText();

    JsonNode created = postJson("/api/session", "{\"action\":\"new\"}");

    assertNotEquals(before, created.path("sessionId").asText());
    assertEquals(0, json("/api/history").path("events").size(), "新会话是空的");
    assertEquals(0, json("/api/status").path("usage").path("inputTokens").asInt(), "总计已重置");
  }

  @Test
  void onlyAReconnectingClientGetsTheReplayBuffer() throws Exception {
    provider.reply(Message.Assistant.text("live answer"));
    long lastIdOfTheTurn;
    try (Sse first = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      SseEvent done = first.awaitEvent("done", 5000);
      lastIdOfTheTurn = done.id();
    }

    // 全新的页面没有缺口要补：它从 /api/history 渲染对话，所以在这里回放缓冲区会把每个最近的
    // 事件都画第二遍。
    try (Sse fresh = watch()) {
      fresh.await("status", 3000);
      Thread.sleep(300);
      assertEquals(
          List.of("status"),
          fresh.types(),
          "全新的连接拿到的是它的状态，而不是它从未见过的那些回合的实时事件");
    }

    // 重连的页面确实有缺口，而且只有那个缺口。
    try (Sse resumed = watchWithLastEventId(lastIdOfTheTurn - 1)) {
      assertEquals("done", resumed.await("done", 3000).path("type").asText());
    }
  }

  @Test
  void aResumedSessionContinuesItsTotalsInsteadOfStartingAtZero() throws Exception {
    provider.usage(100, 5, 40).reply(Message.Assistant.text("first"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"one\"}");
      sse.await("done", 5000);
    }
    String sessionId = json("/api/status").path("sessionId").asText();

    api.close();
    hub.close();
    hub = hub(new MockProvider("mock"), testConfig());
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();
    postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + sessionId + "\"}");

    JsonNode usage = json("/api/status").path("usage");
    assertEquals(1, usage.path("turns").asInt(), "更早的那个回合属于本会话");
    assertEquals(100, usage.path("inputTokens").asInt());
    assertEquals(0.4, usage.path("cacheHitRate").asDouble(), 0.001);
    assertEquals(2, json("/api/history").path("events").size(), "它的对话也一样");
  }

  @Test
  void aServerStartedOnAnExistingSessionContinuesItsBooks() throws Exception {
    FileSession owned = SessionStore.create(sessions);
    owned.append(new Message.User("an earlier conversation"));
    owned.totals(new UsageTotals(200, 30, 150, 1, 2, 1, 0, 900, true));

    api.close();
    hub.close();
    hub = hub(provider, testConfig(), owned);
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();

    JsonNode usage = json("/api/status").path("usage");
    assertEquals(1, usage.path("turns").asInt(), "重启绝不能把回合数忘掉");
    assertEquals(200, usage.path("inputTokens").asInt());
    assertEquals(150, usage.path("cachedInputTokens").asInt());
    assertEquals(0.75, usage.path("cacheHitRate").asDouble(), 0.001);
    assertEquals(1, json("/api/history").path("events").size());
  }

  // ------------------------------------------------------------------ 工作区

  @Test
  void listsTheStartingDirectoryAsTheActiveWorkspace() throws Exception {
    JsonNode payload = json("/api/workspaces");

    assertEquals("ws", payload.path("active").asText());
    assertEquals(1, payload.path("workspaces").size());
    JsonNode entry = payload.path("workspaces").get(0);
    assertEquals(cwd.toString(), entry.path("path").asText());
    assertTrue(entry.path("active").asBoolean());
    assertEquals(0, entry.path("sessions").asInt());

    JsonNode status = json("/api/status");
    assertEquals("ws", status.path("workspace").path("name").asText());
    assertEquals(cwd.toString(), status.path("cwd").asText());
  }

  @Test
  void addingAndSwitchingAWorkspaceMovesBothTheDirectoryAndTheHistory() throws Exception {
    Path other = tmp.resolve("other-project");
    Files.writeString(Files.createDirectories(other).resolve("note.txt"), "in the other project");

    JsonNode added = postJson("/api/workspaces", "{\"name\":\"other\",\"path\":\"" + other + "\"}");
    assertEquals(2, added.path("workspaces").size(), added.toString());

    JsonNode switched = postJson("/api/workspace", "{\"name\":\"other\"}");
    assertEquals("other", switched.path("workspace").path("name").asText());
    assertEquals(other.toString(), switched.path("cwd").asText(), "工具现在在那个目录里工作");

    // 切换之前写下的会话属于旧工作区，绝不能在这里出现。
    assertEquals(0, json("/api/sessions").path("sessions").size());
    assertEquals(0, json("/api/history").path("events").size());

    // 而且相对的工具路径真的在那里解析：这个文件只存在于新工作区里。
    provider.reply(call("read", "path", "note.txt"));
    provider.reply(Message.Assistant.text("read it"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"read note.txt\"}");
      sse.await("done", 5000);
      JsonNode ended = lastOf(sse, "tool");
      assertTrue(ended.path("ok").asBoolean(), ended.toString());
      assertTrue(ended.path("output").asText().contains("in the other project"), ended.toString());
    }
  }

  @Test
  void eachWorkspaceKeepsItsOwnSessions() throws Exception {
    provider.reply(Message.Assistant.text("from the first workspace"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"remember this\"}");
      sse.await("done", 5000);
    }
    assertEquals(1, json("/api/sessions").path("sessions").size());
    String firstSession = json("/api/status").path("sessionId").asText();

    postJson("/api/workspaces", "{\"name\":\"second\",\"path\":\"" + tmp.resolve("second") + "\"}");
    postJson("/api/workspace", "{\"name\":\"second\"}");
    assertEquals(
        0, json("/api/sessions").path("sessions").size(), "另一个工作区就是另一段历史");

    postJson("/api/workspace", "{\"name\":\"ws\"}");

    JsonNode back = json("/api/sessions").path("sessions");
    assertEquals(1, back.size());
    assertEquals(firstSession, back.get(0).path("id").asText());
  }

  @Test
  void forgettingAWorkspaceIsRefusedForTheActiveOne() throws Exception {
    postJson("/api/workspaces", "{\"name\":\"temp\",\"path\":\"" + tmp.resolve("temp") + "\"}");

    HttpResponse<String> refusal =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/workspace?name=ws"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, refusal.statusCode(), refusal.body());
    assertTrue(refusal.body().contains("无法移除活动工作区 'ws'；请先切换到另一个"), refusal.body());

    HttpResponse<String> removed =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/workspace?name=temp"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, removed.statusCode(), removed.body());
    assertEquals(1, json("/api/workspaces").path("workspaces").size());
  }

  @Test
  void aBadWorkspaceIsRejectedWithAReason() throws Exception {
    assertEquals(400, post("/api/workspaces", "{\"name\":\"a/b\",\"path\":\"/tmp\"}").statusCode());
    assertEquals(400, post("/api/workspaces", "{\"name\":\"ws\",\"path\":\"/tmp\"}").statusCode());
    assertEquals(400, post("/api/workspaces", "{\"name\":\"ok\"}").statusCode());
    assertEquals(400, post("/api/workspace", "{\"name\":\"nope\"}").statusCode());
  }

  @Test
  void aPathWithoutANameIsAddedUnderTheFoldersOwnName() throws Exception {
    Path picked = Files.createDirectories(tmp.resolve("picked-thing"));

    JsonNode added = postJson("/api/workspaces", "{\"path\":\"" + picked + "\"}");

    assertEquals(2, added.path("workspaces").size(), added.toString());
    JsonNode entry = added.path("workspaces").get(1);
    assertEquals("picked-thing", entry.path("name").asText());
    assertEquals(picked.toString(), entry.path("path").asText());
    assertEquals("ws", added.path("active").asText(), "挑一个文件夹不会切换");
  }

  @Test
  void pickingTheSameDirectoryTwiceIsRefusedWithTheWorkspaceThatOwnsIt() throws Exception {
    Path project = Files.createDirectories(tmp.resolve("twice"));

    assertEquals("twice", postJson("/api/workspaces", "{\"path\":\"" + project + "\"}").path("workspaces").get(1).path("name").asText());

    // 同一个目录，不同的写法：正是归一化让这里变成一次拒绝，而不是多出一条带着竞争对手会话
    // 历史的条目。
    HttpResponse<String> refusal =
        post("/api/workspaces", "{\"path\":\"" + project.resolve(".") + "\"}");

    assertEquals(400, refusal.statusCode(), refusal.body());
    assertTrue(refusal.body().contains("twice"), refusal.body());
    assertEquals(2, json("/api/workspaces").path("workspaces").size());
  }

  @Test
  void aPathIsRequiredAndAnUnusableFolderNameIsRefused() throws Exception {
    HttpResponse<String> noPath = post("/api/workspaces", "{\"path\":\"\"}");
    assertEquals(400, noPath.statusCode(), noPath.body());
    assertTrue(noPath.body().contains("工作区需要一个目录"), noPath.body());

    // 名为 "my project" 的文件夹会被添加，而不是被拒——但名字没法当单独一个路径段的（开头的
    // 短横会读成一个 flag）会带着原因退回来。
    Path spaced = Files.createDirectories(tmp.resolve("my project"));
    assertEquals(
        "my project",
        postJson("/api/workspaces", "{\"path\":\"" + spaced + "\"}")
            .path("workspaces").get(1).path("name").asText());

    Path odd = Files.createDirectories(tmp.resolve("-dashed"));
    HttpResponse<String> oddName = post("/api/workspaces", "{\"path\":\"" + odd + "\"}");
    assertEquals(400, oddName.statusCode(), oddName.body());
    assertTrue(oddName.body().contains("文件夹名 '-dashed' 不能作为工作区名：工作区名称必须为 1-40 个字符，不能包含路径分隔符，不能以连字符开头，也不能是 '.' 或 '..'"), oddName.body());
  }

  // ------------------------------------------------------------------ 删除与选择器

  @Test
  void deletingASessionRemovesItAndLeavesTheRest() throws Exception {
    provider.reply(Message.Assistant.text("one"));
    provider.reply(Message.Assistant.text("two"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"first\"}");
      sse.await("done", 5000);
    }
    String older = json("/api/status").path("sessionId").asText();
    JsonNode created = postJson("/api/session", "{\"action\":\"new\"}");
    String newer = created.path("sessionId").asText();
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"second\"}");
      sse.await("done", 5000);
    }

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/session?id=" + older))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, Json.parse(response.body()).path("sessions").size());
    assertEquals(newer, json("/api/status").path("sessionId").asText(), "另一个保持活动状态");
  }

  @Test
  void deletingTheActiveSessionStartsAFreshOne() throws Exception {
    provider.reply(Message.Assistant.text("hi"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      sse.await("done", 5000);
    }
    String active = json("/api/status").path("sessionId").asText();

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/session?id=" + active))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode(), response.body());
    assertFalse(Files.exists(sessions.resolve(active + ".jsonl")), "文件没了");
    assertNotEquals(active, json("/api/status").path("sessionId").asText(), "总得有个地方接着待");
    assertEquals(0, json("/api/sessions").path("sessions").size());
  }

  @Test
  void deletingASessionThatDoesNotExistIsRejected() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/session?id=20200101-000000-abcd"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(400, response.statusCode(), response.body());
    assertTrue(response.body().contains("在 本工作区 里没有会话 '20200101-000000-abcd'"), response.body());
    assertEquals(400, client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/session"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  // ------------------------------------------------------------------ 压缩

  @Test
  void compactingReplacesTheOlderTurnsWithASummaryAndKeepsTheFile() throws Exception {
    // 一个长到值得压缩的对话：8 组往来，所以最新的 5 组会被留下。
    for (int i = 0; i < 8; i++) {
      provider.reply(Message.Assistant.text("answer " + i + " " + "detail ".repeat(50)));
      try (Sse sse = watch()) {
        post("/api/message", "{\"text\":\"question " + i + " " + "context ".repeat(50) + "\"}");
        sse.await("done", 5000);
      }
    }
    String id = json("/api/status").path("sessionId").asText();
    Path original = sessions.resolve(id + ".jsonl");
    String originalBytes = Files.readString(original);
    JsonNode usageBefore = json("/api/status").path("usage");
    int stepsBefore = usageBefore.path("steps").asInt();

    // 向模型索要摘要的东西，是一个关于转录的提问，而不是一个回合。
    provider.reply(Message.Assistant.text("Goal: answer questions. Files: none. Open: nothing."));
    JsonNode result = postJson("/api/compact", "{}");

    assertTrue(result.path("compacted").asBoolean(), result.toString());
    assertEquals(6, result.path("summarised").asInt(), "三组各两条消息的往来被替换掉了");
    assertEquals(10, result.path("kept").asInt());
    assertTrue(result.path("afterTokens").asInt() < result.path("beforeTokens").asInt(), result.toString());
    assertEquals(1, result.path("generation").asInt());

    // 新代文件装着摘要加上留下的尾巴；原文件逐字节完好，这正是压缩可以放心尝试的全部理由。
    Path generation = sessions.resolve(id + ".g1.jsonl");
    assertTrue(Files.isRegularFile(generation), "新的一代已经在磁盘上");
    assertEquals(originalBytes, Files.readString(original), "它替换掉的那一代未被触碰");
    List<Message> compacted = FileSession.readAll(generation);
    assertTrue(compacted.get(0) instanceof Message.Summary, compacted.get(0).toString());

    // 这次摘要请求不是一个回合：没有追加用户消息，也没有计入步骤。
    var requests = provider.requests();
    var summariseRequest = requests.get(requests.size() - 1);
    assertNull(summariseRequest.system(), "摘要请求不带系统提示词");
    assertTrue(summariseRequest.tools().isEmpty(), "也没有工具可以去干活");
    assertEquals(1, requests.get(requests.size() - 1).messages().size(), "只有一条消息：转录本身");
    assertTrue(
        ((Message.User) summariseRequest.messages().get(0)).text().contains(Compaction.INSTRUCTIONS),
        "正是那段指令把一份转录变成摘要");
    assertEquals(stepsBefore, json("/api/status").path("usage").path("steps").asInt(), "不算一步");

    // 但它会被计入，而且是单独计：它耗了 token，把它折进步骤里会让那个数字同时意味着两件事。
    assertEquals(1, json("/api/status").path("usage").path("compactions").asInt());

    // 而且会话继续下去：下一个回合会被作答，基于压缩后的对话。
    provider.reply(Message.Assistant.text("continuing"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"what next?\"}");
      sse.await("done", 5000);
    }
    var nextTurn = provider.requests().get(provider.requests().size() - 1);
    assertTrue(
        nextTurn.messages().stream().anyMatch(m -> m instanceof Message.Summary),
        "下一个请求带着的就是摘要，而不是那些旧的回合：" + nextTurn.messages());
  }

  @Test
  void aCompactionIsRefusedWhileATurnIsRunning() throws Exception {
    // 一份转录上两个写入者，正是「每对话一个回合」标志存在的意义所在要防的例外，而压缩会
    // 重写这个对话是什么。
    CountDownLatch gate = new CountDownLatch(1);
    provider.reply(Message.Assistant.text("slow"));
    provider.gate(gate);
    post("/api/message", "{\"text\":\"start a turn\"}");

    HttpResponse<String> refusal = post("/api/compact", "{}");
    assertEquals(409, refusal.statusCode(), refusal.body());
    assertTrue(refusal.body().contains("还有回合在跑；请先中止它"), refusal.body());

    provider.release();
    gate.countDown();
  }

  @Test
  void aConversationTooShortToCompactIsRefusedWithoutCallingTheModel() throws Exception {
    provider.reply(Message.Assistant.text("only one exchange"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      sse.await("done", 5000);
    }
    int callsBefore = provider.requests().size();

    HttpResponse<String> refusal = post("/api/compact", "{}");

    assertEquals(400, refusal.statusCode(), refusal.body());
    assertTrue(refusal.body().contains("这个对话还没有可压缩的东西——它比压缩会保留的 " + Compaction.KEEP_EXCHANGES + " 组往复还短"), refusal.body());
    assertEquals(callsBefore, provider.requests().size(), "不会让模型去总结「什么都没有」");
    assertEquals(405, get("/api/compact").statusCode());
  }

  @Test
  void anEmptySummaryChangesNothing() throws Exception {
    for (int i = 0; i < 8; i++) {
      provider.reply(Message.Assistant.text("answer " + i));
      try (Sse sse = watch()) {
        post("/api/message", "{\"text\":\"question " + i + "\"}");
        sse.await("done", 5000);
      }
    }
    String id = json("/api/status").path("sessionId").asText();

    provider.reply(Message.Assistant.text("   "));
    HttpResponse<String> refusal = post("/api/compact", "{}");

    assertEquals(409, refusal.statusCode(), refusal.body());
    assertTrue(refusal.body().contains("模型返回了空摘要；什么都没有改动"), refusal.body());
    // 什么都没写，所以会话仍然是它的第 0 代，也仍然完整。
    assertEquals(16, json("/api/status").path("messageCount").asInt());
    assertFalse(Files.exists(sessions.resolve(id + ".g1.jsonl")));
    assertEquals(0, json("/api/status").path("usage").path("compactions").asInt());
  }

  @Test
  void deletingEverythingClearsTheWorkspaceAndStartsOver() throws Exception {
    provider.reply(Message.Assistant.text("one"));
    provider.reply(Message.Assistant.text("two"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"first\"}");
      sse.await("done", 5000);
      postJson("/api/session", "{\"action\":\"new\"}");
      post("/api/message", "{\"text\":\"second\"}");
      sse.awaitAtLeast("done", 2, 5000);
    }
    assertEquals(2, json("/api/sessions").path("sessions").size());

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/sessions")).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(0, Json.parse(response.body()).path("sessions").size());
    assertEquals(0, json("/api/sessions").path("sessions").size());
  }

  @Test
  void theFolderChooserIsOpenedOnDemandAndItsAnswersArePassedThrough() throws Exception {
    Path project = Files.createDirectories(tmp.resolve("picked-project"));

    chooserBehaviour = title -> Optional.of(project);

    JsonNode chosen = postJson("/api/workspaces/browse", "{}");
    assertEquals(project.toString(), chosen.path("path").asText());

    chooserBehaviour = title -> Optional.empty();
    assertTrue(postJson("/api/workspaces/browse", "{}").path("cancelled").asBoolean());

    chooserBehaviour =
        title -> {
          throw new IOException("no desktop session available, so ccj cannot open a folder chooser");
        };
    HttpResponse<String> failure = post("/api/workspaces/browse", "{}");
    assertEquals(400, failure.statusCode(), failure.body());
    assertTrue(failure.body().contains("desktop"), failure.body());

    assertEquals(405, get("/api/workspaces/browse").statusCode());
  }

  @Test
  void theCatalogueListsBuiltInsAndUserDefinitions() throws Exception {
    providerStore.save(
        new com.ccj.agent.core.ProviderDefinition(
            "myrelay",
            com.ccj.agent.core.ProviderDefinition.OPENAI,
            "https://relay.invalid/v1",
            null,
            List.of("fast-model", "smart-model")));

    JsonNode catalog = json("/api/models");

    JsonNode relay =
        lastWhere(catalog.path("providers"), entry -> entry.path("name").asText().equals("myrelay"));
    assertFalse(relay.path("builtIn").asBoolean());
    assertEquals("https://relay.invalid/v1", relay.path("baseUrl").asText());
    assertEquals(2, relay.path("models").size());

    JsonNode openai =
        lastWhere(catalog.path("providers"), entry -> entry.path("name").asText().equals("openai"));
    assertTrue(openai.path("builtIn").asBoolean());
    assertEquals(List.of("gpt-4o-mini"), modelsOf(openai));

    JsonNode fast =
        lastWhere(catalog.path("models"), entry -> entry.path("model").asText().equals("fast-model"));
    assertEquals("myrelay", fast.path("provider").asText());
    assertEquals("config", fast.path("source").asText(), "这个条目从哪儿来的");
  }

  @Test
  void aCustomProviderIsOfferedByTheSettingsForm() throws Exception {
    providerStore.save(
        new com.ccj.agent.core.ProviderDefinition(
            "myrelay", com.ccj.agent.core.ProviderDefinition.OPENAI, "https://relay.invalid/v1", null,
            List.of("fast-model")));

    List<String> providers = new ArrayList<>();
    json("/api/config").path("providers").forEach(name -> providers.add(name.asText()));

    assertTrue(providers.contains("openai"), providers.toString());
    assertTrue(providers.contains("myrelay"), "自定义的提供方必须可选");

    // 而且它立刻就能用：把它保存为当前提供方绝不能被拒
    JsonNode saved =
        postJson(
            "/api/config",
            "{\"provider\":\"myrelay\",\"model\":\"fast-model\",\"apiKey\":\"sk-custom\"}");
    assertEquals("myrelay", saved.path("provider").asText());
    assertTrue(saved.path("configured").asBoolean());
  }

  @Test
  void aProviderCanBeDefinedFromTheUiAndIsThenUsable() throws Exception {
    JsonNode added =
        postJson(
            "/api/providers",
            "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"http://127.0.0.1:1/v1\","
                + "\"apiKeyEnv\":\"MY_KEY\",\"models\":\"fast,smart\"}");

    JsonNode relay =
        lastWhere(added.path("providers"), entry -> entry.path("name").asText().equals("myrelay"));
    assertFalse(relay.path("builtIn").asBoolean());
    assertEquals(List.of("fast", "smart"), modelsOf(relay), "逗号分隔的列表是能被读懂的");

    // 它可选，而且正在运行的会话可以立刻切到它上面
    JsonNode saved =
        postJson(
            "/api/config",
            "{\"provider\":\"myrelay\",\"model\":\"fast\",\"baseUrl\":\"http://127.0.0.1:1/v1\"}");
    assertEquals("myrelay", saved.path("provider").asText());
    assertEquals("fast", saved.path("model").asText());
  }

  @Test
  void aBadProviderDefinitionIsRejectedWithAReason() throws Exception {
    assertEquals(
        400,
        post("/api/providers", "{\"name\":\"bad name\",\"kind\":\"openai\",\"baseUrl\":\"http://x\"}")
            .statusCode());
    assertEquals(
        400,
        post("/api/providers", "{\"name\":\"relay\",\"kind\":\"grpc\",\"baseUrl\":\"http://x\"}")
            .statusCode());
    assertEquals(
        400, post("/api/providers", "{\"name\":\"relay\",\"kind\":\"openai\"}").statusCode());
    assertTrue(providerStore.list().isEmpty(), "被拒的定义绝不能存下来");
  }

  @Test
  void switchingProviderKeepsTheEndpointAndKeyUnderTheProviderItIsLeaving() throws Exception {
    // 输入框上的选择器会提交的东西：提供方和模型，别的什么都没有。存着的 baseUrl 和密钥属于
    // 正要离开的那个提供方，拿它们去给新提供方用，就是一个自称 "myrelay" 的会话最后拿着上一个
    // 提供方的密钥、把流量发到上一个提供方的地址上——记在上一个提供方的账上。它们会被留在自己
    // 所属的名字底下，而不是留在当前那一对里，这才让切回去是免费的。
    postJson(
        "/api/config",
        "{\"provider\":\"openai\",\"model\":\"m\",\"baseUrl\":\"https://previous.example.com/v1\",\"apiKey\":\"sk-previous\"}");
    assertEquals("config", json("/api/config").path("apiKeySource").asText());

    postJson(
        "/api/providers",
        "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"https://relay.example.com/v1\"}");
    JsonNode switched = postJson("/api/config", "{\"provider\":\"myrelay\",\"model\":\"m\"}");

    assertEquals("myrelay", switched.path("provider").asText());
    JsonNode stored = Json.parse(Files.readString(configFile));
    assertNull(
        stored.path("apiKey").isTextual() ? stored.path("apiKey").asText() : null,
        "旧的那一对里任何东西都不能留在当前字段里：" + stored);
    assertFalse(
        stored.path("baseUrl").asText("").contains("previous.example.com"),
        "正要离开的那个端点也不能留在当前那一对里：" + stored);
    assertEquals(
        "https://relay.example.com/v1",
        switched.path("baseUrl").asText(),
        "报告出来的端点必须是这次请求将要使用的那个，也就是定义里的那个");
    assertEquals(
        "none",
        json("/api/config").path("apiKeySource").asText(),
        "表单绝不能报告一个不会被发出去的密钥");
    assertEquals(
        "sk-previous",
        stored.path("remembered").path("openai").path("apiKey").asText(),
        "它被留在当初录入它的那个提供方名下：" + stored);
  }

  @Test
  void aKeyIsRememberedPerProviderSoSwitchingBackRestoresIt() throws Exception {
    postJson(
        "/api/providers",
        "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"https://relay.example.com/v1\"}");
    postJson("/api/config", "{\"provider\":\"openai\",\"model\":\"gpt-x\",\"apiKey\":\"sk-openai\"}");
    postJson("/api/config", "{\"provider\":\"myrelay\",\"model\":\"m\"}");
    postJson("/api/config", "{\"apiKey\":\"sk-relay\"}");

    JsonNode cfg = json("/api/config");
    assertEquals(
        List.of("openai", "myrelay"),
        strings(cfg.path("rememberedProviders")),
        "每一个存有密钥的提供方——openai 的是被记着的，myrelay 的此刻正在生效。"
            + " 只给名字，绝不给密钥");
    assertEquals("config", cfg.path("apiKeySource").asText());

    // 回到 openai：它自己的端点和密钥回来了，什么都不用重新粘贴。
    JsonNode back = postJson("/api/config", "{\"provider\":\"openai\",\"model\":\"gpt-x\"}");
    assertEquals("openai", back.path("provider").asText());
    JsonNode stored = Json.parse(Files.readString(configFile));
    assertEquals("sk-openai", stored.path("apiKey").asText(), "回到它自己的密钥上：" + stored);
    assertFalse(
        stored.path("baseUrl").asText("").contains("relay.example.com"),
        "而不是停在它被切走的那个端点上：" + stored);
    assertEquals(
        "sk-relay",
        stored.path("remembered").path("myrelay").path("apiKey").asText(),
        "而 myrelay 的密钥等着回去的路：" + stored);
  }

  @Test
  void clearingAKeyForgetsItForThatProviderOnly() throws Exception {
    postJson(
        "/api/providers",
        "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"https://relay.example.com/v1\"}");
    postJson("/api/config", "{\"provider\":\"openai\",\"model\":\"gpt-x\",\"apiKey\":\"sk-openai\"}");
    postJson("/api/config", "{\"provider\":\"myrelay\",\"model\":\"m\",\"apiKey\":\"sk-relay\"}");

    postJson("/api/config", "{\"clearApiKey\":true}");

    assertEquals("none", json("/api/config").path("apiKeySource").asText());
    JsonNode stored = Json.parse(Files.readString(configFile));
    assertFalse(
        stored.path("remembered").has("myrelay"), "被忘掉的密钥绝不能回来：" + stored);
    assertEquals(
        "sk-openai",
        stored.path("remembered").path("openai").path("apiKey").asText(),
        "另一个提供方的密钥不关这个提供方的事：" + stored);
  }

  @Test
  void aKeySavedForTheActiveProviderSaysSoAndSurvivesAnUnrelatedSave() throws Exception {
    postJson(
        "/api/providers",
        "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"https://relay.example.com/v1\"}");
    postJson("/api/config", "{\"provider\":\"myrelay\",\"model\":\"m\"}");
    postJson("/api/config", "{\"apiKey\":\"sk-mine\"}");

    assertEquals("config", json("/api/config").path("apiKeySource").asText());
    assertTrue(json("/api/config").path("usesStoredSettings").asBoolean());
    assertEquals(
        "myrelay",
        Json.parse(Files.readString(configFile)).path("settingsFor").asText(),
        "文件记下了这些端点和密钥是谁的");

    // 之后一次对密钥只字未提的保存会把它留下：它是这个提供方的。
    postJson("/api/config", "{\"reasoning\":\"high\"}");
    assertEquals("config", json("/api/config").path("apiKeySource").asText());
    assertTrue(Files.readString(configFile).contains("sk-mine"));
  }

  @Test
  void removingTheProviderInUseWorksAndSaysWhatItMeans() throws Exception {
    postJson(
        "/api/providers",
        "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"http://127.0.0.1:1/v1\",\"models\":\"m\"}");
    postJson("/api/config", "{\"provider\":\"myrelay\",\"model\":\"m\",\"baseUrl\":\"http://127.0.0.1:1/v1\"}");

    HttpResponse<String> removed =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=myrelay"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, removed.statusCode(), removed.body());
    assertTrue(providerStore.list().isEmpty(), "定义没了");
    assertTrue(
        json("/api/status").path("configured").asBoolean(),
        "正在运行的会话留着它的提供方：它是在被选中时构建的");
  }

  @Test
  void sessionsOfAnyWorkspaceCanBeListedWithoutSwitching() throws Exception {
    provider.reply(Message.Assistant.text("from ws"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"in the first\"}");
      sse.await("done", 5000);
    }
    String firstId = json("/api/status").path("sessionId").asText();
    postJson("/api/workspaces", "{\"name\":\"other\",\"path\":\"" + tmp.resolve("other") + "\"}");
    postJson("/api/workspace", "{\"name\":\"other\"}");

    // 折叠一个文件夹绝不能挪动当前工作区，只是读它的内容。
    JsonNode others = json("/api/sessions?workspace=ws");
    assertEquals("ws", others.path("workspace").asText());
    assertEquals(1, others.path("sessions").size());
    assertEquals(firstId, others.path("sessions").get(0).path("id").asText());
    assertEquals(
        "other", json("/api/status").path("workspace").path("name").asText(), "仍然是当前活动：other");

    assertEquals(0, json("/api/sessions?workspace=other").path("sessions").size());
    assertEquals(0, json("/api/sessions").path("sessions").size(), "不带参数就意味着当前活动的那个");
    assertEquals(400, get("/api/sessions?workspace=nope").statusCode());
  }

  @Test
  void theReasoningTierCanBeChosenChangedAndCleared() throws Exception {
    JsonNode saved = postJson("/api/config", "{\"reasoning\":\"high\"}");

    assertEquals("high", saved.path("reasoning").asText());
    assertEquals(List.of("low", "high", "max"), levels(saved), "选择器提供这些档位");
    assertEquals("high", json("/api/config").path("reasoning").asText());

    assertEquals("max", postJson("/api/config", "{\"reasoning\":\"max\"}").path("reasoning").asText());

    JsonNode cleared = postJson("/api/config", "{\"reasoning\":\"default\"}");
    assertTrue(cleared.path("reasoning").isNull(), "default 意味着由提供方决定");
    assertTrue(json("/api/config").path("reasoning").isNull());

    HttpResponse<String> bad = post("/api/config", "{\"reasoning\":\"turbo\"}");
    assertEquals(400, bad.statusCode(), bad.body());
    assertTrue(bad.body().contains("low, high, max"), bad.body());
  }

  @Test
  void theThinkingLanguageIsOfferedSavedAndPutInThePrompt() throws Exception {
    JsonNode fresh = json("/api/config");
    assertEquals("auto", fresh.path("language").asText(), "不做选择就意味着提示里什么都不提");
    List<String> offered = languages(fresh);
    assertTrue(offered.contains("Simplified Chinese"), offered.toString());
    assertEquals(offered.get(0), "Simplified Chinese", "这个列表就是表单显示它们的顺序");

    // POST 用状态载荷作答，所以表单自己的值要从 GET 读回来——这也正是这项设置必须挺过来的那次
    // 往返。
    postJson("/api/config", "{\"language\":\"Simplified Chinese\"}");
    assertEquals("Simplified Chinese", json("/api/config").path("language").asText());

    // 这项设置的意义：交给模型的提示会按名字要求它，连思考流也一样。检查的是循环真正发出的那个
    // 请求。
    provider.reply(Message.Assistant.text("好的"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"say hi\"}");
      sse.await("done", 5000);
    }
    // 提示里点名这门语言用的是它自己的名字——简体中文，而不是 "Simplified Chinese"——这样那句话
    // 读起来是关于一门语言的指令，而不是表单上的一个标签。
    var sent = lastBuilt.requests().get(lastBuilt.requests().size() - 1);
    assertTrue(sent.system().contains("think in 简体中文"), sent.system());
    assertTrue(
        sent.system().contains("always reason and reply in 简体中文"),
        "作答与思考是在同一句话里被要求的：" + sent.system());

    // 清回 auto 会把那句话移掉，而不是留下一句过时的。
    postJson("/api/config", "{\"language\":\"auto\"}");
    assertEquals("auto", json("/api/config").path("language").asText());
    assertFalse(
        com.ccj.agent.core.Prompts.DEFAULT_SYSTEM.contains("always reason and reply"),
        "auto 不给提示加任何东西");
  }

  @Test
  void aLanguageTheBuildDoesNotListIsStillKept() throws Exception {
    // 这个列表是方便，不是闸门：模型能听从一个这个构建从未听说过的名字，而拒绝它就等于让表单来
    // 决定一个人被允许用什么语言思考。
    postJson("/api/config", "{\"language\":\"Klingon\"}");

    assertEquals("Klingon", json("/api/config").path("language").asText());
  }

  @Test
  void aSessionOfAnotherWorkspaceCanBeDeletedWithoutSwitching() throws Exception {
    provider.reply(Message.Assistant.text("from ws"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"keep me\"}");
      sse.await("done", 5000);
    }
    String wsSession = json("/api/status").path("sessionId").asText();
    postJson("/api/workspaces", "{\"name\":\"other\",\"path\":\"" + tmp.resolve("other") + "\"}");
    postJson("/api/workspace", "{\"name\":\"other\"}");
    String activeNow = json("/api/status").path("sessionId").asText();

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/session?workspace=ws&id=" + wsSession))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(0, Json.parse(response.body()).path("sessions").size());
    assertEquals(activeNow, json("/api/status").path("sessionId").asText(), "仍然在 'other' 里");
    assertFalse(Files.exists(sessions.resolve(wsSession + ".jsonl")));

    // 而且全部删除那条路由接受同一个参数
    assertEquals(
        200,
        client.send(
                HttpRequest.newBuilder(URI.create(origin + "/api/sessions?workspace=ws"))
                    .DELETE()
                    .build(),
            HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(400, get("/api/sessions?workspace=nope").statusCode());
  }

  @Test
  void theChosenEffortTierIsCarriedIntoTheNextTurn() throws Exception {
    postJson("/api/config", "{\"reasoning\":\"high\"}");
    provider.reply(Message.Assistant.text("answered"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"think hard\"}");
      sse.await("done", 5000);
    }

    // 这个档位必须走通整条路径：设置 -> 存下来的配置 -> 回合的选项 -> 提供方真正收到的那个
    // 请求。只断言配置抓不到路上被丢掉的参数。
    var requests = lastBuilt.requests();
    assertEquals("high", requests.get(requests.size() - 1).reasoning(), requests.toString());
    assertEquals(1, requests.size(), "一个回合，一个请求");
  }

  @Test
  void aModelAddedToABuiltInProviderIsRemembered() throws Exception {
    // 原话就是这么抱怨的：在一个自己没有定义的提供方底下敲一个模型名。
    JsonNode after = postJson("/api/models", "{\"provider\":\"openai\",\"model\":\"gpt-5-preview\"}");

    JsonNode openai = providerOf(after, "openai");
    assertEquals(List.of("gpt-4o-mini", "gpt-5-preview"), modelsOf(openai), "是添加，不是替换");
    assertTrue(after.path("models").toString().contains("gpt-5-preview"), "被提供为一个可选项");
    assertTrue(
        Files.readString(tmp.resolve("providers.json")).contains("gpt-5-preview"),
        "而且被记了下来，所以它能挺过一次重启");

    // 删掉它是算数的，因为记下来的列表才是权威
    HttpResponse<String> removed =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(origin + "/api/models?provider=openai&model=gpt-5-preview"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, removed.statusCode(), removed.body());
    assertEquals(List.of("gpt-4o-mini"), modelsOf(providerOf(Json.parse(removed.body()), "openai")));
    assertEquals(
        List.of("gpt-4o-mini"),
        modelsOf(providerOf(json("/api/models"), "openai")),
        "下次读取时仍然是没有的");

    // 重建一次存储也不能把它带回来
    assertEquals(List.of("gpt-4o-mini"), modelsOf(providerOf(json("/api/models"), "openai")));
  }

  @Test
  void modelEditsAreValidated() throws Exception {
    assertEquals(400, post("/api/models", "{\"provider\":\"nope\",\"model\":\"m\"}").statusCode());
    assertEquals(400, post("/api/models", "{\"provider\":\"openai\"}").statusCode());
    assertEquals(400, post("/api/models", "{\"model\":\"m\"}").statusCode());
    assertEquals(200, get("/api/models?provider=openai&model=x").statusCode(), "GET 会列出目录");
    assertTrue(providerStore.modelsFor("openai").isEmpty(), "这些拒绝什么都没记下");
  }

  @Test
  void theModelInUseCanStillBeRemovedFromTheOfferList() throws Exception {
    // 这里替掉的那个死锁：唯一被提供的模型也正是正在用的那个，于是它删不掉，也没有别的可切。
    postJson("/api/config", "{\"provider\":\"openai\",\"model\":\"gpt-4o-mini\"}");

    HttpResponse<String> removed =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(origin + "/api/models?provider=openai&model=gpt-4o-mini"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, removed.statusCode(), removed.body());
    assertEquals(
        List.of(), modelsOf(providerOf(Json.parse(removed.body()), "openai")), "不再被建议");
    assertEquals(
        "gpt-4o-mini",
        json("/api/status").path("model").asText(),
        "把它从列表里删掉绝不能改变会话在用的东西");

    // 而且下次读取时它真的没了
    assertEquals(List.of(), modelsOf(providerOf(json("/api/models"), "openai")));
  }

  @Test
  void aBuiltInProviderCanBeDeletedAndAddedBack() throws Exception {
    assertTrue(providerNames(json("/api/models")).contains("groq"), "一开始就在那儿");

    HttpResponse<String> deleted =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=groq")).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, deleted.statusCode(), deleted.body());
    JsonNode afterDelete = Json.parse(deleted.body());
    assertFalse(providerNames(afterDelete).contains("groq"), "从列表里没了");
    assertEquals(
        List.of(), afterDelete.path("hidden").findValuesAsText("hidden"),
        "没有任何东西被记成隐藏——「删掉」就是这个意思");
    assertTrue(availableBuiltIns(afterDelete).contains("groq"), "但它还能再加回来");
    assertFalse(providerNames(json("/api/models")).contains("groq"), "下次读取时仍然是没有的");
    assertFalse(providerStore.shown().contains("groq"), "显式列表里已经没它了");
    assertEquals(6, providerStore.shown().size(), "其余的都还在：" + providerStore.shown());

    // 把一个内置提供方加回来就是一次普通的添加，不是一次恢复。
    HttpResponse<String> added =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/providers"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"groq\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, added.statusCode(), added.body());
    assertTrue(providerNames(Json.parse(added.body())).contains("groq"));
    assertFalse(availableBuiltIns(Json.parse(added.body())).contains("groq"), "只提供一次");

    // 删两次不是错误，添加已经在的东西也不是。
    assertEquals(
        400,
        client.send(
                HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=nope"))
                    .DELETE()
                    .build(),
            HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        400,
        client.send(
                HttpRequest.newBuilder(URI.create(origin + "/api/providers"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"groq\"}"))
                    .build(),
            HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  @Test
  void aUserDefinedProviderIsDeletedOutright() throws Exception {
    postJson(
        "/api/providers",
        "{\"name\":\"mine\",\"kind\":\"openai\",\"baseUrl\":\"http://127.0.0.1:9/v1\",\"models\":\"m\"}");
    assertTrue(providerNames(json("/api/models")).contains("mine"));

    HttpResponse<String> removed =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=mine")).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, removed.statusCode(), removed.body());
    assertFalse(providerNames(Json.parse(removed.body())).contains("mine"));
    assertTrue(providerStore.list().isEmpty(), "定义没了");
    assertEquals(
        List.of(),
        providerStore.shown(),
        "删掉一个定义不会动内置列表：本来就没有一个被缩窄过的列表");
  }

  @Test
  void deletingTheProviderInUseKeepsTheSessionRunning() throws Exception {
    client.send(
        HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=openai")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());

    // 删除是列表层面的决定，不是能力的移除：已配置的提供方继续工作。
    assertEquals("openai", json("/api/status").path("provider").asText());
    assertTrue(json("/api/status").path("configured").asBoolean());
  }

  @Test
  void removingUnknownProvidersAndAddingNonBuiltInsAreRejected() throws Exception {
    HttpResponse<String> unknown =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=nope")).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, unknown.statusCode(), unknown.body());

    HttpResponse<String> notBuiltIn =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/providers"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"whatever\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, notBuiltIn.statusCode(), notBuiltIn.body());
    assertTrue(notBuiltIn.body().contains("'whatever' 不是内置提供方；请改为自定义一个"), notBuiltIn.body());
  }

  @Test
  void aNewDefinitionJoinsAnExplicitListInsteadOfBeingInvisible() throws Exception {
    // 有人报的 bug：用户先把列表缩窄了，然后定义了一个提供方，而它从未出现——定义被保存了，但
    // 列表没有提到它。
    client.send(
        HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=groq")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
    assertFalse(providerNames(json("/api/models")).contains("groq"), "一开始就是缩窄过的");

    JsonNode added =
        postJson(
            "/api/providers",
            "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"http://127.0.0.1:9/v1\",\"models\":\"m1\"}");

    assertTrue(providerNames(added).contains("myrelay"), "已保存并且列出：" + added);
    assertTrue(providerStore.shown().contains("myrelay"), "而且在显式列表里");
    assertTrue(providerNames(json("/api/models")).contains("myrelay"), "下次读取时仍然在");
  }

  @Test
  void deletingADefinitionAlsoLeavesTheExplicitList() throws Exception {
    // 先把列表缩窄，好让「这个列表」是一件真东西，而不是隐含的「所有一切」。
    client.send(
        HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=groq")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
    postJson(
        "/api/providers",
        "{\"name\":\"mine\",\"kind\":\"openai\",\"baseUrl\":\"http://127.0.0.1:9/v1\",\"models\":\"m\"}");
    assertTrue(providerStore.shown().contains("mine"), "一个定义会加入列表：" + providerStore.shown());

    client.send(
        HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=mine")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());

    assertFalse(providerStore.shown().contains("mine"), "被删掉的名字绝不能留着");
    assertFalse(
        providerNames(json("/api/models")).contains("mine"),
        "也不能像一个幽灵内置提供方那样回来");
  }

  @Test
  void removingTheLastProviderLeavesTheListEmptyInsteadOfRestoringThemAll() throws Exception {
    // 那个 bug：存储分不清「从未缩窄」和「我把一切都删了」，于是删掉最后一个提供方被读成「没有
    // 意见」，整个目录又全都冒了出来。
    for (String name : providerNames(json("/api/models"))) {
      assertEquals(
          200,
          client.send(
                  HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=" + name))
                      .DELETE()
                      .build(),
              HttpResponse.BodyHandlers.ofString())
              .statusCode(),
          "删除 " + name);
    }

    JsonNode emptied = json("/api/models");
    assertTrue(providerNames(emptied).isEmpty(), "列表保持为空：" + emptied);
    assertFalse(emptied.path("builtIns").isEmpty(), "而且每个内置提供方仍然作为回去的路被提供");

    // 没有任何东西被记成隐藏：加回来就是一次普通的添加。
    JsonNode restored =
        Json.parse(
            client.send(
                    HttpRequest.newBuilder(URI.create(origin + "/api/providers"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"anthropic\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString())
                .body());
    assertEquals(List.of("anthropic"), providerNames(restored), restored.toString());
  }

  @Test
  void aTokenlessServerRefusesRequestsAddressedToAnotherHost() throws Exception {
    // 没有 token 的服务器，用户访问的任何页面都够得着；正是 Host 头拦住了「一个解析到
    // 127.0.0.1 的名字（DNS 重绑定）」被当成这台服务器。
    assertTrue(
        rawResponse("rebind.example", "/api/status").startsWith("HTTP/1.1 403"),
        "不是 loopback 的名字就不是这台服务器");
    assertTrue(
        rawResponse("127.0.0.1:" + api.port(), "/api/status").startsWith("HTTP/1.1 200"),
        "而 loopback 字面量是");
  }

  /**
   * 一个裸请求，因为 {@code HttpClient} 不让测试自己选 {@code Host} 头——而那个头在这里有三处
   * 正是被测的东西。
   */
  private String rawResponse(String hostHeader, String path) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", api.port())) {
      socket
          .getOutputStream()
          .write(
              ("GET " + path + " HTTP/1.1\r\nHost: " + hostHeader + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.UTF_8));
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void bothAddressesServeTheSamePageAndOnlyTheNetworkOneAsksForTheToken() throws Exception {
    // 「直接跑 ccj」是什么意思：本机在 127.0.0.1 上打开页面，什么都不用附带；而同一个服务器
    // 会向手机要 token——同一个进程、同一个 hub、同一个对话。这个测试要有第二个地址才成立，所以
    // 只有 loopback 地址的机器会跳过它，而不是假装通过。
    Optional<InetAddress> elsewhere = anAddressOtherThanLoopback();
    assumeTrue(elsewhere.isPresent(), "这台机器除了 loopback 没有别的地址");

    api.close();
    api =
        HttpApi.start(
            hub,
            List.of(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                new InetSocketAddress(elsewhere.get(), 0)),
            "s3cret",
            new Wallpapers(null));
    List<String> urls = api.urls();

    assertEquals(2, urls.size(), urls.toString());
    assertTrue(urls.get(0).startsWith("http://127.0.0.1:"), urls.toString());
    assertFalse(urls.get(0).contains("token"), "本机自己不会被索取秘密");
    assertTrue(urls.get(1).contains("?token=s3cret"), urls.toString());

    assertEquals(200, plainGet("http://127.0.0.1:" + api.port() + "/api/status"), "loopback");
    assertEquals(
        401,
        plainGet("http://" + elsewhere.get().getHostAddress() + ":" + api.port() + "/api/status"),
        "网络地址不带 token 时");
    assertEquals(
        200,
        plainGet(
            "http://"
                + elsewhere.get().getHostAddress()
                + ":"
                + api.port()
                + "/api/status?token=s3cret"),
        "带上 token 时");
  }

  private static int plainGet(String url) throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    return response.statusCode();
  }

  /**
   * 本机拥有的一个非 loopback 地址，没有就是空。
   *
   * <p>故意不写死一个：这个测试的意义在于一个真实的第二地址会从服务器拿到不同的答案，而那是
   * 哪个地址取决于机器。
   */
  private static Optional<InetAddress> anAddressOtherThanLoopback() throws Exception {
    java.util.Enumeration<java.net.NetworkInterface> interfaces =
        java.net.NetworkInterface.getNetworkInterfaces();
    while (interfaces != null && interfaces.hasMoreElements()) {
      java.net.NetworkInterface candidate = interfaces.nextElement();
      if (!candidate.isUp() || candidate.isLoopback()) {
        continue;
      }
      java.util.Enumeration<InetAddress> addresses = candidate.getInetAddresses();
      while (addresses.hasMoreElements()) {
        InetAddress address = addresses.nextElement();
        if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
          return Optional.of(address);
        }
      }
    }
    return Optional.empty();
  }

  @Test
  void aRequestBodyTooLargeToBeASettingsFormIsRefused() throws Exception {
    HttpResponse<String> response =
        post("/api/config", "{\"system\":\"" + "x".repeat(1024 * 1024 + 64) + "\"}");

    assertEquals(413, response.statusCode(), response.body());
    assertTrue(response.body().contains("请求体大于 1048576 字节"), response.body());
  }

  @Test
  void usageReportsAContextEstimate() throws Exception {
    JsonNode usage = json("/api/status").path("usage");
    assertTrue(usage.has("contextTokens"), usage.toString());
    assertTrue(usage.path("contextLimit").asInt() >= 0, usage.toString());
    // 会话花掉的是 token 数，不是价钱：ccj 不携带费率表，所以一个金额字段会是一个用户无从核对
    // 的数字。
    assertFalse(usage.has("costUsd"), usage.toString());
    assertFalse(usage.has("priceAsOf"), usage.toString());
  }

  @Test
  void undoTakesBackWhatTheLastTurnChanged() throws Exception {
    // 人们在让代理靠近自己的文件之前会问的问题是「这能撤回吗」，而它的答案是一次回合的写入可以
    // 恢复——不是审批提示给的答案，后者从来只回答「这能跑吗」。
    hub.setAutoApprove(true);
    Path file = cwd.resolve("Notes.java");
    Files.writeString(file, "class Notes {\n  int a = 1;\n}\n");
    provider.reply(
        new Message.Assistant(
            "",
            List.of(
                new Message.ToolCall(
                    "call_1",
                    "edit",
                    "{\"path\":\"Notes.java\",\"edits\":["
                        + "{\"old_string\":\"int a = 1;\",\"new_string\":\"int a = 2;\"},"
                        + "{\"old_string\":\"class Notes {\",\"new_string\":\"class Notes implements Cloneable {\"}]}"))));
    provider.reply(Message.Assistant.text("changed two things"));

    JsonNode undone;
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"edit the notes\"}");
      sse.await("done", 5000);
      assertTrue(Files.readString(file).contains("int a = 2;"), "编辑确实发生了");
      assertEquals(
          "class Notes implements Cloneable {\n  int a = 2;\n}\n",
          Files.readString(file),
          "两个块都改了，各自落在该在的位置");

      undone = postJson("/api/undo", "{}");

      // 用通知而不是历史：undo 管的是文件，不是对话，而转录会说出放回了什么——读者本来就在看
      // 那块地方。
      JsonNode notice = sse.await("notice", 5000);
      assertTrue(notice.path("text").asText().startsWith("已退回：1 个文件恢复到上一回合之前的样子 ——"), notice.toString());
      assertTrue(notice.path("text").asText().contains("Notes.java"), notice.toString());
    }

    assertEquals(1, undone.path("restored").asInt(), undone.toString());
    assertEquals(
        "class Notes {\n  int a = 1;\n}\n",
        Files.readString(file),
        "文件变回了那个回合起初看到的样子");
    assertEquals(0, undone.path("remaining").asInt(), "那个回合已经用完了");
  }

  @Test
  void aFileTheTurnCreatedIsRemovedByUndo() throws Exception {
    hub.setAutoApprove(true);
    provider.reply(
        new Message.Assistant(
            "",
            List.of(
                new Message.ToolCall(
                    "call_1",
                    "write",
                    "{\"path\":\"Brand.java\",\"content\":\"class Brand {}\\n\"}"))));
    provider.reply(Message.Assistant.text("created"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"create it\"}");
      sse.await("done", 5000);
    }
    assertTrue(Files.exists(cwd.resolve("Brand.java")));

    postJson("/api/undo", "{}");

    assertFalse(Files.exists(cwd.resolve("Brand.java")), "新建的文件是被删掉，而不是被清空");
  }

  @Test
  void undoIsRefusedWhileATurnIsRunning() throws Exception {
    // 在一个正在运行的回合底下撤回，会把模型正推理到一半的文件恢复回去，那比两种状态里的任何
    // 一种都糟。
    provider.reply(Message.Assistant.text("slow"));
    provider.gate(new CountDownLatch(1));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"think\"}");

      HttpResponse<String> refused = post("/api/undo", "{}");

      assertEquals(409, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("还有回合在跑；退回上一个回合之前请先中止它"), refused.body());
      provider.release();
      sse.await("done", 5000);
    }
  }

  @Test
  void aConversationOverItsBudgetIsCompactedBetweenTurns() throws Exception {
    // 越过 `maxContextTokens` 之后，投影会开始省略工具输出、丢掉整组往来，而它的通知会说明这
    // 一点（"context: 213 → 26 tokens, 5 earlier exchange(s) dropped"），却从不说什么被丢了。
    // 一份点名自己替换了什么的摘要是更好的损失，而且它发生在回合之间——绝不在回合之内，那会让
    // 模型正想到一半的历史在它脚下变样。
    startWithBudget(50);
    // 十二组往来：一次压缩留下最新的五组、总结其余，所以它替换掉的那部分必须比摘要提示本身更大
    // ——实测下来，一组被总结的往来（52 token）对上 74 的摘要，会以 "nothing to gain" 被拒。
    for (int i = 0; i < 12; i++) {
      provider.reply(Message.Assistant.text("answer " + i));
    }
    // 第七次调用就是那次摘要调用，它不要工具、只要一条消息。
    provider.reply(Message.Assistant.text("Summary."));

    List<String> notices = new java.util.ArrayList<>();
    String question =
        "a question with a good many words in it, so that six of them are worth summarising: "
            + "the point of a compaction is that what it replaces is larger than what it writes";
    try (Sse sse = watch()) {
      for (int i = 0; i < 12; i++) {
        post("/api/message", "{\"text\":\"" + question + " " + i + "\"}");
        sse.awaitAtLeast("done", i + 1, 5000);
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline && !conversationWasCompacted()) {
        Thread.sleep(50);
      }
      sse.ofType("notice").forEach(notice -> notices.add(notice.path("text").asText()));
    }

    // 那次摘要调用在记录下来的请求里认得出来：没有工具，只有一条消息——压缩提示自己就带着
    // 转录。
    assertTrue(
        provider.requests().stream()
            .anyMatch(request -> request.tools().isEmpty() && request.messages().size() == 1),
        "本该发出过一次摘要调用：" + notices);
    assertTrue(
        conversationWasCompacted(),
        "会话文件本该有第二代；通知：" + notices);
    assertTrue(
        notices.stream().anyMatch(text -> text.startsWith("已自动压缩：")),
        "而且转录里该说这件事发生了：" + notices);
  }

  private boolean conversationWasCompacted() throws Exception {
    String id = json("/api/status").path("sessionId").asText();
    try (var files = java.nio.file.Files.list(sessions)) {
      return files.anyMatch(path -> path.getFileName().toString().startsWith(id + ".g"));
    }
  }

  /** 再次建起 hub，带一个足够小的提示预算，好让几个回合就能越过它。 */
  private void startWithBudget(int maxContextTokens) throws IOException {
    api.close();
    hub.close();
    // 十二个参数的那个构造以 maxContextTokens 收尾，而它是这个测试唯一设置的东西。
    Config budgeted =
        testConfig()
            .merge(
                new Config(
                    null, null, null, null, null, null, null, null, null, null, null,
                    maxContextTokens));
    hub = hub(provider, budgeted);
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();
  }

  @Test
  void aTurnIsOverBeforeTheDoneEventAnnouncesIt() throws Exception {
    // 页面收到 `done` 就会让用户再次发送。如果服务器在那一刻还忙着，下一条消息就会被 409 拒
    // ——而输入框会一直禁用，直到有别的事发生、发布出一个状态。所以客户端在 `done` 之前一瞬间
    // 看到的状态，必须已经说回合结束了。
    List<AgentHub.Event> seen = new CopyOnWriteArrayList<>();
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
    assertTrue(done > 0, "回合必须结束：" + seen.stream().map(AgentHub.Event::type).toList());
    AgentHub.Event before = seen.get(done - 1);
    assertEquals("status", before.type(), "状态在回合宣布之前就已经定下来了");
    assertFalse(before.payload().path("busy").asBoolean(true), before.payload().toString());
  }

  // ------------------------------------------------------------------ 辅助方法

  private static Message.Assistant call(String name, String field, String value) {
    return new Message.Assistant(
        "",
        List.of(
            new Message.ToolCall(
                "call_" + name, name, Json.write(Json.object().put(field, value)))));
  }

  private static Message.Assistant bashCall(String command) {
    return new Message.Assistant(
        "",
        List.of(
            new Message.ToolCall("call_bash", "bash", Json.write(Json.object().put("command", command)))));
  }

  private Sse watch() throws Exception {
    HttpResponse<InputStream> response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/events"))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode(), "事件流必须能打开");
    return new Sse(response.body());
  }

  private Sse watchWithLastEventId(long lastEventId) throws Exception {
    HttpResponse<InputStream> response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/events"))
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", String.valueOf(lastEventId))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode(), "事件流必须能打开");
    return new Sse(response.body());
  }

  private HttpResponse<String> delete(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(origin + path)).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(origin + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** 一个带 Origin 的 POST，就像浏览器页面发出的那样。 */
  private HttpResponse<String> postFrom(HttpExchangeOrigin origin, String path, String json)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(this.origin + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    if (origin != null) {
      request.header("Origin", origin.value());
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** 一个请求声称自己从哪里来。 */
  private record HttpExchangeOrigin(String value) {
    static final HttpExchangeOrigin EVIL = new HttpExchangeOrigin("https://evil.example");
    static final HttpExchangeOrigin SELF = new HttpExchangeOrigin("http://127.0.0.1:8080");
    static final HttpExchangeOrigin LOCALHOST = new HttpExchangeOrigin("http://localhost:3000");
    static final HttpExchangeOrigin OPAQUE = new HttpExchangeOrigin("null");
  }

  @Test
  void aCrossOriginRequestCannotChangeState() throws Exception {
    // 这里堵上的攻击，是在这道检查存在之前复现出来的：另一个站点上的页面 POST 到
    // /api/auto-approve，loopback 和 Host 两道检查都通过了——浏览器跑在这台机器上，所以它的连接
    // *就是* loopback、它的 Host *就是* 127.0.0.1——于是自动批准真的被打开了。从那以后代理在跑
    // 命令之前就不再问了。
    assertFalse(json("/api/status").path("autoApprove").asBoolean());

    HttpResponse<String> refused =
        postFrom(HttpExchangeOrigin.EVIL, "/api/auto-approve", "{\"enabled\":true}");

    assertEquals(403, refused.statusCode(), refused.body());
    assertTrue(refused.body().contains("cross-origin"), refused.body());
    assertFalse(
        json("/api/status").path("autoApprove").asBoolean(),
        "守卫仍然开着：那个页面什么都没改变");
  }

  @Test
  void theSameOriginTheServerItselfServesIsAccepted() throws Exception {
    // ccj 提供的页面必须继续能用：它自己的 POST 会带 Origin。
    assertEquals(
        200, postFrom(HttpExchangeOrigin.SELF, "/api/auto-approve", "{\"enabled\":true}").statusCode());
    assertTrue(json("/api/status").path("autoApprove").asBoolean());
    postFrom(HttpExchangeOrigin.SELF, "/api/auto-approve", "{\"enabled\":false}");
  }

  @Test
  void everyLoopbackSpellingIsAcceptedAsSelf() throws Exception {
    // 打开 localhost 的用户和监听 127.0.0.1 的服务器，是同一台机器上的同一个人；拒绝其中一种
    // 写法会是一个读起来像安全功能的 bug。
    assertEquals(
        200,
        postFrom(HttpExchangeOrigin.LOCALHOST, "/api/auto-approve", "{\"enabled\":true}")
            .statusCode());
    postFrom(HttpExchangeOrigin.LOCALHOST, "/api/auto-approve", "{\"enabled\":false}");
  }

  @Test
  void anOpaqueOriginIsRefused() throws Exception {
    // 沙箱化的 iframe 或 file:// 页面会发送 Origin: null。它不是这台服务器，而它恰恰就是一个
    // 被注入的框架会有的形状。
    assertEquals(
        403,
        postFrom(HttpExchangeOrigin.OPAQUE, "/api/auto-approve", "{\"enabled\":true}").statusCode());
    assertFalse(json("/api/status").path("autoApprove").asBoolean());
  }

  @Test
  void aCallerThatSendsNoOriginIsStillServed() throws Exception {
    // curl、测试、CLI：都不是浏览器页面，而没有这个头，浏览器也不会把跨源请求递过来。拒绝它们
    // 会为了防一个不可能这样到达的调用者，而弄坏每一个正当的调用者。
    assertEquals(200, post("/api/auto-approve", "{\"enabled\":true}").statusCode());
    assertTrue(json("/api/status").path("autoApprove").asBoolean());
    post("/api/auto-approve", "{\"enabled\":false}");
    assertFalse(json("/api/status").path("autoApprove").asBoolean());
  }

  @Test
  void readingIsNotBlockedByOrigin() throws Exception {
    // 这道检查针对的是会改变东西的请求。GET 反正也漏不出跨源页面读得到的东西——浏览器会把响应
    // 扣下——所以拦它只有代价，没有收获。
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/status"))
                .header("Origin", HttpExchangeOrigin.EVIL.value())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode(), response.body());
  }

  @Test
  void aPageServedFromTheAddressItWasOpenedOnMayChangeState() throws Exception {
    // 手机这条，而且它曾经是坏的：从 tailnet 地址打开页面时，页面会把这个地址当作自己的 Origin
    // 发出来，而它既不是 loopback，也不是这台服务器能事先知道的任何东西——于是来自手机的每一个
    // 会改变状态的请求都被拒了。对着一个按手机接入方式启动的真实服务器量过：发消息、中止回合、
    // 保存设置、作答审批、上传图片全都返回 403，而同样一个请求只要带上 loopback 的 Origin 就被
    // 服务了。那张图片本身没有任何特别之处；它只是用户从手机上试的第一件事。
    start("t0ken");

    Raw ok = postByHand("100.72.92.41:6767", "http://100.72.92.41:6767", "/api/auto-approve",
        "{\"enabled\":true}", "t0ken");

    assertEquals(200, ok.status(), ok.body());
    assertTrue(json("/api/status").path("autoApprove").asBoolean(), "这个改动真的发生了");
    postByHand("100.72.92.41:6767", "http://100.72.92.41:6767", "/api/auto-approve",
        "{\"enabled\":false}", "t0ken");
  }

  @Test
  void anotherSiteIsStillRefusedWhenTheRequestNamesARealHost() throws Exception {
    // 让上面那条规则不成为一个漏洞的东西：另一个站点上的页面发的是它自己的源，而那并不是这个
    // 请求所瞄准的主机。
    start("t0ken");

    Raw refused = postByHand("100.72.92.41:6767", "https://evil.example", "/api/auto-approve",
        "{\"enabled\":true}", "t0ken");

    assertEquals(403, refused.status(), refused.body());
    assertFalse(json("/api/status").path("autoApprove").asBoolean());
  }

  @Test
  void aRebindingNameAgreesWithItselfAndStillGetsNowhere() throws Exception {
    // 经由一个解析到这里的名字，Origin 和 Host 都是 dead.beef——所以这个一致性故意不是唯一的
    // 守卫。没有 token 的服务器在 origin 检查跑之前就已经拒掉了非 loopback 的 Host；而有 token
    // 的服务器会因为这个请求没带凭证而拒掉它，而另一个站点上的页面拿不到它并不拥有的名字的
    // 凭证。
    Raw tokenless =
        postByHand("dead.beef:6767", "http://dead.beef:6767", "/api/auto-approve",
            "{\"enabled\":true}", null);
    assertEquals(403, tokenless.status(), tokenless.body());

    start("t0ken");
    Raw withoutToken =
        postByHand("dead.beef:6767", "http://dead.beef:6767", "/api/auto-approve",
            "{\"enabled\":true}", null);
    assertEquals(401, withoutToken.status(), withoutToken.body());
    assertFalse(json("/api/status").path("autoApprove").asBoolean());
  }

  /** 从 socket 上读到的响应：来的是什么就是什么，不掺客户端库的意见。 */
  private record Raw(int status, String body) {}

  /**
   * 一个手写的请求。
   *
   * <p>{@code Host} 在 {@code HttpClient} 里是受限的头——设不了，而一个 {@code Origin} 点名了
   * 自己所瞄准主机的请求，恰恰就是手机发出来的形状。没法让 Java HTTP 客户端给出那种形状，所以
   * 只能把字节写出来。
   */
  private Raw postByHand(String host, String origin, String path, String json, String token)
      throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    StringBuilder head = new StringBuilder()
        .append("POST ").append(path).append(" HTTP/1.1\r\n")
        .append("Host: ").append(host).append("\r\n")
        .append("Origin: ").append(origin).append("\r\n")
        .append("Content-Type: application/json\r\n")
        .append("Content-Length: ").append(body.length).append("\r\n");
    if (token != null) {
      head.append("Authorization: Bearer ").append(token).append("\r\n");
    }
    head.append("Connection: close\r\n\r\n");

    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), api.port())) {
      socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().write(body);
      socket.getOutputStream().flush();
      String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int firstSpace = raw.indexOf(' ');
      int status = Integer.parseInt(raw.substring(firstSpace + 1, firstSpace + 4));
      int split = raw.indexOf("\r\n\r\n");
      return new Raw(status, split < 0 ? raw : raw.substring(split + 4).strip());
    }
  }

  // ------------------------------------------------------------------ 图片

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

  @Test
  void aBudgetOfZeroIsRefusedRatherThanSaved() throws Exception {
    // 它会意味着「什么都不写」，而拒绝必须在文件被写之前就到达。
    Files.writeString(configFile, "{}");

    HttpResponse<String> refused =
        post("/api/config", "{\"visionBaseUrl\":\"http://127.0.0.1:1/v1\",\"visionMaxTokens\":0}");

    assertEquals(400, refused.statusCode(), refused.body());
    assertTrue(refused.body().contains("视觉补全预算至少要有 1 个 token"), refused.body());
    assertFalse(Files.readString(configFile).contains("vision"), "什么都没写");
  }

  /** 再次建起 hub，用配置文件现在说的东西——就像 CLI 启动它的方式。 */
  private void restartFromConfigFile() throws IOException {
    api.close();
    hub.close();
    hub = hub(provider, Config.layered(configFile, Map.of(), null));
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();
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

  @Test
  void everyStateChangingEndpointRefusesAnotherSite() throws Exception {
    // 这道防御是按方法来的，不是按一张路径清单来的，正是为了让新端点没法忘记加入。这里检查的是
    // 今天已经存在的那些端点确实就是这样表现的。
    for (String path : List.of("/api/message", "/api/attachment", "/api/abort", "/api/compact",
        "/api/session", "/api/workspaces", "/api/workspace", "/api/config", "/api/auto-approve",
        "/api/approval")) {
      HttpResponse<String> refused = postFrom(HttpExchangeOrigin.EVIL, path, "{}");
      assertEquals(
          403,
          refused.statusCode(),
          "另一个站点上的页面绝不能碰到 " + path + ": " + refused.body());
    }
  }

  private HttpResponse<String> post(String path, String json) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(origin + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode json(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertEquals(200, response.statusCode(), response.body());
    return Json.parse(response.body());
  }

  private JsonNode postJson(String path, String json) throws Exception {
    HttpResponse<String> response = post(path, json);
    assertEquals(200, response.statusCode(), response.body());
    return Json.parse(response.body());
  }

  private String body(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertEquals(200, response.statusCode(), response.body());
    return response.body();
  }

  private static JsonNode lastWhere(JsonNode array, java.util.function.Predicate<JsonNode> match) {
    for (JsonNode entry : array) {
      if (match.test(entry)) {
        return entry;
      }
    }
    throw new AssertionError("没有任何条目匹配 " + array);
  }

  private static List<String> languages(JsonNode config) {
    List<String> values = new ArrayList<>();
    config.path("languages").forEach(entry -> values.add(entry.path("value").asText()));
    return values;
  }

  private static List<String> levels(JsonNode config) {
    List<String> levels = new ArrayList<>();
    config.path("reasoningLevels").forEach(level -> levels.add(level.asText()));
    return levels;
  }

  private static List<String> providerNames(JsonNode catalog) {
    List<String> names = new ArrayList<>();
    catalog.path("providers").forEach(entry -> names.add(entry.path("name").asText()));
    return names;
  }

  private static List<String> availableBuiltIns(JsonNode catalog) {
    List<String> names = new ArrayList<>();
    catalog.path("builtIns").forEach(entry -> names.add(entry.asText()));
    return names;
  }

  private static JsonNode providerOf(JsonNode catalog, String name) {
    return lastWhere(catalog.path("providers"), entry -> entry.path("name").asText().equals(name));
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    if (array != null && array.isArray()) {
      array.forEach(entry -> out.add(entry.asText()));
    }
    return out;
  }

  private static List<String> modelsOf(JsonNode provider) {
    List<String> models = new ArrayList<>();
    provider.path("models").forEach(model -> models.add(model.asText()));
    return models;
  }

  private static JsonNode lastOf(Sse sse, String type) {
    List<JsonNode> events = sse.ofType(type);
    assertFalse(events.isEmpty(), "至少要有一个 " + type + " 事件");
    return events.get(events.size() - 1);
  }

  /** 在后台收集 SSE 流，好让测试能等待单个事件。 */
  /** 一个收到的事件：SSE 的 id 和它的载荷。 */
  private record SseEvent(long id, JsonNode payload, String name) {}

  private static final class Sse implements AutoCloseable {

    private final List<SseEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final InputStream body;

    Sse(InputStream body) {
      this.body = body;
      Thread reader =
          new Thread(
              () -> {
                try (BufferedReader in =
                    new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                  long id = 0;
                  String name = "";
                  String line;
                  while ((line = in.readLine()) != null) {
                    if (line.startsWith("id: ")) {
                      id = Long.parseLong(line.substring("id: ".length()).strip());
                    } else if (line.startsWith("event: ")) {
                      // 保留，而不是跳过。这个读取器过去会丢掉一切不是 `id:` 或 `data:` 的
                      // 行，所以一个以*注释*形式发出的保活能这么久没被发现：测试看不出一个能到
                      // 达页面的帧和一个到不了的帧有什么区别。
                      name = line.substring("event: ".length()).strip();
                    } else if (line.startsWith("data: ")) {
                      events.add(
                          new SseEvent(id, Json.parse(line.substring("data: ".length())), name));
                      name = "";
                    } else if (line.isEmpty()) {
                      name = "";
                    }
                  }
                } catch (IOException | RuntimeException ignored) {
                  // 测试把流关了，或者服务器停了；无论哪种我们都完事了。
                }
              },
              "web-api-test-sse");
      reader.setDaemon(true);
      reader.start();
    }

    JsonNode await(String type, long millis) throws InterruptedException {
      return awaitAtLeast(type, 1, millis);
    }

    /**
     * 等待一个带着给定 SSE {@code event} 名字的帧，这和载荷里面的 {@code type} 是两回事：无名
     * 的帧永远到不了页面的 {@code onmessage}，而有名的帧只有在为那个名字注册了监听器时才到
     * 得了。
     */
    JsonNode awaitRaw(String field, String value, long millis) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
      while (System.nanoTime() < deadline) {
        for (SseEvent event : raw()) {
          if ("event".equals(field) && value.equals(event.name())) {
            return event.payload();
          }
        }
        Thread.sleep(10);
      }
      return null;
    }

    /** 和 {@link #await} 一样，只是还带上 SSE 的 id，供关于回放和重连的测试使用。 */
    SseEvent awaitEvent(String type, long millis) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
      while (System.nanoTime() < deadline) {
        for (SseEvent event : raw()) {
          if (type.equals(event.payload().path("type").asText())) {
            return event;
          }
        }
        Thread.sleep(10);
      }
      throw new AssertionError("没有带 id 的 '" + type + "' 事件；看到的是 " + snapshot());
    }

    List<SseEvent> raw() {
      synchronized (events) {
        return List.copyOf(events);
      }
    }

    List<String> types() {
      return raw().stream().map(event -> event.payload().path("type").asText()).toList();
    }

    /** 等到至少到达 {@code count} 个 {@code type} 事件为止。 */
    JsonNode awaitAtLeast(String type, int count, long millis) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
      while (System.nanoTime() < deadline) {
        List<JsonNode> seen = ofType(type);
        if (seen.size() >= count) {
          return seen.get(seen.size() - 1);
        }
        Thread.sleep(10);
      }
      throw new AssertionError(
          "少于 "
              + count
              + " 个 '"
              + type
              + "' 事件，在 "
              + millis
              + "ms 内；看到的是 "
              + snapshot());
    }

    List<JsonNode> ofType(String type) {
      List<JsonNode> matches = new ArrayList<>();
      for (SseEvent event : raw()) {
        if (type.equals(event.payload().path("type").asText())) {
          matches.add(event.payload());
        }
      }
      return matches;
    }

    /** 所有流式散文拼在一起——转录会显示出来的东西。 */
    String text() {
      StringBuilder out = new StringBuilder();
      for (JsonNode event : ofType("text")) {
        out.append(event.path("delta").asText());
      }
      return out.toString();
    }

    List<JsonNode> snapshot() {
      return raw().stream().map(SseEvent::payload).toList();
    }

    /** 属于某一个会话的事件，也就是一个页面会渲染的东西。 */
    List<JsonNode> forSession(String sessionId) {
      List<JsonNode> matches = new ArrayList<>();
      for (JsonNode event : snapshot()) {
        if (sessionId.equals(event.path("sessionId").asText())) {
          matches.add(event);
        }
      }
      return matches;
    }

    /** 等到 {@code sessionId} 至少有 {@code count} 个 {@code type} 事件为止。 */
    JsonNode awaitInSession(String sessionId, String type, int count, long millis)
        throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
      while (System.nanoTime() < deadline) {
        List<JsonNode> seen =
            forSession(sessionId).stream()
                .filter(event -> type.equals(event.path("type").asText()))
                .toList();
        if (seen.size() >= count) {
          return seen.get(seen.size() - 1);
        }
        Thread.sleep(10);
      }
      throw new AssertionError(
          "少于 "
              + count
              + " 个 '"
              + type
              + "' 事件，属于会话 "
              + sessionId
              + "，在 "
              + millis
              + "ms 内；看到的是 "
              + snapshot());
    }

    /** 只要有事件被发布时没说它属于哪个会话，就为真。 */
    boolean anyEventWithoutSession() {
      return snapshot().stream().anyMatch(event -> event.path("sessionId").asText().isEmpty());
    }

    @Override
    public void close() {
      try {
        body.close();
      } catch (IOException ignored) {
        // 拆掉一个测试的时候，没什么有用的事可做。
      }
    }
  }

  /** 脚本化的提供方：每个请求一个排好队的助手回合，可选地由一个闩锁延迟。 */
  private static final class MockProvider implements Provider {

    private final String name;
    private final Deque<Message.Assistant> script = new ArrayDeque<>();
    private final List<Provider.Request> requests = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch gate;
    private int[] usage;
    /** 下一个回合应当发出并携带的推理内容，不思考的回合为 null。 */
    private String reasoning;

    MockProvider(String name) {
      this.name = name;
    }

    /**
     * 让接下来的回合出声地思考。
     *
     * <p>两半都重要：增量发给监听器（这样页面会实时渲染它们），同一段文本又会进入返回的回合
     * （这样它被持久化，而后来的一次回放读的就是它）。
     */
    MockProvider emitReasoning(String text) {
      this.reasoning = text;
      return this;
    }

    MockProvider reply(Message.Assistant assistant) {
      script.add(assistant);
      return this;
    }

    /** 每个回合之后报告这笔 token 账；cached 可以是 null，表示「没有报告」。 */
    MockProvider usage(int inputTokens, int outputTokens, Integer cachedInputTokens) {
      this.usage =
          new int[] {inputTokens, outputTokens, cachedInputTokens == null ? -1 : cachedInputTokens};
      return this;
    }

    void gate(CountDownLatch latch) {
      this.gate = latch;
    }

    void release() {
      CountDownLatch current = gate;
      if (current != null) {
        current.countDown();
      }
    }

    List<Provider.Request> requests() {
      return List.copyOf(requests);
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public Message.Assistant complete(Request request, Consumer<Event> listener)
        throws InterruptedException {
      requests.add(request);
      CountDownLatch current = gate;
      if (current != null) {
        current.await(10, TimeUnit.SECONDS);
      }
      Message.Assistant next =
          script.isEmpty()
              ? Message.Assistant.text("(no scripted reply left)")
              : script.poll();
      String thinking = reasoning;
      if (thinking != null && !thinking.isEmpty()) {
        listener.accept(new Event.ReasoningDelta(thinking));
        next =
            new Message.Assistant(
                next.text(),
                next.toolCalls(),
                List.of(Message.Thinking.of(thinking, "sig-" + thinking.length())));
      }
      if (!next.text().isEmpty()) {
        listener.accept(new Event.TextDelta(next.text()));
      }
      for (Message.ToolCall call : next.toolCalls()) {
        listener.accept(new Event.ToolCallStart(call.id(), call.name()));
      }
      if (usage != null) {
        listener.accept(new Event.Usage(usage[0], usage[1], usage[2] < 0 ? null : usage[2]));
      }
      return next;
    }
  }
}
