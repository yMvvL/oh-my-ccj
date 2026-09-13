package com.ccj.agent.web;

import com.ccj.agent.core.AgentListener;
import com.ccj.agent.core.AgentLoop;
import com.ccj.agent.core.AgentOptions;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Prompts;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolRegistry;
import com.ccj.agent.core.ToolResult;
import com.ccj.agent.core.ToolSpec;
import com.ccj.agent.core.TokenEstimate;
import com.ccj.agent.core.UsageTotals;
import com.ccj.agent.core.Workspace;
import com.ccj.agent.core.ProviderDefinition;
import com.ccj.agent.core.SessionRepair;
import com.ccj.agent.provider.ModelCatalog;
import com.ccj.agent.provider.Providers;
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
 * object could back a websocket or a test.
 *
 * <p>Every turn belongs to a conversation and runs in that conversation's own slot, so several can
 * be in flight at once: a long job in one session is not a reason the user cannot start work in
 * another. The unit that takes one turn at a time is the session — a second message in the
 * <em>same</em> conversation is refused rather than queued, because two writers on one transcript is
 * how it gets corrupted. Every event names the session it belongs to, since one stream carries them
 * all.
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

  /**
   * One thing that happened, shaped for the wire.
   *
   * <p>{@code sessionId} is part of the event rather than of the connection because one stream
   * carries every conversation on the server: with turns running in parallel, an event that did not
   * name its session would be prose rendered into whichever transcript happened to be open.
   */
  public record Event(long id, String type, String sessionId, ObjectNode payload) {}

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

  private final ToolRegistry tools;
  private final Settings settings;

  /** One per running turn: a session that is working gets its own thread, not the server's. */
  private final ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();

  private final List<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();
  private final Deque<Event> replay = new ArrayDeque<>();
  private final AtomicLong nextEventId = new AtomicLong();
  private final AtomicInteger nextApprovalId = new AtomicInteger();
  /**
   * Approvals waiting for a human, keyed by their id.
   *
   * <p>Server-wide rather than per conversation: an approval is answered by id, from whichever page
   * notices it, and a request that needs a human in a background turn is exactly the case this must
   * not get wrong.
   */
  private final Map<String, Pending> pendingApprovals = new ConcurrentHashMap<>();
  private final AtomicBoolean autoApprove = new AtomicBoolean();
  private final AtomicReference<Provider> provider = new AtomicReference<>();

  /**
   * Running turns, one per session, and the conversation each one belongs to.
   *
   * <p>The map is the server's answer to "is this session busy": a turn is registered before it
   * starts and removed when it ends, so a second message in the same conversation is refused while a
   * message in any other conversation is not. Keyed by session id, so two conversations never share
   * a slot and switching between them cannot confuse one turn with another.
   */
  private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

  /** The conversation the browsing front end is looking at. */
  private final Object sessionLock = new Object();

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
    // The endpoint the provider will actually be called at, not the one the file happens to store:
    // a custom provider is served by its definition unless the stored URL was entered for it.
    String endpoint = Providers.effectiveBaseUrl(active, settings.providerStore(), active.provider());
    node.put("baseUrl", endpoint == null ? "" : endpoint);
    node.put("cwd", cwd().toString());
    // Empty in the normal case. Non-empty means -C pinned a working directory that is not the active
    // workspace's: two facts that disagree, which the page has to be able to show rather than let
    // the tree claim the tools run where they do not.
    Path override = settings.cwdOverride();
    node.put("cwdOverride", override == null ? "" : override.toString());
    ObjectNode workspaceNode = node.putObject("workspace");
    Workspace activeWorkspace = settings.workspaces().active();
    workspaceNode.put(
        "name", activeWorkspace == null ? "" : activeWorkspace.name());
    workspaceNode.put(
        "path", activeWorkspace == null ? "" : activeWorkspace.path().toString());
    node.put("sessionId", current == null ? "" : current.id());
    node.put("messageCount", current == null ? 0 : current.messages().size());
    node.put("autoApprove", autoApprove.get());
    // "busy" answers for the conversation on screen: a page's composer is about the transcript it
    // shows, and a turn running somewhere else must not disable it.
    Conversation shown = conversation(current == null ? "" : current.id());
    node.put("busy", shown != null && shown.running());
    // Which other conversations are working, so the tree can mark their rows. Named rather than
    // counted: "something is running" cannot tell you which session to go back to watch.
    ArrayNode runningNow = node.putArray("running");
    for (Conversation entry : conversations.values()) {
      if (entry.running() && !entry.id().equals(current == null ? "" : current.id())) {
        runningNow.add(entry.id());
      }
    }
    // Approvals this conversation is waiting on. They are requests blocked in memory rather than
    // messages, so they are not in the history a page replays when it opens a session — without them
    // here, looking at another conversation and coming back lost the prompt and left abort as the
    // only way out.
    ArrayNode waiting = node.putArray("approvals");
    String shownId = current == null ? "" : current.id();
    for (Map.Entry<String, Pending> entry : pendingApprovals.entrySet()) {
      Pending pending = entry.getValue();
      if (!pending.sessionId().equals(shownId)) {
        continue;
      }
      ObjectNode request = waiting.addObject();
      request.put("id", entry.getKey());
      request.put("title", pending.title());
      request.put("detail", pending.detail());
    }
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
      node.put("title", summary.title());
      node.put("preview", summary.preview());
      node.put("messageCount", summary.messageCount());
      node.put("lastModified", TIMESTAMP.format(summary.lastModified()));
      // Which rows are working, so a turn left running in another conversation is visible from
      // anywhere rather than only from the transcript it is writing.
      Conversation conversation = conversations.get(summary.id());
      node.put("running", conversation != null && conversation.running());
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
    return usageFields(session());
  }

  private ObjectNode usageFields(FileSession target) {
    UsageTotals totals = totalsOf(target);
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
    // How much of the model's window this conversation would take. It is an estimate and the panel
    // says so: the count is a heuristic, and the number that matters is the one the user paid for.
    node.put(
        "contextTokens",
        TokenEstimate.of(target == null ? List.<Message>of() : target.messages()));
    Config active = config;
    node.put("contextLimit", active.maxContextTokens() == null ? 0 : active.maxContextTokens());
    return node;
  }

  private UsageTotals currentTotals() {
    return totalsOf(session());
  }

  /** A session's books: what the file recorded before, or an empty ledger when there is no file. */
  private static UsageTotals totalsOf(FileSession session) {
    return session == null ? UsageTotals.empty() : session.totals();
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
    // Repaired first, so a conversation an interruption left invalid replays the same way the model
    // will read it: a call whose result went missing shows as "not run" rather than as a card that
    // spins forever.
    List<Message> messages =
        current == null || current.messages().isEmpty()
            ? List.of()
            : SessionRepair.apply(current.messages()).messages();
    for (Message message : messages) {
      switch (message) {
        case Message.User user -> events.add(replay("user").put("text", user.text()));
        case Message.Assistant assistant -> {
          // Reasoning first: it is what the model said to itself before answering, which is also the
          // order the live stream produced it in. Without this a conversation replayed after a
          // session switch lost its thinking — the reasoning is in the file, so a replay that drops
          // it is a replay of a different conversation.
          for (Message.Thinking block : assistant.thinking()) {
            if (block.redacted() || block.text().isEmpty()) {
              // A redacted block's payload is opaque and belongs to the model, unchanged: rendering
              // it as prose would put a wall of base64 in the transcript. A signature-only block has
              // nothing to show either.
              continue;
            }
            events.add(replay("reasoning").put("delta", block.text()));
          }
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
      // The variable a key would come from, so the settings form can name it instead of guessing
      // the protocol's default — which is how a relay got told to read somebody else's key variable.
      if (provider.apiKeyEnv() == null) {
        entry.putNull("apiKeyEnv");
      } else {
        entry.put("apiKeyEnv", provider.apiKeyEnv());
      }
      ArrayNode list = entry.putArray("models");
      provider.models().forEach(list::add);
    }
    for (ModelCatalog.Model model : catalog.models()) {
      ObjectNode entry = models.addObject();
      entry.put("provider", model.provider());
      entry.put("model", model.model());
      entry.put("source", model.source());
    }
    // Built-ins that are not in the list right now: what an "add a built-in" control can offer.
    // Framed as available, not as hidden — there is nothing to bring back from the dead.
    ArrayNode available = root.putArray("builtIns");
    Set<String> offered = new LinkedHashSet<>();
    providers.forEach(entry -> offered.add(entry.path("name").asText().toLowerCase()));
    for (String name : com.ccj.agent.provider.Providers.supported()) {
      if (!offered.contains(name.toLowerCase())) {
        available.add(name);
      }
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
    // Saving a definition is also saying "I want this provider": if the list is explicit, the new
    // name has to join it, otherwise the catalogue never shows what was just saved.
    ensureListed(definition.name());
    publish(
        "notice",
        Json.object().put("text", "provider '" + definition.name() + "' defined"));
    publishStatus();
    return modelsJson();
  }

  /**
   * Removes a provider from the list.
   *
   * <p>A definition the user made is deleted. A built-in one is compiled into the agent, so there is
   * nothing to delete: it leaves the list and the list becomes explicit, which is all "deleted"
   * needs to mean. Nothing is remembered as hidden and nothing is offered as a restore — adding it
   * back is an ordinary add.
   */
  public ObjectNode removeProvider(String name) {
    ProviderStore store = requireProviderStore();
    String clean = knownProvider(name);
    boolean inUse = config.provider() != null && config.provider().equalsIgnoreCase(clean);
    String tail =
        inUse
            ? " — it was the one in use; this session keeps running, but choose another provider"
                + " before the next restart"
            : "";

    if (store.find(clean).isEmpty()) {
      List<String> remaining = new ArrayList<>(currentProviderNames());
      remaining.removeIf(known -> known.equalsIgnoreCase(clean));
      store.setShown(remaining);
    } else {
      store.remove(clean);
      List<String> stillShown = new ArrayList<>(store.shown());
      if (stillShown.removeIf(known -> known.equalsIgnoreCase(clean))) {
        store.setShown(stillShown);
      }
    }
    publish("notice", Json.object().put("text", "provider '" + clean + "' deleted" + tail));
    publishStatus();
    return modelsJson();
  }

  /** Adds a built-in back to the list — an ordinary add, not a resurrection. */
  public ObjectNode addBuiltInProvider(String name) {
    ProviderStore store = requireProviderStore();
    String clean = name == null ? "" : name.strip();
    boolean builtIn =
        Providers.supported().stream().anyMatch(known -> known.equalsIgnoreCase(clean));
    if (!builtIn) {
      throw new IllegalArgumentException(
          "'" + clean + "' is not a built-in provider; define it instead");
    }
    if (currentProviderNames().stream().anyMatch(known -> known.equalsIgnoreCase(clean))) {
      throw new IllegalArgumentException("'" + clean + "' is already in the list");
    }
    List<String> next = new ArrayList<>(currentProviderNames());
    next.add(clean);
    store.setShown(next);
    publish("notice", Json.object().put("text", "provider '" + clean + "' added"));
    publishStatus();
    return modelsJson();
  }

  /** Every provider name the catalogue currently offers. */
  private List<String> currentProviderNames() {
    List<String> names = new java.util.ArrayList<>();
    ModelCatalog catalog = settings.modelCatalog();
    if (catalog != null) {
      catalog.providers().forEach(entry -> names.add(entry.name()));
    }
    return names;
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

  /** Adds a name to the explicit list, doing nothing while the list is still implicit. */
  private void ensureListed(String name) {
    ProviderStore store = requireProviderStore();
    List<String> shown = new ArrayList<>(store.shown());
    if (!store.narrowed() || shown.stream().anyMatch(known -> known.equalsIgnoreCase(name))) {
      return;
    }
    shown.add(name);
    store.setShown(shown);
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
      entry.put("sessions", SessionStore.count(workspace.sessionsDir()));
      entry.put("active", active != null && active.name().equals(workspace.name()));
    }
    return root;
  }

  public ObjectNode addWorkspace(String name, String path) {
    // A registry entry, and nothing more: no running turn reads the list, so adding one cannot
    // disturb work already under way. Requiring idle here is what made a second workspace
    // unreachable while the first one was busy.
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("a workspace needs a directory");
    }
    settings.workspaces().add(name, Path.of(path.strip()));
    publish("notice", Json.object().put("text", "workspace '" + name.strip() + "' added"));
    return workspacesJson();
  }

  /**
   * Adds a directory chosen from the desktop's own chooser. The chooser is the whole gesture, so the
   * name is the directory's — see {@link WorkspaceStore#add(Path)} for how a taken name is resolved.
   */
  public ObjectNode addWorkspace(Path path) {
    if (path == null) {
      throw new IllegalArgumentException("a workspace needs a directory");
    }
    requireDistinctPath(path);
    Workspace added = settings.workspaces().add(path);
    publish(
        "notice",
        Json.object().put("text", "workspace '" + added.name() + "' → " + added.path()));
    return workspacesJson();
  }

  /* The registry is keyed by name, so two names may point at one directory — which is fine until it
   * is the same directory twice: two session histories would then compete for one working
   * directory, and nothing on screen would say which list belongs to which. */
  private void requireDistinctPath(Path path) {
    Path wanted = path.toAbsolutePath().normalize();
    for (Workspace known : settings.workspaces().list()) {
      if (known.path().equals(wanted)) {
        throw new IllegalArgumentException(
            "that directory is already the workspace '"
                + known.name()
                + "'; use the entry in the tree instead of adding it again");
      }
    }
  }

  public ObjectNode removeWorkspace(String name) {
    // Forgetting an entry deletes no file and stops no turn; a running turn keeps the session file it
    // already holds. Only the active workspace is protected, and that is the registry's own rule.
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
    // Allowed while turns are running: each one holds the working directory it started in, so
    // switching the namespace here cannot redirect a job that is already under way.
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
    // A pair that belongs to this provider: either the file's flat fields, or one it remembers for
    // this provider from a previous visit. Either way it is this provider's key, and the key itself
    // never leaves the server.
    boolean storedForThisProvider =
        fileConfig.apiKey() != null
            && !fileConfig.apiKey().isBlank()
            && (fileConfig.settingsBelongTo(active.provider())
                || fileConfig.rememberedFor(active.provider()) != null);
    String apiKeySource =
        storedForThisProvider
            ? "config"
            : active.resolvedApiKey(settings.env()) != null ? "env" : "none";
    ObjectNode node = Json.object();
    node.put("configured", provider.get() != null);
    node.put("provider", active.provider());
    node.put("model", active.model());
    node.put("baseUrl", Providers.effectiveBaseUrl(active, settings.providerStore(), active.provider()));
    node.put("apiKeyEnv", active.apiKeyEnv());
    // Whether the two above are what this provider will actually use. False means they were entered
    // for a different provider (a custom provider does not use them), and the form must offer this
    // provider's own endpoint instead of the stale one.
    node.put(
        "usesStoredSettings",
        Providers.usesStoredSettings(active, settings.providerStore(), active.provider()));
    // Which providers have one saved, by name: the form says "saved" or "not saved" per provider
    // before anything is pasted, and switching between them is the whole reason the map exists.
    ArrayNode remembered = node.putArray("rememberedProviders");
    Set<String> withKey = new LinkedHashSet<>();
    fileConfig.rememberedNames().forEach(withKey::add);
    if (storedForThisProvider && active.provider() != null) {
      withKey.add(active.provider().strip().toLowerCase());
    }
    withKey.forEach(remembered::add);
    node.put("apiKeySource", apiKeySource);
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
    // The language the prompt asks for, and the list the form offers. `auto` is the absence of a
    // choice, so it is what a cleared field reports.
    node.put("language", active.language() == null ? Prompts.AUTO : active.language());
    ArrayNode languages = node.putArray("languages");
    Prompts.languageChoices().forEach(choice -> {
      ObjectNode entry = languages.addObject();
      entry.put("value", choice[0]);
      entry.put("label", choice[1]);
    });
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
   *
   * <p>Switching provider starts from a configuration with no endpoint and no credential: they
   * belonged to the provider that was active, and inheriting them is how a session that says
   * "CommandCode" sends its traffic to the previous provider's address with the previous provider's
   * key. Whatever this request carried still applies, and the result is marked as the new
   * provider's, so the stored pair stays honest the next time it is read.
   */
  public ObjectNode applyConfig(JsonNode posted) {
    requireEverythingIdle("change the model");
    Config changes = changesFrom(posted);
    // Both sides go through the same transition: the endpoint and key being left are remembered under
    // the provider they belong to, the one being switched to is recalled, and a change that names a
    // setting writes it as the active provider's.
    Config candidate = config.changedBy(changes);
    Provider built = settings.providerFactory().create(candidate, settings.env());
    try {
      // The file is the side that is written, so it carries the provider actually in use: a form can
      // post a key on its own, and a save must not leave the file without the provider it is about.
      Config stored = Config.fromFile(settings.configFile()).namedBy(config).changedBy(changes);
      Config.writeInto(settings.configFile(), stored);
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
    requireShownIdle("test these settings");
    // Testing settings that are not saved yet must test exactly what was posted: the same transition
    // the form's Save uses, minus the write, so a value this provider does not own is replaced rather
    // than left behind.
    Config candidate = config.changedBy(changesFrom(posted));
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
    String reasoning = text(posted, "reasoning");
    if (reasoning != null) {
      // "default" is the picker's way of saying "let the provider decide", i.e. clear the tier.
      reasoning = "default".equalsIgnoreCase(reasoning) ? "" : Config.normaliseReasoning(reasoning);
    }
    Double temperature = number(posted, "temperature");
    if (temperature != null && (temperature < 0 || temperature > 2)) {
      throw new IllegalArgumentException("temperature must be between 0 and 2");
    }
    String language = text(posted, "language");
    // The full shape, named by position: every field this form does not manage is an explicit null,
    // because the shorter constructors put a String in the wrong slot without saying so.
    return new Config(
        text(posted, "provider"),
        text(posted, "model"),
        text(posted, "baseUrl"),
        apiKey,
        text(posted, "apiKeyEnv"),
        temperature,
        integer(posted, "maxTokens"),
        null, // autoApprove
        null, // outputLimitBytes
        null, // systemPrompt
        language,
        reasoning,
        null, // maxContextTokens
        null, // settingsFor
        java.util.Map.of());
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

  /**
   * Starts a turn in the session the front end is looking at. Returns false when that session already
   * has one running.
   *
   * <p>Refusing per session rather than per server is the whole point: a long job in one conversation
   * must not stop the user from starting work in another. What is still refused is a second turn in
   * the <em>same</em> conversation — two writers appending to one transcript is the thing that
   * corrupts it.
   */
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
    FileSession current = session();
    if (current == null) {
      throw new IllegalStateException("no session is open");
    }
    String id = current.id();
    Conversation conversation = conversations.computeIfAbsent(id, key -> new Conversation(key, current));
    if (!conversation.begin()) {
      return false;
    }
    // Where this turn's tools will run, decided now: the user pressed send while looking at this
    // workspace, and switching to another one later must not move the work they already started.
    conversation.useCurrentCwd();
    current.totals(current.totals().plus(0, 0, null, 1, 0, 0, 0, 0));
    publish(id, "user", Json.object().put("text", text));
    publishStatus();
    turns.submit(() -> runTurn(conversation, text));
    return true;
  }

  /** Requests that the turn of the session on screen stops at the next safe point. */
  public boolean abort() {
    FileSession current = session();
    Conversation conversation = current == null ? null : conversations.get(current.id());
    return conversation != null && conversation.abort();
  }

  /** Requests that one named session's turn stops, whether or not it is the one on screen. */
  public boolean abort(String sessionId) {
    Conversation conversation = sessionId == null ? null : conversations.get(sessionId);
    return conversation != null && conversation.abort();
  }

  /**
   * One user turn, from the request that started it to the events that end it.
   *
   * <p>Everything published here carries {@code conversation.id()}, because several of these run at
   * once and the page files events by session. The books are the session's own: totals live in the
   * conversation's file, so two turns running side by side cannot add their tokens to each other.
   */
  private void runTurn(Conversation conversation, String text) {
    AgentLoop loop = newLoop(conversation);
    conversation.attach(loop);
    long started = System.nanoTime();
    AgentLoop.Result result = null;
    Throwable failure = null;
    try {
      result = loop.run(text);
    } catch (RuntimeException | Error e) {
      // An Error is not this turn's business to report as a value, but it is still the end of the
      // turn: leaving the cleanup below to the happy path would strand this session as permanently
      // busy — every later message refused with 409 — over a StackOverflowError raised by one tool.
      failure = e;
    }
    // The turn is over *before* it says so. A client that reacts to `done` by sending the next
    // message must not find the session still claiming to be busy — that race is a message answered
    // with 409 and a composer that stays disabled until something else publishes a status.
    conversation.end();
    conversation.addElapsed((System.nanoTime() - started) / 1_000_000);
    // Persisting at turn boundaries keeps the file from growing per token and still survives a
    // kill: after the worst case the last turn is missing, never the whole session.
    conversation.persist();
    publishUsage(conversation.id());
    publishStatus();
    if (failure != null) {
      publish(conversation.id(), "error", Json.object().put("message", message(failure)));
    } else {
      publish(
          conversation.id(),
          "done",
          Json.object().put("finalText", result.finalText()).put("aborted", result.aborted()));
    }
  }

  private AgentLoop newLoop(Conversation conversation) {
    Provider current = provider.get();
    if (current == null) {
      throw new IllegalStateException("no model configured — open Settings and add one");
    }
    Config active = config;
    AgentOptions options =
        new AgentOptions(
            active.model(),
            // The project's own CCJ.md is read from this conversation's working directory, which is
            // the one fixed when its turn started: a rules file belongs to the directory the tools
            // will actually run in, not to whatever is on screen when the request is built.
            Prompts.system(active.systemPrompt(), active.language(), conversation.cwd()),
            active.temperature(),
            active.maxTokens(),
            active.reasoning(),
            active.maxContextTokens());
    ToolContext context =
        new ToolContext(conversation.cwd(), this::askApproval, active.outputLimitBytes());
    return new AgentLoop(
        current, tools, conversation.session(), options, context, new WebListener(conversation.id()));
  }

  // ------------------------------------------------------------------ sessions

  /**
   * Starts a fresh session — unless the current one is already empty, in which case minting another
   * id would only produce a second empty session and confuse whoever pressed the button.
   */
  public void newSession() {
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
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("a session id is required");
    }
    useSession(openForDisplay(id));
  }

  /**
   * The session object to put on screen for {@code id}.
   *
   * <p>A conversation that is running already has a {@link FileSession} — the one its turn is
   * appending to — and that is the object the page gets. Opening a second one on the same file would
   * be two writers on one transcript, which is the thing the whole per-conversation rule protects;
   * refusing to *show* the conversation instead is what made a running turn impossible to look at.
   */
  private FileSession openForDisplay(String id) {
    Conversation running = conversations.get(id);
    if (running != null) {
      return running.session();
    }
    return SessionStore.open(sessionsDir(), id);
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
    String target = normaliseWorkspace(workspace);
    Path directory = sessionsDirOf(target);
    FileSession current = session();
    boolean active =
        target == null && current != null && current.id().equals(id);
    requireNotRunning(id, "delete it");
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
    String target = normaliseWorkspace(workspace);
    requireEverythingIdle("delete every session");
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
    // The session leaving the screen is closed only when nothing is using it. A conversation that is
    // running owns its file — it is what the turn appends to, and it may be the same object being put
    // back on screen — so closing it here would fail a turn that is still working.
    if (previous != null && previous != next && !conversations.containsKey(previous.id())) {
      previous.close();
    }
    publishUsage();
    publishStatus();
  }

  /**
   * Refuses an operation whose subject is running.
   *
   * <p>This is the rule that replaces "nothing may run while anything runs". One conversation being
   * busy must not stop the user from working in another, but it must still stop them from pulling the
   * ground out from under the one that is working: you cannot resume a session over the turn writing
   * it, or delete the file it is appending to.
   */
  private void requireNotRunning(String sessionId, String what) {
    Conversation running = sessionId == null ? null : conversations.get(sessionId);
    if (running != null && running.running()) {
      throw new IllegalStateException(
          "that session is running a turn; abort it before you " + what);
    }
  }

  /** Refuses while any conversation is working: what every conversation is built on is changing. */
  private void requireEverythingIdle(String what) {
    for (Conversation conversation : conversations.values()) {
      if (conversation.running()) {
        throw new IllegalStateException(
            "a turn is still running in session "
                + conversation.id()
                + "; abort it before you "
                + what);
      }
    }
  }

  /** Refuses while the session on screen is working. */
  private void requireShownIdle(String what) {
    requireNotRunning(sessionIdOf(session()), what);
  }

  // ------------------------------------------------------------------ approvals

  /** Answers a pending approval. Returns false when the id is unknown or already answered. */
  public boolean resolveApproval(String id, boolean allow, boolean remember) {
    Pending pending = id == null ? null : pendingApprovals.get(id);
    if (pending == null) {
      return false;
    }
    if (allow && remember) {
      setAutoApprove(true);
    }
    return pending.answer().complete(allow);
  }

  /**
   * One approval a turn is blocked on, and the session that asked.
   *
   * <p>The session is kept because abort has to be able to deny a conversation's own requests:
   * without it a turn waiting for a human could never be stopped, and a background turn the user is
   * not looking at would sit there for the full timeout with no way out.
   */
  /**
   * One approval a turn is blocked on: who asked, what about, and the answer it is waiting for.
   *
   * <p>The title and detail are kept, not just the future, because the prompt has to be drawable
   * again. An approval is a request blocked in memory rather than a message, so a page that switches
   * away and back has no way to reconstruct it from the conversation — it reports what is outstanding
   * and the page draws it. Without that, looking at another conversation silently threw the question
   * away and left abort as the only way out.
   */
  private record Pending(
      String sessionId, String title, String detail, CompletableFuture<Boolean> answer) {}

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
    String sessionId = currentTurnSession();
    String id = "ap-" + nextApprovalId.incrementAndGet();
    CompletableFuture<Boolean> answer = new CompletableFuture<>();
    pendingApprovals.put(id, new Pending(sessionId, title, detail, answer));
    // A status too, so a page that is not looking at this conversation still learns that something
    // is waiting — and so a page that *is* looking at it can rebuild the prompt from the status it
    // asks for on the way in.
    publishStatus();
    publish(
        sessionId,
        "approval",
        Json.object()
            .put("id", id)
            .put("title", title)
            .put("detail", detail)
            .put("sessionId", sessionId));
    try {
      boolean allow = answer.get(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      publish(sessionId, "approval-closed", Json.object().put("id", id).put("allow", allow));
      return allow;
    } catch (TimeoutException e) {
      publish(
          sessionId,
          "approval-closed",
          Json.object().put("id", id).put("allow", false).put("reason", "timeout"));
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

  /** The running state of one session, or null when it has none. */
  private Conversation conversation(String sessionId) {
    return sessionId == null || sessionId.isEmpty() ? null : conversations.get(sessionId);
  }

  /**
   * The session whose turn this thread is running.
   *
   * <p>Resolved from the calling thread rather than passed in, because the loop hands the approver to
   * its tools as a plain {@link Approver}: the turn's own session is the only thing that can say
   * which conversation is asking, and an approval published without one would appear in every open
   * transcript at once.
   */
  private String currentTurnSession() {
    for (Conversation conversation : conversations.values()) {
      if (conversation.ownedByCurrentThread()) {
        return conversation.id();
      }
    }
    return sessionIdOf(session());
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
    publish("", type, payload);
  }

  private void publish(String sessionId, String type, ObjectNode payload) {
    ObjectNode node = payload == null ? Json.object() : payload;
    node.put("type", type);
    // A payload that already names its session keeps it — the status is *about* one conversation and
    // is built with that id in hand. Only a payload that says nothing gets the given id, so an empty
    // one stays "this is about the server", not "this is about session ''".
    if (!node.has("sessionId")) {
      node.put("sessionId", sessionId == null ? "" : sessionId);
    }
    Event event =
        new Event(nextEventId.incrementAndGet(), type, node.path("sessionId").asText(), node);
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

  private static String sessionIdOf(FileSession session) {
    return session == null ? "" : session.id();
  }

  public void publishStatus() {
    // The status is about one conversation — the one on screen — so it says which, from the same
    // place the page reads the id out of: a status with no session would be filed nowhere.
    ObjectNode node = statusFields();
    node.put("sessionId", sessionIdOf(session()));
    publish(sessionIdOf(session()), "status", node);
  }

  public void publishUsage() {
    publish(sessionIdOf(session()), "usage", usageFields());
  }

  /** Usage of one session, which is what a turn publishes when its own books change. */
  public void publishUsage(String sessionId) {
    Conversation conversation = sessionId == null ? null : conversations.get(sessionId);
    FileSession target = conversation == null ? session() : conversation.session();
    ObjectNode node = usageFields(target);
    node.put("sessionId", sessionId == null ? "" : sessionId);
    publish(sessionId, "usage", node);
  }

  private FileSession session() {
    synchronized (sessionLock) {
      return session;
    }
  }

  @Override
  public void close() {
    closed = true;
    for (Pending waiting : pendingApprovals.values()) {
      waiting.answer().complete(false);
    }
    pendingApprovals.clear();
    turns.shutdownNow();
    Provider current = provider.getAndSet(null);
    if (current != null) {
      current.close();
    }
    for (Conversation conversation : conversations.values()) {
      conversation.close();
    }
    conversations.clear();
    FileSession active = session();
    if (active != null) {
      active.close();
    }
  }

  /**
   * One conversation's live state: its session file, its running turn, and its own books.
   *
   * <p>When the server ran one turn at a time this was all instance state, which is precisely why a
   * running turn locked everything. The unit that can be busy is now the conversation, so the flag,
   * the loop and the counters live here, and a second conversation simply has its own.
   *
   * <p>The counters are the ones the session file already records: they are read and written through
   * {@link FileSession#totals()}, so two conversations running side by side cannot add their tokens
   * to each other's ledger.
   */
  private final class Conversation {

    private final String id;
    private final FileSession session;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicReference<AgentLoop> loop = new AtomicReference<>();
    /** The thread running this conversation's turn, so a callback can be attributed to it. */
    private final AtomicReference<Thread> owner = new AtomicReference<>();
    /**
     * Where this conversation's tools run, fixed when the turn starts.
     *
     * <p>Read once rather than on every tool call, because the active workspace can change while a
     * turn is running — switching to another workspace must not redirect a job that is already half
     * done into a different directory. A turn owns its working directory from the moment it starts.
     */
    private volatile Path cwd;

    Conversation(String id, FileSession session) {
      this.id = id;
      this.session = session;
      this.cwd = AgentHub.this.cwd();
    }

    String id() {
      return id;
    }

    FileSession session() {
      return session;
    }

    Path cwd() {
      return cwd;
    }

    /** Re-reads the working directory, which a turn starting now should use. */
    void useCurrentCwd() {
      this.cwd = AgentHub.this.cwd();
    }

    boolean running() {
      return busy.get();
    }

    /** Claims the turn slot, or returns false when this conversation already has one running. */
    boolean begin() {
      return busy.compareAndSet(false, true);
    }

    void attach(AgentLoop running) {
      loop.set(running);
      owner.set(Thread.currentThread());
    }

    /** Releases the turn slot, so the next message in this conversation is accepted. */
    void end() {
      loop.set(null);
      owner.set(null);
      busy.set(false);
    }

    boolean ownedByCurrentThread() {
      return Thread.currentThread().equals(owner.get());
    }

    boolean abort() {
      AgentLoop running = loop.get();
      if (running == null) {
        return false;
      }
      // A turn waiting for a human is stopped by answering the question: the flag alone would leave
      // it blocked until the approval timed out, which is not "stopped" in any sense the user meant.
      for (Map.Entry<String, Pending> entry : pendingApprovals.entrySet()) {
        if (id.equals(entry.getValue().sessionId())) {
          entry.getValue().answer().complete(false);
        }
      }
      running.abort();
      return true;
    }

    synchronized void add(
        int input,
        int output,
        Integer cached,
        int userTurnDelta,
        int modelTurnDelta,
        int toolCallDelta,
        int toolErrorDelta,
        long elapsedDelta) {
      session.totals(
          session
              .totals()
              .plus(
                  input,
                  output,
                  cached,
                  userTurnDelta,
                  modelTurnDelta,
                  toolCallDelta,
                  toolErrorDelta,
                  elapsedDelta));
    }

    synchronized void addElapsed(long elapsedMillis) {
      session.totals(session.totals().withElapsed(elapsedMillis));
    }

    /** Writes the books to disk; called at turn boundaries so the file survives a kill. */
    synchronized void persist() {
      session.totals(session.totals());
    }

    void close() {
      session.close();
    }
  }

  /** Translates loop callbacks into wire events; nothing here decides policy. */
  private final class WebListener implements AgentListener {

    private final Conversation conversation;

    WebListener(String sessionId) {
      Conversation known = conversations.get(sessionId);
      this.conversation =
          known != null ? known : new Conversation(sessionId, session());
    }

    @Override
    public void onText(String delta) {
      publish(conversation.id(), "text", Json.object().put("delta", delta));
    }

    @Override
    public void onReasoning(String delta) {
      publish(conversation.id(), "reasoning", Json.object().put("delta", delta));
    }

    @Override
    public void onToolStart(Message.ToolCall call) {
      conversation.add(0, 0, null, 0, 0, 1, 0, 0);
      publish(conversation.id(), "tool", toolPayload(call, "start"));
      // The panel counts what is on screen: without this the counters would sit
      // at the previous turn's totals while a card says a call is running.
      publishUsage(conversation.id());
    }

    @Override
    public void onToolEnd(Message.ToolCall call, ToolResult result, long elapsedMillis) {
      if (result.error()) {
        conversation.add(0, 0, null, 0, 0, 0, 1, 0);
      }
      publish(
          conversation.id(),
          "tool",
          toolPayload(call, "end")
              .put("ok", !result.error())
              .put("elapsedMs", elapsedMillis)
              .put("output", result.content()));
      publishUsage(conversation.id());
    }

    /** Model turns are counted here, not from usage: a provider that reports no usage still ran. */
    @Override
    public void onTurnStart(int step) {
      conversation.add(0, 0, null, 0, 1, 0, 0, 0);
    }

    @Override
    public void onUsage(int inputTokens, int outputTokens, Integer cachedInputTokens) {
      conversation.add(inputTokens, outputTokens, cachedInputTokens, 0, 0, 0, 0, 0);
      publishUsage(conversation.id());
    }

    @Override
    public void onNotice(String text) {
      publish(conversation.id(), "notice", Json.object().put("text", text));
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
