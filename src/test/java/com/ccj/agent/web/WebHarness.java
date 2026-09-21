package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.ccj.agent.core.ApprovalRules;
import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import com.ccj.agent.session.FileSession;
import com.sun.net.httpserver.HttpServer;
import java.util.Optional;
import com.ccj.agent.session.SessionStore;
import com.ccj.agent.workspace.WorkspaceStore;
import com.ccj.agent.tool.Tools;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * 这些 web 测试共用的夹具：真的服务器、真的 HTTP、真的 SSE、脚本化的提供方。
 *
 * <p>夹具用继承而不是组合：用例原本就直接调用 {@code get}/{@code post}/{@code watch} 这些帮手、直接读写
 * {@code cwd}/{@code provider}/{@code api} 这些字段，继承让每个方法体一字不改地搬过来；组合则要把每个调用点
 * 都改成一次转发，或者把全部字段都包一层访问器。同时 {@code @TempDir} 与 {@code @BeforeEach} 放在父类上就
 * 对每个子类的用例生效，不必在每个子类里重复装配。
 *
 * <p>只放「每个用例都要用的东西」：启动与关闭、审批文件的路径、传输与观测的原语（HTTP 帮手、{@code Sse}、
 * JSON 与事件的查找）以及脚本化的提供方。只服务某一个主题的帮手留在那个主题的测试类里。
 */
class WebHarness {

  @TempDir Path tmp;

  final HttpClient client = HttpClient.newHttpClient();

  Path cwd;
  Path sessions;
  Path configFile;
  final AtomicInteger factoryCalls = new AtomicInteger();
  /** 测试会改动的行为；hub 持有 {@link #chooser}，由它委托到这里。 */
  com.ccj.agent.provider.ProviderStore providerStore;

  volatile FolderChooser chooserBehaviour = title -> Optional.empty();
  final FolderChooser chooser = title -> chooserBehaviour.choose(title);
  volatile MockProvider lastBuilt;
  MockProvider provider;
  AgentHub hub;
  HttpApi api;
  String origin;
  /** 替身的视觉端点，仅在某个测试启动了它时存在。 */
  HttpServer vision;

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

  void start(String token) throws IOException {
    start(token, new Wallpapers(null));
  }

  /** 启动一个服务器，其壁纸目录由测试自己选定。 */
  void start(String token, Wallpapers wallpapers) throws IOException {
    if (api != null) {
      api.close();
    }
    hub = hub(provider, testConfig());
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), token, wallpapers);
    origin = "http://127.0.0.1:" + api.port();
  }

  /** 一个注册表，其默认工作区是测试的工作目录。 */
  WorkspaceStore store() {
    return WorkspaceStore.open(tmp, cwd);
  }

  static Config testConfig() {
    return new Config(
            "openai", "mock-model", "http://mock.invalid/v1", "sk-test", null, null, null, null,
            0, null, null)
        .resolved();
  }

  /**
   * 镜像 CLI 注入的东西：一个像真货那样做校验的工厂，返回以模型命名的提供方，这样运行时的
   * 切换从外部就观察得到。
   */
  AgentHub hub(Provider initial, Config config) {
    return hub(initial, config, SessionStore.create(sessions));
  }

  /** 这些测试写入规则的审批文件；决策链在每次判定时都会读它。 */
  Path approvalsFile() {
    return tmp.resolve("approvals.json");
  }

  AgentHub hub(Provider initial, Config config, FileSession session) {
    AgentHub built = hubWithoutRules(initial, config, session);
    // 严格按 CLI 的接法接线：本项目的规则，放在应用主目录里。
    built.setApprovalRules(ApprovalRules.open(approvalsFile(), cwd));
    return built;
  }

  AgentHub hubWithoutRules(Provider initial, Config config, FileSession session) {
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

  static int plainGet(String url) throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    return response.statusCode();
  }

  static Message.Assistant call(String name, String field, String value) {
    return new Message.Assistant(
        "",
        List.of(
            new Message.ToolCall(
                "call_" + name, name, Json.write(Json.object().put(field, value)))));
  }

  static Message.Assistant bashCall(String command) {
    return new Message.Assistant(
        "",
        List.of(
            new Message.ToolCall("call_bash", "bash", Json.write(Json.object().put("command", command)))));
  }

  Sse watch() throws Exception {
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

  Sse watchWithLastEventId(long lastEventId) throws Exception {
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

  HttpResponse<String> delete(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(origin + path)).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(origin + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** 一个带 Origin 的 POST，就像浏览器页面发出的那样。 */
  HttpResponse<String> postFrom(HttpExchangeOrigin origin, String path, String json)
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
  record HttpExchangeOrigin(String value) {
    static final HttpExchangeOrigin EVIL = new HttpExchangeOrigin("https://evil.example");
    static final HttpExchangeOrigin SELF = new HttpExchangeOrigin("http://127.0.0.1:8080");
    static final HttpExchangeOrigin LOCALHOST = new HttpExchangeOrigin("http://localhost:3000");
    static final HttpExchangeOrigin OPAQUE = new HttpExchangeOrigin("null");
  }

  /** 再次建起 hub，用配置文件现在说的东西——就像 CLI 启动它的方式。 */
  void restartFromConfigFile() throws IOException {
    api.close();
    hub.close();
    hub = hub(provider, Config.layered(configFile, Map.of(), null));
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();
  }

  HttpResponse<String> post(String path, String json) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(origin + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode json(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertEquals(200, response.statusCode(), response.body());
    return Json.parse(response.body());
  }

  JsonNode postJson(String path, String json) throws Exception {
    HttpResponse<String> response = post(path, json);
    assertEquals(200, response.statusCode(), response.body());
    return Json.parse(response.body());
  }

  String body(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertEquals(200, response.statusCode(), response.body());
    return response.body();
  }

  static JsonNode lastWhere(JsonNode array, java.util.function.Predicate<JsonNode> match) {
    for (JsonNode entry : array) {
      if (match.test(entry)) {
        return entry;
      }
    }
    throw new AssertionError("没有任何条目匹配 " + array);
  }

  static JsonNode lastOf(Sse sse, String type) {
    List<JsonNode> events = sse.ofType(type);
    assertFalse(events.isEmpty(), "至少要有一个 " + type + " 事件");
    return events.get(events.size() - 1);
  }

  /** 在后台收集 SSE 流，好让测试能等待单个事件。 */
  /** 一个收到的事件：SSE 的 id 和它的载荷。 */
  record SseEvent(long id, JsonNode payload, String name) {}

  static final class Sse implements AutoCloseable {

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
  static final class MockProvider implements Provider {

    private final String name;
    private final Deque<Message.Assistant> script = new ArrayDeque<>();
    private final List<Provider.Request> requests = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch gate;
    private int[] usage;
    /** 每次请求各自的一笔账，按请求到达的顺序取用；空了就回落到 {@link #usage}。 */
    private final Deque<int[]> queuedUsage = new ArrayDeque<>();
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

    /**
     * 接下来的一次请求报这笔账，一次一笔，按请求到达的顺序取用；排好的用完就回落到 {@link #usage}。
     *
     * <p>{@code usage} 说的是「此后每次都报同一笔」；这个说的是「各报各的」。当一次请求链上有好几个模型
     * 请求时（委派里主代理、子代理、主代理；压缩再加一次），只有各报各的才看得出是哪一个被漏掉、
     * 哪一个被记了两遍——同一个数字加两遍和加一遍分不出来。
     */
    MockProvider usageFor(int inputTokens, int outputTokens, Integer cachedInputTokens) {
      queuedUsage.add(
          new int[] {inputTokens, outputTokens, cachedInputTokens == null ? -1 : cachedInputTokens});
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
      int[] reported = queuedUsage.isEmpty() ? usage : queuedUsage.poll();
      if (reported != null) {
        listener.accept(
            new Event.Usage(reported[0], reported[1], reported[2] < 0 ? null : reported[2]));
      }
      return next;
    }
  }
}
