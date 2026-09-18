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
  /** The stand-in vision endpoint, when a test starts one. */
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

  /** A server whose wallpaper directory is a directory of the test's own choosing. */
  private void start(String token, Wallpapers wallpapers) throws IOException {
    if (api != null) {
      api.close();
    }
    hub = hub(provider, testConfig());
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), token, wallpapers);
    origin = "http://127.0.0.1:" + api.port();
  }


  /** A registry whose default workspace is the test's working directory. */
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
   * Mirrors what the CLI injects: a factory that validates like the real one and hands back a
   * provider named after the model, so a runtime swap is observable from the outside.
   */
  private AgentHub hub(Provider initial, Config config) {
    return hub(initial, config, SessionStore.create(sessions));
  }

  /** The approvals file these tests write rules into; the chain reads it on every decision. */
  private Path approvalsFile() {
    return tmp.resolve("approvals.json");
  }

  private AgentHub hub(Provider initial, Config config, FileSession session) {
    AgentHub built = hubWithoutRules(initial, config, session);
    // Wired exactly as the CLI does it: rules for this project, in the application home.
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
    assertTrue(listed.indexOf("1.png") < listed.indexOf("2.png"), "listed in reading order: " + listed);

    HttpResponse<byte[]> image =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/wallpaper/1.png")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, image.statusCode());
    assertEquals("image/png", image.headers().firstValue("content-type").orElse(""));
    assertArrayEquals(png, image.body(), "the bytes are the file's own");

    assertEquals(404, get("/wallpaper/../config.json").statusCode(), "a traversal is not a name");
    assertEquals(404, get("/wallpaper/nope.png").statusCode(), "and nor is a file that is not there");
  }

  @Test
  void aServerWithNoWallpaperDirectoryOffersNone() throws Exception {
    // The page hides its control on an empty list, so "no directory" has to be an empty list rather
    // than an error it would then have to interpret.
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
    assertFalse(status.path("busy").asBoolean(), "nothing should be running yet");
    assertEquals(8, status.path("tools").size(), "every standard tool must be advertised");
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
  void theStreamSendsARealKeepAliveRatherThanAComment() throws Exception {
    // The bug this pins, measured on a session waiting for an approval: the keep-alive was `: ping`,
    // an SSE *comment*. A comment is delivered to nobody — EventSource dispatches only frames with a
    // data field — so the page's `lastEventAt` never moved, its 20-second "the stream is dead" timer
    // fired on a perfectly healthy connection, and the page reconnected underneath a prompt that was
    // still open. To the user that is a prompt that flickers and then disappears.
    //
    // Asserted on the frame the server actually writes, because the failure was invisible to both
    // sides: the server believed it was keeping the connection alive and the page believed the
    // connection was gone.
    try (Sse sse = watch()) {
      JsonNode ping = sse.awaitRaw("event", "ping", 25_000);
      assertNotNull(ping, "a keep-alive must arrive within the heartbeat interval");
    }
  }

  @Test
  void thePageListensForTheKeepAlive() throws Exception {
    // The other half: a named event does not reach `onmessage`, so the server's frame is delivered
    // only when the page registers a listener for it. One half without the other is the same bug.
    String app = Files.readString(Path.of("src", "main", "resources", "web", "app.js"));

    assertTrue(
        app.contains("addEventListener('ping'"),
        "the page must listen for the keep-alive the server sends");
    int listener = app.indexOf("addEventListener('ping'");
    int body = app.indexOf("lastEventAt = Date.now()", listener);
    assertTrue(
        body > listener && body - listener < 400,
        "and it must refresh the staleness clock, which is the whole point of sending it");
  }

  @Test
  void anUnansweredApprovalWaitsRatherThanExpiring() throws Exception {
    // The behaviour, pinned: a question to a person does not expire. The old 120-second cap ended the
    // turn and left the page showing a prompt that had already been withdrawn — the work abandoned
    // and the user unable to tell why.
    //
    // Waited past the old timeout on purpose. Two minutes would make the suite unusable, so the
    // assertion is that the turn is still waiting well after the *page* would have given up on a
    // silent stream (20s), which is the window in which the old design failed: a prompt that never
    // reached the browser in time. Still waiting here means the request survived it.
    provider.reply(bashCall("printf hi > waiting.txt"));
    provider.reply(Message.Assistant.text("done"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"do it\"}");
      JsonNode approval = sse.await("approval", 5000);
      assertFalse(approval.path("id").asText().isEmpty());

      Thread.sleep(21_000); // past the page's stale-stream window

      // Still pending, not answered for the user.
      JsonNode status = json("/api/status");
      assertEquals(1, status.path("approvals").size(), status.toString());
      assertFalse(Files.exists(cwd.resolve("waiting.txt")), "nothing ran while nobody had answered");

      // And the two ways out still work: answering it.
      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      sse.await("done", 5000);
      assertEquals("hi", Files.readString(cwd.resolve("waiting.txt")));
    }
  }

  @Test
  void abortingAnswersAPendingApproval() throws Exception {
    // The way out that does not depend on a timer: a turn waiting for a person used to need the
    // timeout to end it, and without one abort has to be the thing that answers.
    provider.reply(bashCall("printf hi > never.txt"));
    provider.reply(Message.Assistant.text("stopped"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"do it\"}");
      sse.await("approval", 5000);

      assertEquals(200, post("/api/abort", "{}").statusCode());

      // The turn ends rather than waiting for ever, and nothing ran.
      sse.await("done", 5000);
      assertFalse(Files.exists(cwd.resolve("never.txt")));
      assertEquals(0, json("/api/status").path("approvals").size(), "the question is withdrawn");
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
  void allowingForTheSessionStopsAskingAboutThatCommandAndNothingElse() throws Exception {
    // What "remember" means now, and the difference is the point. It used to switch the whole
    // session to auto-approve — so answering a question about one command decided every future
    // question — which is why the toggle that did that was the one nobody dared touch. The answer is
    // now as narrow as the button the user pressed: this command, for this session, and the gate
    // stays on for everything else.
    // Replies are enqueued per turn rather than all at once: the turns are different commands, and a
    // queue filled up front would hand the second turn the third turn's answer.
    provider.reply(bashCall("printf a > one.txt"));
    provider.reply(Message.Assistant.text("first"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"one\"}");
      JsonNode approval = sse.await("approval", 5000);
      post(
          "/api/approval",
          "{\"id\":\"" + approval.path("id").asText() + "\",\"answer\":\"session\"}");
      sse.await("done", 5000);
      assertFalse(hub.autoApprove(), "the question was about one command, not about every command");

      // The same command again: answered by what was remembered, so no second prompt.
      provider.reply(bashCall("printf a > one.txt"));
      provider.reply(Message.Assistant.text("same command"));
      post("/api/message", "{\"text\":\"same again\"}");
      sse.awaitAtLeast("done", 2, 5000);
      assertEquals(1, sse.ofType("approval").size(), "the same command must not ask twice");

      // A different command is a different question, and it is still asked.
      provider.reply(bashCall("printf c > three.txt"));
      provider.reply(Message.Assistant.text("different command"));
      post("/api/message", "{\"text\":\"something else\"}");
      JsonNode second = sse.awaitAtLeast("approval", 2, 5000);
      assertNotNull(second, "a command nobody allowed must still be asked about");
      assertEquals("bash", second.path("tool").asText());
      assertEquals("printf c > three.txt", second.path("command").asText(),
          "and the prompt carries the command as a field, so it can be matched by a rule");
      post("/api/approval", "{\"id\":\"" + second.path("id").asText() + "\",\"answer\":\"deny\"}");
      sse.awaitAtLeast("done", 3, 5000);
      assertFalse(Files.exists(cwd.resolve("three.txt")), "the denied command must not have run");
    }
  }

  @Test
  void alwaysAllowWritesARuleAndAProjectPicksItUp() throws Exception {
    // The "Always allow" answer is a file write, so it is the one to be careful about: what is
    // written has to be no wider than what was asked about, and it has to actually work afterwards —
    // including for a process that starts later and reads the file fresh.
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
    assertTrue(written.contains(cwd.toString()), "filed under the project it was granted in: " + written);
    assertTrue(
        written.contains("one.txt"),
        "the rule is the command that was approved, verbatim: " + written);
    assertFalse(
        written.contains("\"command\" : \"*\"") || written.contains("\"tool\" : \"bash\"\n    }"),
        "and nothing that would allow every command: " + written);

    // A fresh hub over the same file, as the next process would have: the command is not asked about.
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
      assertEquals(0, sse.ofType("approval").size(), "the rule in the file answers for it now");
      assertTrue(Files.exists(cwd.resolve("one.txt")));
    }
  }

  @Test
  void aRuleThatForbidsIsRefusedWithoutAskingAnybody() throws Exception {
    // Deny wins, and it must not turn into a prompt: the point of writing a rule is that it decides.
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
      assertEquals(0, sse.ofType("approval").size(), "the rule answers, so nobody is asked");
      assertFalse(Files.exists(cwd.resolve("one.txt")), "and the command did not run");
      assertTrue(done.toString().contains("tried"), done.toString());
    }
    // The transcript says a rule said no, rather than reporting a person's refusal: the two are
    // different events in a session nobody was watching.
    assertTrue(
        sseText().contains("denied by a rule"),
        "a rule's refusal has to read as one: " + sseText());
  }

  private String sseText() throws Exception {
    // Whatever the transcript holds for this session, as the page would have rendered it.
    return json("/api/history").toString();
  }

  @Test
  void aMessageSentWhileBusyWaitsAndThenRuns() throws Exception {
    // It used to be refused with 409, which is what made a thinking pause a dead stop: the composer
    // was disabled until the turn ended and whatever you thought of while waiting was lost. Now it is
    // queued, and it runs as a turn of its own — with its own user event and its own done.
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
          "the status says what is waiting, so the composer can show it");

      provider.release();
      sse.await("done", 5000);
      // The queued message starts on its own, and the queue is empty once it has.
      assertEquals("second", sse.await("user", 5000).path("text").asText());
      assertEquals("the queued answer", sse.awaitAtLeast("done", 2, 5000).path("finalText").asText());
      assertEquals(List.of(), queuedTexts());
    }
  }

  @Test
  void abortDropsWhatWasQueuedBehindTheTurn() throws Exception {
    // Abort is the button pressed when something is going wrong. Leaving four messages waiting to
    // start the moment the aborted turn lets go is the opposite of stopping, and the count is
    // published so a dropped message is visible rather than silent.
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
      assertTrue(notice.path("text").asText().contains("2 queued messages dropped"), notice.toString());
      assertEquals(List.of(), queuedTexts());
      assertFalse(
          sse.ofType("user").stream().anyMatch(event -> event.path("text").asText().equals("second")),
          "a dropped message must not start later");
    }
  }

  @Test
  void aQueueHasABoundAndSaysSo() throws Exception {
    // A queue with no limit is a way to lose control of a session that is already running something.
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
      assertTrue(over.body().contains("waiting"), over.body());
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
    // The whole point of the change: a turn in session b must not lock the server, or "start a task
    // in another conversation" is a sentence about nothing. A *second* turn in the *same* session is
    // still refused — one writer per conversation is what keeps a transcript from being two of them.
    // b is held at an approval, which is the realistic way a turn occupies the server for a while.
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_b", "bash", "{\"command\":\"echo b\"}"))));
    provider.reply(Message.Assistant.text("a finished"));
    provider.reply(Message.Assistant.text("b finished"));
    provider.reply(Message.Assistant.text("b's queued answer"));
    try (Sse sse = watch()) {
      assertEquals(202, post("/api/message", "{\"text\":\"b: first\"}").statusCode());
      String b = json("/api/status").path("sessionId").asText();
      assertFalse(b.isEmpty(), "the running session must be identifiable");
      JsonNode approval = sse.awaitInSession(b, "approval", 1, 5000);

      assertTrue(json("/api/status").path("busy").asBoolean(), "b is running");
      // One turn at a time per conversation is unchanged — what changed is that the second message
      // waits for it instead of being refused.
      HttpResponse<String> queuedBehindB = post("/api/message", "{\"text\":\"b: second\"}");
      assertEquals(202, queuedBehindB.statusCode(), queuedBehindB.body());
      assertTrue(Json.parse(queuedBehindB.body()).path("queued").asBoolean(), queuedBehindB.body());

      // Open another conversation and deploy a task in it, while b is still waiting for a human.
      String a = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(b, a);
      assertFalse(
          json("/api/status").path("busy").asBoolean(),
          "the session being looked at is idle even though b is running");
      assertTrue(
          json("/api/status").path("running").toString().contains(b),
          "and the status names the session that is running");

      assertEquals(
          202,
          post("/api/message", "{\"text\":\"a: hello\"}").statusCode(),
          "a turn in another session must be accepted");
      assertEquals("a finished", sse.awaitInSession(a, "done", 1, 5000).path("finalText").asText());

      // b was never disturbed: its approval is still pending, and answering it finishes b.
      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      // Both of b's turns, in order: the one that was waiting for a human, then the message that was
      // queued behind it. Read as a list rather than twice through `awaitInSession`, which answers
      // with the newest event that matches and would race the queued turn's own `done`.
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
    // One stream carries every conversation, so an event that does not name its session is an event
    // one page would render into another conversation's transcript.
    provider.reply(Message.Assistant.text("ok"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      sse.await("done", 5000);
      assertFalse(
          sse.anyEventWithoutSession(),
          "every event must carry a sessionId: " + sse.snapshot());
      assertEquals(1, sse.ofType("user").size(), sse.snapshot().toString());
      assertFalse(sse.ofType("user").get(0).path("sessionId").asText().isEmpty());
    }
  }

  @Test
  void anApprovalInAnotherSessionIsVisibleAndAnswerableFromHere() throws Exception {
    // A background turn that needs a human must not wait forever because the user is looking at
    // another conversation: the request is published with its session, and answering it works from
    // anywhere.
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
      assertEquals(b, approval.path("sessionId").asText(), "the request names its session");
      assertEquals("bash", approval.path("title").asText());

      post(
          "/api/approval",
          "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      assertEquals("ran it", sse.awaitInSession(b, "done", 1, 5000).path("finalText").asText());
    }
  }

  @Test
  void whatTouchesOneConversationIsRefusedOnlyWhileThatOneRuns() throws Exception {
    // Which operations care about concurrency is a decision, not a habit. Showing a conversation
    // changes nothing and is always allowed — including the one that is working, which is the whole
    // point of leaving a turn running. Operations that would pull the ground out from under a turn —
    // deleting its file, changing the model — wait for it.
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"b: long job\"}");
      String b = json("/api/status").path("sessionId").asText();
      JsonNode approval = sse.awaitInSession(b, "approval", 1, 5000);

      // Looking at the busy conversation is fine, and so is looking at it repeatedly.
      assertEquals(
          200,
          post("/api/session", "{\"action\":\"resume\",\"id\":\"" + b + "\"}").statusCode(),
          "the running conversation can be displayed");
      // Its file cannot be deleted, though: that would pull the transcript out from under the turn.
      assertEquals(
          409,
          client
              .send(
                  HttpRequest.newBuilder(URI.create(origin + "/api/session?id=" + b))
                      .DELETE()
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode(),
          "nor deleted while it is being written");

      // The settings are the ground under every conversation, so they stay refused.
      assertEquals(
          409,
          post("/api/config", "{\"model\":\"other-model\"}").statusCode(),
          "changing the model under a running turn is what has to wait");

      // Switching *away* from it is how the user keeps working, so that is allowed.
      String a = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(b, a, "a new conversation can be started beside a running one");

      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      sse.awaitInSession(b, "done", 1, 5000);
    }
  }

  @Test
  void anAbortStopsTheSessionOnScreenAndLeavesTheOtherAlone() throws Exception {
    // Abort is per conversation: stopping the job you are looking at must not stop the other one.
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

      // The session on screen is a, and a is not running: there is nothing to abort here.
      assertFalse(
          postJson("/api/abort", "{}").path("aborted").asBoolean(),
          "abort acts on the conversation on screen, which is idle");

      JsonNode aborted = postJson("/api/abort?id=" + b, "{}");
      assertTrue(aborted.path("aborted").asBoolean(), "the running session can still be stopped");
      JsonNode stopped = sse.awaitInSession(b, "done", 1, 5000);
      assertTrue(stopped.path("aborted").asBoolean(), "and it ends as aborted: " + stopped);
    }
  }

  @Test
  void theSidebarCanTellWhichSessionsAreRunning() throws Exception {
    // Without this the page cannot mark the row the user started and switched away from.
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
          "switching away does not stop it");

      postJson("/api/abort?id=" + b, "{}");
      sse.awaitInSession(b, "done", 1, 5000);
      assertFalse(
          json("/api/sessions").path("sessions").get(0).path("running").asBoolean(),
          "a finished turn stops being running");
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
        "the sidebar labels a session by what was asked, not by its timestamp id");
    assertEquals(first, list.get(0).path("id").asText());

    JsonNode created = postJson("/api/session", "{\"action\":\"new\"}");
    assertNotEquals(first, created.path("sessionId").asText());

    JsonNode resumed = postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + first + "\"}");
    assertEquals(first, resumed.path("sessionId").asText());
    assertEquals(2, resumed.path("messageCount").asInt(), "history must be replayed from disk");
  }

  @Test
  void aRunningConversationCanStillBeOpened() throws Exception {
    // Reported bug: with a turn running in a, clicking a in the sidebar bounced. Switching which
    // conversation is *displayed* changes nothing about the turn that is running — the point of
    // leaving a job running is being able to look at it.
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"a: long job\"}");
      String a = json("/api/status").path("sessionId").asText();
      sse.awaitInSession(a, "approval", 1, 5000);

      // Look away…
      String b = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(a, b);
      // …and back at the one that is working.
      JsonNode back = postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + a + "\"}");
      assertEquals(a, back.path("sessionId").asText(), "the running session is the one to look at");
      assertTrue(back.path("busy").asBoolean(), "and it is still shown as running");

      postJson("/api/abort?id=" + a, "{}");
      sse.awaitInSession(a, "done", 1, 5000);
      // The turn's result landed in a's own file, once: opening a again must not have started a
      // second writer on it.
      List<String> lines = Files.readAllLines(sessions.resolve(a + ".jsonl"));
      assertTrue(lines.stream().anyMatch(line -> line.contains("tool_result")), lines.toString());
    }
  }

  @Test
  void openingARunningSessionAgainDoesNotOpenASecondWriter() throws Exception {
    // The reason the old check existed. It has to be answered by reusing the file the turn is
    // writing, not by refusing to show the conversation: two FileSessions appending to one JSONL is
    // exactly the corruption the per-session rule is for.
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("a finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"a: long job\"}");
      String a = json("/api/status").path("sessionId").asText();
      sse.awaitInSession(a, "approval", 1, 5000);

      // A second conversation that exists on disk, to switch to and away from.
      String b = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(a, b);
      assertEquals(202, post("/api/message", "{\"text\":\"b: hello\"}").statusCode());
      sse.awaitInSession(b, "done", 1, 5000);

      // Going back and forth is what a user does while waiting, and each return to a must be the
      // same file its turn is using rather than a second writer on it.
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
      // Every line is still one whole JSON object: a second writer would have interleaved bytes.
      for (String line : Files.readAllLines(sessions.resolve(a + ".jsonl"))) {
        assertFalse(line.isBlank(), "no torn lines");
        Json.parse(line);
      }
    }
  }

  @Test
  void anotherWorkspacesConversationCanBeOpenedWhileATurnRuns() throws Exception {
    // Reported bug: a turn in one workspace blocked opening a conversation in another. A turn owns
    // its working directory and its own session file from the moment it starts, so looking at a
    // different workspace while it runs cannot disturb it.
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

      // Registering the workspace is a registry entry, and it must be possible while a is running —
      // otherwise the second workspace is unreachable exactly when the user wants to go there.
      postJson("/api/workspaces", "{\"name\":\"other\",\"path\":\"" + other + "\"}");

      // Switch to it: a change of namespace, not of what is running.
      JsonNode switched = postJson("/api/workspace", "{\"name\":\"other\"}");
      assertEquals("other", switched.path("workspace").path("name").asText());
      String c = switched.path("sessionId").asText();
      assertNotEquals(a, c, "a fresh session there");

      // …and it is usable while the first workspace's turn keeps running.
      assertEquals(202, post("/api/message", "{\"text\":\"c: in the other workspace\"}").statusCode());
      assertEquals("c finished", sse.awaitInSession(c, "done", 1, 5000).path("finalText").asText());
      assertTrue(
          json("/api/status").path("running").toString().contains(a),
          "the turn in the first workspace is untouched: " + json("/api/status"));

      // The turn in a still runs in the directory it started in, not in the one now on screen: the
      // command writes a file, and the file has to land in the first workspace.
      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      assertEquals("a finished", sse.awaitInSession(a, "done", 1, 5000).path("finalText").asText());
      assertTrue(
          Files.exists(cwd.resolve("made-by-a.txt")),
          "a's tool ran in a's own workspace, which is where it was started: " + cwd);
      assertFalse(
          Files.exists(other.resolve("made-by-a.txt")),
          "and not in the workspace that is merely on screen now");
    }
  }


  @Test
  void eachConversationsOwnRulesFollowItsWorkingDirectory() throws Exception {
    // The rules come from the directory the conversation's tools run in, which in the web UI is a
    // property of the *session*, not of the server: a page can hold one conversation in a project and
    // another in a sibling, and each request must carry its own project's rules.
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
          "and not the other workspace's rule: " + secondPrompt);
    }
  }

  /** The system prompt of the nth request the mock provider received. */
  private String lastSystemPrompt(int index) {
    List<Provider.Request> seen = provider.requests();
    assertTrue(seen.size() > index, "only " + seen.size() + " requests so far");
    String system = seen.get(index).system();
    assertTrue(system != null, "every request must carry a system prompt");
    return system;
  }

  @Test
  void aReplayedConversationKeepsItsReasoning() throws Exception {
    // Reported bug: switch away from a conversation while it is thinking, switch back, and the
    // reasoning is gone — because historyJson replayed only prose and tool calls. The reasoning is in
    // the session file, so a replay that drops it is a replay of a different conversation.
    provider.reply(Message.Assistant.text("answered"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"think about it\"}");
      sse.await("done", 5000);
      String session = json("/api/status").path("sessionId").asText();

      // The reasoning arrives on the stream and is persisted with the assistant turn.
      provider.emitReasoning("considering the problem");
      post("/api/message", "{\"text\":\"and again\"}");
      sse.awaitAtLeast("done", 2, 5000);
      String file = Files.readString(sessions.resolve(session + ".jsonl"));
      assertTrue(file.contains("considering the problem"), "the reasoning is on disk:\n" + file);

      // So replaying that conversation must show it again.
      JsonNode history = json("/api/history");
      assertEquals(session, history.path("sessionId").asText());
      List<String> reasoning = new ArrayList<>();
      history.path("events").forEach(event -> {
        if ("reasoning".equals(event.path("type").asText())) {
          reasoning.add(event.path("delta").asText());
        }
      });
      assertEquals(List.of("considering the problem"), reasoning,
          "a replay must carry the reasoning, or switching away and back loses it: "
              + history.path("events"));
    }
  }

  @Test
  void aRedactedReasoningBlockIsNotReplayedAsText() throws Exception {
    // A redacted block's payload is opaque and must go back to the *model* unchanged; showing it as
    // prose would put a wall of base64 in the transcript, which is worse than showing nothing.
    provider.reply(Message.Assistant.text("done"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"go\"}");
      sse.await("done", 5000);
      String session = json("/api/status").path("sessionId").asText();
      // Write one by hand: only Anthropic produces these, and this test is about the projection.
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
              "the opaque payload must not be rendered as prose: " + event));
    }
  }

  @Test
  void aPendingApprovalSurvivesLeavingAndComingBack() throws Exception {
    // Reported bug: a turn waiting for approval lost its prompt as soon as the user looked at
    // another conversation, leaving only abort. The approval is a request blocked in memory, not a
    // message, so replaying the history does not bring it back — it has to be visible in the status
    // of the conversation that is waiting, and the page has to render it again on the way in.
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("ran it"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"a: run it\"}");
      String a = json("/api/status").path("sessionId").asText();
      JsonNode approval = sse.awaitInSession(a, "approval", 1, 5000);
      String approvalId = approval.path("id").asText();

      // Looking away and back: the request is still outstanding, so the page must be told about it.
      String b = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(a, b);
      JsonNode back = postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + a + "\"}");
      assertEquals(a, back.path("sessionId").asText());

      // The status of the conversation that is waiting names the pending request, with everything the
      // prompt needs to be drawn again.
      JsonNode pending = back.path("approvals");
      assertTrue(pending.isArray(), back.toString());
      assertEquals(1, pending.size(), "the outstanding request is reported: " + back);
      assertEquals(approvalId, pending.get(0).path("id").asText());
      assertEquals("bash", pending.get(0).path("title").asText());
      assertTrue(pending.get(0).path("detail").asText().contains("echo hi"), pending.toString());

      // And answering it still works from the other conversation.
      post("/api/approval", "{\"id\":\"" + approvalId + "\",\"allow\":true}");
      assertEquals("ran it", sse.awaitInSession(a, "done", 1, 5000).path("finalText").asText());
      assertEquals(
          0,
          json("/api/status").path("approvals").size(),
          "a resolved request is no longer pending");
    }
  }

  @Test
  void anApprovalBelongsToOneConversationOnly() throws Exception {
    // Two turns can be waiting at once. Each page must be offered only its own conversation's
    // requests, or answering the one on screen would resolve the other's.
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("a ran it"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"a: run it\"}");
      String a = json("/api/status").path("sessionId").asText();
      JsonNode approval = sse.awaitInSession(a, "approval", 1, 5000);

      // A fresh conversation has no pending request of its own.
      String b = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertEquals(
          0,
          json("/api/status").path("approvals").size(),
          "b has nothing pending; a's request is not b's: " + json("/api/status"));
      // a is still waiting, and still says so.
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

    // The token is for reaching this server from somewhere else, and a request that names a host
    // other than loopback is that case even when it was made on this machine — which is the half that
    // answers a page whose own domain resolves to 127.0.0.1.
    String denied = rawResponse("rebind.example", "/api/status");
    assertTrue(denied.startsWith("HTTP/1.1 401"), denied);
    assertTrue(denied.contains("token"), denied);

    String refused = rawResponse("rebind.example", "/api/status?token=wrong");
    assertTrue(refused.startsWith("HTTP/1.1 401"), refused);

    // This machine is not "somewhere else": reaching the page at 127.0.0.1 is how the user at the
    // keyboard uses ccj, and a secret to type there would be a password on their own command line.
    assertEquals(200, get("/api/status").statusCode(), "the machine itself is let in");

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
            "{\"provider\":\"anthropic\",\"model\":\"claude-test\",\"baseUrl\":\"http://relay.invalid\",\"apiKey\":\"sk-written\",\"maxTokens\":900}");

    assertEquals("claude-test", saved.path("model").asText());
    assertEquals("anthropic", saved.path("provider").asText());
    assertTrue(saved.path("configured").asBoolean());
    assertEquals("claude-test", lastBuilt.name(), "the new provider must be the one in use");

    JsonNode file = Json.parse(Files.readString(configFile));
    assertEquals("anthropic", file.path("provider").asText());
    assertEquals("claude-test", file.path("model").asText());
    assertEquals("sk-written", file.path("apiKey").asText());
    assertEquals(900, file.path("maxTokens").asInt());
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
  void aHandWrittenConfigCanBeSavedFromTheForm() throws Exception {
    // Reported defect: a configuration file somebody typed by hand — an endpoint and a key, no
    // `settingsFor` mark, which is what a hand-written file is — could not be saved from the settings
    // form at all. The form always posts its key-variable field, pre-filled with the provider's
    // default, and that was read as "this change names its own credential": the unmarked pair in the
    // file was dropped and the provider build then failed with `no API key for provider 'openai'`.
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
        "the key that was already there must survive a save that did not replace it");
  }

  @Test
  void namingANonDefaultKeyVariableStillCountsAsNamingACredential() throws Exception {
    // The other half of the same rule: a form save that does say where the key comes from is still a
    // deliberate act, and the pair it replaces is dropped rather than inherited.
    Files.writeString(
        configFile,
        "{\"provider\":\"openai\",\"model\":\"hand-written\",\"apiKey\":\"sk-hand-written\"}");
    restartFromConfigFile();

    postJson("/api/config", "{\"provider\":\"openai\",\"model\":\"m\","
        + "\"apiKeyEnv\":\"MY_OWN_KEY_VARIABLE\"}");

    assertEquals("MY_OWN_KEY_VARIABLE", Config.fromFile(configFile).apiKeyEnv());
    assertNull(
        Config.fromFile(configFile).apiKey(),
        "a save that names its own credential does not inherit the one that was there");
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
    assertEquals("ws", added.path("active").asText(), "picking a folder does not switch");
  }

  @Test
  void pickingTheSameDirectoryTwiceIsRefusedWithTheWorkspaceThatOwnsIt() throws Exception {
    Path project = Files.createDirectories(tmp.resolve("twice"));

    assertEquals("twice", postJson("/api/workspaces", "{\"path\":\"" + project + "\"}").path("workspaces").get(1).path("name").asText());

    // Same directory, different spelling: normalising is what makes this a refusal and not a second
    // entry with a competitor's session history.
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
    assertTrue(noPath.body().contains("directory"), noPath.body());

    // A folder named "my project" is added, not refused — but one whose name cannot be a single path
    // segment (a leading dash would read as a flag) comes back with the reason.
    Path spaced = Files.createDirectories(tmp.resolve("my project"));
    assertEquals(
        "my project",
        postJson("/api/workspaces", "{\"path\":\"" + spaced + "\"}")
            .path("workspaces").get(1).path("name").asText());

    Path odd = Files.createDirectories(tmp.resolve("-dashed"));
    HttpResponse<String> oddName = post("/api/workspaces", "{\"path\":\"" + odd + "\"}");
    assertEquals(400, oddName.statusCode(), oddName.body());
    assertTrue(oddName.body().contains("path separator"), oddName.body());
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

  // ------------------------------------------------------------------ compaction

  @Test
  void compactingReplacesTheOlderTurnsWithASummaryAndKeepsTheFile() throws Exception {
    // A conversation long enough to compact: 8 exchanges, so the newest 5 are kept.
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

    // What the model is asked for the summary is a question about the transcript, not a turn.
    provider.reply(Message.Assistant.text("Goal: answer questions. Files: none. Open: nothing."));
    JsonNode result = postJson("/api/compact", "{}");

    assertTrue(result.path("compacted").asBoolean(), result.toString());
    assertEquals(6, result.path("summarised").asInt(), "three exchanges of two messages are replaced");
    assertEquals(10, result.path("kept").asInt());
    assertTrue(result.path("afterTokens").asInt() < result.path("beforeTokens").asInt(), result.toString());
    assertEquals(1, result.path("generation").asInt());

    // The generation file holds the summary plus the kept tail; the original is byte-for-byte intact,
    // which is the whole reason a compaction is safe to attempt.
    Path generation = sessions.resolve(id + ".g1.jsonl");
    assertTrue(Files.isRegularFile(generation), "the new generation is on disk");
    assertEquals(originalBytes, Files.readString(original), "the generation it replaced is untouched");
    List<Message> compacted = FileSession.readAll(generation);
    assertTrue(compacted.get(0) instanceof Message.Summary, compacted.get(0).toString());

    // The summarising request is not a turn: no user message was appended and no step was counted.
    var requests = provider.requests();
    var summariseRequest = requests.get(requests.size() - 1);
    assertNull(summariseRequest.system(), "the summary request carries no system prompt");
    assertTrue(summariseRequest.tools().isEmpty(), "and no tools to go and do work with");
    assertEquals(1, requests.get(requests.size() - 1).messages().size(), "one message: the transcript");
    assertTrue(
        ((Message.User) summariseRequest.messages().get(0)).text().contains(Compaction.INSTRUCTIONS),
        "the instruction is what turns a transcript into a summary");
    assertEquals(stepsBefore, json("/api/status").path("usage").path("steps").asInt(), "not a step");

    // But it is counted, and separately: it cost tokens and folding it into steps would make that
    // number mean two things.
    assertEquals(1, json("/api/status").path("usage").path("compactions").asInt());

    // And the session carries on: the next turn is answered, from the compacted conversation.
    provider.reply(Message.Assistant.text("continuing"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"what next?\"}");
      sse.await("done", 5000);
    }
    var nextTurn = provider.requests().get(provider.requests().size() - 1);
    assertTrue(
        nextTurn.messages().stream().anyMatch(m -> m instanceof Message.Summary),
        "the summary is what the next request carries instead of the old turns: " + nextTurn.messages());
  }

  @Test
  void aCompactionIsRefusedWhileATurnIsRunning() throws Exception {
    // Two writers on one transcript is the exception the per-conversation turn flag exists to prevent,
    // and a compaction rewrites what the conversation is.
    CountDownLatch gate = new CountDownLatch(1);
    provider.reply(Message.Assistant.text("slow"));
    provider.gate(gate);
    post("/api/message", "{\"text\":\"start a turn\"}");

    HttpResponse<String> refusal = post("/api/compact", "{}");
    assertEquals(409, refusal.statusCode(), refusal.body());
    assertTrue(refusal.body().contains("turn is still running"), refusal.body());

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
    assertTrue(refusal.body().contains("nothing to compact"), refusal.body());
    assertEquals(callsBefore, provider.requests().size(), "the model is not asked to summarise nothing");
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
    assertTrue(refusal.body().contains("empty summary"), refusal.body());
    // Nothing was written, so the session is still its generation 0 and still complete.
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
  void switchingProviderKeepsTheEndpointAndKeyUnderTheProviderItIsLeaving() throws Exception {
    // What the composer picker posts: provider and model, nothing else. The stored baseUrl and key
    // belong to the provider being left, so using them for the new one is how a session that says
    // "myrelay" ends up sending its traffic to the previous provider's address with the previous
    // provider's key — on the previous provider's bill. They are kept under the name they belong to
    // instead of being left in the active pair, which is what lets switching back be free.
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
        "nothing of the old pair may stay in the active fields: " + stored);
    assertFalse(
        stored.path("baseUrl").asText("").contains("previous.example.com"),
        "the endpoint being left must not stay in the active pair either: " + stored);
    assertEquals(
        "https://relay.example.com/v1",
        switched.path("baseUrl").asText(),
        "the endpoint reported must be the one the request will use, which is the definition's");
    assertEquals(
        "none",
        json("/api/config").path("apiKeySource").asText(),
        "the form must not report a key that would not be sent");
    assertEquals(
        "sk-previous",
        stored.path("remembered").path("openai").path("apiKey").asText(),
        "it is kept under the provider it was entered for: " + stored);
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
        "every provider with a saved key — openai's is remembered, myrelay's is in effect right now."
            + " Names only, never a key");
    assertEquals("config", cfg.path("apiKeySource").asText());

    // Back to openai: its own endpoint and key come back without pasting anything.
    JsonNode back = postJson("/api/config", "{\"provider\":\"openai\",\"model\":\"gpt-x\"}");
    assertEquals("openai", back.path("provider").asText());
    JsonNode stored = Json.parse(Files.readString(configFile));
    assertEquals("sk-openai", stored.path("apiKey").asText(), "back on its own key: " + stored);
    assertFalse(
        stored.path("baseUrl").asText("").contains("relay.example.com"),
        "and not on the endpoint it was switched away from: " + stored);
    assertEquals(
        "sk-relay",
        stored.path("remembered").path("myrelay").path("apiKey").asText(),
        "and myrelay's key waits for the way back: " + stored);
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
        stored.path("remembered").has("myrelay"), "a forgotten key must not come back: " + stored);
    assertEquals(
        "sk-openai",
        stored.path("remembered").path("openai").path("apiKey").asText(),
        "the other provider's key is none of this one's business: " + stored);
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
        "the file records whose endpoint and key these are");

    // A later save that says nothing about the key keeps it: it is this provider's.
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
  void theThinkingLanguageIsOfferedSavedAndPutInThePrompt() throws Exception {
    JsonNode fresh = json("/api/config");
    assertEquals("auto", fresh.path("language").asText(), "no choice means the prompt says nothing");
    List<String> offered = languages(fresh);
    assertTrue(offered.contains("Simplified Chinese"), offered.toString());
    assertEquals(offered.get(0), "Simplified Chinese", "the list is in the order the form shows it");

    // POST answers with the status payload, so the form's own value is read back from GET — which is
    // also the round trip the setting has to survive.
    postJson("/api/config", "{\"language\":\"Simplified Chinese\"}");
    assertEquals("Simplified Chinese", json("/api/config").path("language").asText());

    // The point of the setting: the prompt the model is handed asks for it, by name, including the
    // thinking stream. Checked on the request the loop actually sent.
    provider.reply(Message.Assistant.text("好的"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"say hi\"}");
      sse.await("done", 5000);
    }
    // The prompt names the language the way it names itself — 简体中文, not "Simplified Chinese" — so
    // the sentence reads as an instruction about a language rather than a label from a form.
    var sent = lastBuilt.requests().get(lastBuilt.requests().size() - 1);
    assertTrue(sent.system().contains("think in 简体中文"), sent.system());
    assertTrue(
        sent.system().contains("always reason and reply in 简体中文"),
        "the answer is asked for in the same sentence as the thinking: " + sent.system());

    // Clearing it back to auto removes the sentence rather than leaving a stale one behind.
    postJson("/api/config", "{\"language\":\"auto\"}");
    assertEquals("auto", json("/api/config").path("language").asText());
    assertFalse(
        com.ccj.agent.core.Prompts.DEFAULT_SYSTEM.contains("always reason and reply"),
        "auto adds nothing to the prompt");
  }

  @Test
  void aLanguageTheBuildDoesNotListIsStillKept() throws Exception {
    // The list is a convenience, not a gate: a model can follow a name this build never heard of, and
    // refusing one would be the form deciding what somebody is allowed to think in.
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

  @Test
  void aNewDefinitionJoinsAnExplicitListInsteadOfBeingInvisible() throws Exception {
    // The reported bug: the user had narrowed the list, then defined a provider, and it never
    // appeared — the definition was saved but the list did not mention it.
    client.send(
        HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=groq")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
    assertFalse(providerNames(json("/api/models")).contains("groq"), "narrowed to begin with");

    JsonNode added =
        postJson(
            "/api/providers",
            "{\"name\":\"myrelay\",\"kind\":\"openai\",\"baseUrl\":\"http://127.0.0.1:9/v1\",\"models\":\"m1\"}");

    assertTrue(providerNames(added).contains("myrelay"), "saved and listed: " + added);
    assertTrue(providerStore.shown().contains("myrelay"), "and in the explicit list");
    assertTrue(providerNames(json("/api/models")).contains("myrelay"), "still there on the next read");
  }

  @Test
  void deletingADefinitionAlsoLeavesTheExplicitList() throws Exception {
    // Narrow the list first, so "the list" is a real thing rather than the implicit everything.
    client.send(
        HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=groq")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
    postJson(
        "/api/providers",
        "{\"name\":\"mine\",\"kind\":\"openai\",\"baseUrl\":\"http://127.0.0.1:9/v1\",\"models\":\"m\"}");
    assertTrue(providerStore.shown().contains("mine"), "a definition joins the list: " + providerStore.shown());

    client.send(
        HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=mine")).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());

    assertFalse(providerStore.shown().contains("mine"), "a deleted name must not linger");
    assertFalse(
        providerNames(json("/api/models")).contains("mine"),
        "and must not come back as a phantom built-in");
  }

  @Test
  void removingTheLastProviderLeavesTheListEmptyInsteadOfRestoringThemAll() throws Exception {
    // The bug: the store could not tell "never narrowed" from "I removed everything", so deleting
    // the final provider read as "no opinion" and the entire catalogue reappeared.
    for (String name : providerNames(json("/api/models"))) {
      assertEquals(
          200,
          client.send(
                  HttpRequest.newBuilder(URI.create(origin + "/api/providers?name=" + name))
                      .DELETE()
                      .build(),
              HttpResponse.BodyHandlers.ofString())
              .statusCode(),
          "deleting " + name);
    }

    JsonNode emptied = json("/api/models");
    assertTrue(providerNames(emptied).isEmpty(), "the list stays empty: " + emptied);
    assertFalse(emptied.path("builtIns").isEmpty(), "and every built-in is still offered as a way back");

    // Nothing is remembered as hidden: adding one back is an ordinary add.
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
    // A tokenless server is reachable from any page the user visits; the Host header is what keeps
    // a name that resolves to 127.0.0.1 (DNS rebinding) from being treated as this server.
    assertTrue(
        rawResponse("rebind.example", "/api/status").startsWith("HTTP/1.1 403"),
        "a name that is not loopback is not this server");
    assertTrue(
        rawResponse("127.0.0.1:" + api.port(), "/api/status").startsWith("HTTP/1.1 200"),
        "and the loopback literal is");
  }

  /**
   * A raw request, because {@code HttpClient} will not let a test choose the {@code Host} header — and
   * that header is the thing under test in three places here.
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
    // What "just run ccj" means: the machine itself reaches the page at 127.0.0.1 with nothing
    // attached, and the same server asks the phone for the token — over the same process, the same
    // hub and the same conversation. A test needs a second address to be one, so a machine whose only
    // address is loopback skips this rather than pretending.
    Optional<InetAddress> elsewhere = anAddressOtherThanLoopback();
    assumeTrue(elsewhere.isPresent(), "this machine has no address other than loopback");

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
    assertFalse(urls.get(0).contains("token"), "the machine itself is not asked for a secret");
    assertTrue(urls.get(1).contains("?token=s3cret"), urls.toString());

    assertEquals(200, plainGet("http://127.0.0.1:" + api.port() + "/api/status"), "loopback");
    assertEquals(
        401,
        plainGet("http://" + elsewhere.get().getHostAddress() + ":" + api.port() + "/api/status"),
        "the network address without the token");
    assertEquals(
        200,
        plainGet(
            "http://"
                + elsewhere.get().getHostAddress()
                + ":"
                + api.port()
                + "/api/status?token=s3cret"),
        "and with it");
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
   * An address this machine has that is not loopback, or empty when it has none.
   *
   * <p>Deliberately not a hard-coded one: the point of the test is that a real second address gets a
   * different answer from the server, and which address that is depends on the machine.
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
    assertTrue(response.body().contains("larger than"), response.body());
  }

  @Test
  void usageReportsAContextEstimate() throws Exception {
    JsonNode usage = json("/api/status").path("usage");
    assertTrue(usage.has("contextTokens"), usage.toString());
    assertTrue(usage.path("contextLimit").asInt() >= 0, usage.toString());
    // What a session spent is a token count, not a price: ccj does not carry a
    // rate table, so a money field would be a number the user cannot check.
    assertFalse(usage.has("costUsd"), usage.toString());
    assertFalse(usage.has("priceAsOf"), usage.toString());
  }

  @Test
  void undoTakesBackWhatTheLastTurnChanged() throws Exception {
    // The question people ask before letting an agent near their files is "may it be undone", and it
    // is answered by a turn's writes being recoverable — not by the approval prompt, which only ever
    // answered "may it run".
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
      assertTrue(Files.readString(file).contains("int a = 2;"), "the edit happened");
      assertEquals(
          "class Notes implements Cloneable {\n  int a = 2;\n}\n",
          Files.readString(file),
          "both hunks, each where it belongs");

      undone = postJson("/api/undo", "{}");

      // A notice rather than history: undo is about the files, not about the conversation, and the
      // transcript says what was put back where a reader is already looking.
      JsonNode notice = sse.await("notice", 5000);
      assertTrue(notice.path("text").asText().startsWith("undone: 1 file"), notice.toString());
      assertTrue(notice.path("text").asText().contains("Notes.java"), notice.toString());
    }

    assertEquals(1, undone.path("restored").asInt(), undone.toString());
    assertEquals(
        "class Notes {\n  int a = 1;\n}\n",
        Files.readString(file),
        "the file is what the turn found");
    assertEquals(0, undone.path("remaining").asInt(), "that turn is spent");
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

    assertFalse(Files.exists(cwd.resolve("Brand.java")), "a created file is deleted, not emptied");
  }

  @Test
  void undoIsRefusedWhileATurnIsRunning() throws Exception {
    // Undoing underneath a running turn would restore files the model is in the middle of reasoning
    // about, which is a worse state than either.
    provider.reply(Message.Assistant.text("slow"));
    provider.gate(new CountDownLatch(1));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"think\"}");

      HttpResponse<String> refused = post("/api/undo", "{}");

      assertEquals(409, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("abort it before undoing"), refused.body());
      provider.release();
      sse.await("done", 5000);
    }
  }

  @Test
  void aConversationOverItsBudgetIsCompactedBetweenTurns() throws Exception {
    // Past `maxContextTokens` the projection starts eliding tool output and dropping whole exchanges,
    // and its notices say so ("context: 213 → 26 tokens, 5 earlier exchange(s) dropped") without ever
    // saying what went. A summary that names what it replaced is the better loss, and it happens
    // between turns — never inside one, where the model is mid-thought about a history that would
    // change under it.
    startWithBudget(50);
    // Twelve exchanges: a compaction keeps the newest five and summarises the rest, so the part it
    // replaces has to be larger than the summary prompt itself — measured, one summarised exchange
    // (52 tokens) against a summary of 74, which is refused with "nothing to gain".
    for (int i = 0; i < 12; i++) {
      provider.reply(Message.Assistant.text("answer " + i));
    }
    // The seventh call is the summarising one, which asks for no tools and a single message.
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

    // The summarising call is recognisable in the recorded requests: no tools, one message — the
    // compaction prompt carries the transcript itself.
    assertTrue(
        provider.requests().stream()
            .anyMatch(request -> request.tools().isEmpty() && request.messages().size() == 1),
        "a summarising call should have been made: " + notices);
    assertTrue(
        conversationWasCompacted(),
        "the session file should have a second generation; notices: " + notices);
    assertTrue(
        notices.stream().anyMatch(text -> text.startsWith("compacted automatically")),
        "and the transcript should say it happened: " + notices);
  }

  private boolean conversationWasCompacted() throws Exception {
    String id = json("/api/status").path("sessionId").asText();
    try (var files = java.nio.file.Files.list(sessions)) {
      return files.anyMatch(path -> path.getFileName().toString().startsWith(id + ".g"));
    }
  }

  /** The hub again, with a prompt budget small enough that a few turns pass it. */
  private void startWithBudget(int maxContextTokens) throws IOException {
    api.close();
    hub.close();
    // The twelve-argument shape ends in maxContextTokens, which is the only field this test sets.
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
    // A page reacts to `done` by letting the user send again. If the server were still busy at that
    // moment, the next message would be refused with 409 — and the composer would stay disabled
    // until something else happened to publish a status. So the status a client sees immediately
    // before `done` must already say the turn is over.
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
    assertTrue(done > 0, "the turn must finish: " + seen.stream().map(AgentHub.Event::type).toList());
    AgentHub.Event before = seen.get(done - 1);
    assertEquals("status", before.type(), "the state is settled before the turn says it is");
    assertFalse(before.payload().path("busy").asBoolean(true), before.payload().toString());
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

  /** A POST carrying an Origin, the way a browser page sends one. */
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

  /** Where a request claims to have come from. */
  private record HttpExchangeOrigin(String value) {
    static final HttpExchangeOrigin EVIL = new HttpExchangeOrigin("https://evil.example");
    static final HttpExchangeOrigin SELF = new HttpExchangeOrigin("http://127.0.0.1:8080");
    static final HttpExchangeOrigin LOCALHOST = new HttpExchangeOrigin("http://localhost:3000");
    static final HttpExchangeOrigin OPAQUE = new HttpExchangeOrigin("null");
  }

  @Test
  void aCrossOriginRequestCannotChangeState() throws Exception {
    // The attack this closes, reproduced before the check existed: a page on another site POSTed to
    // /api/auto-approve, the loopback and Host checks both passed — the browser runs on this machine,
    // so its connection *is* loopback and its Host *is* 127.0.0.1 — and auto-approval really did
    // switch on. After that the agent stops asking before it runs commands.
    assertFalse(json("/api/status").path("autoApprove").asBoolean());

    HttpResponse<String> refused =
        postFrom(HttpExchangeOrigin.EVIL, "/api/auto-approve", "{\"enabled\":true}");

    assertEquals(403, refused.statusCode(), refused.body());
    assertTrue(refused.body().contains("cross-origin"), refused.body());
    assertFalse(
        json("/api/status").path("autoApprove").asBoolean(),
        "the guard is still on: the page changed nothing");
  }

  @Test
  void theSameOriginTheServerItselfServesIsAccepted() throws Exception {
    // The page ccj serves has to keep working: it sends Origin on its own POSTs.
    assertEquals(
        200, postFrom(HttpExchangeOrigin.SELF, "/api/auto-approve", "{\"enabled\":true}").statusCode());
    assertTrue(json("/api/status").path("autoApprove").asBoolean());
    postFrom(HttpExchangeOrigin.SELF, "/api/auto-approve", "{\"enabled\":false}");
  }

  @Test
  void everyLoopbackSpellingIsAcceptedAsSelf() throws Exception {
    // A user who opened localhost and a server on 127.0.0.1 are the same person on the same machine;
    // refusing one of them would be a bug that reads as a security feature.
    assertEquals(
        200,
        postFrom(HttpExchangeOrigin.LOCALHOST, "/api/auto-approve", "{\"enabled\":true}")
            .statusCode());
    postFrom(HttpExchangeOrigin.LOCALHOST, "/api/auto-approve", "{\"enabled\":false}");
  }

  @Test
  void anOpaqueOriginIsRefused() throws Exception {
    // A sandboxed iframe or a file:// page sends Origin: null. It is not this server, and it is
    // exactly the shape an injected frame would have.
    assertEquals(
        403,
        postFrom(HttpExchangeOrigin.OPAQUE, "/api/auto-approve", "{\"enabled\":true}").statusCode());
    assertFalse(json("/api/status").path("autoApprove").asBoolean());
  }

  @Test
  void aCallerThatSendsNoOriginIsStillServed() throws Exception {
    // curl, a test, the CLI: not a browser page, and the browser would not have delivered a
    // cross-origin request without the header. Refusing these would break every legitimate caller to
    // defend against one that cannot arrive this way.
    assertEquals(200, post("/api/auto-approve", "{\"enabled\":true}").statusCode());
    assertTrue(json("/api/status").path("autoApprove").asBoolean());
    post("/api/auto-approve", "{\"enabled\":false}");
    assertFalse(json("/api/status").path("autoApprove").asBoolean());
  }

  @Test
  void readingIsNotBlockedByOrigin() throws Exception {
    // The check is for requests that change something. A GET leaks nothing a cross-origin page can
    // read anyway — the browser withholds the response — so blocking it would cost without buying.
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
    // The phone, and this was broken: a page reached at the tailnet address sends that address as its
    // Origin, which is neither loopback nor anything this server can know in advance — so every
    // state-changing request from the phone was refused. Measured against a real server started the
    // way the phone reaches it: sending a message, aborting a turn, saving settings, answering an
    // approval and uploading a picture all came back 403, while the same request with a loopback
    // Origin was served. Nothing about the picture was special; it was simply the first one the user
    // tried from a phone.
    start("t0ken");

    Raw ok = postByHand("100.72.92.41:6767", "http://100.72.92.41:6767", "/api/auto-approve",
        "{\"enabled\":true}", "t0ken");

    assertEquals(200, ok.status(), ok.body());
    assertTrue(json("/api/status").path("autoApprove").asBoolean(), "the change really happened");
    postByHand("100.72.92.41:6767", "http://100.72.92.41:6767", "/api/auto-approve",
        "{\"enabled\":false}", "t0ken");
  }

  @Test
  void anotherSiteIsStillRefusedWhenTheRequestNamesARealHost() throws Exception {
    // What keeps the rule above from being a hole: a page on another site sends its own origin, and
    // that is not the host this request was aimed at.
    start("t0ken");

    Raw refused = postByHand("100.72.92.41:6767", "https://evil.example", "/api/auto-approve",
        "{\"enabled\":true}", "t0ken");

    assertEquals(403, refused.status(), refused.body());
    assertFalse(json("/api/status").path("autoApprove").asBoolean());
  }

  @Test
  void aRebindingNameAgreesWithItselfAndStillGetsNowhere() throws Exception {
    // Through a name that resolves here, Origin and Host are both dead.beef — so the agreement is
    // deliberately not the only guard. A tokenless server has already refused the non-loopback Host
    // before the origin check runs, and a token server refuses the request for arriving without the
    // proof, which a page on another site cannot have for a name it does not own.
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

  /** A response read off a socket: whatever arrived, without a client library's opinion of it. */
  private record Raw(int status, String body) {}

  /**
   * One request written by hand.
   *
   * <p>{@code Host} is a restricted header in {@code HttpClient} — it cannot be set, and a request
   * whose {@code Origin} names the host it was aimed at is exactly the shape a phone sends. There is
   * no way to ask a Java HTTP client for that shape, so the bytes are written.
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

  // ------------------------------------------------------------------ pictures

  @Test
  void aPictureBecomesATurnOnItsDescription() throws Exception {
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

      // The message the model is asked about is the description plus where the picture is, so a
      // detail the description dropped can be read back with the read tool.
      JsonNode user = sse.await("user", 5000);
      String text = user.path("text").asText();
      assertTrue(text.contains("a whiteboard with a red arrow"), text);
      assertTrue(text.startsWith("[picture whiteboard.png]"), text);
      assertTrue(text.contains(response.path("attachment").asText()), text);
      assertTrue(
          sse.await("done", 5000).path("finalText").asText().contains("arrow"),
          "the description is what the turn ran on");

      // No image ever reaches the main model: the request holds text and nothing else.
      String toMainModel = provider.requests().get(0).messages().toString();
      assertTrue(toMainModel.contains("a whiteboard with a red arrow"), toMainModel);
      assertFalse(toMainModel.contains("base64"), "the picture itself stays out of the conversation");
    }

    assertEquals(1, visionCalls.get(), "one picture, one description");
    assertEquals(1, asked.size());
    assertTrue(asked.get(0).contains("data:image/png;base64,"), asked.get(0));
    assertTrue(
        asked.get(0).contains("not from the user"),
        "the picture is described as data, so text inside it is not a turn from the user");

    Path stored = Path.of(response.path("attachment").asText());
    assertTrue(Files.exists(stored), "the picture is kept: " + stored);
    assertTrue(
        stored.getParent().getFileName().toString().endsWith(".attachments"),
        "beside the session, not in the project: " + stored);
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
    assertEquals(0, visionCalls.get(), "the bytes decide, before the vision endpoint is asked");
    assertEquals(List.of(), attachmentDirectories(), "nothing was written");
  }

  @Test
  void aPictureOverTheLimitIsRefusedWithoutBeingHeld() throws Exception {
    AtomicInteger visionCalls = new AtomicInteger();
    startVision("never asked", visionCalls, new CopyOnWriteArrayList<>());
    restartWithVision();

    // Nine megabytes, which the endpoint refuses on the advertised length. What this pins is that
    // the refusal is a refusal and not a crash, and that nothing is written or described on the way
    // to it; that the limit applies to the read rather than to what was already buffered is pinned
    // in AttachmentStoreTest, where a stream that fails the test if it is read at all makes it
    // checkable.
    byte[] big = new byte[(int) AttachmentStore.MAX_BYTES + 1];
    System.arraycopy(pngBytes(), 0, big, 0, 8);

    HttpResponse<String> posted = postPicture("huge.png", big);

    assertEquals(413, posted.statusCode(), posted.body());
    assertTrue(posted.body().contains("8 MB"), posted.body());
    assertEquals(0, visionCalls.get());
    assertEquals(List.of(), attachmentDirectories(), "and nothing was written");
  }

  @Test
  void aPictureIsRefusedWhileThatConversationIsBusyBeforeTheVisionCall() throws Exception {
    AtomicInteger visionCalls = new AtomicInteger();
    startVision("a description nobody should pay for", visionCalls, new CopyOnWriteArrayList<>());
    restartWithVision();
    provider.reply(Message.Assistant.text("slow answer"));
    provider.gate(new CountDownLatch(1));

    try (Sse sse = watch()) {
      assertEquals(202, post("/api/message", "{\"text\":\"first\"}").statusCode());
      HttpResponse<String> posted = postPicture("photo.png", pngBytes());
      assertEquals(409, posted.statusCode(), posted.body());
      assertTrue(posted.body().contains("still running"), posted.body());

      provider.release();
      sse.await("done", 5000);
    }
    // Claiming the conversation first is what makes this true: describing first would spend a
    // multi-megabyte upload and a vision call on a turn that can never start.
    assertEquals(0, visionCalls.get());
    assertEquals(List.of(), attachmentDirectories(), "and nothing was written either");
  }

  @Test
  void withoutAVisionModelAPictureIsRefusedWithWhatToSet() throws Exception {
    // The default test configuration has no vision block: the feature reports itself off, and the
    // refusal names the block and the flags that turn it on rather than failing obscurely.
    HttpResponse<String> posted = postPicture("photo.png", pngBytes());

    assertEquals(409, posted.statusCode(), posted.body());
    assertTrue(posted.body().contains("no vision model is configured"), posted.body());
    assertTrue(posted.body().contains("--vision-base-url"), posted.body());
    assertEquals(List.of(), attachmentDirectories(), "and nothing was written");
  }

  @Test
  void theVisionBlockIsConfiguredFromTheSettingsFormAndUsed() throws Exception {
    // The whole panel path: what the form posts is what the file keeps, and the picture that follows
    // goes to the model that was just entered — not to the provider, and not to a stale block.
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

    // The save answers with the status, so what the form would read back is the config endpoint.
    assertEquals("mock-model", saved.path("model").asText());
    JsonNode vision = json("/api/config").path("vision");
    assertTrue(vision.path("on").asBoolean(), vision.toString());
    assertEquals("mock-vision", vision.path("model").asText());
    assertEquals(4096, vision.path("maxTokens").asInt());
    assertEquals(
        "mock-vision",
        Json.parse(Files.readString(configFile)).path("vision").path("model").asText(),
        "and it is in the file, not only in memory");

    assertEquals(202, postPicture("whiteboard.png", pngBytes()).statusCode());
    assertEquals(1, visionCalls.get(), "the picture went to the model the form saved");
  }

  @Test
  void theVisionKeyIsNeverReturnedToTheBrowser() throws Exception {
    // The same rule as the provider's key: the form is told whether one exists and where it comes
    // from, never what it is.
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
    // The running configuration is built from the file at startup, so the file has to exist before
    // the hub does — which is what the CLI does too.
    restartFromConfigFile();

    // Forgetting the key is not forgetting the endpoint the user looked up.
    postJson("/api/config", "{\"clearVisionApiKey\":true}");
    assertFalse(Files.readString(configFile).contains("sk-vision-secret"));
    JsonNode cleared = json("/api/config").path("vision");
    assertEquals("http://127.0.0.1:1/v1", cleared.path("baseUrl").asText());
    assertEquals(4096, cleared.path("maxTokens").asInt());
    assertEquals("none", cleared.path("apiKeySource").asText());

    // And turning it off is not "clearing a field": an empty field means "leave it alone", which is
    // why the form sends a flag for this one.
    postJson("/api/config", "{\"clearVision\":true}");
    assertFalse(json("/api/config").path("vision").path("configured").asBoolean());
    assertFalse(Files.readString(configFile).contains("\"vision\""), "the block is gone from the file");

    HttpResponse<String> posted = postPicture("photo.png", pngBytes());
    assertEquals(409, posted.statusCode(), posted.body());
    assertTrue(posted.body().contains("no vision model is configured"), posted.body());
  }

  @Test
  void aBudgetOfZeroIsRefusedRatherThanSaved() throws Exception {
    // It would mean "write nothing", and the refusal has to arrive before the file is written.
    Files.writeString(configFile, "{}");

    HttpResponse<String> refused =
        post("/api/config", "{\"visionBaseUrl\":\"http://127.0.0.1:1/v1\",\"visionMaxTokens\":0}");

    assertEquals(400, refused.statusCode(), refused.body());
    assertTrue(refused.body().contains("at least 1 token"), refused.body());
    assertFalse(Files.readString(configFile).contains("vision"), "nothing was written");
  }

  /** The hub again, on whatever the config file now says — the way the CLI starts it. */
  private void restartFromConfigFile() throws IOException {
    api.close();
    hub.close();
    hub = hub(provider, Config.layered(configFile, Map.of(), null));
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();
  }

  /** A stand-in vision endpoint on loopback: one canned description, and a count of the calls. */
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
            // The client hung up; the count is what this stub is for.
          }
        });
    vision.start();
  }

  /** The hub again, on a configuration that carries a vision block, as the config file would. */
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

  /** Every {@code <id>.attachments} directory under the sessions directory, by name. */
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
    assertTrue(javax.imageio.ImageIO.write(image, "png", out), "the test needs a real PNG");
    return out.toByteArray();
  }

  @Test
  void everyStateChangingEndpointRefusesAnotherSite() throws Exception {
    // The defence is by method, not by a list of paths, precisely so a new endpoint cannot forget to
    // opt in. This checks the ones that exist today actually behave that way.
    for (String path : List.of("/api/message", "/api/attachment", "/api/abort", "/api/compact",
        "/api/session", "/api/workspaces", "/api/workspace", "/api/config", "/api/auto-approve",
        "/api/approval")) {
      HttpResponse<String> refused = postFrom(HttpExchangeOrigin.EVIL, path, "{}");
      assertEquals(
          403,
          refused.statusCode(),
          "a page on another site must not reach " + path + ": " + refused.body());
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
    throw new AssertionError("no entry matched in " + array);
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
    assertFalse(events.isEmpty(), "expected at least one " + type + " event");
    return events.get(events.size() - 1);
  }

  /** Collects the SSE stream in the background so tests can await individual events. */
  /** One received event: the SSE id and its payload. */
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
                      // Kept, not skipped. The reader used to drop everything that was not `id:` or
                      // `data:`, which is why a keep-alive sent as a *comment* went unnoticed for so
                      // long: the test could not see the difference between a frame that reaches the
                      // page and one that does not.
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

    /**
     * Waits for a frame carrying the given SSE {@code event} name, which is a different thing from the
     * {@code type} inside the payload: an unnamed frame never reaches the page's {@code onmessage},
     * and a named one reaches it only when a listener is registered for that name.
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

    /** The events that belong to one session, which is what one page renders. */
    List<JsonNode> forSession(String sessionId) {
      List<JsonNode> matches = new ArrayList<>();
      for (JsonNode event : snapshot()) {
        if (sessionId.equals(event.path("sessionId").asText())) {
          matches.add(event);
        }
      }
      return matches;
    }

    /** Waits until {@code sessionId} has at least {@code count} events of {@code type}. */
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
          "fewer than "
              + count
              + " '"
              + type
              + "' events for session "
              + sessionId
              + " within "
              + millis
              + "ms; saw "
              + snapshot());
    }

    /** True when any event was published without saying which session it belongs to. */
    boolean anyEventWithoutSession() {
      return snapshot().stream().anyMatch(event -> event.path("sessionId").asText().isEmpty());
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
    /** Reasoning the next turn should emit and carry, or null for a turn that does not think. */
    private String reasoning;

    MockProvider(String name) {
      this.name = name;
    }

    /**
     * Makes the coming turns think out loud.
     *
     * <p>Both halves matter: the deltas go to the listener (so the page renders them live) and the
     * same text goes into the returned turn (so it is persisted, which is what a later replay reads).
     */
    MockProvider emitReasoning(String text) {
      this.reasoning = text;
      return this;
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
