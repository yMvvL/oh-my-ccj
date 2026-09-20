package com.ccj.agent.web;

import com.ccj.agent.core.AgentListener;
import com.ccj.agent.core.AgentLoop;
import com.ccj.agent.core.AgentOptions;
import com.ccj.agent.core.ApprovalAnswer;
import com.ccj.agent.core.ApprovalRequest;
import com.ccj.agent.core.ApprovalRules;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.RuleApprover;
import com.ccj.agent.core.Compaction;
import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.SubAgentRunner;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Prompts;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolRegistry;
import com.ccj.agent.core.ToolResult;
import com.ccj.agent.core.ToolSpec;
import com.ccj.agent.core.TokenEstimate;
import com.ccj.agent.core.UsageTotals;
import com.ccj.agent.core.VisionConfig;
import com.ccj.agent.core.Workspace;
import com.ccj.agent.core.ProviderDefinition;
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
import java.util.concurrent.ConcurrentLinkedDeque;
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
 * 拥有浏览器无法拥有的东西：会话、正在跑的回合、回合被卡住时所等的审批，以及回合要去的模型。
 *
 * <p>有意做成无头的——它发布 {@link Event}，对 HTTP 一无所知，所以同一个对象也可以支撑一个 websocket
 * 或一个测试。
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

  /** 重连的浏览器可以重放多少条事件。 */
  private static final int REPLAY_LIMIT = 500;

  /**
   * 一次工具调用在拒绝之前等人类多久。超时就拒绝：与 CLI 在 stdin 不是终端时采用的同一条「失败即关闭」
   * 的规则。
   */
  /**
   * 一个审批在替用户作答之前要等多久。
   *
   * <p>{@code 0} 意味着「一直等到有人作答」——这是默认值，也是诚实的那个：请求许可就是向一个人提一个
   * 问题，而会过期的问题是从未真正被问过的问题。用旧的 120 秒上限实测过：一个在等一条从未到达页面的审批的
   * 回合，被计时器结束、工作被丢下，而用户屏幕上显示的是一个早已被撤回的提示——两头都糟，因为它看起来像
   * 工具里的一个 bug，而不是一个没被回答的问题。
   *
   * <p>无限等下去是安全的，而不是死锁，因为有两条不依赖计时器的出路：中止对话会用「否」回答待处理的审批
   * （见 {@code Conversation.abort}），而重新加载的页面会从它进来时索要的状态里重建提示。另一种做法
   * ——计时器——会把第一条出路弄坏，因为它把「还没有人作答」变成了「没有人作答」。
   *
   * <p>设了数字时仍然尊重它，给想要回合失败而不是挂起的调用方：测试会设它，无人值守的运行也可以。
   */
  private static final long APPROVAL_TIMEOUT_SECONDS = 0;

  /** 一个回合运行期间，一个对话最多可以积压多少条消息。见 Conversation.queued。 */
  private static final int MAX_QUEUED_MESSAGES = 16;

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

  /**
   * 发生过的一件事，按线上格式塑形。
   *
   * <p>{@code sessionId} 属于事件而不是连接，是因为一条流承载服务器上的每一个对话：在回合并行运行的
   * 时候，一个不点名自己会话的事件会被渲染进当时碰巧打开的那份转录里。
   */
  public record Event(long id, String type, String sessionId, ObjectNode payload) {}

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

  private final List<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();
  private final Deque<Event> replay = new ArrayDeque<>();
  private final AtomicLong nextEventId = new AtomicLong();
  private final AtomicInteger nextApprovalId = new AtomicInteger();
  /**
   * 在等人类的审批，按它们的 id 键存放。
   *
   * <p>是整个服务器一份，而不是每个对话一份：审批按 id 作答，来自任何一个注意到它的页面，而一个在后台
   * 回合里需要人类的请求，正是这件事绝不能弄错的情形。
   */
  private final Map<String, Pending> pendingApprovals = new ConcurrentHashMap<>();
  private final AtomicBoolean autoApprove = new AtomicBoolean();
  private final AtomicReference<Provider> provider = new AtomicReference<>();

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
    this.autoApprove.set(settings.autoApprove());
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

  /** 这条会话持有的待发送图片，或者 {@code null}。 */
  private Held heldPicture(FileSession current) {
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
    node.put("autoApprove", autoApprove.get());
    // 一张已经描述好、还没走的图片：刷新之后那条缩略条还在，而不是变成一张看不见、却会跟着下一句话发
    // 出去的图片。
    Held held = heldPicture(current);
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
    for (SessionStore.Summary summary : SessionStore.list(directory)) {
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

  /**
   * 设置表单可以提供的选择：提供方（内置的和用户定义的）以及它们的模型。
   *
   * <p>由一个 {@link ModelCatalog} 提供，所以以后换成路由器支撑的目录时页面不必改：它渲染的形状是
   * 目录的形状，不是配置的形状。
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
      // 密钥将来自哪个变量，好让设置表单能点名它，而不是去猜协议的默认值——一个中转提供方就是这样被
      // 告知去读别人家密钥变量的。
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
    // 此刻不在列表里的内置项：「添加一个内置提供方」控件可以给出的东西。措辞是「可选」，不是「被隐藏」
    // ——没有什么需要从死里复活。
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
   * 定义一个用户自己拥有的提供方。存起来之前先校验，所以一个不可能工作的定义永远不会变成可选项。
   */
  public ObjectNode addProvider(JsonNode body) {
    ProviderStore store = requireProviderStore();
    List<String> models = new ArrayList<>();
    JsonNode list = body.path("models");
    if (list.isArray()) {
      list.forEach(model -> models.add(model.asText()));
    } else if (list.isTextual()) {
      // 逗号分隔的字符串正是表单字段自然会发来的东西。
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
    // 保存一个定义同时也等于说「我要这个提供方」：如果列表是显式的，新名字就必须加入它，否则目录永远
    // 不会显示刚刚保存的东西。
    ensureListed(definition.name());
    publish(
        "notice",
        Json.object().put("text", "已定义提供方 '" + definition.name() + "'"));
    publishStatus();
    return modelsJson();
  }

  /**
   * 从列表中移除一个提供方。
   *
   * <p>用户自己做的定义会被删除。内置的编译进了 agent 里，所以没有什么可删：它离开列表，而列表变成
   * 显式的，这就是「已删除」需要表达的全部意思。没有任何东西被记成「已隐藏」，也没有任何东西被当作「可
   * 恢复项」提供——加回来就是一次普通的添加。
   */
  public ObjectNode removeProvider(String name) {
    ProviderStore store = requireProviderStore();
    String clean = knownProvider(name);
    boolean inUse = config.provider() != null && config.provider().equalsIgnoreCase(clean);
    String tail =
        inUse
            ? " —— 它正是正在使用的那个；本会话继续运行，但请在下次重启之前选另一个提供方"
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
    publish("notice", Json.object().put("text", "已删除提供方 '" + clean + "'" + tail));
    publishStatus();
    return modelsJson();
  }

  /** 把一个内置提供方加回列表——一次普通的添加，不是复活。 */
  public ObjectNode addBuiltInProvider(String name) {
    ProviderStore store = requireProviderStore();
    String clean = name == null ? "" : name.strip();
    boolean builtIn =
        Providers.supported().stream().anyMatch(known -> known.equalsIgnoreCase(clean));
    if (!builtIn) {
      throw new IllegalArgumentException(
          "'" + clean + "' 不是内置提供方；请改为自定义一个");
    }
    if (currentProviderNames().stream().anyMatch(known -> known.equalsIgnoreCase(clean))) {
      throw new IllegalArgumentException("'" + clean + "' 已经在列表里");
    }
    List<String> next = new ArrayList<>(currentProviderNames());
    next.add(clean);
    store.setShown(next);
    publish("notice", Json.object().put("text", "已添加提供方 '" + clean + "'"));
    publishStatus();
    return modelsJson();
  }

  /** 目录当前提供的每一个提供方名字。 */
  private List<String> currentProviderNames() {
    List<String> names = new java.util.ArrayList<>();
    ModelCatalog catalog = settings.modelCatalog();
    if (catalog != null) {
      catalog.providers().forEach(entry -> names.add(entry.name()));
    }
    return names;
  }

  /**
   * 为一个提供方记住某个模型，好让手输的名字活过下一次渲染——选择器给出的列表，和当前正在用的模型不是
   * 一回事。
   */
  public ObjectNode addModel(String provider, String model) {
    ProviderStore store = requireProviderStore();
    String name = knownProvider(provider);
    String value = model == null ? "" : model.strip();
    if (value.isEmpty()) {
      throw new IllegalArgumentException("需要一个模型名");
    }
    List<String> models = new java.util.ArrayList<>(offeredModels(name));
    if (!models.contains(value)) {
      models.add(value);
      store.setModels(name, models);
      publish("notice", Json.object().put("text", "已把模型 '" + value + "' 添加到 " + name));
      publishStatus();
    }
    return modelsJson();
  }

  /**
   * 忘掉一个提供方的某个模型。
   *
   * <p>可选项列表和当前的选择是两回事：从列表里移除一个模型绝不能改动配置，而拒绝移除正在使用的模型，
   * 在它是唯一可选项时会把用户卡死。活动的模型保持活动、保持可见——它只是不再被推荐了。
   */
  public ObjectNode removeModel(String provider, String model) {
    ProviderStore store = requireProviderStore();
    String name = knownProvider(provider);
    String value = model == null ? "" : model.strip();
    List<String> models = new java.util.ArrayList<>(offeredModels(name));
    if (!models.remove(value)) {
      throw new IllegalArgumentException("'" + value + "' 不是 " + name + " 的模型");
    }
    store.setModels(name, models);
    publish("notice", Json.object().put("text", "已从 " + name + " 移除模型 '" + value + "'"));
    publishStatus();
    return modelsJson();
  }

  /** 目录当前为一个提供方提供的东西，也就是编辑的起点。 */
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
      throw new IllegalArgumentException("需要一个提供方名");
    }
    ModelCatalog catalog = settings.modelCatalog();
    boolean known =
        catalog != null
            && catalog.providers().stream().anyMatch(entry -> entry.name().equalsIgnoreCase(name));
    if (!known) {
      throw new IllegalArgumentException(
          "没有名为 '"
              + name
              + "' 的提供方；请先添加一个，或使用其中之一："
              + (catalog == null
                  ? ""
                  : String.join(
                      ", ",
                      catalog.providers().stream().map(ModelCatalog.ProviderInfo::name).toList())));
    }
    return name;
  }

  /** 把一个名字加入显式列表；列表还是隐式的时候什么都不做。 */
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
      throw new IllegalStateException("这个服务器启动时没有提供方注册表");
    }
    return settings.providerStore();
  }

  // ------------------------------------------------------------------ workspaces

  /** 切换器需要的注册表形态：名字、路径、那里积了多少历史、哪一个是活动的。 */
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
    // 只是一条注册表记录，仅此而已：没有正在跑的回合会读这个列表，所以添加一条不会打扰已经在进行的工作。
    // 过去要求这里必须空闲，正是第一个工作区忙着时第二个工作区无法被添加的原因。
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("工作区需要一个目录");
    }
    settings.workspaces().add(name, Path.of(path.strip()));
    publish("notice", Json.object().put("text", "已添加工作区 '" + name.strip() + "'"));
    return workspacesJson();
  }

  /**
   * 添加一个从桌面自己的选择器里挑出来的目录。选择器就是整个手势，所以名字取目录的名字——名字被占用时
   * 如何解决，见 {@link WorkspaceStore#add(Path)}。
   */
  public ObjectNode addWorkspace(Path path) {
    if (path == null) {
      throw new IllegalArgumentException("工作区需要一个目录");
    }
    requireDistinctPath(path);
    Workspace added = settings.workspaces().add(path);
    publish(
        "notice",
        Json.object().put("text", "工作区 '" + added.name() + "' → " + added.path()));
    return workspacesJson();
  }

  /* 注册表按名字键存放，所以两个名字可以指向同一个目录——这本来没问题，但同一个目录出现两次就不行：两份
   * 会话历史会争抢同一个工作目录，而屏幕上没有任何东西说明哪份列表属于哪一个。 */
  private void requireDistinctPath(Path path) {
    Path wanted = path.toAbsolutePath().normalize();
    for (Workspace known : settings.workspaces().list()) {
      if (known.path().equals(wanted)) {
        throw new IllegalArgumentException(
            "那个目录已经是工作区 '"
                + known.name()
                + "'；请直接用树里已有的条目，不要再添加一次");
      }
    }
  }

  public ObjectNode removeWorkspace(String name) {
    // 忘掉一条记录不删任何文件，也不停任何回合；正在跑的回合继续持有它已经拿到的会话文件。只有活动工作区
    // 受保护，而那是注册表自己的规则。
    settings.workspaces().remove(name);
    publish("notice", Json.object().put("text", "已忘记工作区 '" + name.strip() + "'"));
    return workspacesJson();
  }

  /**
   * 同时切换工作目录和会话存储，并在目标工作区里开一个全新的会话——跨一次目录变更去续上别人的对话，比
   * 一份空转录更糟。
   */
  public ObjectNode switchWorkspace(String name) {
    // 回合并行时也允许：每一个都持有自己启动时的工作目录，所以在这里切换命名空间没法把已经在进行的工作
    // 改道。
    Workspace workspace = settings.workspaces().activate(name);
    useSession(SessionStore.create(workspace.sessionsDir()));
    publish(
        "notice",
        Json.object().put("text", "workspace '" + workspace.name() + "' → " + workspace.path()));
    return status();
  }

  // ------------------------------------------------------------------ settings

  /** 设置表单需要的东西。API 密钥本身从不离开服务器。 */
  public ObjectNode configJson() {
    Config active = config;
    Config fileConfig = Config.fromFile(settings.configFile());
    // 属于这个提供方的一对：要么是文件里的扁平字段，要么是它从上次访问起为这个提供方记住的那一对。无论
    // 哪种，都是这个提供方的密钥，而密钥本身从不离开服务器。
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
    // 上面两个是不是这个提供方实际会用的。false 表示它们是为别的提供方输入的（自定义提供方不用它们），
    // 那么表单必须给出这个提供方自己的端点，而不是那个过期的。
    node.put(
        "usesStoredSettings",
        Providers.usesStoredSettings(active, settings.providerStore(), active.provider()));
    // 哪些提供方已存有密钥，按名字：表单在任何东西被粘贴之前就逐个提供方说「已保存」或「未保存」，而在
    // 它们之间切换正是这个 map 存在的全部理由。
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
    // 提示要求使用的语言，以及表单给出的列表。`auto` 是「没有选择」，所以清空的字段报出来的就是它。
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
    node.set("vision", visionJson(active));
    return node;
  }

  /**
   * 设置表单需要知道的、关于那个描述图片的模型的东西。
   *
   * <p>密钥遵循这里其他每一个密钥都遵循的规则：说明有没有、从哪来，绝不说它是什么。{@code apiKeySource}
   * 用的是提供方字段用的同一套词汇，所以表单不必再定一条规则就能说「已存在配置文件里」或「来自环境变量」。
   *
   * <p>{@code on} 是这个功能实际会做的事，这和「那个块存在」不是一回事：一个点了端点却没点模型的块什么
   * 也描述不了，而把这种情况称作 on 的表单，等于承诺了图片按钮随后会拒绝的东西。
   */
  private ObjectNode visionJson(Config active) {
    VisionConfig vision = active.vision();
    ObjectNode node = Json.object();
    node.put("configured", vision != null && vision.isConfigured());
    node.put("on", vision != null && vision.isConfigured() && vision.resolvedApiKey(settings.env()) != null);
    node.put("defaultMaxTokens", VisionClient.DEFAULT_MAX_TOKENS);
    node.put("baseUrl", (String) (vision == null ? null : vision.baseUrl()));
    node.put("model", (String) (vision == null ? null : vision.model()));
    node.put("apiKeyEnv", (String) (vision == null ? null : vision.apiKeyEnv()));
    if (vision == null || vision.maxTokens() == null) {
      node.putNull("maxTokens");
    } else {
      node.put("maxTokens", vision.maxTokens());
    }
    node.put(
        "apiKeySource",
        vision == null || vision.resolvedApiKey(settings.env()) == null
            ? "none"
            : vision.apiKey() != null ? "config" : "env");
    return node;
  }

  /**
   * 校验提交上来的设置，把它们持久化，并把正在跑的会话切到它们上面。
   *
   * <p>先构建提供方：错误的密钥或不可达的 base URL 必须在文件被动到之前就失败，否则一个笔误就会留下
   * 一份起不来的配置。
   *
   * <p>切换提供方是从一份没有端点、没有凭据的配置开始的：它们属于此前活动的那个提供方，继承它们，正是
   * 一个自称 "CommandCode" 的会话把流量发到上一个提供方的地址、并带上上一个提供方密钥的方式。这次请求
   * 携带的东西仍然生效，而结果会标记为新提供方的，这样存下来的那一对在下一次被读取时仍然诚实。
   */
  public ObjectNode applyConfig(JsonNode posted) {
    requireEverythingIdle("修改模型");
    Config changes = changesFrom(posted);
    // 两边走同一套过渡：被离开的端点和密钥会记在它们所属的提供方名下，要切过去的那个会被回忆起来，而
    // 点名了某项设置的改动会把它写成活动提供方的。
    Config candidate = withVisionEdits(config.changedBy(changes), posted);
    Provider built = settings.providerFactory().create(candidate, settings.env());
    try {
      // 文件是被写入的那一侧，所以它带着实际在用的提供方：表单可以只提交一个密钥，而一次保存绝不能把
      // 文件留成没有它所属提供方的样子。
      Config stored = Config.fromFile(settings.configFile()).namedBy(config).changedBy(changes);
      Config.writeInto(settings.configFile(), withVisionEdits(stored, posted));
    } catch (RuntimeException e) {
      built.close();
      throw e;
    }
    Provider previous = provider.getAndSet(built);
    if (previous != null) {
      previous.close();
    }
    config = candidate;
    // 用配置里的名字，而不是实现的名字：对一个用户称作 "myrelay" 的提供方说「正在使用 openai」，
    // 会让人觉得设置被忽略了。
    publish(
        "notice",
        Json.object()
            .put("text", "using " + candidate.provider() + " · " + candidate.model()));
    publishStatus();
    return status();
  }

  /**
   * 用提交上来的设置发一个最小的请求，什么都不保存。这是本服务器唯一花用户 token 的地方，而且只在他们
   * 按下按钮时花。
   */
  public ObjectNode testConfiguration(JsonNode posted) {
    requireShownIdle("测试这些设置");
    // 测试还没保存的设置，就必须测提交上来的那套东西：与表单的「保存」相同的过渡，只是不写盘，这样一个
    // 不属于本提供方的值会被替换掉，而不是留在那儿。
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
      String text = reply.text().isBlank() ? "（空回复）" : reply.text().strip();
      return Json.object().put("ok", true).put("reply", text).put("elapsedMs", elapsedMillis);
    } catch (Exception e) {
      throw new IllegalArgumentException(message(e));
    }
  }

  /** 只包含表单实际发来的字段；空文本意味着「保持原样」。 */
  private Config changesFrom(JsonNode posted) {
    if (posted == null || !posted.isObject()) {
      throw new IllegalArgumentException("需要一个 JSON 对象");
    }
    String apiKey = text(posted, "apiKey");
    if (posted.path("clearApiKey").asBoolean(false)) {
      apiKey = "";
    }
    String reasoning = text(posted, "reasoning");
    if (reasoning != null) {
      // "default" 是选择器表达「让提供方自己决定」的方式，也就是清掉这个档位。
      reasoning = "default".equalsIgnoreCase(reasoning) ? "" : Config.normaliseReasoning(reasoning);
    }
    Double temperature = number(posted, "temperature");
    if (temperature != null && (temperature < 0 || temperature > 2)) {
      throw new IllegalArgumentException("temperature 必须在 0 与 2 之间");
    }
    String language = text(posted, "language");
    // vision 块逐字段处理：表单提交它所持有的东西，而它留空的字段意味着「保持原样」——和上面那个密钥
    // 字段遵循同一条规则。清掉密钥和关掉这个功能没法用空字符串表达（空字段是「不变」），所以它们是显式的
    // 旗标，由 `applyConfig` 在合并之后应用。
    Integer visionMaxTokens = integer(posted, "visionMaxTokens");
    if (visionMaxTokens != null && visionMaxTokens < 1) {
      throw new IllegalArgumentException("视觉补全预算至少要有 1 个 token");
    }
    String visionApiKey = text(posted, "visionApiKey");
    VisionConfig vision =
        new VisionConfig(
            text(posted, "visionBaseUrl"),
            visionApiKey,
            text(posted, "visionApiKeyEnv"),
            text(posted, "visionModel"),
            visionMaxTokens);
    String provider = text(posted, "provider");
    // 表单总是会提交它的密钥变量字段，并预填该提供方的默认值，而那不算用户在指认一份凭据。这是从一个手写
    // 配置文件报上来的 bug：提交默认值被算成「这次改动写了自己的端点和密钥」，`changedBy` 随后丢掉了文件里
    // 那对未标记的值，而构建提供方就因「没有 API key」失败——结果一份有人手敲出来的配置文件完全无法从表单
    // 保存。只等于默认值的值在这里不算一个值，这与 `writeInto` 在决定写什么时已经遵循的规则是同一条。
    String apiKeyEnv = text(posted, "apiKeyEnv");
    if (apiKeyEnv != null
        && apiKeyEnv.equals(Config.defaultKeyEnv(provider == null ? config.provider() : provider))) {
      apiKeyEnv = null;
    }
    // 完整的形状，按位置点名：这个表单不管理的每一个字段都显式给 null，因为更短的构造函数会把一个 String
    // 放进错误的槽位里而不吭声。
    return new Config(
        provider,
        text(posted, "model"),
        text(posted, "baseUrl"),
        apiKey,
        apiKeyEnv,
        temperature,
        integer(posted, "maxTokens"),
        null, // autoApprove
        null, // outputLimitBytes
        null, // systemPrompt
        language,
        reasoning,
        null, // maxContextTokens
        null, // settingsFor
        java.util.Map.of(),
        vision);
  }

  /**
   * 把提交上来的视觉指令应用到一份配置上。
   *
   * <p>在合并之后、而不是合并之中：「忘掉密钥」和「关掉图片」不是值，而是移除某个值——而一个对某个字段
   * 一无所知的合并会保留那里的东西，那与这两者中任何一个的意思都相反。
   */
  private static Config withVisionEdits(Config config, JsonNode posted) {
    if (posted == null || !posted.isObject()) {
      return config;
    }
    Config edited =
        posted.path("clearVisionApiKey").asBoolean(false) ? config.withoutVisionKey() : config;
    return posted.path("clearVision").asBoolean(false) ? edited.withoutVision() : edited;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("字段 '" + field + "' 必须是字符串");
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
      throw new IllegalArgumentException("字段 '" + field + "' 必须是整数");
    }
    return value.asInt();
  }

  private static Double number(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isNumber()) {
      throw new IllegalArgumentException("字段 '" + field + "' 必须是数字");
    }
    return value.asDouble();
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
        conversations.computeIfAbsent(current.id(), key -> new Conversation(key, current));
    Held held = conversation.pendingPicture.get();
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
              + MAX_QUEUED_MESSAGES
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
        conversations.computeIfAbsent(current.id(), key -> new Conversation(key, current));
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
    Held previous = conversation.pendingPicture.getAndSet(new Held(saved, description, mediaType));
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
  public String discardPicture() {
    FileSession current = session();
    if (current == null) {
      return null;
    }
    Conversation conversation = conversations.get(current.id());
    if (conversation == null) {
      return null;
    }
    Held dropped = conversation.pendingPicture.getAndSet(null);
    if (dropped == null) {
      return null;
    }
    publish(current.id(), "picture", Json.object().putNull("name"));
    publishStatus();
    return dropped.saved().path().getFileName().toString();
  }

  /** 一张已经描述过、正等着和用户下一句话一起发出去的图片。 */
  record Held(AttachmentStore.Saved saved, String description, String mediaType) {

    /** 这条消息在对话里的开头，用户自己说的话接在它后面。 */
    String block() {
      return pictureMessage(saved, description);
    }
  }

  /**
   * 一张图片在对话里变成什么：它展示了什么，以及文件在哪里。
   *
   * <p>做了标记，而不是冒充打字打出来的。读者——以及模型——应当知道这条消息是一张图片，而路径正是让
   * 描述可核对的东西：图片仍在磁盘上，{@code read} 不需要审批，问一个被描述漏掉的细节是一次工具调用，
   * 而不是再上传一次。
   *
   * <p>它进到对话里的那段文本保持英文，因为模型会读到它：翻译会改变模型的行为。
   */
  private static String pictureMessage(AttachmentStore.Saved saved, String description) {
    return "[picture "
        + saved.path().getFileName()
        + "] "
        + description
        + "\n\n(The picture this describes is saved at "
        + saved.path()
        + "; read it if a detail the description dropped matters.)";
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
        conversations.computeIfAbsent(id, key -> new Conversation(key, shown));
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
              event -> {});
    } catch (Exception e) {
      throw new IllegalStateException("无法摘要这个对话：" + message(e));
    }
    String summary = reply.text() == null ? "" : reply.text().strip();
    if (summary.isEmpty()) {
      throw new IllegalStateException("模型返回了空摘要；什么都没有改动");
    }
    return install(conversation, before, summary, beforeTokens);
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
    conversation.addElapsed((System.nanoTime() - started) / 1_000_000);
    // 在回合边界做持久化，既让文件不至于每来一个 token 就长一点，也仍然扛得住被杀：最坏情况下丢的是最后
    // 一个回合，而绝不是整个会话。
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
              .put("text", "没有自动压缩：" + message(e)));
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
            Prompts.system(active.systemPrompt(), active.language(), conversation.cwd()),
            active.temperature(),
            active.maxTokens(),
            active.reasoning(),
            active.maxContextTokens());
    ToolContext context =
        new ToolContext(conversation.cwd(), gate(), active.outputLimitBytes());
    return new AgentLoop(
        current,
        registryFor(conversation, current, options, active),
        conversation.session(),
        options,
        context,
        new WebListener(conversation.id()));
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
            conversation.cwd(),
            conversation::aborting,
            active.outputLimitBytes(),
            // 这个对话自己的审批人，这样子代理的许可请求会出现在用户已经在看的转录里。它在本回合的线程
            // 上运行，所以 `currentTurnSession` 会把它归到这里，而中止这个回合就会回答它。
            gate());
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
                    (int) Math.min(Integer.MAX_VALUE, spent.cachedInputTokens()),
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

  /**
   * 回答一个待处理的审批。id 未知或已经回答过时返回 false。
   *
   * <p>{@code remember} 过去的意思是「把整个会话切成自动审批」，在一个布尔关口下它也只能是这个意思：
   * 对「别再问我这个了」的回答变成了「别再问我任何事了」。它现在的意思就是那个人在按钮上读到的——这个
   * 请求，在本会话余下的时间里——而「永远别再问」该去的地方是规则文件。
   */
  public boolean resolveApproval(String id, ApprovalAnswer answer) {
    Pending pending = id == null ? null : pendingApprovals.get(id);
    if (pending == null || answer == null) {
      return false;
    }
    return pending.answer().complete(answer);
  }

  /** 页面提交上来的回答，仍然理解更老的那两个布尔字段。 */
  static ApprovalAnswer answerOf(boolean allow, boolean remember, String posted) {
    if (posted != null && !posted.isBlank()) {
      return switch (posted.strip().toLowerCase(java.util.Locale.ROOT)) {
        case "session" -> ApprovalAnswer.ALLOW_SESSION;
        case "always" -> ApprovalAnswer.ALLOW_ALWAYS;
        case "allow", "once", "yes" -> ApprovalAnswer.ALLOW_ONCE;
        case "deny", "no" -> ApprovalAnswer.DENY;
        default -> throw new IllegalArgumentException(
            "未知的审批回答 '" + posted + "'；请使用 deny、once、session 或 always");
      };
    }
    if (!allow) {
      return ApprovalAnswer.DENY;
    }
    return remember ? ApprovalAnswer.ALLOW_SESSION : ApprovalAnswer.ALLOW_ONCE;
  }

  /**
   * 一个回合被卡住时所等的一个审批，以及发问的会话。
   *
   * <p>会话被保留下来，是因为中止必须能够拒绝一个对话自己的请求：没有它，一个在等人类的回合永远没法被
   * 停下，而一个用户没在看的后台回合会一直坐到超时，毫无出路。
   */
  /**
   * 一个回合被卡住时所等的一个审批：谁在问、问什么，以及它在等哪个回答。
   *
   * <p>标题和详情都被保留，不只是那个 future，因为提示必须能被重新画出来。审批是阻塞在内存里的请求
   * 而不是消息，所以一个切走又切回来的页面没法从对话里重建它——它上报还悬着什么，由页面把它画出来。
   * 没有这一条，看一眼别的对话就会把问题悄悄扔掉，只剩中止这一条出路。
   */
  private record Pending(
      String sessionId,
      String title,
      String detail,
      ApprovalRequest request,
      CompletableFuture<ApprovalAnswer> answer) {}

  public boolean autoApprove() {
    return autoApprove.get();
  }

  public void setAutoApprove(boolean enabled) {
    if (autoApprove.getAndSet(enabled) != enabled) {
      publishStatus();
    }
  }

  public boolean subAgents() {
    return subAgents.get();
  }

  public void setSubAgents(boolean enabled) {
    if (subAgents.getAndSet(enabled) != enabled) {
      publishStatus();
    }
  }

  /**
   * 阻塞循环线程，直到浏览器作答。在调用内部发布，是让代理的请求可见的原因；超时返回 false，是让它不
   * 至于永远等下去的原因。
   */
  private ApprovalAnswer askApproval(ApprovalRequest request) {
    if (autoApprove.get()) {
      return ApprovalAnswer.ALLOW_ONCE;
    }
    String sessionId = currentTurnSession();
    String id = "ap-" + nextApprovalId.incrementAndGet();
    CompletableFuture<ApprovalAnswer> answer = new CompletableFuture<>();
    pendingApprovals.put(id, new Pending(sessionId, request.title(), request.detail(), request, answer));
    // 还要发一个状态，这样一个没在看这个对话的页面也能知道有东西在等——而一个*正在*看它的页面，能从它
    // 进来时索要的状态里重建提示。
    publishStatus();
    publish(
        sessionId,
        "approval",
        Json.object()
            .put("id", id)
            .put("title", request.title())
            .put("detail", request.detail())
            .put("tool", request.tool())
            .put("command", request.command())
            .put("path", request.path() == null ? null : request.path().toString())
            .put("sessionId", sessionId));
    try {
      ApprovalAnswer answered =
          APPROVAL_TIMEOUT_SECONDS <= 0
              // 没有截止时间：问题一直立着，直到有人作答，或者直到回合被中止——那会从另一边回答它。
              ? answer.get()
              : answer.get(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      publish(
          sessionId,
          "approval-closed",
          Json.object()
              .put("id", id)
              .put("allow", answered.allowed())
              // 它是四个回答里的哪一个：重新渲染转录的页面会说「本会话内允许」，而不是从一个布尔值去猜。
              .put("answer", answered.name().toLowerCase(java.util.Locale.ROOT)));
      return answered;
    } catch (TimeoutException e) {
      publish(
          sessionId,
          "approval-closed",
          Json.object().put("id", id).put("allow", false).put("reason", "timeout"));
      return ApprovalAnswer.DENY;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ApprovalAnswer.DENY;
    } catch (ExecutionException e) {
      return ApprovalAnswer.DENY;
    } finally {
      pendingApprovals.remove(id);
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

  /**
   * 注册一个订阅者并在同一个原子步骤里交回它错过的事件——否则在「读重放缓冲区」和「开始监听」之间发布
   * 的事件会丢掉。
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
    // 已经点名自己会话的载荷保留它——状态是*关于*某个对话的，构建时就拿着那个 id。只有什么都不说的
    // 载荷才会拿到传进来的 id，所以空的那个仍然是「这是关于服务器的」，而不是「这是关于会话 '' 的」。
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
    // 状态是关于一个对话的——屏幕上那个——所以它点名是哪一个，用的也是页面读出 id 的同一个地方：一个
    // 没有会话的状态会被归到无处。
    ObjectNode node = statusFields();
    node.put("sessionId", sessionIdOf(session()));
    publish(sessionIdOf(session()), "status", node);
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
    for (Pending waiting : pendingApprovals.values()) {
      waiting.answer().complete(ApprovalAnswer.DENY);
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
   * 一个对话的实时状态：它的会话文件、它正在跑的回合，以及它自己的账本。
   *
   * <p>服务器一次只跑一个回合的时候，这些都是实例状态，而那正是一个正在跑的回合把所有东西都锁住的原因。
   * 现在会忙的单位是对话，所以标记、循环和计数器都住在这里，第二个对话只是有自己的一份。
   *
   * <p>计数器就是会话文件已经在记录的那些：它们通过 {@link FileSession#totals()} 读写，所以并排运行的
   * 两个对话不会把 token 加到对方的账本上。
   */
  private final class Conversation {

    private final String id;
    private final FileSession session;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicReference<AgentLoop> loop = new AtomicReference<>();
    /** 正在跑这个对话回合的线程，好让回调能被归到它身上。 */
    private final AtomicReference<Thread> owner = new AtomicReference<>();
    /**
     * 这个对话忙的时候敲进来的消息，最旧的在前。
     *
     * <p>有上界，而上界正是重点：一个无限增长的队列，就是在一个已经在跑东西的会话里失去控制的一条路，
     * 而十六条比任何人看着一个回合时会敲的都多。
     */
    private final Deque<String> queued = new ConcurrentLinkedDeque<>();
    /**
     * 最近一次自动压缩被拒绝时的提示尺寸，或者 0。
     *
     * <p>一次拒绝是一次没产出任何可用东西的模型调用——一段并不比它要替换的东西小的摘要——没有这个值，
     * 只要对话在预算上方徘徊，同一个调用就会在每个回合结束时再做一次。它会在对话从被拒绝的尺寸再长过
     * 一成之后重试，那大致就是摘要开始划算的时候。
     */
    private volatile long autoCompactRefusedAt;
    /**
     * 这个对话的工具在哪里运行，在回合启动时定下。
     *
     * <p>只读一次，而不是每次工具调用都读，因为活动工作区可能在一个回合运行期间改变——切换到另一个
     * 工作区，绝不能把一个已经做了一半的工作重定向到别的目录里。一个回合从它启动的那一刻起就拥有自己的
     * 工作目录。
     */
    private volatile Path cwd;
    /**
     * 已经描述好、等着和用户下一句话一起发出去的那张图片。
     *
     * <p>挂在这个对话上，而不是挂在整个 hub 上：两个对话各持有自己的那一张，切换会话时各自看到自己的
     * 那一张，而不是看到另一个会话上传的图片。
     *
     * <p>描述与「发送」分开，是因为这两件事是分开的。上传一张图片是「看看这个」，而从输入框发出去才是
     * 「照这个做」——过去它们是一个动作，于是模型在用户还没说出想让它做什么之前，就已经拿着一段描述开始
     * 干活了。
     */
    private final AtomicReference<Held> pendingPicture = new AtomicReference<>();

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

    /** 重新读取工作目录，现在启动的回合应当用它。 */
    void useCurrentCwd() {
      this.cwd = AgentHub.this.cwd();
    }

    /**
     * 这个对话的回合一旦被要求停下就返回 true。
     *
     * <p>这是子代理用来知道自己也该停下的依据：中止一个回合必须能到达这个回合委派出去的工作，否则用户
     * 的停止按钮会留下一个子代理继续跑着，而屏幕上没有任何东西说明这一点。
     */
    boolean aborting() {
      AgentLoop active = loop.get();
      return active != null && active.isAborted();
    }

    boolean running() {
      return busy.get();
    }

    /** 把一条消息加入这个对话的队列；队满时返回 false。 */
    boolean enqueue(String text) {
      if (queued.size() >= MAX_QUEUED_MESSAGES) {
        return false;
      }
      queued.addLast(text);
      return true;
    }

    /** 把一条消息放回队首，用于回合槽位在中间被占走的那个竞态。 */
    void enqueueFirst(String text) {
      queued.addFirst(text);
    }

    /** 下一条在等的消息，没有则为 null。 */
    String dequeue() {
      return queued.pollFirst();
    }

    /** 正在等的东西，最旧的在前，供页面画出的状态使用。 */
    List<String> queued() {
      return List.copyOf(queued);
    }

    long autoCompactRefusedAt() {
      return autoCompactRefusedAt;
    }

    void autoCompactRefusedAt(long tokens) {
      this.autoCompactRefusedAt = tokens;
    }

    /** 丢掉正在等的东西；一个意思是「停下，全都停下」的中止会报出这个数量。 */
    int clearQueue() {
      int dropped = queued.size();
      queued.clear();
      return dropped;
    }

    /** 占下回合槽位；这个对话已经有一个在跑时返回 false。 */
    boolean begin() {
      return busy.compareAndSet(false, true);
    }

    void attach(AgentLoop running) {
      loop.set(running);
      owner.set(Thread.currentThread());
    }

    /** 释放回合槽位，好让这个对话里的下一条消息被接受。 */
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
      // 一个在等人类的回合，是靠回答那个问题来停下的：光有标志会让它一直阻塞到审批超时，那不是用户所想
      // 的任何意义上的「停下」。
      for (Map.Entry<String, Pending> entry : pendingApprovals.entrySet()) {
        if (id.equals(entry.getValue().sessionId())) {
          entry.getValue().answer().complete(ApprovalAnswer.DENY);
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

    /** 把账本写到磁盘；在回合边界调用，好让文件扛得住被杀。 */
    synchronized void persist() {
      session.totals(session.totals());
    }

    void close() {
      session.close();
    }
  }

  /** 把循环的回调翻译成线上事件；这里没有任何东西做策略决定。 */
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
      // 面板数的是屏幕上的东西：没有这一句，卡片说某次调用正在跑，计数器却还停在上一回合的总数上。
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

    /** 模型回合在这里计数，而不是来自用量：一个不报用量的提供方照样跑过。 */
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
          .put("summary", ToolSummary.summarise(call));
    }
  }

  /** 留给想在无浏览器的情况下断言审批契约的测试。 */
  public Approver approver() {
    return this::askApproval;
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

  /**
   * 审批链：先由规则回答，再由人回答，而每一个自动给出的回答都在转录里说出来——一次静悄悄发生的审批
   * 是没人能审计的审批。
   */
  private Approver gate() {
    ApprovalRules rules = approvalRules;
    if (rules == null) {
      return this::askApproval;
    }
    return new RuleApprover(
        rules,
        this::askApproval,
        text ->
            publish(
                currentTurnSession(), "notice", Json.object().put("text", text)));
  }

  private static String message(Throwable e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }
}
