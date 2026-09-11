package com.ccj.agent.web;

import com.ccj.agent.core.AgentListener;
import com.ccj.agent.core.AgentLoop;
import com.ccj.agent.core.AgentOptions;
import com.ccj.agent.core.Approver;
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
 * Owns what a browser cannot: the session, the running turn, and the approvals a turn is blocked on.
 *
 * <p>Deliberately headless — it publishes {@link Event}s and knows nothing about HTTP, so the same
 * object could back a websocket or a test. One turn runs at a time; a second submit is refused
 * rather than queued, because two writers on one session is how transcripts get corrupted.
 */
public final class AgentHub implements AutoCloseable {

  /** How many events a reconnecting browser can replay. */
  private static final int REPLAY_LIMIT = 500;

  /**
   * How long a tool call waits for a human before refusing. Timeout denies: the same fail-closed
   * rule the CLI applies when stdin is not a terminal.
   */
  private static final long APPROVAL_TIMEOUT_SECONDS = 120;

  /** Everything the status payload needs that the loop itself does not carry. */
  public record Settings(
      AgentOptions agent,
      String version,
      String baseUrl,
      Path cwd,
      Path sessionsDir,
      int outputLimitBytes,
      boolean autoApprove) {}

  /** One thing that happened, shaped for the wire. */
  public record Event(long id, String type, ObjectNode payload) {}

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

  private final Provider provider;
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
  private final Object sessionLock = new Object();

  private volatile FileSession session;
  private volatile boolean closed;

  public AgentHub(
      Provider provider,
      ToolRegistry tools,
      Settings settings,
      FileSession session) {
    this.provider = provider;
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
    ObjectNode node = Json.object();
    node.put("version", settings.version());
    node.put("provider", provider.name());
    node.put("model", settings.agent().model());
    node.put("baseUrl", settings.baseUrl());
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

  // ------------------------------------------------------------------ turns

  /** Starts a turn. Returns false when one is already running. */
  public boolean submit(String text) {
    if (closed) {
      throw new IllegalStateException("the web session is shutting down");
    }
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("a message is required");
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
    AgentLoop loop =
        new AgentLoop(
            provider,
            tools,
            session(),
            settings.agent(),
            new ToolContext(settings.cwd(), this::askApproval, settings.outputLimitBytes()),
            new WebListener());
    running.set(loop);
    try {
      AgentLoop.Result result = loop.run(text);
      publish(
          "done",
          Json.object().put("finalText", result.finalText()).put("aborted", result.aborted()));
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      publish("error", Json.object().put("message", message));
    } finally {
      running.set(null);
      busy.set(false);
      publishStatus();
    }
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
    FileSession current = session();
    if (current != null) {
      current.close();
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
}
