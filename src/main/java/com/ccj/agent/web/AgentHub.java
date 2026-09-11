package com.ccj.agent.web;

import com.ccj.agent.core.AgentListener;
import com.ccj.agent.core.AgentLoop;
import com.ccj.agent.core.AgentOptions;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolRegistry;
import com.ccj.agent.core.ToolResult;
import com.ccj.agent.core.ToolSpec;
import com.ccj.agent.session.FileSession;
import com.ccj.agent.session.SessionStore;
import com.ccj.agent.ui.ToolSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Owns what a browser cannot: the session, the running turn, the approvals a turn is blocked on, and
 * the model the turns go to.
 *
 * <p>Deliberately headless — it publishes {@link Event}s and knows nothing about HTTP, so the same
 * object could back a websocket or a test. One turn runs at a time; a second submit is refused
 * rather than queued, because two writers on one session is how transcripts get corrupted.
 *
 * <p>The provider is swappable at runtime: this server starts happily with no model configured so
 * the UI can be used to configure one, and {@link #applyConfig} validates and installs a new one
 * before anything is written to disk.
 */
public final class AgentHub implements AutoCloseable {

  /** How many events a reconnecting browser can replay. */
  private static final int REPLAY_LIMIT = 500;

  /**
   * How long a tool call waits for a human before refusing. Timeout denies: the same fail-closed
   * rule the CLI applies when stdin is not a terminal.
   */
  private static final long APPROVAL_TIMEOUT_SECONDS = 120;

  /** Builds a provider from settings; injected so this class stays free of transport details. */
  @FunctionalInterface
  public interface ProviderFactory {
    Provider create(Config config, Map<String, String> env);
  }

  /**
   * The parts of the environment that do not change while the server runs.
   *
   * @param configFile where settings are persisted, and read back from on save
   * @param providerNames names offered to the settings form
   */
  public record Settings(
      String version,
      Path cwd,
      Path sessionsDir,
      Path configFile,
      Map<String, String> env,
      ProviderFactory providerFactory,
      List<String> providerNames,
      boolean autoApprove) {}

  /** One thing that happened, shaped for the wire. */
  public record Event(long id, String type, ObjectNode payload) {}

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

  private final ToolRegistry tools;
  private final Settings settings;
  private final ExecutorService turns =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "ccj-web-turn");
            thread.setDaemon(true);
            return thread;
          });

  private final List<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();
  private final Deque<Event> replay = new ArrayDeque<>();
  private final AtomicLong nextEventId = new AtomicLong();
  private final AtomicInteger nextApprovalId = new AtomicInteger();
  private final Map<String, CompletableFuture<Boolean>> pendingApprovals = new ConcurrentHashMap<>();
  private final AtomicBoolean busy = new AtomicBoolean();
  private final AtomicBoolean autoApprove = new AtomicBoolean();
  private final AtomicReference<AgentLoop> running = new AtomicReference<>();
  private final AtomicReference<Provider> provider = new AtomicReference<>();
  private final Object sessionLock = new Object();

  private volatile Config config;
  private volatile FileSession session;
  private volatile boolean closed;

  public AgentHub(
      Provider initialProvider,
      Config config,
      ToolRegistry tools,
      Settings settings,
      FileSession session) {
    this.provider.set(initialProvider);
    this.config = config;
    this.tools = tools;
    this.settings = settings;
    this.session = session;
    this.autoApprove.set(settings.autoApprove());
  }

  // ------------------------------------------------------------------ status

  public ObjectNode status() {
    ObjectNode node = statusFields();
    node.put("type", "status");
    return node;
  }

  private ObjectNode statusFields() {
    FileSession current = session();
    Config active = config;
    Provider currentProvider = provider.get();
    ObjectNode node = Json.object();
    node.put("version", settings.version());
    node.put("configured", currentProvider != null);
    // The configured name, not the implementation's: the user picked "custom" + a relay URL, and
    // "openai" would be a confusing thing to show them.
    node.put("provider", currentProvider == null || active.provider() == null ? "" : active.provider());
    node.put("model", active.model() == null ? "" : active.model());
    node.put("baseUrl", active.baseUrl() == null ? "" : active.baseUrl());
    node.put("cwd", settings.cwd().toString());
    node.put("sessionId", current == null ? "" : current.id());
    node.put("messageCount", current == null ? 0 : current.messages().size());
    node.put("autoApprove", autoApprove.get());
    node.put("busy", busy.get());
    ArrayNode toolList = node.putArray("tools");
    for (ToolSpec spec : tools.specs()) {
      ObjectNode tool = toolList.addObject();
      tool.put("name", spec.name());
      tool.put("description", spec.description().lines().findFirst().orElse("").strip());
    }
    return node;
  }

  public ArrayNode sessionsJson() {
    ArrayNode array = Json.mapper().createArrayNode();
    for (SessionStore.Summary summary : SessionStore.list(settings.sessionsDir())) {
      ObjectNode node = array.addObject();
      node.put("id", summary.id());
      node.put("preview", summary.preview());
      node.put("messageCount", summary.messageCount());
      node.put("lastModified", TIMESTAMP.format(summary.lastModified()));
    }
    return array;
  }

  // ------------------------------------------------------------------ settings

  /** What the settings form needs. The API key itself never leaves the server. */
  public ObjectNode configJson() {
    Config active = config;
    Config fileConfig = Config.fromFile(settings.configFile());
    String apiKeySource =
        fileConfig.apiKey() != null && !fileConfig.apiKey().isBlank()
            ? "config"
            : active.resolvedApiKey(settings.env()) != null ? "env" : "none";
    ObjectNode node = Json.object();
    node.put("configured", provider.get() != null);
    node.put("provider", active.provider());
    node.put("model", active.model());
    node.put("baseUrl", active.baseUrl());
    node.put("apiKeyEnv", active.apiKeyEnv());
    node.put("apiKeySource", apiKeySource);
    node.put("maxSteps", active.maxSteps());
    if (active.temperature() == null) {
      node.putNull("temperature");
    } else {
      node.put("temperature", active.temperature());
    }
    if (active.maxTokens() == null) {
      node.putNull("maxTokens");
    } else {
      node.put("maxTokens", active.maxTokens());
    }
    node.put("configFile", settings.configFile().toString());
    ArrayNode providers = node.putArray("providers");
    settings.providerNames().forEach(providers::add);
    return node;
  }

  /**
   * Validates the posted settings, persists them, and switches the running session to them.
   *
   * <p>The provider is built first: a wrong key or an unreachable base URL must fail before the file
   * is touched, otherwise a typo would leave a configuration that cannot start.
   */
  public ObjectNode applyConfig(JsonNode posted) {
    requireIdle();
    Config changes = changesFrom(posted);
    Config candidate = config.merge(changes).resolved();
    Provider built = settings.providerFactory().create(candidate, settings.env());
    try {
      Config.writeInto(settings.configFile(), Config.fromFile(settings.configFile()).merge(changes));
    } catch (RuntimeException e) {
      built.close();
      throw e;
    }
    Provider previous = provider.getAndSet(built);
    if (previous != null) {
      previous.close();
    }
    config = candidate;
    publish(
        "notice",
        Json.object().put("text", "using " + built.name() + " · " + candidate.model()));
    publishStatus();
    return status();
  }

  /**
   * Sends one minimal request with the posted settings without saving anything. The only place this
   * server spends the user's tokens, and only when they press the button.
   */
  public ObjectNode testConfiguration(JsonNode posted) {
    requireIdle();
    Config candidate = config.merge(changesFrom(posted)).resolved();
    try (Provider probe = settings.providerFactory().create(candidate, settings.env())) {
      long started = System.nanoTime();
      Message.Assistant reply =
          probe.complete(
              new Provider.Request(
                  candidate.model(),
                  "Reply with the single word: ok",
                  List.of(new Message.User("ping")),
                  List.of(),
                  0.0,
                  16),
              event -> {});
      long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
      String text = reply.text().isBlank() ? "(empty reply)" : reply.text().strip();
      return Json.object().put("ok", true).put("reply", text).put("elapsedMs", elapsedMillis);
    } catch (Exception e) {
      throw new IllegalArgumentException(message(e));
    }
  }

  /** Only the fields the form actually sent; blank text means "leave as it is". */
  private static Config changesFrom(JsonNode posted) {
    if (posted == null || !posted.isObject()) {
      throw new IllegalArgumentException("a JSON object is required");
    }
    String apiKey = text(posted, "apiKey");
    if (posted.path("clearApiKey").asBoolean(false)) {
      apiKey = "";
    }
    Integer maxSteps = integer(posted, "maxSteps");
    if (maxSteps != null && maxSteps < 1) {
      throw new IllegalArgumentException("maxSteps must be at least 1");
    }
    Double temperature = number(posted, "temperature");
    if (temperature != null && (temperature < 0 || temperature > 2)) {
      throw new IllegalArgumentException("temperature must be between 0 and 2");
    }
    return new Config(
        text(posted, "provider"),
        text(posted, "model"),
        text(posted, "baseUrl"),
        apiKey,
        text(posted, "apiKeyEnv"),
        temperature,
        integer(posted, "maxTokens"),
        maxSteps,
        null,
        null,
        null);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("field '" + field + "' must be a string");
    }
    String text = value.asText().strip();
    return text.isEmpty() ? null : text;
  }

  private static Integer integer(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber()) {
      throw new IllegalArgumentException("field '" + field + "' must be an integer");
    }
    return value.asInt();
  }

  private static Double number(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isNumber()) {
      throw new IllegalArgumentException("field '" + field + "' must be a number");
    }
    return value.asDouble();
  }

  // ------------------------------------------------------------------ turns

  /** Starts a turn. Returns false when one is already running. */
  public boolean submit(String text) {
    if (closed) {
      throw new IllegalStateException("the web session is shutting down");
    }
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("a message is required");
    }
    if (provider.get() == null) {
      throw new IllegalStateException("no model configured — open Settings and add one");
    }
    if (!busy.compareAndSet(false, true)) {
      return false;
    }
    publish("user", Json.object().put("text", text));
    publishStatus();
    turns.submit(() -> runTurn(text));
    return true;
  }

  /** Requests that the running turn stop at the next safe point. */
  public boolean abort() {
    AgentLoop loop = running.get();
    if (loop == null) {
      return false;
    }
    loop.abort();
    return true;
  }

  private void runTurn(String text) {
    AgentLoop loop = newLoop();
    running.set(loop);
    try {
      AgentLoop.Result result = loop.run(text);
      publish(
          "done",
          Json.object().put("finalText", result.finalText()).put("aborted", result.aborted()));
    } catch (RuntimeException e) {
      publish("error", Json.object().put("message", message(e)));
    } finally {
      running.set(null);
      busy.set(false);
      publishStatus();
    }
  }

  private AgentLoop newLoop() {
    Provider current = provider.get();
    if (current == null) {
      throw new IllegalStateException("no model configured — open Settings and add one");
    }
    Config active = config;
    AgentOptions options =
        new AgentOptions(
            active.model(),
            active.systemPrompt(),
            active.temperature(),
            active.maxTokens(),
            active.maxSteps());
    ToolContext context =
        new ToolContext(settings.cwd(), this::askApproval, active.outputLimitBytes());
    return new AgentLoop(current, tools, session(), options, context, new WebListener());
  }

  // ------------------------------------------------------------------ sessions

  public void newSession() {
    requireIdle();
    useSession(SessionStore.create(settings.sessionsDir()));
  }

  public void resumeSession(String id) {
    requireIdle();
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("a session id is required");
    }
    useSession(SessionStore.open(settings.sessionsDir(), id));
  }

  private void useSession(FileSession next) {
    FileSession previous;
    synchronized (sessionLock) {
      previous = session;
      session = next;
    }
    if (previous != null) {
      previous.close();
    }
    publishStatus();
  }

  private void requireIdle() {
    if (busy.get()) {
      throw new IllegalStateException("a turn is still running; abort it first");
    }
  }

  // ------------------------------------------------------------------ approvals

  /** Answers a pending approval. Returns false when the id is unknown or already answered. */
  public boolean resolveApproval(String id, boolean allow, boolean remember) {
    CompletableFuture<Boolean> answer = id == null ? null : pendingApprovals.get(id);
    if (answer == null) {
      return false;
    }
    if (allow && remember) {
      setAutoApprove(true);
    }
    return answer.complete(allow);
  }

  public boolean autoApprove() {
    return autoApprove.get();
  }

  public void setAutoApprove(boolean enabled) {
    if (autoApprove.getAndSet(enabled) != enabled) {
      publishStatus();
    }
  }

  /**
   * Blocks the loop thread until a browser answers. Publishing inside the call is what makes the
   * agent's request visible; returning false on timeout is what keeps it from waiting forever.
   */
  private boolean askApproval(String title, String detail) {
    if (autoApprove.get()) {
      return true;
    }
    String id = "ap-" + nextApprovalId.incrementAndGet();
    CompletableFuture<Boolean> answer = new CompletableFuture<>();
    pendingApprovals.put(id, answer);
    publish("approval", Json.object().put("id", id).put("title", title).put("detail", detail));
    try {
      boolean allow = answer.get(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      publish("approval-closed", Json.object().put("id", id).put("allow", allow));
      return allow;
    } catch (TimeoutException e) {
      publish("approval-closed", Json.object().put("id", id).put("allow", false).put("reason", "timeout"));
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (ExecutionException e) {
      return false;
    } finally {
      pendingApprovals.remove(id);
    }
  }

  // ------------------------------------------------------------------ events

  /**
   * Registers a subscriber and hands back the events it missed, in one atomic step — otherwise an
   * event published between "read the replay buffer" and "start listening" would be lost.
   */
  public List<Event> subscribe(Consumer<Event> subscriber) {
    synchronized (replay) {
      subscribers.add(subscriber);
      return List.copyOf(replay);
    }
  }

  public void unsubscribe(Consumer<Event> subscriber) {
    synchronized (replay) {
      subscribers.remove(subscriber);
    }
  }

  private void publish(String type, ObjectNode payload) {
    ObjectNode node = payload == null ? Json.object() : payload;
    node.put("type", type);
    Event event = new Event(nextEventId.incrementAndGet(), type, node);
    synchronized (replay) {
      replay.addLast(event);
      while (replay.size() > REPLAY_LIMIT) {
        replay.removeFirst();
      }
      for (Consumer<Event> subscriber : subscribers) {
        subscriber.accept(event);
      }
    }
  }

  public void publishStatus() {
    publish("status", statusFields());
  }

  private FileSession session() {
    synchronized (sessionLock) {
      return session;
    }
  }

  @Override
  public void close() {
    closed = true;
    for (CompletableFuture<Boolean> waiting : pendingApprovals.values()) {
      waiting.complete(false);
    }
    pendingApprovals.clear();
    turns.shutdownNow();
    Provider current = provider.getAndSet(null);
    if (current != null) {
      current.close();
    }
    FileSession active = session();
    if (active != null) {
      active.close();
    }
  }

  /** Translates loop callbacks into wire events; nothing here decides policy. */
  private final class WebListener implements AgentListener {

    @Override
    public void onText(String delta) {
      publish("text", Json.object().put("delta", delta));
    }

    @Override
    public void onReasoning(String delta) {
      publish("reasoning", Json.object().put("delta", delta));
    }

    @Override
    public void onToolStart(Message.ToolCall call) {
      publish("tool", toolPayload(call, "start"));
    }

    @Override
    public void onToolEnd(Message.ToolCall call, ToolResult result, long elapsedMillis) {
      publish(
          "tool",
          toolPayload(call, "end")
              .put("ok", !result.error())
              .put("elapsedMs", elapsedMillis)
              .put("output", result.content()));
    }

    @Override
    public void onNotice(String text) {
      publish("notice", Json.object().put("text", text));
    }

    private ObjectNode toolPayload(Message.ToolCall call, String state) {
      return Json.object()
          .put("id", call.id())
          .put("name", call.name())
          .put("state", state)
          .put("summary", ToolSummary.summarize(call));
    }
  }

  /** Kept for tests that want to assert on the approval contract without a browser. */
  public Approver approver() {
    return this::askApproval;
  }

  private static String message(Throwable e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }
}
