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
import java.util.Optional;
import java.util.Set;
import java.util.Collection;
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

  /** 状态与会话列表那一块（4.8 搬出来的第三块）。 */
  private final HubViews views = new HubViews(seat);

  /** 附件那条路（4.8 搬出来的第四块）。 */
  private final HubAttachments attachments = new HubAttachments(seat);

  /** 会话本身：新建、恢复、删除、切换（4.8 搬出来的第五块）。 */
  private final HubSessions sessions = new HubSessions(seat);

  /** 回合的执行：submit、abort、循环、压缩、收回（4.8 搬出来的第六块）。 */
  private final HubTurns turns = new HubTurns(seat);

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
  // ---- 状态、会话列表、用量、收据与转录：搬到了 HubViews（4.8），这里只剩下委托

  public ObjectNode status() {
    return views.status();
  }

  public String currentSessionId() {
    return views.currentSessionId();
  }

  public Metrics metrics() {
    return views.metrics();
  }

  public void setTraceSink(Recorder.TraceSink sink) {
    views.setTraceSink(sink);
  }

  public RunTrace lastTrace(String sessionId) {
    return views.lastTrace(sessionId);
  }

  public void setChecks(Checks rules) {
    views.setChecks(rules);
  }

  public ObjectNode receiptJson() {
    return views.receiptJson();
  }

  public String exportHtml(String id) {
    return views.exportHtml(id);
  }

  private ObjectNode statusFields() {
    return views.statusFields();
  }

  public ArrayNode sessionsJson() {
    return views.sessionsJson();
  }

  public ArrayNode sessionsJson(String workspace) {
    return views.sessionsJson(workspace);
  }

  public ObjectNode usageFields() {
    return views.usageFields();
  }

  private ObjectNode usageFields(FileSession target) {
    return views.usageFields(target);
  }

  public ObjectNode historyJson() {
    return views.historyJson();
  }

  private Conversation.Held heldPicture(FileSession current) {
    return views.heldPicture(current);
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

  // ---- 回合的执行：搬到了 HubTurns（4.8），这里只剩下委托

  public Submit submit(String text) {
    return turns.submit(text);
  }

  public Submit submit(String text, String pictureName) {
    return turns.submit(text, pictureName);
  }

  public ObjectNode undoPreview() {
    return turns.undoPreview();
  }

  public ObjectNode compact() {
    return turns.compact();
  }

  public boolean abort() {
    return turns.abort();
  }

  public boolean abortAll() {
    return turns.abortAll();
  }

  public boolean abort(String sessionId) {
    return turns.abort(sessionId);
  }

  public ObjectNode undoTurn() {
    return turns.undoTurn();
  }

  // ------------------------------------------------------------------ turns


  /** 一条提交上来的消息后来怎么了：它开始了，或者它正在跑着的那个回合后面排队。 */
  public enum Submit {
    STARTED,
    QUEUED
  }



  /**
   * 页面要显示的那张待发送图片，或者 {@code null}。
   *
   * <p>状态快照和事件说的是同一件事：刷新页面之后那张缩略条仍然在，而不是变成一张看不见、却会跟着下一
   * 句话一起发出去的图片。
   */



  /**
   * 把调用方给的一个名字解析成目录里的一个文件，或者 {@code null}。
   *
   * <p>规矩是「解析之后仍然必须在那个目录里」：名字里带分隔符、或者解析后跑到目录外面去（{@code ..}、
   * 绝对路径、指向别处的符号链接），一律返回 {@code null}。检查的是解析结果而不是名字的拼写，因为名字
   * 的拼写有太多写法。
   */
  /** 一张图片的字节，以及它们是什么类型。 */
  public record PictureFile(byte[] bytes, String mediaType) {}



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



  // ---- 附件那条路：搬到了 HubAttachments（4.8），这里只剩下委托

  public ObjectNode describePicture(String name, byte[] bytes) {
    return attachments.describePicture(name, bytes);
  }

  public PictureFile attachmentThumbnail(String name) {
    return attachments.attachmentThumbnail(name);
  }

  public String discardPicture() {
    return attachments.discardPicture();
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



  // ---- 会话的增删与切换：搬到了 HubSessions（4.8），这里只剩下委托

  public void newSession() {
    sessions.newSession();
  }

  public void resumeSession(String id) {
    sessions.resumeSession(id);
  }

  public ObjectNode deleteSession(String id) {
    return sessions.deleteSession(id);
  }

  public ObjectNode deleteSession(String workspace, String id) {
    return sessions.deleteSession(workspace, id);
  }

  public ObjectNode deleteAllSessions() {
    return sessions.deleteAllSessions();
  }

  public ObjectNode deleteAllSessions(String workspace) {
    return sessions.deleteAllSessions(workspace);
  }

  /** 向桌面要一个目录。阻塞直到用户作答、取消或超时。 */
  public Optional<Path> chooseFolder(java.io.IOException[] failure) {
    try {
      return folderChooser.choose("选择工作区文件夹");
    } catch (java.io.IOException e) {
      failure[0] = e;
      return Optional.empty();
    }
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

  /**
   * 这个会话的对话；没有就地开一个。
   *
   * <p>见 {@link HubState#conversationFor}：新对话拿到的是屏幕上的那个会话对象，而它写的正是页面读的那个
   * 文件——按对话规则保护的东西，前提就是这一句。
   */
  public Conversation conversationFor(FileSession session) {
    return conversations.computeIfAbsent(
        session.id(), key -> new Conversation(key, session, this::cwd, approvals::denySession));
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
    return HubState.idOf(session());
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

  public void publishStatus() {
    // 状态是关于一个对话的——屏幕上那个——所以它点名是哪一个，用的也是页面读出 id 的同一个地方：一个
    // 没有会话的状态会被归到无处。
    ObjectNode node = statusFields();
    node.put("sessionId", HubState.idOf(session()));
    publish(HubState.idOf(session()), "status", node);
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
    publish(HubState.idOf(session()), "usage", usageFields());
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
    // 线程池是回合那一块的财产，所以「停下并等它们真的停下」也归它说（见 HubTurns#close）。
    turns.close();
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
    public boolean closed() {
      return closed;
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
      return views.status();
    }

    @Override
    public void recordTrace(RunTrace trace) {
      AgentHub.this.recordTrace(trace);
    }

    @Override
    public RunTrace lastTrace(String sessionId) {
      return lastTraces.get(sessionId);
    }

    @Override
    public Metrics metrics() {
      return metrics;
    }

    @Override
    public void setTraceSink(Recorder.TraceSink sink) {
      traceSink = sink;
    }

    @Override
    public Checks checks() {
      return checks;
    }

    @Override
    public void setChecks(Checks rules) {
      checks = rules;
    }

    @Override
    public ApprovalDesk approvals() {
      return approvals;
    }

    @Override
    public Collection<Conversation> conversations() {
      return conversations.values();
    }

    @Override
    public boolean subAgents() {
      return subAgents.get();
    }

    @Override
    public ArrayNode sessionsJson(String workspace) {
      return AgentHub.this.sessionsJson(workspace);
    }

    @Override
    public Conversation conversationFor(FileSession session) {
      return AgentHub.this.conversationFor(session);
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
      return sessions.normaliseWorkspace(workspace);
    }

    @Override
    public Path sessionsDirOf(String workspace) {
      return sessions.sessionsDirOf(workspace);
    }

    @Override
    public FileSession openForDisplay(String id) {
      return sessions.openForDisplay(id);
    }

    @Override
    public void requireNotRunning(String sessionId, String what) {
      sessions.requireNotRunning(sessionId, what);
    }

    @Override
    public void requireEverythingIdle(String what) {
      sessions.requireEverythingIdle(what);
    }

    @Override
    public void requireShownIdle(String what) {
      sessions.requireShownIdle(what);
    }
  }

}
