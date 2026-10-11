package com.ccj.agent.web;

import com.ccj.agent.core.AgentListener;
import com.ccj.agent.core.AgentLoop;
import com.ccj.agent.core.AgentOptions;
import com.ccj.agent.core.ApprovalAnswer;
import com.ccj.agent.core.ApprovalRules;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.SpendLimit;
import com.ccj.agent.core.Checks;
import com.ccj.agent.core.Compaction;
import com.ccj.agent.core.Config;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.ToolRegistry;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.SubAgentRunner;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Prompts;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.ccj.agent.core.ToolSpec;
import com.ccj.agent.core.TokenEstimate;
import com.ccj.agent.core.UsageTotals;
import com.ccj.agent.core.VisionConfig;
import com.ccj.agent.core.Workspace;
import com.ccj.agent.core.ProviderDefinition;
import com.ccj.agent.core.Receipt;
import com.ccj.agent.export.SessionExport;
import com.ccj.agent.obs.MeteredApprover;
import com.ccj.agent.obs.Metric;
import com.ccj.agent.obs.Metrics;
import com.ccj.agent.obs.Recorder;
import com.ccj.agent.obs.RunTrace;
import com.ccj.agent.tool.TaskTool;
import com.ccj.agent.core.SessionRepair;
import com.ccj.agent.provider.ModelCatalog;
import com.ccj.agent.provider.Providers;
import com.ccj.agent.provider.ProviderStore;
import com.ccj.agent.provider.VisionClient;
import com.ccj.agent.workspace.WorkspaceStore;
import com.ccj.agent.session.AttachmentStore;
import com.ccj.agent.session.FileSession;
import com.ccj.agent.session.SessionStore;
import com.ccj.agent.session.Thumbnails;
import com.ccj.agent.ui.ToolSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 拥有浏览器无法拥有的东西：会话、正在跑的回合、回合被卡住时所等的审批，以及回合要去的模型。
 *
 * <p>有意做成无头的——它发布 {@link EventStream.Event}，对 HTTP 一无所知，所以同一个对象也可以支撑一个
 * websocket 或一个测试。
 *
 * <p>每个回合都属于一个对话，并在那个对话自己的槽位里运行，所以可以有好几个同时在飞：一个会话里的长任务
 * 不是用户不能在另一个会话里开始工作的理由。一次只接一个回合的单位是会话——<em>同一个</em>对话里的第二条
 * 消息会被拒绝而不是排队，因为两个写入者往同一份转录里写，正是它被毁掉的方式。每个事件都点名它所属的会话，
 * 因为一条流承载的是全部。
 *
 * <p>提供方在运行时可换：这个服务器在没有配置任何模型时也能正常启动，这样 UI 就能用来配置一个，而
 * {@link #applyConfig} 会在有任何东西写进磁盘之前校验并装好新的提供方。
 */
public final class AgentHub implements AutoCloseable {

  /**
   * {@link #close()} 最多等多久让正在跑的回合收尾。
   *
   * <p>五秒是一个「够一个正在写会话文件的回合写完」的界，而不是「够一个长命令跑完」的界：被中断的工具命令
   * 只看循环的中止标志，等它等不来。
   */
  private static final long SHUTDOWN_GRACE_SECONDS = 5;

  /** 按设置构建一个提供方；做成注入的，好让本类不沾传输层的细节。 */
  @FunctionalInterface
  public interface ProviderFactory {
    Provider create(Config config, Map<String, String> env);
  }

  /**
   * 服务器运行期间不会变化的那部分环境。
   *
   * @param workspaces 注册表，它的活动条目决定工作目录以及会话存放的位置；切换工作区就是前端一次性改变
   *     这两者的方式
   * @param cwdOverride 本次运行使用的工作目录，不属于工作区，这正是 {@code -C} 的含义；正常情况下为 null
   * @param configFile 设置持久化的位置，保存时也从这里读回
   * @param providerNames 提供给设置表单的名字
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
      boolean autoApprove,
      boolean subAgents) {

    /** 子代理存在之前的设置形状：一个都不提供。 */
    public Settings(
        String version,
        WorkspaceStore workspaces,
        Path cwdOverride,
        Path configFile,
        Map<String, String> env,
        ProviderFactory providerFactory,
        ModelCatalog modelCatalog,
        ProviderStore providerStore,
        FolderChooser folderChooser,
        boolean autoApprove) {
      this(
          version,
          workspaces,
          cwdOverride,
          configFile,
          env,
          providerFactory,
          modelCatalog,
          providerStore,
          folderChooser,
          autoApprove,
          false);
    }
  }

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

  private final ToolRegistry tools;
  /**
   * 是否提供 `task`。
   *
   * <p>除非被要求，否则关闭，而这个默认值是诚实的：子代理会把 token 花在一个用户没有亲手输入消息的第二
   * 个对话上，而拿到这个工具的代理，会在它本可以自己完成的工作上伸手去用它。把它打开，就是在声明这笔额外
   * 开销是想要的。
   */
  private final java.util.concurrent.atomic.AtomicBoolean subAgents =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final Settings settings;

  /** 每个正在跑的回合一个：干着活的会话拿到自己的线程，而不是服务器的线程。 */
  private final ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * 这个服务器的指标，`/metrics` 上暴露的就是它。
   *
   * <p>它住在这里，因为这里就是那个「只有一个」的地方：一个进程一个指标注册表，正如一个进程一个事件流。
   * 按会话分开的话，面板没法回答任何跨会话的问题（现在有几个对话在干活、一共花了多少 token）。
   */
  private final Metrics metrics = new Metrics();

  /** 事件扇出：谁在听、重连可以重放哪一段、每条事件拿到哪个序号。 */
  private final EventStream events = new EventStream(metrics);
  /** 审批台：谁在等人类，以及一个请求能等多久。 */
  private final ApprovalDesk approvals;
  private final AtomicReference<Provider> provider = new AtomicReference<>();

  /**
   * 每个会话最近一次运行的 trace，`GET /api/trace` 读的就是它。
   *
   * <p>只留最近一次，而且只留在内存里：它回答的是「刚刚那个回合里到底发生了什么」——一次运行的形状与
   * 它里面那几段耗时。一次运行一棵树，保留历史是另一件事，那件事由 {@link #setTraceSink} 指向的文件来做。
   */
  private final Map<String, RunTrace> lastTraces = new ConcurrentHashMap<>();

  /** 每次运行的 trace 写到哪；null 表示不留。由调用方设置，因为只有它知道命令行给了什么。 */
  private volatile Recorder.TraceSink traceSink;

  /**
   * 这个项目声明的编辑后检查。
   *
   * <p>工具表另有一份、用来决定编辑之后跑什么；这里那一份只回答收据里的一个问题：「这个文件被改过，可是
   * 有没有东西检查过它」。默认是什么都没有——一个没被告诉过的 hub 不该替用户声称某个文件被验证过。
   */
  private volatile Checks checks = Checks.none();

  /**
   * 正在跑的回合，每个会话一个，以及每一个所属的对话。
   *
   * <p>这个 map 就是服务器对「这个会话忙不忙」的回答：回合在开始之前登记，在结束时移除，所以同一个对话
   * 里的第二条消息会被拒绝，而任何其他对话里的消息不会。按 session id 键存放，所以两个对话从不共用槽位，
   * 在它们之间切换也不会把一个回合和另一个搞混。
   */
  private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

  /** 浏览前端正在看的那个对话。 */
  private final Object sessionLock = new Object();

  /**
   * 用户在屏幕上这个项目里的审批规则，在调用方提供之前为 null：没有任何规则的 hub 会对一切都发问，测试
   * 和嵌入式用法期望的就是这个。
   */
  private volatile ApprovalRules approvalRules;
  /** 一个回合此前的文件内容存到哪，这样这个回合才可以被收回。 */
  private final com.ccj.agent.session.CheckpointStore checkpoints;
  private volatile Config config;
  private volatile FileSession session;
  private volatile boolean closed;
  private final FolderChooser folderChooser;

  /**
   * 交给搬出去那几块的窄面（{@link HubState}，见 4.8）。
   *
   * <p>它是一个内部类而不是直接把那些方法放宽成 public：这个类自己的表面（`submit`、`close`、`abort`……）是它
   * 对外的承诺，不该因为「有几块代码想读同一个字段」而变大。窄面里每一个名字都是**特意挑出来的**——一块代码能
   * 不能开始一个回合、能不能关掉服务器，答案就在这两处代码的差集里。
   */
  private final HubState seat = new Seat();

  /** 表单往返那一块（4.8 搬出来的第二块）。 */
  private final HubForms forms = new HubForms(seat);

  public AgentHub(
      Provider initialProvider,
      Config config,
      ToolRegistry tools,
      Settings settings,
      FileSession session) {
    this.provider.set(initialProvider);
    this.config = config;
    this.tools = tools;
    this.checkpoints = com.ccj.agent.session.CheckpointStore.recording();
    this.settings = settings;
    this.session = session;
    // 审批台拿到的全是回调，而不是本对象的引用：规则来自设置那一刻装上的那个字段，状态由这里发布，
    // 审批按 id 作答，而它该归到哪个会话只有本类说得出来（见 currentTurnSession）。
    this.approvals =
        new ApprovalDesk(
            settings.autoApprove(),
            () -> approvalRules,
            this::publishStatus,
            events::publish,
            this::currentTurnSession);
    this.subAgents.set(settings.subAgents());
    this.folderChooser =
        settings.folderChooser() == null ? new NativeFolderChooser() : settings.folderChooser();
  }

  /** 工具解析相对路径的基准，也是本次运行的会话存放的地方。 */
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

  /** 屏幕上那个会话的 id；没有打开的会话时是空串。 */
  public String currentSessionId() {
    return sessionIdOf(session());
  }

  /** 这个服务器的指标；`GET /metrics` 暴露的就是它。 */
  public Metrics metrics() {
    return metrics;
  }

  /**
   * 每次运行的痕迹追加到哪。
   *
   * <p>由调用方设置，理由与 {@link #setApprovalRules} 相同：那是一个命令行 flag 指到的文件，而本类看不到
   * 命令行。不设置就什么都不写——痕迹的另一个去处是 {@link #lastTrace}，在内存里，永远都在。
   */
  public void setTraceSink(Recorder.TraceSink sink) {
    this.traceSink = sink;
  }

  /** 某个会话最近一次运行的痕迹；它还没跑过回合时为 null。 */
  public RunTrace lastTrace(String sessionId) {
    return sessionId == null ? null : lastTraces.get(sessionId);
  }

  /** 见 {@link #checks}。由调用方设置，理由与 {@link #setApprovalRules} 相同：那是一个命令行 flag 指到的
   * 文件，而本类看不到命令行。 */
  public void setChecks(Checks rules) {
    this.checks = rules == null ? Checks.none() : rules;
  }

  /**
   * 屏幕上那个会话最近一次运行的收据。
   *
   * <p>和痕迹一样只在内存里、只留最近一次：它回答的是「刚刚那个回合到底做了什么」，而留下历史是导出负责
   * 的事。没有收据时返回 {@code {"receipt": null}} 而不是 404——「这个进程里它还没跑过回合」是一种状态，
   * 不是一次失败。
   */
  public ObjectNode receiptJson() {
    FileSession current = session();
    Conversation conversation = current == null ? null : conversations.get(current.id());
    ObjectNode node = Json.object();
    Receipt receipt = conversation == null ? null : conversation.receipt();
    if (receipt == null) {
      node.putNull("receipt");
      return node;
    }
    node.set("receipt", receipt.toJson());
    return node;
  }

  /**
   * 一个会话导出成一份自包含的 HTML。
   *
   * <p>只读：打开、渲染、交回来。它不改动会话文件、也不写服务器上的任何地方——「导出绝不改动原文件」这条
   * 承诺由测试用（路径、大小、修改时间）的快照比对钉住，而不是靠自觉。
   */
  public String exportHtml(String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("导出需要一个会话 id");
    }
    return SessionExport.toHtml(openForDisplay(id), Instant.now());
  }

  /** 这条会话持有的待发送图片，或者 {@code null}。 */
  private Conversation.Held heldPicture(FileSession current) {
    if (current == null) {
      return null;
    }
    Conversation conversation = conversations.get(current.id());
    return conversation == null ? null : conversation.pendingPicture.get();
  }

  private ObjectNode statusFields() {
    FileSession current = session();
    Config active = config;
    Provider currentProvider = provider.get();
    ObjectNode node = Json.object();
    node.put("version", settings.version());
    node.put("configured", currentProvider != null);

    // 用配置里的名字，而不是实现的名字：用户选的是 "custom" + 一个中转 URL，给他看 "openai" 会让人困惑。
    node.put("provider", currentProvider == null || active.provider() == null ? "" : active.provider());
    node.put("model", active.model() == null ? "" : active.model());
    // 提供方实际会被调用的端点，而不是文件恰好存着的那个：自定义提供方由它的定义来服务，除非存着的 URL
    // 就是为它输入的。
    String endpoint = Providers.effectiveBaseUrl(active, settings.providerStore(), active.provider());
    node.put("baseUrl", endpoint == null ? "" : endpoint);
    node.put("cwd", cwd().toString());
    // 正常情况下为空。非空意味着 -C 钉住了一个不属于当前活动工作区的工作目录：两个互相冲突的事实，页面
    // 必须能把它显示出来，而不是让树声称工具跑在它们并不在的地方。
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
    node.put("autoApprove", approvals.autoApprove());
    // 上限是用户设的，不是推测的；null 表示没设。页面用它把「用了多少」变成「用了多少 / 上限多少」。
    if (config.maxTotalTokens() == null) {
      node.putNull("maxTotalTokens");
    } else {
      node.put("maxTotalTokens", config.maxTotalTokens());
    }
    // 一张已经描述好、还没走的图片：刷新之后那条缩略条还在，而不是变成一张看不见、却会跟着下一句话发
    // 出去的图片。
    Conversation.Held held = heldPicture(current);
    if (held == null) {
      node.putNull("picture");
    } else {
      node.set("picture", pictureEvent(held.saved(), held.description(), held.mediaType()));
    }
    node.put("subAgents", subAgents.get());
    // "busy" 回答的是屏幕上那个对话：页面的输入框针对的是它显示的转录，别处正在跑的回合不能把它禁用掉。
    Conversation shown = conversation(current == null ? "" : current.id());
    node.put("busy", shown != null && shown.running());
    // 屏幕上的回合背后有什么在排队。页面会画出计数，所以代理思考时敲进去的消息是可见地被扣住，而不是被
    // 悄悄吞掉。
    ArrayNode queued = node.putArray("queued");
    if (shown != null) {
      shown.queued().forEach(queued::add);
    }
    // 还有哪些别的对话在干活，好让树标出它们的行。给名字而不是给数量：「有东西在跑」没法告诉你该回到哪个
    // 会话去看。
    ArrayNode runningNow = node.putArray("running");
    for (Conversation entry : conversations.values()) {
      if (entry.running() && !entry.id().equals(current == null ? "" : current.id())) {
        runningNow.add(entry.id());
      }
    }
    // 这个对话正在等的审批。它们是阻塞在内存里的请求而不是消息，所以不在页面打开会话时重放的历史里——这里
    // 不列出来的话，看一眼别的对话再回来就会丢掉提示，只剩中止这一条出路。
    ArrayNode waiting = node.putArray("approvals");
    String shownId = current == null ? "" : current.id();
    for (Map.Entry<String, ApprovalDesk.Pending> entry : approvals.pending().entrySet()) {
      ApprovalDesk.Pending pending = entry.getValue();
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
    // 选择器坐在输入框上方，所以这些档位也跟着实时状态一起走。
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
   * 一个工作区的会话；{@code workspace} 为 null 时则是活动工作区的会话。
   *
   * <p>树视图必须能在切换之前先展示一个折叠文件夹的内容，这正是这次调用：读另一个工作区的列表绝不能
   * 移动当前活动的那个。
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
                          "没有名为 '" + workspace.strip() + "' 的工作区"));
      directory = target.sessionsDir();
    }
    ArrayNode array = Json.mapper().createArrayNode();
    // 列一次会话目录会重新推导刚被写入的那个会话（见 SessionIndex），而这发生在每个回合结束之后——所以
    // 它值得有一个自己的数字，好让「侧栏开始变慢」这件事有地方冒出来。
    long started = System.nanoTime();
    List<SessionStore.Summary> sessions = SessionStore.list(directory);
    metrics.observe(Metric.SESSION_LIST_SECONDS, (System.nanoTime() - started) / 1_000_000_000.0);
    for (SessionStore.Summary summary : sessions) {
      ObjectNode node = array.addObject();
      node.put("id", summary.id());
      node.put("title", summary.title());
      node.put("preview", summary.preview());
      node.put("messageCount", summary.messageCount());
      node.put("lastModified", TIMESTAMP.format(summary.lastModified()));
      // 哪些行在干活，这样留在另一个对话里跑着的回合从哪儿都看得见，而不只是从它正在写的那份转录里。
      Conversation conversation = conversations.get(summary.id());
      node.put("running", conversation != null && conversation.running());
    }
    return array;
  }

  /**
   * 当前会话的累计统计。
   *
   * <p>{@code cacheHitRate} 在提供方报出缓存数字之前为 null：「没有信息」和「什么都没被缓存」是两件
   * 不同的事实，而其中只有一件是 0%。
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
    // 单独一行，绝不并进 steps：压缩是用户付过费的一次请求，而且它不是对话的一个回合。面板把它显示出来，
    // 这些 token 才算有交代。
    node.put("compactions", totals.compactions());
    node.put("elapsedMs", totals.elapsedMillis());
    // 这个对话会占掉模型窗口的多少。它是估算值，面板也这么说：这个计数是启发式的，真正重要的数字是用户
    // 付过费的那个。
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

  /** 一个会话的账本：文件此前记录的内容；没有文件时则是一本空账。 */
  private static UsageTotals totalsOf(FileSession session) {
    return session == null ? UsageTotals.empty() : session.totals();
  }

  /**
   * 把当前会话重放成这条流当初会发出的事件，这样页面用一条代码路径就能渲染历史和实时回合。
   *
   * <p>重放的工具结果不带耗时：对话存的是模型看到的东西，而不是工具花了多久，凭空造一个 0 会被读成一次
   * 实测。
   */
  public ObjectNode historyJson() {
    FileSession current = session();
    ArrayNode events = Json.mapper().createArrayNode();
    // 先做修复，这样一个被中断弄成非法的对话，重放出来的样子与模型将读到的样子一致：结果丢失的那次调用
    // 显示为「未运行」，而不是一张永远转圈的卡片。
    List<Message> messages =
        current == null || current.messages().isEmpty()
            ? List.of()
            : SessionRepair.apply(current.messages()).messages();
    for (Message message : messages) {
      switch (message) {
        case Message.User user -> events.add(replay("user").put("text", user.text()));
        case Message.Assistant assistant -> {
          // 先放推理：那是模型在回答之前对自己说的话，也是实时流产生它们的顺序。没有这一段，切换会话后
          // 重放的对话会丢掉它的思考——推理就在文件里，所以丢掉它的重放就是对另一个对话的重放。
          for (Message.Thinking block : assistant.thinking()) {
            if (block.redacted() || block.text().isEmpty()) {
              // 被隐去的内容块，其载荷是不透明的、属于模型且原样保留：把它当散文渲染，会在转录里放一堵
              // base64 的墙。只有签名的块同样没有东西可展示。
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
                    .put("summary", ToolSummary.summarise(call)));
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
        // 摘要现在也是对话的一部分——被压缩的那些回合归结成了它——所以重放必须把它画出来。没有这个分支
        // 时它会掉进下面的 default，压缩后重新加载的页面会显示保留下来的往复，却没有任何迹象说明发生过
        // 什么，而那正是用户需要看到、用来确认压缩起了作用的东西。
        case Message.Summary summary ->
            events.add(
                replay("summary")
                    .put("text", summary.text())
                    .put("covers", summary.covers())
                    .put("source", summary.source()));
        default -> {
          // 系统消息不属于被渲染的对话。
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

  // ---- 表单往返：搬到了 HubForms（4.8），这里只剩下委托

  public ObjectNode modelsJson() {
    return forms.modelsJson();
  }

  public ObjectNode addProvider(JsonNode body) {
    return forms.addProvider(body);
  }

  public ObjectNode removeProvider(String name) {
    return forms.removeProvider(name);
  }

  public ObjectNode addBuiltInProvider(String name) {
    return forms.addBuiltInProvider(name);
  }

  public ObjectNode addModel(String provider, String model) {
    return forms.addModel(provider, model);
  }

  public ObjectNode removeModel(String provider, String model) {
    return forms.removeModel(provider, model);
  }

  public ObjectNode workspacesJson() {
    return forms.workspacesJson();
  }

  public ObjectNode addWorkspace(String name, String path) {
    return forms.addWorkspace(name, path);
  }

  public ObjectNode addWorkspace(Path path) {
    return forms.addWorkspace(path);
  }

  public ObjectNode removeWorkspace(String name) {
    return forms.removeWorkspace(name);
  }

  public ObjectNode switchWorkspace(String name) {
    return forms.switchWorkspace(name);
  }

  public ObjectNode configJson() {
    return forms.configJson();
  }

  public ObjectNode applyConfig(JsonNode posted) {
    return forms.applyConfig(posted);
  }

  public ObjectNode testConfiguration(JsonNode posted) {
    return forms.testConfiguration(posted);
  }

  // ------------------------------------------------------------------ turns

  /**
   * 在前端正看着的会话里启动一个回合。那个会话已经有一个在跑时返回 false。
   *
   * <p>按会话拒绝而不是按服务器拒绝，正是重点：一个对话里的长任务绝不能挡住用户在另一个对话里开始工作。
   * 仍然拒绝的，是<em>同一个</em>对话里的第二个回合——两个写入者往同一份转录里追加，正是毁掉它的东西。
   */
  public Submit submit(String text) {
    return submit(text, null);
  }

  /**
   * 一句话，以及它带着的那张已经描述过的图片。
   *
   * <p>这就是「上传」和「发送」重新合成一条消息的地方：描述放在前面，用户说的话放在后面，于是模型一次
   * 收到的是「这是图片里有什么」和「照这个做」——而不是先收到前半句、自己决定后半句。
   *
   * <p>{@code pictureName} 是一次一致性检查，不是指令：图片就是这条会话持有的那一张（上传即「我要发它」），
   * 而页面如果在命名另一张，那就是页面和服务器对不上了，值得说清楚而不是猜。名字不对时什么都不发送，
   * 图片也仍然留着——一次重命名竞争不该花掉用户的一次上传。
   */
  public Submit submit(String text, String pictureName) {
    if (closed) {
      throw new IllegalStateException("web 会话正在关闭");
    }
    FileSession current = session();
    if (current == null) {
      throw new IllegalStateException("没有打开的会话");
    }
    Conversation conversation =
        conversations.computeIfAbsent(current.id(), key -> new Conversation(key, current, this::cwd, approvals::denySession));
    Conversation.Held held = conversation.pendingPicture.get();
    String name = held == null ? null : held.saved().path().getFileName().toString();
    if (pictureName != null && !pictureName.isBlank() && held != null) {
      boolean named =
          pictureName.equals(name) || pictureName.equals(held.saved().path().toString());
      if (!named) {
        throw new IllegalArgumentException(
            "这张会话里待发送的图片是 " + name + "，不是 " + pictureName + "——请重新选一张");
      }
    }
    if (pictureName != null && !pictureName.isBlank() && held == null) {
      throw new IllegalArgumentException("这条会话里已经没有待发送的图片了——请重新选一张");
    }
    // 空白的正文只有在它带着一张图片时才是可以接受的：那时候这条消息说的就是图片本身，也就是过去上传
    // 独自达成的效果，只不过现在是用户要求的。
    if ((text == null || text.isBlank()) && held == null) {
      throw new IllegalArgumentException("需要一条消息");
    }
    if (provider.get() == null) {
      throw new IllegalStateException("没有配置模型——打开「设置」添加一个");
    }
    // 花费上限在**开始之前**判定，而不是在花掉了之后：一个跑了一半才被拦下的回合，既花了钱又没有结果，
    // 而用户能做的补救（提高上限、改用便宜模型）都得在下一次开始之前做。
    String overBudget =
        SpendLimit.refusal(
            current.totals().inputTokens() + current.totals().outputTokens(),
            config.maxTotalTokens());
    if (overBudget != null) {
      throw new IllegalStateException(overBudget);
    }
    String composed =
        held == null ? text.strip() : held.block() + (text.isBlank() ? "" : "\n\n" + text.strip());
    if (conversation.begin()) {
      conversation.pendingPicture.compareAndSet(held, null);
      startTurn(conversation, composed);
      return Submit.STARTED;
    }
    // 忙，而这不再是个错误：消息等着轮到自己。用 409 拒绝它是旧行为，而一次拒绝会把一段思考中的停顿变成
    // 彻底停摆——你看着这个回合时冒出的想法，等它结束时已经没了。
    if (!conversation.enqueue(composed)) {
      throw new IllegalStateException(
          "这个对话已经有 "
              + Conversation.MAX_QUEUED_MESSAGES
              + " 条消息在等了；请等回合结束，或者中止它");
    }
    // 图片跟着这句话走了，所以它不再等着。入队失败时上面就已经抛出，而图片仍在原处：队列满了是稍后重试，
    // 不是丢掉一次上传。
    conversation.pendingPicture.compareAndSet(held, null);
    publish(conversation.id(), "picture", Json.object().putNull("name"));
    publishStatus();
    return Submit.QUEUED;
  }

  /** 一条提交上来的消息后来怎么了：它开始了，或者它正在跑着的那个回合后面排队。 */
  public enum Submit {
    STARTED,
    QUEUED
  }

  /**
   * 发布用户的消息，并把回合交给线程池。
   *
   * <p>从 {@link #submit} 里拆出来，是为了那一个必须在启动回合之前更早占住对话的调用方：一张图片是在
   * 占位与回合<em>之间</em>由另一个模型描述的，而在这个调用之后才去占位，要么会把一次描述花在一个已经有
   * 回合在跑的对话上，要么会把占位从已经持有它的那个回合手里夺走。
   */
  private void startTurn(Conversation conversation, String text) {
    // 这个回合的工具将在哪里运行，现在就定下来：用户是在看着这个工作区时按下发送的，之后切换到另一个
    // 工作区绝不能挪动他们已经开始的活。
    conversation.useCurrentCwd();
    FileSession current = conversation.session();
    current.totals(current.totals().plus(0, 0, null, 1, 0, 0, 0, 0));
    publish(current.id(), "user", Json.object().put("text", text));
    publishStatus();
    turns.submit(() -> runTurn(conversation, text));
  }

  /**
   * 把一张图片存到会话旁边，让视觉模型描述它，并基于那段描述启动一个回合。
   *
   * <p>主模型从不收到图片。它收到的是文本——描述，以及文件在哪里，这样一个被描述漏掉的细节可以用
   * {@code read} 工具读回来——而磁盘上的会话仍是可读的文本，旁边多了一张图片。视觉模型之所以是一次
   * 单独的调用，全部理由就在这里：两种线上协议都不必长出图片分支，而 {@code Message}、两个提供方、压缩
   * 以及每一个渲染器都保持原样。
   *
   * <p>这里失败就拒绝这张图片，不启动任何回合。什么都还没到达主模型，所以一个「图片无法被描述」的回合
   * 会是用户没有要求过的回合，而且关于一张没人看过的图片。
   */
  public ObjectNode describePicture(String name, byte[] bytes) {
    if (closed) {
      throw new IllegalStateException("web 会话正在关闭");
    }
    FileSession current = session();
    if (current == null) {
      throw new IllegalStateException("没有打开的会话");
    }
    // 字节说了算，而且先由它说了算：不是图片的上传，在任何东西被写入之前、在视觉端点被调用之前就被拒绝。
    String mediaType = AttachmentStore.mediaTypeOf(bytes);
    Config active = config;
    if (active.vision() == null || !active.vision().isConfigured()) {
      throw new IllegalStateException(
          "没有配置视觉模型，所以图片没法被描述——请设置 "
              + settings.configFile()
              + " 里的 \"vision\" 块（baseUrl、model 和一个密钥），"
              + "或者传入 --vision-base-url 和 --vision-model");
    }
    Conversation conversation =
        conversations.computeIfAbsent(current.id(), key -> new Conversation(key, current, this::cwd, approvals::denySession));
    AttachmentStore.Saved saved;
    String description;
    try (VisionClient client = VisionClient.from(active.vision(), settings.env())) {
      description = client.describe(bytes, mediaType);
      saved = AttachmentStore.forSession(current.file()).save(name, bytes);
    }
    // 没有回合，也不占位：这里没有任何东西开始运行。一个已经在跑自己的回合的对话仍然可以接收一张图片，
    // 因为描述这张图片不是那个对话的工作——它是一次辅助调用，结果就停在这里等着，而旧的「先中止它」正是
    // 把一次上传变成一条不通的路。
    //
    // 一次只留一张，所以第二次上传取代第一次。被取代的那个文件留在会话的附件目录里，随之一起删除——它
    // 从来没有进过对话，所以也不该假装它被送出去过。
    Conversation.Held previous =
        conversation.pendingPicture.getAndSet(
            new Conversation.Held(saved, description, mediaType));
    publish(
        current.id(),
        "picture",
        pictureEvent(saved, description, mediaType));
    return Json.object()
        .put("accepted", true)
        .put("attachment", saved.path().toString())
        .put("mediaType", saved.mediaType())
        .put("description", description)
        // 说清楚它现在处于什么状态：一个 202 说的是「收到了」，而页面需要知道它还没有被送出去。
        .put("held", true)
        .put("replaced", previous != null);
  }

  /**
   * 页面要显示的那张待发送图片，或者 {@code null}。
   *
   * <p>状态快照和事件说的是同一件事：刷新页面之后那张缩略条仍然在，而不是变成一张看不见、却会跟着下一
   * 句话一起发出去的图片。
   */
  private static ObjectNode pictureEvent(
      AttachmentStore.Saved saved, String description, String mediaType) {
    return Json.object()
        .put("name", saved.path().getFileName().toString())
        .put("file", saved.path().toString())
        .put("mediaType", mediaType)
        .put("description", description);
  }

  /**
   * 丢掉这张待发送的图片，它没有跟着任何一句话走。
   *
   * <p>返回丢掉的是哪一个的名字，或者 {@code null}：页面说得出「移除了 photo.png」，而点名一张从来
   * 不在那里的图片是一次会撒谎的成功。
   */
  /**
   * 下一次退回会动哪些文件，以及还能退回几个回合。
   *
   * <p>只读：它不建立任何东西，也不消费任何东西。退回会覆盖磁盘上的文件，而一个会覆盖文件的按钮值得先
   * 说出它要覆盖什么——这份清单于是成了那个「先说」。
   */
  public ObjectNode undoPreview() {
    FileSession current = session();
    if (current == null) {
      throw new IllegalStateException("没有打开的会话");
    }
    Conversation conversation = conversations.get(current.id());
    if (conversation != null && conversation.running()) {
      throw new IllegalStateException("还有回合在跑；预览退回之前请先中止它");
    }
    return checkpoints.previewUndo(current.file(), cwd());
  }

  /**
   * 这条会话附件目录里某个文件的小图，或者 {@code null}（那里没有这个文件）。
   *
   * @throws IllegalArgumentException 名字不是一个文件名的形状时抛出——它是调用方给的路径参数，而
   *     {@code ../} 会让它变成「读服务器上的任意文件」
   */
  public PictureFile attachmentThumbnail(String name) {
    FileSession current = session();
    if (current == null || name == null || name.isBlank()) {
      return null;
    }
    Path file = AttachmentStore.forSession(current.file()).resolve(name);
    if (file == null) {
      return null;
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (IOException e) {
      throw new IllegalStateException("读不出这张图片：" + e.getMessage());
    }
    byte[] thumb = Thumbnails.of(bytes);
    return new PictureFile(thumb, AttachmentStore.mediaTypeOf(thumb));
  }

  /**
   * 把调用方给的一个名字解析成目录里的一个文件，或者 {@code null}。
   *
   * <p>规矩是「解析之后仍然必须在那个目录里」：名字里带分隔符、或者解析后跑到目录外面去（{@code ..}、
   * 绝对路径、指向别处的符号链接），一律返回 {@code null}。检查的是解析结果而不是名字的拼写，因为名字
   * 的拼写有太多写法。
   */
  /** 一张图片的字节，以及它们是什么类型。 */
  public record PictureFile(byte[] bytes, String mediaType) {}

  public String discardPicture() {
    FileSession current = session();
    if (current == null) {
      return null;
    }
    Conversation conversation = conversations.get(current.id());
    if (conversation == null) {
      return null;
    }
    Conversation.Held dropped = conversation.pendingPicture.getAndSet(null);
    if (dropped == null) {
      return null;
    }
    publish(current.id(), "picture", Json.object().putNull("name"));
    publishStatus();
    return dropped.saved().path().getFileName().toString();
  }


  /**
   * 压缩屏幕上的这个会话：对话里较早的那部分被模型写的一段摘要替换，会话以同一个 id 的下一代继续存在。
   *
   * <p>那个会话有回合在跑时拒绝。压缩重写的是「这个对话是什么」，而在一个正在跑的回合底下做这件事，就是
   * 两个写入者往同一份转录里写——也就是按对话设置的回合标记存在的意义所在。其他对话不受影响，和普通回合
   * 一样。
   *
   * <p>这次摘要请求<em>不是</em>一个回合：它不走那个循环，不追加用户消息，也不推进回合计数。它是这个
   * 服务器就一段已经发生过的工作向模型提的问题，如果它显示成用户说过的东西，那转录就是在撒谎。
   */
  public ObjectNode compact() {
    if (closed) {
      throw new IllegalStateException("web 会话正在关闭");
    }
    Provider current = provider.get();
    if (current == null) {
      throw new IllegalStateException("没有配置模型——打开「设置」添加一个");
    }
    FileSession shown = session();
    if (shown == null) {
      throw new IllegalStateException("没有打开的会话");
    }
    String id = shown.id();
    Conversation conversation =
        conversations.computeIfAbsent(id, key -> new Conversation(key, shown, this::cwd, approvals::denySession));
    // 全程占位，这样一个回合没法在压缩进行到一半时启动，输入框也会在这次压缩期间把该对话显示为忙。
    if (!conversation.begin()) {
      throw new IllegalStateException("还有回合在跑；请先中止它");
    }
    try {
      return runCompaction(conversation, current);
    } finally {
      conversation.end();
    }
  }

  private ObjectNode runCompaction(Conversation conversation, Provider current) {
    List<Message> before = conversation.session().messages();
    if (!Compaction.possible(before)) {
      throw new IllegalArgumentException(
          "这个对话还没有可压缩的东西——它比压缩会保留的 "
              + Compaction.KEEP_EXCHANGES
              + " 组往复还短");
    }
    Config active = config;
    int beforeTokens = TokenEstimate.of(before);
    String transcript = Compaction.transcript(before);
    Message.Assistant reply;
    try {
      reply =
          current.complete(
              new Provider.Request(
                  active.model(),
                  // 不给工具、不给历史：这是就一份就在消息里的转录提一个问题，而给出工具集等于请模型去
                  // 干活，而不是描述已经干过的活。
                  null,
                  List.of(new Message.User(Compaction.INSTRUCTIONS + "\n\n" + transcript)),
                  List.of(),
                  null,
                  null,
                  active.reasoning()),
              // 摘要也是**一次真的模型请求**，而用户为它付钱：把它丢掉，就是让用量面板与花费上限同时少算
              // 一笔（每次压缩都少算一笔）。这里只记 token，不记步数与回合——压缩不是对话里的一步，那两格
              // 由 plusCompaction() 表达。
              event -> accountForSummary(conversation, event));
    } catch (Exception e) {
      throw new IllegalStateException("无法摘要这个对话：" + FormFields.message(e));
    }
    String summary = reply.text() == null ? "" : reply.text().strip();
    if (summary.isEmpty()) {
      throw new IllegalStateException("模型返回了空摘要；什么都没有改动");
    }
    return install(conversation, before, summary, beforeTokens);
  }

  /** 摘要那一次请求的 token，记进这条会话的账本；别的都被忽略。 */
  private static void accountForSummary(Conversation conversation, Provider.Event event) {
    if (event instanceof Provider.Event.Usage u) {
      conversation.add(u.inputTokens(), u.outputTokens(), u.cachedInputTokens(), 0, 0, 0, 0, 0);
    }
  }

  /**
   * 写下下一代，并报告发生了什么。
   *
   * <p>那些被摘要掉的消息所在的那个文件，既被记录也被点名给模型，这样一个被摘要漏掉的细节可以读回来：
   * {@code read} 是只读的、不需要审批，正是这一点把一次有损的压缩变成一次可以按需读回去的压缩。
   */
  private ObjectNode install(
      Conversation conversation, List<Message> before, String summary, int beforeTokens) {
    FileSession session = conversation.session();
    Path source = session.file();
    Compaction.Result result;
    try {
      result = Compaction.apply(before, summary, source.toString(), conversation.cwd());
    } catch (Compaction.NotWorthIt notWorthIt) {
      // 摘要出来并不比它要替换的东西小。报成一次拒绝而不是写下去：一次什么都没腾出来的压缩是有成本无收益
      // 的，而诚实告诉用户的是：这个对话还没长到值得压缩的程度。
      throw new IllegalStateException(
          "没有收益："
              + notWorthIt.getMessage()
              + " —— 这个对话还不够长，摘要还省不下任何东西");
    }
    session.compactInto(result.messages(), session.totals().plusCompaction());
    int afterTokens = TokenEstimate.of(result.messages());
    int generation = FileSession.newestGeneration(sessionsDir(), session.id());
    publish(
        conversation.id(),
        "compacted",
        Json.object()
            .put("summarised", result.summarised())
            .put("kept", result.kept())
            .put("beforeTokens", beforeTokens)
            .put("afterTokens", afterTokens)
            .put("generation", generation)
            .put("savedPercent", result.savedPercent())
            .put("source", source.toString()));
    publishUsage(conversation.id());
    publishStatus();
    ObjectNode node = Json.object();
    node.put("compacted", true);
    node.put("summarised", result.summarised());
    node.put("kept", result.kept());
    node.put("beforeTokens", beforeTokens);
    node.put("afterTokens", afterTokens);
    node.put("generation", generation);
    node.put("savedPercent", result.savedPercent());
    node.put("source", source.toString());
    return node;
  }

  /** 请求屏幕上这个会话的回合在下一个安全点停下。 */
  public boolean abort() {
    FileSession current = session();
    Conversation conversation = current == null ? null : conversations.get(current.id());
    return conversation != null && conversation.abort();
  }

  /**
   * 停掉屏幕上的回合，并丢掉排在它后面的东西。
   *
   * <p>两件事都做，因为中止是事情出错时按下的按钮：留下四条消息等着被中止的回合一松手就开始，与停下来
   * 正好相反。数量会被发布，所以一条被丢掉的消息是一个可见事件，而不是一片寂静。
   */
  public boolean abortAll() {
    FileSession current = session();
    Conversation conversation = current == null ? null : conversations.get(current.id());
    if (conversation == null) {
      return false;
    }
    int dropped = conversation.clearQueue();
    boolean stopped = conversation.abort();
    if (dropped > 0) {
      publish(
          conversation.id(),
          "notice",
          Json.object()
              .put("text", "已中止；丢掉了 " + dropped + " 条排队的消息"));
    }
    return stopped || dropped > 0;
  }

  /** 请求某个具名会话的回合停下，无论它是不是屏幕上的那个。 */
  public boolean abort(String sessionId) {
    Conversation conversation = sessionId == null ? null : conversations.get(sessionId);
    return conversation != null && conversation.abort();
  }

  /**
   * 一个用户回合，从启动它的那次请求，到结束它的事件。
   *
   * <p>这里发布的一切都带着 {@code conversation.id()}，因为其中好几个会同时运行，而页面按会话归档
   * 事件。账本是会话自己的：总计存在对话的文件里，所以并排运行的两个回合不会把 token 加到对方账上。
   */
  private void runTurn(Conversation conversation, String text) {
    // 检查点属于这条线程，而这条线程正是回合的工具运行的那条：一个从字段里取「正在进行的回合」的存储，
    // 会把并排运行的对话搞混。
    checkpoints.beginTurn(conversation.session().file(), conversation.cwd());
    AgentLoop loop = newLoop(conversation);
    conversation.attach(loop);
    // 账本的前后两次快照：收据里「这一轮花了多少」是它们之差，而不是会话的累计——一个累计数字没法回答
    // 「刚才那一下花了多少」。
    UsageTotals before = conversation.session().totals();
    long started = System.nanoTime();
    AgentLoop.Result result = null;
    Throwable failure = null;
    try {
      result = loop.run(text);
    } catch (RuntimeException | Error e) {
      // Error 不是这个回合该当作值上报的东西，但它仍然是回合的结束：把下面的清理只留给顺利路径，会因为
      // 一个工具抛出的 StackOverflowError 而让这个会话永远卡在忙的状态——之后每条消息都被 409 拒绝。
      failure = e;
    }
    // 回合在它宣布之前就已经结束。一个收到 `done` 就发下一条消息的客户端，绝不能发现这个会话还声称自己
    // 忙——那个竞态的结果是被 409 回答的一条消息，以及一个在别的东西发布状态之前一直禁用着的输入框。
    conversation.end();
    // 在收尾的其他事之前关闭：这个回合改动的东西要趁着它仍是本线程跑的那个时写下，而下面的一切都在发布
    // 关于它的事件。
    checkpoints.endTurn();
    conversation.receipt(
        Receipt.of(
            conversation.id(),
            Receipt.lastTurn(conversation.session().messages()),
            checkpoints.lastTurnFiles(conversation.session().file(), conversation.cwd()),
            before,
            conversation.session().totals(),
            config.outputLimitBytes() == null ? 0 : config.outputLimitBytes(),
            checks,
            conversation.cwd()));
    conversation.addElapsed((System.nanoTime() - started) / 1_000_000);
    // 在回合边界做持久化，既让文件不至于每来一个 token 就长一点，也仍然扛得住被杀：最坏情况下丢的是最后
    // 一个回合，而绝不是整个会话。
    conversation.persist();
    publishUsage(conversation.id());
    publishStatus();
    if (failure != null) {
      publish(conversation.id(), "error", Json.object().put("message", FormFields.message(failure)));
    } else {
      publish(
          conversation.id(),
          "done",
          Json.object().put("finalText", result.finalText()).put("aborted", result.aborted()));
    }
    // 长过预算的对话在这里、在回合之间被压缩：下一个回合才是为冗长历史付账的那个，而把已经发生的事摘要
    // 一遍，比让投影悄悄省略它更友好。这一步排在队列移动之前，所以一条一直在等的消息是在压缩过的对话上
    // 开始的，而不是在那个没人想再发一遍的对话上。
    autoCompact(conversation);
    // 队列最后才移动，在 `done` 发布之后：一个对回合结束作出反应的客户端，把它的下一条消息发进一个已经
    // 空闲的会话，而一条一直在等的消息会作为它自己的回合开始。
    drain(conversation);
  }

  /**
   * 压缩一个长过预算的对话，如果值得这么做的话。
   *
   * <p>预算是 `maxContextTokens`——投影裁剪到的同一个数字——而条件是*对话*越过了它。越过它时，
   * {@link com.ccj.agent.core.ContextBudget} 的应对是省略旧的工具输出，然后成段丢弃往复，那是用最笨
   * 的方式造成损失：压缩则是用一段说明它原样的摘要，替换掉同一段历史。两者都有损失；只有其中一个会告诉
   * 模型它丢了什么。
   *
   * <p>没有配置预算就没有自动压缩：没有什么可以越过，而一次没人要求、还要花掉一次模型调用的压缩，不是
   * 可以凭猜去做的事。一次拒绝（摘要并不比它的输入小）会被记住，这样同一个调用不会在每个回合结束时再付
   * 一次钱。
   */
  private void autoCompact(Conversation conversation) {
    Config active = config;
    Integer budget = active.maxContextTokens();
    if (closed || budget == null || budget <= 0 || provider.get() == null) {
      return;
    }
    List<Message> messages = conversation.session().messages();
    if (!Compaction.possible(messages)) {
      return;
    }
    int estimate = TokenEstimate.of(messages);
    if (estimate <= budget || estimate <= conversation.autoCompactRefusedAt()) {
      return;
    }
    if (!conversation.begin()) {
      return;
    }
    try {
      ObjectNode result = runCompaction(conversation, provider.get());
      publish(
          conversation.id(),
          "notice",
          Json.object()
              .put(
                  "text",
                  "已自动压缩："
                      + result.path("summarised").asInt()
                      + " 条较旧的消息变成了一段摘要（估计 token "
                      + result.path("beforeTokens").asInt()
                      + " → "
                      + result.path("afterTokens").asInt()
                      + "）。完整对话仍然在磁盘上。"));
    } catch (RuntimeException e) {
      // 被拒绝，或者摘要调用失败了。无论哪种，在这个尺寸下都不值得再来一次，而说出来比一片看起来什么都没
      // 发生的寂静要好。
      //
      // 下一次尝试要等增长到两倍，而不是再多一成。对着一个推理模型在线实测过：它写出的摘要可能是它所替换
      // 的那些往复的好几倍——866 个 token 对 255 个——所以一次拒绝并不是差一点命中，而是一种形状，在对话
      // 稍稍变大时仍然保持这种形状。每增长十分之一个对话就重试，等于每几个回合就有一次注定失败的模型调用；
      // 翻倍让每次拒绝都换来真正的距离。
      conversation.autoCompactRefusedAt((long) (estimate * 2));
      publish(
          conversation.id(),
          "notice",
          Json.object()
              .put("text", "没有自动压缩：" + FormFields.message(e)));
    } finally {
      conversation.end();
    }
  }

  /**
   * 启动这个对话忙的时候敲进来的下一条消息。
   *
   * <p>一步，而不是一个循环：它启动的回合在结束时回调这里，所以五条排队的消息作为五个回合运行，各有
   * 自己的账本、自己的状态和自己的 `done`。
   */
  private void drain(Conversation conversation) {
    if (closed) {
      return;
    }
    String next = conversation.dequeue();
    if (next == null) {
      return;
    }
    if (!conversation.begin()) {
      // 中间有东西占走了槽位，说明有回合在跑：这条消息回到队首而不是被丢掉，而那个回合结束时还会再走到
      // 这里。
      conversation.enqueueFirst(next);
      return;
    }
    startTurn(conversation, next);
  }

  private AgentLoop newLoop(Conversation conversation) {
    Provider current = provider.get();
    if (current == null) {
      throw new IllegalStateException("没有配置模型——打开「设置」添加一个");
    }
    Config active = config;
    AgentOptions options =
        new AgentOptions(
            active.model(),
            // 项目自己的 CCJ.md 是从这个对话的工作目录读取的，也就是它回合启动时定下的那个：规则文件
            // 属于工具实际将运行的那个目录，而不是构建请求时屏幕上碰巧是什么。
            Prompts.system(active.systemPrompt(), conversation.cwd()),
            active.temperature(),
            active.maxTokens(),
            active.reasoning(),
            active.maxContextTokens());
    ToolRegistry registry = registryFor(conversation, current, options, active);
    // 每个回合固定发出去的那一份有多大。它只有在这里才量得准：工具表里可能有 MCP 服务器带来的工具，而
    // 系统提示里可能有项目自己的 CCJ.md。所以它是一个被观察的事实，不是从配置文件里读出来的一个数。
    metrics.set(
        Metric.PREFIX_TOKENS,
        TokenEstimate.of(options.systemPrompt()) + registry.schemaTokens());
    ToolContext context =
        new ToolContext(
            conversation.cwd(),
            // 审批不经过循环的回调（它发生在一次工具调用的里面），所以它自己带一层计时。
            new MeteredApprover(metrics, approvals.gate()),
            active.outputLimitBytes());
    String sessionId = conversation.id();
    return new AgentLoop(
        current,
        registry,
        conversation.session(),
        options,
        context,
        new Recorder(metrics, sessionId, this::recordTrace, new WebListener(conversation, events())));
  }

  /**
   * {@link WebListener} 需要的两个出口。
   *
   * <p>这一处适配器是刻意的：它把「事件往哪去」与「一个回调翻译成哪种事件」分开，于是 WebListener 不必抓着一个
   * hub 的引用。六行换掉的是一处「读监听器必须同时读整个服务器」的耦合。
   */
  private WebListener.Events events() {
    return new WebListener.Events() {
      @Override
      public void publish(String sessionId, String type, ObjectNode payload) {
        AgentHub.this.publish(sessionId, type, payload);
      }

      @Override
      public void publishUsage(String sessionId) {
        AgentHub.this.publishUsage(sessionId);
      }
    };
  }

  /**
   * 一次运行结束：最近的那棵树留在内存里供 {@code GET /api/trace} 读，另外（如果命令行给了文件）追加到
   * 那份 JSONL 里。
   *
   * <p>两部分都要，因为它们的用处不同：内存里那棵回答「刚刚那一次到底发生了什么」，而在文件里的那些能被
   * 拿走、被 diff、被另一个工具读——那正是「痕迹」这个词在这个项目里的意思。
   */
  private void recordTrace(RunTrace trace) {
    lastTraces.put(trace.sessionId(), trace);
    Recorder.TraceSink sink = traceSink;
    if (sink != null) {
      sink.accept(trace);
    }
  }

  /**
   * 一个对话的工具集：标准工具加上 {@code task}。
   *
   * <p>按对话一份而不是共享，因为 {@code task} 需要委派它的那个对话的模型和设置——子代理用与派发它的
   * 代理相同的模型、相同的投入运行，而不是服务器接下来碰巧配置成什么就用什么。
   *
   * <p>交给子代理的注册表是从这一份里减去 {@code task} 构建的，这让递归变得不可能，而不只是不被鼓励：
   * 没有任何深度上限需要弄错。
   */
  private ToolRegistry registryFor(
      Conversation conversation, Provider current, AgentOptions options, Config active) {
    SubAgentRunner runner =
        new SubAgentRunner(
            // 同一个提供方实例：子代理不是第二个模型，关掉一个会关掉另一个。
            current,
            tools,
            options,
            // 角色表来自当前生效的配置：把 explore 钉到便宜模型是一次运行的选择，不是一次会话的选择。
            active.subAgents(),
            conversation.cwd(),
            conversation::aborting,
            active.outputLimitBytes(),
            // 这个对话自己的审批人，这样子代理的许可请求会出现在用户已经在看的转录里。它在本回合的线程
            // 上运行，所以 `currentTurnSession` 会把它归到这里，而中止这个回合就会回答它。
            approvals.gate());
    ToolRegistry registry = new ToolRegistry();
    for (String name : tools.names()) {
      tools.find(name).ifPresent(registry::register);
    }
    // `task` 最后注册，这样它读起来是它本来的那种升级手段，而不是某个普通的文件工具。不分配暂存目录：
    // 子代理在会话自己的目录里工作，改动那里的任何东西之前都要先问，就像派发它的那个代理一样。
    registry.register(
        new TaskTool(
            (role, task) -> runner.run(role, task, options.system()),
            // 子代理的 token 就是这个对话的 token：同一个模型、同一个账户、同一份账单。加进会话自己的
            // 账本，这样用量面板报的是花掉的东西，而不是主循环碰巧自己花掉的那部分。
            spent ->
                conversation.add(
                    (int) Math.min(Integer.MAX_VALUE, spent.inputTokens()),
                    (int) Math.min(Integer.MAX_VALUE, spent.outputTokens()),
                    // 「这个提供方从不报缓存」必须原样传下去：把它写成一个 0，会让整个对话的命中率从
                    // 「未上报」变成「0.0%」——只要跑过一次子代理就够了。
                    spent.cacheReported()
                        ? (int) Math.min(Integer.MAX_VALUE, spent.cachedInputTokens())
                        : null,
                    spent.userTurns(),
                    spent.modelTurns(),
                    spent.toolCalls(),
                    spent.toolErrors(),
                    0),
            subAgentsAllowed()));
    return registry;
  }

  /** 这个服务器到底跑不跑子代理。 */
  private boolean subAgentsAllowed() {
    return subAgents.get();
  }

  // ------------------------------------------------------------------ sessions

  /**
   * 开一个全新的会话——除非当前这个已经是空的，那种情况下再造一个 id 只会产出第二个空会话，把按按钮的
   * 人搞糊涂。
   */
  public void newSession() {
    FileSession current = session();
    if (current != null && current.messages().isEmpty()) {
      publish(
          "notice",
          Json.object().put("text", "这个会话已经是空的——先随便说点什么"));
      return;
    }
    useSession(SessionStore.create(sessionsDir()));
  }

  public void resumeSession(String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("需要一个 session id");
    }
    useSession(openForDisplay(id));
  }

  /**
   * 为 {@code id} 放到屏幕上的会话对象。
   *
   * <p>正在跑的对话已经有一个 {@link FileSession}——它的回合正往里面追加的那个——页面拿到的就是这
   * 个对象。在同一个文件上再开一个，就会是两个写入者往同一份转录里写，而那正是整套按对话规则保护的东西；
   * 反过来拒绝*展示*这个对话，才是让一个正在跑的回合变得没法看的原因。
   */
  private FileSession openForDisplay(String id) {
    Conversation running = conversations.get(id);
    if (running != null) {
      return running.session();
    }
    return SessionStore.open(sessionsDir(), id);
  }

  /**
   * 删除一个会话。删除当前活动的那个会在它原来的位置开一个新的，这样页面在它正看着的列表少了一行之后
   * 总还有地方可待。
   */
  public ObjectNode deleteSession(String id) {
    return deleteSession(null, id);
  }

  /**
   * 删除一个会话。{@code workspace} 点名另一个工作区，要删的是那里的会话；读一个折叠文件夹和在它里面
   * 做删除属于同一类操作，所以两者都不切换活动工作区。删除你正待着的会话会开一个新的。
   */
  public ObjectNode deleteSession(String workspace, String id) {
    String target = normaliseWorkspace(workspace);
    Path directory = sessionsDirOf(target);
    FileSession current = session();
    boolean active =
        target == null && current != null && current.id().equals(id);
    requireNotRunning(id, "删除它");
    if (!SessionStore.delete(directory, id)) {
      throw new IllegalArgumentException(
          "在 " + (target == null ? "本工作区" : "'" + target + "'") + " 里没有会话 '" + id + "'");
    }
    publish("notice", Json.object().put("text", "会话已删除"));
    if (active) {
      useSession(SessionStore.create(sessionsDir()));
    }
    return Json.object().set("sessions", sessionsJson(target));
  }

  /** 清空活动工作区的每一个会话——那个「我测试留下了一堆烂摊子」按钮。 */
  public ObjectNode deleteAllSessions() {
    return deleteAllSessions(null);
  }

  public ObjectNode deleteAllSessions(String workspace) {
    String target = normaliseWorkspace(workspace);
    requireEverythingIdle("删除全部会话");
    int deleted = SessionStore.deleteAll(sessionsDirOf(target));
    publish(
        "notice",
        Json.object()
            .put("text", deleted == 0 ? "没有可删除的会话" : "已删除 " + deleted + " 个会话"));
    if (target == null) {
      useSession(SessionStore.create(sessionsDir()));
    }
    return Json.object().set("sessions", sessionsJson(target));
  }

  /** null 表示活动工作区；给了名字则它必须存在。 */
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
        .orElseThrow(() -> new IllegalArgumentException("没有名为 '" + clean + "' 的工作区"));
    return clean;
  }

  private Path sessionsDirOf(String workspace) {
    return workspace == null
        ? sessionsDir()
        : settings.workspaces().find(workspace).orElseThrow().sessionsDir();
  }

  /** 向桌面要一个目录。阻塞直到用户作答、取消或超时。 */
  public java.util.Optional<java.nio.file.Path> chooseFolder(java.io.IOException[] failure) {
    try {
      return folderChooser.choose("选择工作区文件夹");
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
    // 离开屏幕的会话，只有在没人在用它时才关闭。正在跑的对话拥有它的文件——那是回合追加的目标，而且它
    // 可能就是正被放回屏幕上的同一个对象——所以在这里关掉它会让一个仍在工作的回合失败。
    if (previous != null && previous != next && !conversations.containsKey(previous.id())) {
      previous.close();
    }
    publishUsage();
    publishStatus();
  }

  /**
   * 拒绝一个其对象正在运行的操作。
   *
   * <p>这就是取代「只要有东西在跑就什么都不许跑」的那条规则。一个对话忙，不能挡住用户在另一个对话里
   * 工作，但它仍然必须挡住用户把正在干活的那个对话脚下的地抽走：你不能在正往会话里写的回合头上恢复那个
   * 会话，也不能删掉它正在追加的那个文件。
   */
  private void requireNotRunning(String sessionId, String what) {
    Conversation running = sessionId == null ? null : conversations.get(sessionId);
    if (running != null && running.running()) {
      throw new IllegalStateException(
          "那个会话正在跑一个回合；" + what + "之前请先中止它");
    }
  }

  /** 任何对话在干活时拒绝：每个对话所立足的东西正在变。 */
  private void requireEverythingIdle(String what) {
    for (Conversation conversation : conversations.values()) {
      if (conversation.running()) {
        throw new IllegalStateException(
            "会话 "
                + conversation.id()
                + " 里还有回合在跑；"
                + what
                + "之前请先中止它");
      }
    }
  }

  /** 屏幕上的会话在干活时拒绝。 */
  private void requireShownIdle(String what) {
    requireNotRunning(sessionIdOf(session()), what);
  }

  // ------------------------------------------------------------------ approvals

  /** 见 {@link ApprovalDesk#resolveApproval}。 */
  public boolean resolveApproval(String id, ApprovalAnswer answer) {
    return approvals.resolveApproval(id, answer);
  }

  public boolean autoApprove() {
    return approvals.autoApprove();
  }

  public void setAutoApprove(boolean enabled) {
    approvals.setAutoApprove(enabled);
  }

  public boolean subAgents() {
    return subAgents.get();
  }

  public void setSubAgents(boolean enabled) {
    if (subAgents.getAndSet(enabled) != enabled) {
      publishStatus();
    }
  }

  /** 一个会话的运行状态；它没有的话为 null。 */
  private Conversation conversation(String sessionId) {
    return sessionId == null || sessionId.isEmpty() ? null : conversations.get(sessionId);
  }

  /**
   * 这条线程正在跑其回合的那个会话。
   *
   * <p>从调用线程解析出来，而不是当作参数传进来，因为循环把审批人当作一个普通的 {@link Approver} 交给
   * 它的工具：只有回合自己的会话能说出是哪个对话在问，而一个不带会话发布的审批会同时出现在每一份打开的
   * 转录里。
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

  /** 见 {@link EventStream#subscribe}。 */
  public List<EventStream.Event> subscribe(Consumer<EventStream.Event> subscriber) {
    return events.subscribe(subscriber);
  }

  /** 见 {@link EventStream#unsubscribe}。 */
  public void unsubscribe(Consumer<EventStream.Event> subscriber) {
    events.unsubscribe(subscriber);
  }

  private void publish(String type, ObjectNode payload) {
    events.publish(type, payload);
  }

  private void publish(String sessionId, String type, ObjectNode payload) {
    events.publish(sessionId, type, payload);
  }

  private static String sessionIdOf(FileSession session) {
    return HubState.idOf(session);
  }

  public void publishStatus() {
    // 状态是关于一个对话的——屏幕上那个——所以它点名是哪一个，用的也是页面读出 id 的同一个地方：一个
    // 没有会话的状态会被归到无处。
    ObjectNode node = statusFields();
    node.put("sessionId", sessionIdOf(session()));
    publish(sessionIdOf(session()), "status", node);
    // 两个仪表跟着状态走：它们是全服务器的，而状态是这里发布得最频繁的东西（每个回合的一头一尾、每次
    // 开关、每次切换）。给它们各自一条定时线程，是拿一个专门的调度器去换两个整数。
    int queued = 0;
    int running = 0;
    for (Conversation conversation : conversations.values()) {
      queued += conversation.queued().size();
      if (conversation.running()) {
        running++;
      }
    }
    metrics.set(Metric.QUEUED_MESSAGES, queued);
    metrics.set(Metric.RUNNING_CONVERSATIONS, running);
  }

  public void publishUsage() {
    publish(sessionIdOf(session()), "usage", usageFields());
  }

  /** 一个会话的用量，也就是一个回合在自己的账本变化时发布的东西。 */
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
    approvals.denyAll();
    turns.shutdownNow();
    // 等一等被中断的回合真的停下，而不是把「关掉」当成「说了一句关掉」：会话文件是每一条消息一次 write，
    // 所以 close() 之后还有人在写，就意味着「关掉之后文件还在变」。
    //
    // 这件事是被一次真实的失败逼出来的（`WebConversationConcurrencyTest` 里 JUnit 删不掉自己的临时目录：
    // 一个还在收尾的回合刚好又往 `sessions/` 里写了一个文件）。等待是有上界的，因为一个正卡在长命令里的
    // 工具不会被线程中断叫醒——它只看循环的中止标志——而 close() 不该为了它挂住五分钟。
    try {
      if (!turns.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
        // 说不出口也要说：这里不抛，因为一个关不下去的回合不该让进程退不掉；但也不该假装它停了。
        java.util.logging.Logger.getLogger(AgentHub.class.getName())
            .warning("关闭时有回合没有在 " + SHUTDOWN_GRACE_SECONDS + " 秒内停下");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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

  /** 见 {@link ApprovalDesk#approver}。 */
  public Approver approver() {
    return approvals.approver();
  }

  /**
   * 用户在这个项目里的规则，由调用方提供，因为它手里有应用 home 而本类没有。在设置之前，每个请求都
   * 交给浏览器。
   */
  public void setApprovalRules(ApprovalRules rules) {
    this.approvalRules = rules;
  }

  /**
   * 把上一回合改动过的东西放回去。
   *
   * <p>这是人们在让代理靠近自己的文件之前会问的那个问题——「可以退回吗」——的答案，也是审批可以被
   * 留在它该在的位置、而不必为了补偿被关掉的原因。有回合在跑时拒绝：在它底下做退回会恢复模型正在思考
   * 的文件，那比两种状态中的任何一种都糟。
   */
  public ObjectNode undoTurn() {
    FileSession current = session();
    if (current == null) {
      throw new IllegalStateException("没有打开的会话");
    }
    Conversation conversation = conversations.get(current.id());
    if (conversation != null && conversation.running()) {
      throw new IllegalStateException("还有回合在跑；退回上一个回合之前请先中止它");
    }
    List<String> restored = checkpoints.undoLastTurn(current.file(), cwd());
    if (restored.isEmpty()) {
      publish(
          current.id(),
          "notice",
          Json.object().put("text", "没有可退回的东西：还没有哪个回合改动过文件"));
    } else {
      publish(
          current.id(),
          "notice",
          Json.object()
              .put(
                  "text",
                  "已退回："
                      + restored.size()
                      + " 个文件恢复到上一回合之前的样子 —— "
                      + String.join(", ", restored)));
    }
    ObjectNode response = Json.object();
    response.put("restored", restored.size());
    ArrayNode files = response.putArray("files");
    restored.forEach(files::add);
    response.put("remaining", checkpoints.undoableTurns(current.file()));
    return response;
  }

  // ------------------------------------------------- approvals and post-edit checks

  /** 见 {@link SettingsFiles#rulesJson}。 */
  public ObjectNode rulesJson() {
    return SettingsFiles.rulesJson(approvalRules, cwd());
  }

  /** 见 {@link SettingsFiles#applyRules}。 */
  public ObjectNode applyRules(JsonNode posted) {
    return SettingsFiles.applyRules(posted, approvalRules, cwd());
  }

  /** 见 {@link SettingsFiles#checksJson}。 */
  public ObjectNode checksJson() {
    return SettingsFiles.checksJson(settings.configFile());
  }

  /** 见 {@link SettingsFiles#applyChecks}。 */
  public ObjectNode applyChecks(JsonNode posted) {
    return SettingsFiles.applyChecks(posted, settings.configFile());
  }


  /**
   * {@link HubState} 的实现：把每一个名字接到这个类自己的那个字段或方法上。
   *
   * <p>它短是应该的。一份需要「想一下」才能回答的窄面，说明这个类本身还该再拆一次。
   */
  private final class Seat implements HubState {

    @Override
    public Settings settings() {
      return settings;
    }

    @Override
    public ToolRegistry tools() {
      return tools;
    }

    @Override
    public Config config() {
      return config;
    }

    @Override
    public void setConfig(Config next) {
      config = next;
    }

    @Override
    public AtomicReference<Provider> provider() {
      return provider;
    }

    @Override
    public FileSession session() {
      return AgentHub.this.session();
    }

    @Override
    public void useSession(FileSession next) {
      AgentHub.this.useSession(next);
    }

    @Override
    public ObjectNode status() {
      return AgentHub.this.status();
    }

    @Override
    public ArrayNode sessionsJson(String workspace) {
      return AgentHub.this.sessionsJson(workspace);
    }

    @Override
    public Conversation conversation(String sessionId) {
      return AgentHub.this.conversation(sessionId);
    }

    @Override
    public String currentTurnSession() {
      return AgentHub.this.currentTurnSession();
    }

    @Override
    public void publish(String type, ObjectNode payload) {
      AgentHub.this.publish(type, payload);
    }

    @Override
    public void publish(String sessionId, String type, ObjectNode payload) {
      AgentHub.this.publish(sessionId, type, payload);
    }

    @Override
    public void publishStatus() {
      AgentHub.this.publishStatus();
    }

    @Override
    public void publishUsage() {
      AgentHub.this.publishUsage();
    }

    @Override
    public void publishUsage(String sessionId) {
      AgentHub.this.publishUsage(sessionId);
    }

    @Override
    public Path cwd() {
      return AgentHub.this.cwd();
    }

    @Override
    public Path sessionsDir() {
      return AgentHub.this.sessionsDir();
    }

    @Override
    public String normaliseWorkspace(String workspace) {
      return AgentHub.this.normaliseWorkspace(workspace);
    }

    @Override
    public Path sessionsDirOf(String workspace) {
      return AgentHub.this.sessionsDirOf(workspace);
    }

    @Override
    public FileSession openForDisplay(String id) {
      return AgentHub.this.openForDisplay(id);
    }

    @Override
    public void requireNotRunning(String sessionId, String what) {
      AgentHub.this.requireNotRunning(sessionId, what);
    }

    @Override
    public void requireEverythingIdle(String what) {
      AgentHub.this.requireEverythingIdle(what);
    }

    @Override
    public void requireShownIdle(String what) {
      AgentHub.this.requireShownIdle(what);
    }
  }

}
