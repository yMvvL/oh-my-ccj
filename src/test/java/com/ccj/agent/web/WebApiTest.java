package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import com.ccj.agent.session.SessionStore;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the web API the way a browser does: real HTTP, real SSE, real tool execution.
 *
 * <p>The model is scripted, so what is under test is the web layer itself — event ordering, the
 * approval handshake, busy refusal, session switching and the token gate.
 */
class WebApiTest {

  @TempDir Path tmp;

  private final HttpClient client = HttpClient.newHttpClient();

  private Path cwd;
  private Path sessions;
  private Path configFile;
  private final AtomicInteger factoryCalls = new AtomicInteger();
  private volatile MockProvider lastBuilt;
  private MockProvider provider;
  private AgentHub hub;
  private HttpApi api;
  private String origin;

  @BeforeEach
  void setUp() throws IOException {
    cwd = Files.createDirectories(tmp.resolve("ws"));
    sessions = tmp.resolve("sessions");
    configFile = tmp.resolve("config.json");
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
  }

  private void start(String token) throws IOException {
    hub = hub(provider, testConfig());
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), token);
    origin = "http://127.0.0.1:" + api.port();
  }

  private static Config testConfig() {
    return new Config(
            "openai", "mock-model", "http://mock.invalid/v1", "sk-test", null, null, null, 6, null,
            0, null)
        .resolved();
  }

  /**
   * Mirrors what the CLI injects: a factory that validates like the real one and hands back a
   * provider named after the model, so a runtime swap is observable from the outside.
   */
  private AgentHub hub(Provider initial, Config config) {
    return new AgentHub(
        initial,
        config,
        Tools.standard(),
        new AgentHub.Settings(
            "test",
            cwd,
            sessions,
            configFile,
            Map.of(),
            (candidate, env) -> {
              factoryCalls.incrementAndGet();
              if (candidate.provider() == null
                  || !List.of("openai", "anthropic").contains(candidate.provider())) {
                throw new IllegalArgumentException("unknown provider '" + candidate.provider() + "'");
              }
              lastBuilt =
                  new MockProvider(candidate.model() == null ? "mock-model" : candidate.model());
              return lastBuilt;
            },
            List.of("openai", "anthropic"),
            false),
        SessionStore.create(sessions));
  }

  // ------------------------------------------------------------------ tests

  @Test
  void servesThePageAndItsAssetsFromTheClasspath() throws Exception {
    String page = body("/");
    assertTrue(page.contains("<html"), page);
    assertTrue(page.contains("/app.js"), "the page must reference its script");
    assertTrue(page.contains("/style.css"), "the page must reference its stylesheet");

    String script = body("/app.js");
    assertTrue(script.contains("EventSource"), "the page must actually listen to the event stream");
    assertTrue(script.contains("/api/message"), "the page must be able to send a message");
    assertFalse(body("/style.css").isBlank());
  }

  @Test
  void statusDescribesTheModelSessionAndTools() throws Exception {
    JsonNode status = json("/api/status");

    assertEquals("openai", status.path("provider").asText());
    assertEquals("mock-model", status.path("model").asText());
    assertEquals("http://mock.invalid/v1", status.path("baseUrl").asText());
    assertEquals(cwd.toString(), status.path("cwd").asText());
    assertFalse(status.path("sessionId").asText().isBlank());
    assertFalse(status.path("busy").asBoolean(), "nothing should be running yet");
    assertEquals(6, status.path("tools").size(), "all six tools must be advertised");
    assertEquals("read", status.path("tools").get(0).path("name").asText());
  }

  @Test
  void aFreshConnectionImmediatelyReceivesTheStatus() throws Exception {
    try (Sse sse = watch()) {
      JsonNode status = sse.await("status", 3000);

      assertEquals("openai", status.path("provider").asText());
      assertEquals(6, status.path("tools").size());
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
          Files.exists(cwd.resolve("made.txt")), "nothing may happen while the approval is pending");

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
  void aDeniedApprovalLeavesTheWorkspaceUntouched() throws Exception {
    provider.reply(bashCall("printf hi > nope.txt"));
    provider.reply(Message.Assistant.text("stopped"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"do it\"}");
      JsonNode approval = sse.await("approval", 5000);
      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":false}");

      sse.await("done", 5000);
      assertFalse(Files.exists(cwd.resolve("nope.txt")), "a denied call must not touch the disk");
      assertFalse(lastOf(sse, "tool").path("ok").asBoolean(), "the tool must report failure");
      assertFalse(hub.autoApprove(), "denying must not turn the gate off");
    }
  }

  @Test
  void rememberingAnApprovalFlipsTheSessionToAutoApprove() throws Exception {
    provider.reply(bashCall("printf a > one.txt"));
    provider.reply(Message.Assistant.text("first"));
    provider.reply(bashCall("printf b > two.txt"));
    provider.reply(Message.Assistant.text("second"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"one\"}");
      JsonNode approval = sse.await("approval", 5000);
      post(
          "/api/approval",
          "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true,\"remember\":true}");
      sse.await("done", 5000);
      assertTrue(hub.autoApprove());
      assertEquals(1, sse.ofType("approval").size());

      post("/api/message", "{\"text\":\"two\"}");
      sse.awaitAtLeast("done", 2, 5000);
      assertTrue(Files.exists(cwd.resolve("two.txt")), "the second call must have skipped approval");
      assertEquals(1, sse.ofType("approval").size(), "only the first call should have asked");
    }
  }

  @Test
  void aSecondMessageWhileBusyIsRefused() throws Exception {
    provider.reply(Message.Assistant.text("slow answer"));
    provider.gate(new CountDownLatch(1));
    try (Sse sse = watch()) {
      assertEquals(202, post("/api/message", "{\"text\":\"first\"}").statusCode());
      HttpResponse<String> second = post("/api/message", "{\"text\":\"second\"}");
      assertEquals(409, second.statusCode());
      assertTrue(second.body().contains("already running"), second.body());

      provider.release();
      sse.await("done", 5000);
      assertEquals(202, post("/api/message", "{\"text\":\"third\"}").statusCode());
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
    assertEquals(first, list.get(0).path("id").asText());

    JsonNode created = postJson("/api/session", "{\"action\":\"new\"}");
    assertNotEquals(first, created.path("sessionId").asText());

    JsonNode resumed = postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + first + "\"}");
    assertEquals(first, resumed.path("sessionId").asText());
    assertEquals(2, resumed.path("messageCount").asInt(), "history must be replayed from disk");
  }

  @Test
  void theTokenGateProtectsEveryEndpoint() throws Exception {
    api.close();
    start("s3cret");

    HttpResponse<String> denied = get("/api/status");
    assertEquals(401, denied.statusCode());
    assertTrue(denied.body().contains("token"), denied.body());

    HttpResponse<String> allowed = get("/api/status?token=s3cret");
    assertEquals(200, allowed.statusCode());

    HttpResponse<String> page = get("/?token=s3cret");
    assertEquals(200, page.statusCode());
    String cookie = page.headers().firstValue("Set-Cookie").orElse("");
    assertTrue(cookie.startsWith("ccj_token=s3cret"), "the page load must remember the token");
    assertTrue(cookie.contains("HttpOnly"), cookie);
    assertTrue(page.body().contains("<html"), "the page itself must still be served");

    HttpResponse<String> withCookie =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/status"))
                .header("Cookie", "ccj_token=s3cret")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, withCookie.statusCode(), "the browser must not need the token again");

    HttpResponse<String> withHeader =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/status"))
                .header("Authorization", "Bearer s3cret")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, withHeader.statusCode());
  }

  // ------------------------------------------------------------------ settings

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
    assertTrue(status.path("cwd").asText().endsWith("ws"), "the UI still needs its context");

    HttpResponse<String> refused = post("/api/message", "{\"text\":\"hi\"}");
    assertEquals(409, refused.statusCode());
    assertTrue(refused.body().contains("Settings"), refused.body());

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
            "{\"provider\":\"anthropic\",\"model\":\"claude-test\",\"baseUrl\":\"http://relay.invalid\",\"apiKey\":\"sk-written\",\"maxSteps\":9}");

    assertEquals("claude-test", saved.path("model").asText());
    assertEquals("anthropic", saved.path("provider").asText());
    assertTrue(saved.path("configured").asBoolean());
    assertEquals("claude-test", lastBuilt.name(), "the new provider must be the one in use");

    JsonNode file = Json.parse(Files.readString(configFile));
    assertEquals("anthropic", file.path("provider").asText());
    assertEquals("claude-test", file.path("model").asText());
    assertEquals("sk-written", file.path("apiKey").asText());
    assertEquals(9, file.path("maxSteps").asInt());
    assertEquals("keep me", file.path("systemPrompt").asText(), "unmanaged keys must survive");
    assertEquals(4096, file.path("outputLimitBytes").asInt());
    assertEquals(
        "rw-------",
        java.nio.file.attribute.PosixFilePermissions.toString(
            Files.getPosixFilePermissions(configFile)),
        "the file may hold a key");

    JsonNode config = json("/api/config");
    assertEquals("config", config.path("apiKeySource").asText());
    assertFalse(config.toString().contains("sk-written"), "the key must never leave the server");

    // and the next turn really goes to the rebuilt provider
    lastBuilt.reply(Message.Assistant.text("answered by the new model"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      assertEquals("answered by the new model", sse.await("done", 5000).path("finalText").asText());
    }
  }

  @Test
  void aRejectedSettingChangesNothingOnDiskOrInMemory() throws Exception {
    JsonNode before = json("/api/config");

    HttpResponse<String> rejected = post("/api/config", "{\"provider\":\"gemini\"}");

    assertEquals(400, rejected.statusCode(), rejected.body());
    assertTrue(rejected.body().contains("gemini"), rejected.body());
    assertFalse(Files.exists(configFile), "a rejected change must not create a config file");
    assertEquals(before.path("provider").asText(), json("/api/config").path("provider").asText());
    assertEquals("openai", json("/api/status").path("provider").asText());
  }

  @Test
  void outOfRangeValuesAreRejected() throws Exception {
    assertEquals(400, post("/api/config", "{\"maxSteps\":0}").statusCode());
    assertEquals(400, post("/api/config", "{\"temperature\":9}").statusCode());
    assertEquals(400, post("/api/config", "{\"maxSteps\":\"lots\"}").statusCode());
    assertFalse(Files.exists(configFile));
  }

  @Test
  void clearingAStoredKeyFallsBackToTheEnvironment() throws Exception {
    postJson("/api/config", "{\"apiKey\":\"sk-temp\"}");
    assertEquals("config", json("/api/config").path("apiKeySource").asText());

    JsonNode cleared = postJson("/api/config", "{\"clearApiKey\":true}");

    assertTrue(cleared.path("configured").asBoolean(), cleared.toString());
    assertFalse(Files.readString(configFile).contains("sk-temp"), "the key must be gone");
    assertEquals("none", json("/api/config").path("apiKeySource").asText());
  }

  @Test
  void testingSettingsDoesNotSaveThem() throws Exception {
    factoryCalls.set(0);

    JsonNode result = postJson("/api/config/test", "{\"provider\":\"anthropic\",\"model\":\"probe\"}");

    assertTrue(result.path("ok").asBoolean(), result.toString());
    assertTrue(result.path("elapsedMs").asInt() >= 0);
    assertEquals(1, factoryCalls.get(), "the probe must build a throwaway provider");
    assertFalse(Files.exists(configFile), "testing must not persist anything");
    assertEquals("openai", json("/api/config").path("provider").asText(), "in-memory config unchanged");
    assertEquals("openai", json("/api/status").path("provider").asText());
  }

  @Test
  void aFailingProbeIsReportedAsARejection() throws Exception {
    HttpResponse<String> failure =
        post("/api/config/test", "{\"provider\":\"gemini\",\"model\":\"x\"}");

    assertEquals(400, failure.statusCode(), failure.body());
    assertTrue(failure.body().contains("gemini"), failure.body());
  }

  // ------------------------------------------------------------------ helpers

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
    assertEquals(200, response.statusCode(), "the event stream must open");
    return new Sse(response.body());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(origin + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
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

  private static JsonNode lastOf(Sse sse, String type) {
    List<JsonNode> events = sse.ofType(type);
    assertFalse(events.isEmpty(), "expected at least one " + type + " event");
    return events.get(events.size() - 1);
  }

  /** Collects the SSE stream in the background so tests can await individual events. */
  private static final class Sse implements AutoCloseable {

    private final List<JsonNode> events = Collections.synchronizedList(new ArrayList<>());
    private final InputStream body;

    Sse(InputStream body) {
      this.body = body;
      Thread reader =
          new Thread(
              () -> {
                try (BufferedReader in =
                    new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                  String line;
                  while ((line = in.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                      events.add(Json.parse(line.substring("data: ".length())));
                    }
                  }
                } catch (IOException | RuntimeException ignored) {
                  // The test closed the stream, or the server stopped; either way we are done.
                }
              },
              "web-api-test-sse");
      reader.setDaemon(true);
      reader.start();
    }

    JsonNode await(String type, long millis) throws InterruptedException {
      return awaitAtLeast(type, 1, millis);
    }

    /** Waits until at least {@code count} events of {@code type} have arrived. */
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
          "fewer than "
              + count
              + " '"
              + type
              + "' events within "
              + millis
              + "ms; saw "
              + snapshot());
    }

    List<JsonNode> ofType(String type) {
      List<JsonNode> matches = new ArrayList<>();
      for (JsonNode event : snapshot()) {
        if (type.equals(event.path("type").asText())) {
          matches.add(event);
        }
      }
      return matches;
    }

    /** All streamed prose, concatenated — what the transcript would show. */
    String text() {
      StringBuilder out = new StringBuilder();
      for (JsonNode event : ofType("text")) {
        out.append(event.path("delta").asText());
      }
      return out.toString();
    }

    List<JsonNode> snapshot() {
      synchronized (events) {
        return List.copyOf(events);
      }
    }

    @Override
    public void close() {
      try {
        body.close();
      } catch (IOException ignored) {
        // Nothing useful to do while tearing down a test.
      }
    }
  }

  /** Scripted provider: one queued assistant turn per request, optionally delayed by a latch. */
  private static final class MockProvider implements Provider {

    private final String name;
    private final Deque<Message.Assistant> script = new ArrayDeque<>();
    private final List<Provider.Request> requests = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch gate;

    MockProvider(String name) {
      this.name = name;
    }

    MockProvider reply(Message.Assistant assistant) {
      script.add(assistant);
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
      if (!next.text().isEmpty()) {
        listener.accept(new Event.TextDelta(next.text()));
      }
      for (Message.ToolCall call : next.toolCalls()) {
        listener.accept(new Event.ToolCallStart(call.id(), call.name()));
      }
      return next;
    }
  }
}
