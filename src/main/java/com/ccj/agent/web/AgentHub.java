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
import com.ccj.agent.core.UsageTotals;
import com.ccj.agent.core.Workspace;
import com.ccj.agent.core.ProviderDefinition;
import com.ccj.agent.provider.ModelCatalog;
import com.ccj.agent.provider.ProviderStore;
import com.ccj.agent.workspace.WorkspaceStore;
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
   * @param workspaces the registry whose active entry decides the working directory and where
   *     sessions live; switching a workspace is how a front end changes both at once
   * @param cwdOverride a working directory for this run that is not the workspace's, which is what
   *     {@code -C} means; null in the normal case
   * @param configFile where settings are persisted, and read back from on save
   * @param providerNames names offered to the settings form
   */
  public record Settings(
      String version,
      WorkspaceStore workspaces,
      Path cwdOverride,
      Path configFile,
      Map<String, String> env,
      ProviderFactory providerFactory,
      ModelCatalog modelCatalog,
      ProviderStore providerStore,
      FolderChooser folderChooser,
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

  // Session-scoped usage. Live totals only: the session file stores the conversation, not the
  // accounting, so a resumed session starts counting again from zero.
  private final AtomicLong totalInputTokens = new AtomicLong();
  private final AtomicLong totalOutputTokens = new AtomicLong();
  private final AtomicLong totalCachedInputTokens = new AtomicLong();
  private final AtomicInteger userTurns = new AtomicInteger();
  private final AtomicInteger modelTurns = new AtomicInteger();
  private final AtomicInteger toolCalls = new AtomicInteger();
  private final AtomicInteger toolErrors = new AtomicInteger();
  private final AtomicLong turnMillis = new AtomicLong();
  private final AtomicBoolean cacheReported = new AtomicBoolean();

  private volatile Config config;
  private volatile FileSession session;
  private volatile boolean closed;
  private final FolderChooser folderChooser;

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
    this.folderChooser =
        settings.folderChooser() == null ? new NativeFolderChooser() : settings.folderChooser();
    if (session != null) {
      // A server started on an existing session (--resume / --continue) continues its books.
      applyTotals(session.totals());
    }
  }

  /** Where tools resolve relative paths, and where this run's sessions are kept. */
  private Path cwd() {
    Workspace active = settings.workspaces().active();
    Path override = settings.cwdOverride();
    if (override != null) {
      return override;
    }
    return active == null ? Path.of("").toAbsolutePath() : active.path();
  }

  private Path sessionsDir() {
    Workspace active = settings.workspaces().active();
    return active == null ? settings.workspaces().home().resolve("sessions") : active.sessionsDir();
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
    node.put("cwd", cwd().toString());
    ObjectNode workspaceNode = node.putObject("workspace");
    Workspace activeWorkspace = settings.workspaces().active();
    workspaceNode.put(
        "name", activeWorkspace == null ? "" : activeWorkspace.name());
    workspaceNode.put(
        "path", activeWorkspace == null ? "" : activeWorkspace.path().toString());
    node.put("sessionId", current == null ? "" : current.id());
    node.put("messageCount", current == null ? 0 : current.messages().size());
    node.put("autoApprove", autoApprove.get());
    node.put("busy", busy.get());
    if (active.reasoning() == null) {
      node.putNull("reasoning");
    } else {
      node.put("reasoning", active.reasoning());
    }
    // The picker sits above the composer, so the levels travel with the live status too.
    ArrayNode levels = node.putArray("reasoningLevels");
    Config.REASONING_LEVELS.forEach(levels::add);
    node.set("usage", usageFields());
    ArrayNode toolList = node.putArray("tools");
    for (ToolSpec spec : tools.specs()) {
      ObjectNode tool = toolList.addObject();
      tool.put("name", spec.name());
      tool.put("description", spec.description().lines().findFirst().orElse("").strip());
    }
    return node;
  }

  public ArrayNode sessionsJson() {
    return sessionsJson(null);
  }

  /**
   * Sessions of one workspace, or of the active one when {@code workspace} is null.
   *
   * <p>A tree view has to be able to show a folded folder's contents before switching to it, which is
   * exactly this call: reading another workspace's list must not move the active one.
   */
  public ArrayNode sessionsJson(String workspace) {
    Path directory = sessionsDir();
    if (workspace != null && !workspace.isBlank()) {
      Workspace target =
          settings
              .workspaces()
              .find(workspace)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "no workspace named '" + workspace.strip() + "'"));
      directory = target.sessionsDir();
    }
    ArrayNode array = Json.mapper().createArrayNode();
    for (SessionStore.Summary summary : SessionStore.list(directory)) {
      ObjectNode node = array.addObject();
      node.put("id", summary.id());
      node.put("preview", summary.preview());
      node.put("messageCount", summary.messageCount());
      node.put("lastModified", TIMESTAMP.format(summary.lastModified()));
    }
    return array;
  }

  /**
   * Running totals for the current session.
   *
   * <p>{@code cacheHitRate} is null until a provider reports cache figures: "no information" and
   * "nothing was cached" are different facts, and only one of them is a 0%.
   */
  public ObjectNode usageFields() {
    UsageTotals totals = currentTotals();
    ObjectNode node = Json.object();
    node.put("turns", totals.userTurns());
    node.put("steps", totals.modelTurns());
    node.put("inputTokens", totals.inputTokens());
    node.put("outputTokens", totals.outputTokens());
    Double hitRate = totals.cacheHitRate();
    if (hitRate == null) {
      node.putNull("cachedInputTokens");
      node.putNull("cacheHitRate");
    } else {
      node.put("cachedInputTokens", totals.cachedInputTokens());
      node.put("cacheHitRate", hitRate);
    }
    node.put("toolCalls", totals.toolCalls());
    node.put("toolErrors", totals.toolErrors());
    node.put("elapsedMs", totals.elapsedMillis());
    return node;
  }

  private UsageTotals currentTotals() {
    return new UsageTotals(
        totalInputTokens.get(),
        totalOutputTokens.get(),
        totalCachedInputTokens.get(),
        userTurns.get(),
        modelTurns.get(),
        toolCalls.get(),
        toolErrors.get(),
        turnMillis.get(),
        cacheReported.get());
  }

  private void applyTotals(UsageTotals totals) {
    totalInputTokens.set(totals.inputTokens());
    totalOutputTokens.set(totals.outputTokens());
    totalCachedInputTokens.set(totals.cachedInputTokens());
    userTurns.set(totals.userTurns());
    modelTurns.set(totals.modelTurns());
    toolCalls.set(totals.toolCalls());
    toolErrors.set(totals.toolErrors());
    turnMillis.set(totals.elapsedMillis());
    cacheReported.set(totals.cacheReported());
  }

  /**
   * The current session replayed as the events the stream would have emitted, so a page renders
   * history and live turns with one code path.
   *
   * <p>Replayed tool results carry no timing: the conversation stores what the model saw, not how
   * long the tool took, and inventing a zero would read as a measurement.
   */
  public ObjectNode historyJson() {
    FileSession current = session();
    ArrayNode events = Json.mapper().createArrayNode();
    for (Message message : current == null ? List.<Message>of() : current.messages()) {
      switch (message) {
        case Message.User user -> events.add(replay("user").put("text", user.text()));
        case Message.Assistant assistant -> {
          if (!assistant.text().isBlank()) {
            events.add(replay("text").put("delta", assistant.text()));
          }
          for (Message.ToolCall call : assistant.toolCalls()) {
            events.add(
                replay("tool")
                    .put("id", call.id())
                    .put("name", call.name())
                    .put("state", "start")
                    .put("summary", ToolSummary.summarize(call)));
          }
        }
        case Message.ToolResult result ->
            events.add(
                replay("tool")
                    .put("id", result.toolCallId())
                    .put("name", result.toolName())
                    .put("state", "end")
                    .put("ok", !result.error())
                    .putNull("elapsedMs")
                    .put("output", result.content()));
        default -> {
          // System messages are not part of a rendered conversation.
        }
      }
    }
    ObjectNode node = Json.object();
    node.put("sessionId", current == null ? "" : current.id());
    node.set("events", events);
    node.set("usage", usageFields());
    return node;
  }

  private static ObjectNode replay(String type) {
    return Json.object().put("type", type).put("replay", true);
  }

  /**
   * What the settings form can offer: providers (built-in and user-defined) and their models.
   *
   * <p>Served from a {@link ModelCatalog}, so a router-backed catalogue can replace it later without
   * the page changing: the shape it renders is the catalogue's, not the configuration's.
   */
  public ObjectNode modelsJson() {
    ObjectNode root = Json.object();
    ModelCatalog catalog = settings.modelCatalog();
    ArrayNode providers = root.putArray("providers");
    ArrayNode models = root.putArray("models");
    if (catalog == null) {
      return root;
    }
    for (ModelCatalog.ProviderInfo provider : catalog.providers()) {
      ObjectNode entry = providers.addObject();
      entry.put("name", provider.name());
      entry.put("kind", provider.kind());
      entry.put("baseUrl", provider.baseUrl());
      entry.put("builtIn", provider.builtIn());
      ArrayNode list = entry.putArray("models");
      provider.models().forEach(list::add);
    }
    for (ModelCatalog.Model model : catalog.models()) {
      ObjectNode entry = models.addObject();
      entry.put("provider", model.provider());
      entry.put("model", model.model());
      entry.put("source", model.source());
    }
    return root;
  }

  // ------------------------------------------------------------------ providers

  /**
   * Defines a provider the user owns. Validated before it is stored, so a definition that could not
   * work never becomes selectable.
   */
  public ObjectNode addProvider(JsonNode body) {
    ProviderStore store = requireProviderStore();
    List<String> models = new ArrayList<>();
    JsonNode list = body.path("models");
    if (list.isArray()) {
      list.forEach(model -> models.add(model.asText()));
    } else if (list.isTextual()) {
      // A comma-separated string is what a form field naturally sends.
      for (String model : list.asText().split(",")) {
        if (!model.isBlank()) {
          models.add(model.strip());
        }
      }
    }
    ProviderDefinition definition =
        new ProviderDefinition(
                body.path("name").asText(""),
                body.path("kind").asText(ProviderDefinition.OPENAI),
                body.path("baseUrl").asText(""),
                body.path("apiKeyEnv").asText(""),
                models)
            .requireValid();
    store.save(definition);
    publish(
        "notice",
        Json.object().put("text", "provider '" + definition.name() + "' defined"));
    publishStatus();
    return modelsJson();
  }

  /** Removes a definition. The active provider is protected: removing it would break the next turn. */
  public ObjectNode removeProvider(String name) {
    ProviderStore store = requireProviderStore();
    String clean = name == null ? "" : name.strip();
    if (config.provider() != null && config.provider().equalsIgnoreCase(clean)) {
      throw new IllegalArgumentException(
          "cannot remove the provider in use ('" + clean + "'); switch to another one first");
    }
    store.remove(clean);
    publish("notice", Json.object().put("text", "provider '" + clean + "' removed"));
    publishStatus();
    return modelsJson();
  }

  /**
   * Remembers a model for a provider so a hand-typed name survives the next render — the list a
   * picker offers is not the same thing as the model currently in use.
   */
  public ObjectNode addModel(String provider, String model) {
    ProviderStore store = requireProviderStore();
    String name = knownProvider(provider);
    String value = model == null ? "" : model.strip();
    if (value.isEmpty()) {
      throw new IllegalArgumentException("a model name is required");
    }
    List<String> models = new java.util.ArrayList<>(offeredModels(name));
    if (!models.contains(value)) {
      models.add(value);
      store.setModels(name, models);
      publish("notice", Json.object().put("text", "model '" + value + "' added to " + name));
      publishStatus();
    }
    return modelsJson();
  }

  /**
   * Forgets a model for a provider.
   *
   * <p>The offer list and the current choice are separate things: removing a model from the list
   * must not edit the configuration, and refusing to remove the model in use deadlocks the user
   * when it is the only one offered. The active model stays active and stays visible — it is simply
   * no longer suggested.
   */
  public ObjectNode removeModel(String provider, String model) {
    ProviderStore store = requireProviderStore();
    String name = knownProvider(provider);
    String value = model == null ? "" : model.strip();
    List<String> models = new java.util.ArrayList<>(offeredModels(name));
    if (!models.remove(value)) {
      throw new IllegalArgumentException("'" + value + "' is not a model of " + name);
    }
    store.setModels(name, models);
    publish("notice", Json.object().put("text", "model '" + value + "' removed from " + name));
    publishStatus();
    return modelsJson();
  }

  /** What the catalogue currently offers for a provider, which is what an edit starts from. */
  private List<String> offeredModels(String provider) {
    ModelCatalog catalog = settings.modelCatalog();
    if (catalog == null) {
      return List.of();
    }
    return catalog.providers().stream()
        .filter(entry -> entry.name().equalsIgnoreCase(provider))
        .findFirst()
        .map(ModelCatalog.ProviderInfo::models)
        .orElse(List.of());
  }

  private String knownProvider(String provider) {
    String name = provider == null ? "" : provider.strip();
    if (name.isEmpty()) {
      throw new IllegalArgumentException("a provider name is required");
    }
    ModelCatalog catalog = settings.modelCatalog();
    boolean known =
        catalog != null
            && catalog.providers().stream().anyMatch(entry -> entry.name().equalsIgnoreCase(name));
    if (!known) {
      throw new IllegalArgumentException(
          "no provider named '"
              + name
              + "'; add one first, or use one of: "
              + (catalog == null
                  ? ""
                  : String.join(
                      ", ",
                      catalog.providers().stream().map(ModelCatalog.ProviderInfo::name).toList())));
    }
    return name;
  }

  private ProviderStore requireProviderStore() {
    if (settings.providerStore() == null) {
      throw new IllegalStateException("this server was started without a provider registry");
    }
    return settings.providerStore();
  }

  // ------------------------------------------------------------------ workspaces

  /** The registry as the switcher needs it: name, path, how much history is there, which is active. */
  public ObjectNode workspacesJson() {
    ObjectNode root = Json.object();
    Workspace active = settings.workspaces().active();
    root.put("active", active == null ? "" : active.name());
    ArrayNode list = root.putArray("workspaces");
    for (Workspace workspace : settings.workspaces().list()) {
      ObjectNode entry = list.addObject();
      entry.put("name", workspace.name());
      entry.put("path", workspace.path().toString());
      entry.put("sessions", SessionStore.list(workspace.sessionsDir()).size());
      entry.put("active", active != null && active.name().equals(workspace.name()));
    }
    return root;
  }

  public ObjectNode addWorkspace(String name, String path) {
    requireIdle();
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("a workspace needs a directory");
    }
    settings.workspaces().add(name, Path.of(path.strip()));
    publish("notice", Json.object().put("text", "workspace '" + name.strip() + "' added"));
    return workspacesJson();
  }

  public ObjectNode removeWorkspace(String name) {
    requireIdle();
    settings.workspaces().remove(name);
    publish("notice", Json.object().put("text", "workspace '" + name.strip() + "' forgotten"));
    return workspacesJson();
  }

  /**
   * Switches the working directory and the session store together, and starts a fresh session in
   * the target workspace — resuming someone else's conversation across a directory change would be
   * worse than an empty transcript.
   */
  public ObjectNode switchWorkspace(String name) {
    requireIdle();
    Workspace workspace = settings.workspaces().activate(name);
    useSession(SessionStore.create(workspace.sessionsDir()));
    publish(
        "notice",
        Json.object().put("text", "workspace '" + workspace.name() + "' → " + workspace.path()));
    return status();
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
    if (active.reasoning() == null) {
      node.putNull("reasoning");
    } else {
      node.put("reasoning", active.reasoning());
    }
    ArrayNode levels = node.putArray("reasoningLevels");
    Config.REASONING_LEVELS.forEach(levels::add);
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
    Set<String> names = new LinkedHashSet<>();
    if (settings.modelCatalog() != null) {
      settings.modelCatalog().providers().forEach(provider -> names.add(provider.name()));
    }
    names.forEach(providers::add);
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
    // The configured name, not the implementation's: "using openai" for a provider the user called
    // "myrelay" would read like the setting was ignored.
    publish(
        "notice",
        Json.object()
            .put("text", "using " + candidate.provider() + " · " + candidate.model()));
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
                  16, null),
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
    String reasoning = text(posted, "reasoning");
    if (reasoning != null) {
      // "default" is the picker's way of saying "let the provider decide", i.e. clear the tier.
      reasoning = "default".equalsIgnoreCase(reasoning) ? "" : Config.normaliseReasoning(reasoning);
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
        null,
        reasoning);
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
    userTurns.incrementAndGet();
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
    long started = System.nanoTime();
    try {
      AgentLoop.Result result = loop.run(text);
      publish(
          "done",
          Json.object().put("finalText", result.finalText()).put("aborted", result.aborted()));
    } catch (RuntimeException e) {
      publish("error", Json.object().put("message", message(e)));
    } finally {
      turnMillis.addAndGet((System.nanoTime() - started) / 1_000_000);
      running.set(null);
      busy.set(false);
      // Persisting at turn boundaries keeps the file from growing per token and still survives a
      // kill: after the worst case the last turn is missing, never the whole session.
      persistTotals();
      publishUsage();
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
            active.maxSteps(),
            active.reasoning());
    ToolContext context = new ToolContext(cwd(), this::askApproval, active.outputLimitBytes());
    return new AgentLoop(current, tools, session(), options, context, new WebListener());
  }

  // ------------------------------------------------------------------ sessions

  /**
   * Starts a fresh session — unless the current one is already empty, in which case minting another
   * id would only produce a second empty session and confuse whoever pressed the button.
   */
  public void newSession() {
    requireIdle();
    FileSession current = session();
    if (current != null && current.messages().isEmpty()) {
      publish(
          "notice",
          Json.object().put("text", "this session is already empty — say something first"));
      return;
    }
    useSession(SessionStore.create(sessionsDir()));
  }

  public void resumeSession(String id) {
    requireIdle();
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("a session id is required");
    }
    useSession(SessionStore.open(sessionsDir(), id));
  }

  /**
   * Deletes one session. Deleting the active one starts a fresh session in its place, so the page
   * always has somewhere to be after the list it is looking at loses a row.
   */
  public ObjectNode deleteSession(String id) {
    return deleteSession(null, id);
  }

  /**
   * Deletes one session. {@code workspace} names another workspace whose sessions should be removed;
   * reading a folded folder and deleting inside it are the same kind of operation, so neither
   * switches the active workspace. Deleting the session you are in starts a fresh one.
   */
  public ObjectNode deleteSession(String workspace, String id) {
    requireIdle();
    String target = normaliseWorkspace(workspace);
    Path directory = sessionsDirOf(target);
    FileSession current = session();
    boolean active =
        target == null && current != null && current.id().equals(id);
    if (!SessionStore.delete(directory, id)) {
      throw new IllegalArgumentException(
          "no session '" + id + "' in " + (target == null ? "this workspace" : "'" + target + "'"));
    }
    publish("notice", Json.object().put("text", "session deleted"));
    if (active) {
      useSession(SessionStore.create(sessionsDir()));
    }
    return Json.object().set("sessions", sessionsJson(target));
  }

  /** Clears every session in the active workspace — the "my testing left a mess" button. */
  public ObjectNode deleteAllSessions() {
    return deleteAllSessions(null);
  }

  public ObjectNode deleteAllSessions(String workspace) {
    requireIdle();
    String target = normaliseWorkspace(workspace);
    int deleted = SessionStore.deleteAll(sessionsDirOf(target));
    publish(
        "notice",
        Json.object()
            .put("text", deleted == 0 ? "no sessions to delete" : "deleted " + deleted + " sessions"));
    if (target == null) {
      useSession(SessionStore.create(sessionsDir()));
    }
    return Json.object().set("sessions", sessionsJson(target));
  }

  /** Null means the active workspace; a name must exist. */
  private String normaliseWorkspace(String workspace) {
    if (workspace == null || workspace.isBlank()) {
      return null;
    }
    String clean = workspace.strip();
    if (clean.equalsIgnoreCase(settings.workspaces().activeName())) {
      return null;
    }
    settings
        .workspaces()
        .find(clean)
        .orElseThrow(() -> new IllegalArgumentException("no workspace named '" + clean + "'"));
    return clean;
  }

  private Path sessionsDirOf(String workspace) {
    return workspace == null
        ? sessionsDir()
        : settings.workspaces().find(workspace).orElseThrow().sessionsDir();
  }

  /** Asks the desktop for a directory. Blocks until the user answers, cancels or times out. */
  public java.util.Optional<java.nio.file.Path> chooseFolder(java.io.IOException[] failure) {
    try {
      return folderChooser.choose("Choose a workspace folder");
    } catch (java.io.IOException e) {
      failure[0] = e;
      return java.util.Optional.empty();
    }
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
    // Reopening a session continues its books; a brand new one starts empty.
    applyTotals(next.totals());
    publishUsage();
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

  public void publishUsage() {
    publish("usage", usageFields());
  }

  private void persistTotals() {
    FileSession current = session();
    if (current != null) {
      current.totals(currentTotals());
    }
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
      toolCalls.incrementAndGet();
      publish("tool", toolPayload(call, "start"));
    }

    @Override
    public void onToolEnd(Message.ToolCall call, ToolResult result, long elapsedMillis) {
      if (result.error()) {
        toolErrors.incrementAndGet();
      }
      publish(
          "tool",
          toolPayload(call, "end")
              .put("ok", !result.error())
              .put("elapsedMs", elapsedMillis)
              .put("output", result.content()));
    }

    /** Model turns are counted here, not from usage: a provider that reports no usage still ran. */
    @Override
    public void onTurnStart(int step) {
      modelTurns.incrementAndGet();
    }

    @Override
    public void onUsage(int inputTokens, int outputTokens, Integer cachedInputTokens) {
      totalInputTokens.addAndGet(inputTokens);
      totalOutputTokens.addAndGet(outputTokens);
      if (cachedInputTokens != null) {
        cacheReported.set(true);
        totalCachedInputTokens.addAndGet(cachedInputTokens);
      }
      publishUsage();
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
