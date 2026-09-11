package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.UsageTotals;
import com.ccj.agent.session.FileSession;
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
  /** The behaviour the tests change; the hub holds {@link #chooser}, which delegates to it. */
  private com.ccj.agent.provider.ProviderStore providerStore;
  private volatile FolderChooser chooserBehaviour = title -> Optional.empty();
  private final FolderChooser chooser = title -> chooserBehaviour.choose(title);
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
  }

  private void start(String token) throws IOException {
    hub = hub(provider, testConfig());
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), token);
    origin = "http://127.0.0.1:" + api.port();
  }


  /** A registry whose default workspace is the test's working directory. */
  private WorkspaceStore store() {
    return WorkspaceStore.open(tmp, cwd);
  }

  private static Config testConfig() {
    return new Config(
            "openai", "mock-model", "http://mock.invalid/v1", "sk-test", null, null, null, 6, null,
            0, null, null)
        .resolved();
  }

  /**
   * Mirrors what the CLI injects: a factory that validates like the real one and hands back a
   * provider named after the model, so a runtime swap is observable from the outside.
   */
  private AgentHub hub(Provider initial, Config config) {
    return hub(initial, config, SessionStore.create(sessions));
  }

  private AgentHub hub(Provider initial, Config config, FileSession session) {
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
              // Mirrors the real factory: built-ins plus whatever the user defined.
              String requested = candidate.provider();
              if (requested == null
                  || (!List.of("openai", "anthropic").contains(requested)
                      && providerStore.find(requested).isEmpty())) {
                throw new IllegalArgumentException("unknown provider '" + requested + "'");
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

  // ------------------------------------------------------------------ history and usage

  @Test
  void usageTotalsAndCacheHitRateAccumulateAcrossATurn() throws Exception {
    // The double reports the same accounting every turn, so two turns double both sides.
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
    assertTrue(usage.path("cacheHitRate").isNull(), "unknown must not render as 0%");
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
    assertEquals(start.path("id").asText(), end.path("id").asText(), "cards pair by call id");
    assertTrue(end.path("ok").asBoolean());
    assertTrue(end.path("output").asText().contains("on disk"), end.toString());
    assertTrue(end.path("elapsedMs").isNull(), "history stores no timing, so none is invented");
    history
        .path("events")
        .forEach(event -> assertTrue(event.path("replay").asBoolean(), event.toString()));
    assertEquals(1, history.path("usage").path("turns").asInt(), "one user turn");
    assertEquals(2, history.path("usage").path("steps").asInt(), "tool call, then the answer");
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
      assertEquals(before, response.path("sessionId").asText(), "the id must not change");
      assertTrue(sse.await("notice", 3000).path("text").asText().contains("already empty"));
    }
    assertTrue(SessionStore.list(sessions).isEmpty(), "no session file may appear for an empty session");
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
    assertEquals(0, json("/api/history").path("events").size(), "the new session is empty");
    assertEquals(0, json("/api/status").path("usage").path("inputTokens").asInt(), "totals reset");
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

    // A fresh page has no gap to fill: it renders the conversation from /api/history, so replaying
    // the buffer here would draw every recent event a second time.
    try (Sse fresh = watch()) {
      fresh.await("status", 3000);
      Thread.sleep(300);
      assertEquals(
          List.of("status"),
          fresh.types(),
          "a fresh connection gets its state, not the live events of turns it never saw");
    }

    // A reconnecting page does have a gap, and only that gap.
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
    assertEquals(1, usage.path("turns").asInt(), "the earlier turn is part of this session");
    assertEquals(100, usage.path("inputTokens").asInt());
    assertEquals(0.4, usage.path("cacheHitRate").asDouble(), 0.001);
    assertEquals(2, json("/api/history").path("events").size(), "and so is its conversation");
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
    assertEquals(1, usage.path("turns").asInt(), "restarting must not forget the turns");
    assertEquals(200, usage.path("inputTokens").asInt());
    assertEquals(150, usage.path("cachedInputTokens").asInt());
    assertEquals(0.75, usage.path("cacheHitRate").asDouble(), 0.001);
    assertEquals(1, json("/api/history").path("events").size());
  }

  // ------------------------------------------------------------------ workspaces

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
    assertEquals(other.toString(), switched.path("cwd").asText(), "tools now work in that directory");

    // A session written before the switch belongs to the old workspace and must not show up here.
    assertEquals(0, json("/api/sessions").path("sessions").size());
    assertEquals(0, json("/api/history").path("events").size());

    // And a relative tool path really resolves there: this file exists only in the new workspace.
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
        0, json("/api/sessions").path("sessions").size(), "another workspace is another history");

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
    assertTrue(refusal.body().contains("active"), refusal.body());

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
    assertEquals(400, post("/api/workspaces", "{\"name\":\"has space\",\"path\":\"/tmp\"}").statusCode());
    assertEquals(400, post("/api/workspaces", "{\"name\":\"ws\",\"path\":\"/tmp\"}").statusCode());
    assertEquals(400, post("/api/workspaces", "{\"name\":\"ok\"}").statusCode());
    assertEquals(400, post("/api/workspace", "{\"name\":\"nope\"}").statusCode());
  }

  // ------------------------------------------------------------------ deletion and the chooser

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
    assertEquals(newer, json("/api/status").path("sessionId").asText(), "the other one stays active");
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
    assertFalse(Files.exists(sessions.resolve(active + ".jsonl")), "the file is gone");
    assertNotEquals(active, json("/api/status").path("sessionId").asText(), "somewhere to be next");
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
    assertTrue(response.body().contains("no session"), response.body());
    assertEquals(400, client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/session"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString()).statusCode());
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
    assertEquals("config", fast.path("source").asText(), "where the entry came from");
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
    assertTrue(providers.contains("myrelay"), "a defined provider must be selectable");

    // and it is usable immediately: saving it as the active provider must not be rejected
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
    assertEquals(List.of("fast", "smart"), modelsOf(relay), "a comma-separated list is understood");

    // it is selectable, and the running session can switch to it immediately
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
    assertTrue(providerStore.list().isEmpty(), "a rejected definition must not be stored");
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
    assertTrue(providerStore.list().isEmpty(), "the definition is gone");
    assertTrue(
        json("/api/status").path("configured").asBoolean(),
        "the running session keeps its provider: it was built when it was chosen");
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

    // Folding a folder must not move the active workspace, only read its contents.
    JsonNode others = json("/api/sessions?workspace=ws");
    assertEquals("ws", others.path("workspace").asText());
    assertEquals(1, others.path("sessions").size());
    assertEquals(firstId, others.path("sessions").get(0).path("id").asText());
    assertEquals(
        "other", json("/api/status").path("workspace").path("name").asText(), "still active: other");

    assertEquals(0, json("/api/sessions?workspace=other").path("sessions").size());
    assertEquals(0, json("/api/sessions").path("sessions").size(), "no parameter means the active one");
    assertEquals(400, get("/api/sessions?workspace=nope").statusCode());
  }

  @Test
  void theReasoningTierCanBeChosenChangedAndCleared() throws Exception {
    JsonNode saved = postJson("/api/config", "{\"reasoning\":\"high\"}");

    assertEquals("high", saved.path("reasoning").asText());
    assertEquals(List.of("low", "high", "max"), levels(saved), "the picker offers these");
    assertEquals("high", json("/api/config").path("reasoning").asText());

    assertEquals("max", postJson("/api/config", "{\"reasoning\":\"max\"}").path("reasoning").asText());

    JsonNode cleared = postJson("/api/config", "{\"reasoning\":\"default\"}");
    assertTrue(cleared.path("reasoning").isNull(), "default means the provider decides");
    assertTrue(json("/api/config").path("reasoning").isNull());

    HttpResponse<String> bad = post("/api/config", "{\"reasoning\":\"turbo\"}");
    assertEquals(400, bad.statusCode(), bad.body());
    assertTrue(bad.body().contains("low, high, max"), bad.body());
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
    assertEquals(activeNow, json("/api/status").path("sessionId").asText(), "still in 'other'");
    assertFalse(Files.exists(sessions.resolve(wsSession + ".jsonl")));

    // and the delete-all route takes the same parameter
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

    // The tier must survive the whole path: settings -> stored config -> the turn's options ->
    // the request a provider actually receives. Asserting the config alone would not catch a
    // dropped argument on the way.
    var requests = lastBuilt.requests();
    assertEquals("high", requests.get(requests.size() - 1).reasoning(), requests.toString());
    assertEquals(1, requests.size(), "one turn, one request");
  }

  @Test
  void aModelAddedToABuiltInProviderIsRemembered() throws Exception {
    // The exact complaint: type a model under a provider that has no definition of its own.
    JsonNode after = postJson("/api/models", "{\"provider\":\"openai\",\"model\":\"gpt-5-preview\"}");

    JsonNode openai = providerOf(after, "openai");
    assertEquals(List.of("gpt-4o-mini", "gpt-5-preview"), modelsOf(openai), "added, not replaced");
    assertTrue(after.path("models").toString().contains("gpt-5-preview"), "offered as a choice");
    assertTrue(
        Files.readString(tmp.resolve("providers.json")).contains("gpt-5-preview"),
        "and written down, so it survives a restart");

    // removing it sticks, because a recorded list is authoritative
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
        "still gone on the next read");

    // a restart of the store must not bring it back either
    assertEquals(List.of("gpt-4o-mini"), modelsOf(providerOf(json("/api/models"), "openai")));
  }

  @Test
  void modelEditsAreValidated() throws Exception {
    assertEquals(400, post("/api/models", "{\"provider\":\"nope\",\"model\":\"m\"}").statusCode());
    assertEquals(400, post("/api/models", "{\"provider\":\"openai\"}").statusCode());
    assertEquals(400, post("/api/models", "{\"model\":\"m\"}").statusCode());
    assertEquals(200, get("/api/models?provider=openai&model=x").statusCode(), "GET lists the catalogue");
    assertTrue(providerStore.modelsFor("openai").isEmpty(), "nothing recorded by the refusals");
  }

  @Test
  void theModelInUseCanStillBeRemovedFromTheOfferList() throws Exception {
    // The deadlock this replaces: the only model offered was also the one in use, so it could not
    // be removed and nothing else could be switched to.
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
        List.of(), modelsOf(providerOf(Json.parse(removed.body()), "openai")), "no longer suggested");
    assertEquals(
        "gpt-4o-mini",
        json("/api/status").path("model").asText(),
        "removing it from the list must not change what the session uses");

    // and it really is gone on the next read
    assertEquals(List.of(), modelsOf(providerOf(json("/api/models"), "openai")));
  }

  @Test
  void aBuiltInProviderCanBeDeletedAndAddedBack() throws Exception {
    assertTrue(providerNames(json("/api/models")).contains("groq"), "there to begin with");

    HttpResponse<String> deleted =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=groq")).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, deleted.statusCode(), deleted.body());
    JsonNode afterDelete = Json.parse(deleted.body());
    assertFalse(providerNames(afterDelete).contains("groq"), "gone from the list");
    assertEquals(
        List.of(), afterDelete.path("hidden").findValuesAsText("hidden"),
        "nothing is remembered as hidden — that is what 'deleted' means");
    assertTrue(availableBuiltIns(afterDelete).contains("groq"), "but it can be added again");
    assertFalse(providerNames(json("/api/models")).contains("groq"), "still gone on the next read");
    assertFalse(providerStore.shown().contains("groq"), "the explicit list no longer has it");
    assertEquals(6, providerStore.shown().size(), "the rest stayed: " + providerStore.shown());

    // Adding a built-in back is an ordinary add, not a restore.
    HttpResponse<String> added =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/providers"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"groq\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, added.statusCode(), added.body());
    assertTrue(providerNames(Json.parse(added.body())).contains("groq"));
    assertFalse(availableBuiltIns(Json.parse(added.body())).contains("groq"), "offered once");

    // Deleting twice is not an error, and neither is adding what is already there.
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
    assertTrue(providerStore.list().isEmpty(), "the definition is gone");
    assertEquals(
        List.of(),
        providerStore.shown(),
        "deleting a definition leaves the built-in list alone: there was no list to narrow");
  }

  @Test
  void deletingTheProviderInUseKeepsTheSessionRunning() throws Exception {
    client.send(
        HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=openai")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());

    // Deleting is a list decision, not a capability removal: the configured provider keeps working.
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
    assertTrue(notBuiltIn.body().contains("not a built-in"), notBuiltIn.body());
  }

  // ------------------------------------------------------------------ helpers

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
    assertEquals(200, response.statusCode(), "the event stream must open");
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

  private static JsonNode lastWhere(JsonNode array, java.util.function.Predicate<JsonNode> match) {
    for (JsonNode entry : array) {
      if (match.test(entry)) {
        return entry;
      }
    }
    throw new AssertionError("no entry matched in " + array);
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

  private static List<String> modelsOf(JsonNode provider) {
    List<String> models = new ArrayList<>();
    provider.path("models").forEach(model -> models.add(model.asText()));
    return models;
  }

  private static JsonNode lastOf(Sse sse, String type) {
    List<JsonNode> events = sse.ofType(type);
    assertFalse(events.isEmpty(), "expected at least one " + type + " event");
    return events.get(events.size() - 1);
  }

  /** Collects the SSE stream in the background so tests can await individual events. */
  /** One received event: the SSE id and its payload. */
  private record SseEvent(long id, JsonNode payload) {}

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
                  String line;
                  while ((line = in.readLine()) != null) {
                    if (line.startsWith("id: ")) {
                      id = Long.parseLong(line.substring("id: ".length()).strip());
                    } else if (line.startsWith("data: ")) {
                      events.add(new SseEvent(id, Json.parse(line.substring("data: ".length()))));
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

    /** The same as {@link #await} but with the SSE id, for tests about replay and reconnects. */
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
      throw new AssertionError("no '" + type + "' event with an id; saw " + snapshot());
    }

    List<SseEvent> raw() {
      synchronized (events) {
        return List.copyOf(events);
      }
    }

    List<String> types() {
      return raw().stream().map(event -> event.payload().path("type").asText()).toList();
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
      for (SseEvent event : raw()) {
        if (type.equals(event.payload().path("type").asText())) {
          matches.add(event.payload());
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
      return raw().stream().map(SseEvent::payload).toList();
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
    private int[] usage;

    MockProvider(String name) {
      this.name = name;
    }

    MockProvider reply(Message.Assistant assistant) {
      script.add(assistant);
      return this;
    }

    /** Reports this token accounting after every turn; cached may be null for "not reported". */
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
