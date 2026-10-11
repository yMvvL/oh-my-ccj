package com.ccj.agent.web;

import com.ccj.agent.core.Checks;
import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.Receipt;
import com.ccj.agent.core.SessionRepair;
import com.ccj.agent.core.TokenEstimate;
import com.ccj.agent.core.ToolSpec;
import com.ccj.agent.core.UsageTotals;
import com.ccj.agent.core.Workspace;
import com.ccj.agent.export.SessionExport;
import com.ccj.agent.obs.Metric;
import com.ccj.agent.obs.Metrics;
import com.ccj.agent.obs.Recorder;
import com.ccj.agent.obs.RunTrace;
import com.ccj.agent.provider.Providers;
import com.ccj.agent.provider.Providers;
import com.ccj.agent.session.FileSession;
import com.ccj.agent.ui.ToolSummary;
import com.ccj.agent.session.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 「现在长什么样」——状态表、会话列表、用量、收据、导出，以及一段对话的转录。
 *
 * <p>4.8 搬出来的第三块。它只**渲染并发布**：不动配置、不换提供方、不写会话。搬出去之后 `AgentHub` 剩下的那些方法里，
 * 凡是要**改**服务器的都写着一句 `state.`，而这一块里一个都没有——这正是把「读」与「写」分成两块的意义。
 */
final class HubViews {

  /** 会话列表上的时间：本机时区，带着偏移量——一列时间戳对不上钟的时候，读者第一个怀疑的就是时区。 */
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

  private final HubState state;

/**
   * 每个会话最近一次运行的 trace，`GET /api/trace` 读的就是它。
   *
   * <p>只留最近一次，而且只留在内存里：它回答的是「刚刚那个回合里到底发生了什么」——一次运行的形状与
   * 它里面那几段耗时。一次运行一棵树，保留历史是另一件事，那件事由 {@link #setTraceSink} 指向的文件来做。
   */
  private final Map<String, RunTrace> lastTraces = new ConcurrentHashMap<>();

/** 每次运行的 trace 写到哪；null 表示不留。由调用方设置，因为只有它知道命令行给了什么。 */
  private volatile Recorder.TraceSink traceSink;

  HubViews(HubState state) {
    this.state = state;
  }


  ObjectNode status() {
    ObjectNode node = statusFields();
    node.put("type", "status");
    return node;
  }

  /** 屏幕上那个会话的 id；没有打开的会话时是空串。 */
  String currentSessionId() {
    return HubState.idOf(state.session());
  }

  /** 这个服务器的指标；`GET /metrics` 暴露的就是它。 */
  Metrics metrics() {
    return state.metrics();
  }

  /**
   * 每次运行的痕迹追加到哪。
   *
   * <p>由调用方设置，理由与 {@link #setApprovalRules} 相同：那是一个命令行 flag 指到的文件，而本类看不到
   * 命令行。不设置就什么都不写——痕迹的另一个去处是 {@link #lastTrace}，在内存里，永远都在。
   */
  void setTraceSink(Recorder.TraceSink sink) {
    this.traceSink = sink;
  }

  /** 某个会话最近一次运行的痕迹；它还没跑过回合时为 null。 */
  RunTrace lastTrace(String sessionId) {
    return sessionId == null ? null : lastTraces.get(sessionId);
  }

  /** 见 {@link #setChecks}。由调用方设置，理由与 {@link #setApprovalRules} 相同：那是一个命令行 flag 指到的
   * 文件，而本类看不到命令行。 */
  void setChecks(Checks rules) {
    state.setChecks(rules == null ? Checks.none() : rules);
  }

  /**
   * 屏幕上那个会话最近一次运行的收据。
   *
   * <p>和痕迹一样只在内存里、只留最近一次：它回答的是「刚刚那个回合到底做了什么」，而留下历史是导出负责
   * 的事。没有收据时返回 {@code {"receipt": null}} 而不是 404——「这个进程里它还没跑过回合」是一种状态，
   * 不是一次失败。
   */
  ObjectNode receiptJson() {
    FileSession current = state.session();
    Conversation conversation = current == null ? null : state.conversation(current.id());
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
  String exportHtml(String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("导出需要一个会话 id");
    }
    return SessionExport.toHtml(state.openForDisplay(id), Instant.now());
  }

  /** 这条会话持有的待发送图片，或者 {@code null}。 */
  Conversation.Held heldPicture(FileSession current) {
    if (current == null) {
      return null;
    }
    Conversation conversation = state.conversation(current.id());
    return conversation == null ? null : conversation.pendingPicture.get();
  }

  ObjectNode statusFields() {
    FileSession current = state.session();
    Config active = state.config();
    Provider currentProvider = state.provider().get();
    ObjectNode node = Json.object();
    node.put("version", state.settings().version());
    node.put("configured", currentProvider != null);

    // 用配置里的名字，而不是实现的名字：用户选的是 "custom" + 一个中转 URL，给他看 "openai" 会让人困惑。
    node.put("provider", currentProvider == null || active.provider() == null ? "" : active.provider());
    node.put("model", active.model() == null ? "" : active.model());
    // 提供方实际会被调用的端点，而不是文件恰好存着的那个：自定义提供方由它的定义来服务，除非存着的 URL
    // 就是为它输入的。
    String endpoint = Providers.effectiveBaseUrl(active, state.settings().providerStore(), active.provider());
    node.put("baseUrl", endpoint == null ? "" : endpoint);
    node.put("cwd", state.cwd().toString());
    // 正常情况下为空。非空意味着 -C 钉住了一个不属于当前活动工作区的工作目录：两个互相冲突的事实，页面
    // 必须能把它显示出来，而不是让树声称工具跑在它们并不在的地方。
    Path override = state.settings().cwdOverride();
    node.put("cwdOverride", override == null ? "" : override.toString());
    ObjectNode workspaceNode = node.putObject("workspace");
    Workspace activeWorkspace = state.settings().workspaces().active();
    workspaceNode.put(
        "name", activeWorkspace == null ? "" : activeWorkspace.name());
    workspaceNode.put(
        "path", activeWorkspace == null ? "" : activeWorkspace.path().toString());
    node.put("sessionId", current == null ? "" : current.id());
    node.put("messageCount", current == null ? 0 : current.messages().size());
    node.put("autoApprove", state.approvals().autoApprove());
    // 上限是用户设的，不是推测的；null 表示没设。页面用它把「用了多少」变成「用了多少 / 上限多少」。
    if (state.config().maxTotalTokens() == null) {
      node.putNull("maxTotalTokens");
    } else {
      node.put("maxTotalTokens", state.config().maxTotalTokens());
    }
    // 一张已经描述好、还没走的图片：刷新之后那条缩略条还在，而不是变成一张看不见、却会跟着下一句话发
    // 出去的图片。
    Conversation.Held held = state.heldPicture(current);
    if (held == null) {
      node.putNull("picture");
    } else {
      node.set("picture", AttachmentViews.pending(held.saved(), held.description(), held.mediaType()));
    }
    node.put("subAgents", state.subAgents());
    // "busy" 回答的是屏幕上那个对话：页面的输入框针对的是它显示的转录，别处正在跑的回合不能把它禁用掉。
    Conversation shown = state.conversation(current == null ? "" : current.id());
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
    for (Conversation entry : state.conversations()) {
      if (entry.running() && !entry.id().equals(current == null ? "" : current.id())) {
        runningNow.add(entry.id());
      }
    }
    // 这个对话正在等的审批。它们是阻塞在内存里的请求而不是消息，所以不在页面打开会话时重放的历史里——这里
    // 不列出来的话，看一眼别的对话再回来就会丢掉提示，只剩中止这一条出路。
    ArrayNode waiting = node.putArray("approvals");
    String shownId = current == null ? "" : current.id();
    for (Map.Entry<String, ApprovalDesk.Pending> entry : state.approvals().pending().entrySet()) {
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
    for (ToolSpec spec : state.tools().specs()) {
      ObjectNode tool = toolList.addObject();
      tool.put("name", spec.name());
      tool.put("description", spec.description().lines().findFirst().orElse("").strip());
    }
    return node;
  }

  ArrayNode sessionsJson() {
    return sessionsJson(null);
  }
  /**
   * 一个工作区的会话；{@code workspace} 为 null 时则是活动工作区的会话。
   *
   * <p>树视图必须能在切换之前先展示一个折叠文件夹的内容，这正是这次调用：读另一个工作区的列表绝不能
   * 移动当前活动的那个。
   */
  ArrayNode sessionsJson(String workspace) {
    Path directory = state.sessionsDir();
    if (workspace != null && !workspace.isBlank()) {
      Workspace target =
          state
              .settings()
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
    state.metrics().observe(Metric.SESSION_LIST_SECONDS, (System.nanoTime() - started) / 1_000_000_000.0);
    for (SessionStore.Summary summary : sessions) {
      ObjectNode node = array.addObject();
      node.put("id", summary.id());
      node.put("title", summary.title());
      node.put("preview", summary.preview());
      node.put("messageCount", summary.messageCount());
      node.put("lastModified", TIMESTAMP.format(summary.lastModified()));
      // 哪些行在干活，这样留在另一个对话里跑着的回合从哪儿都看得见，而不只是从它正在写的那份转录里。
      Conversation conversation = state.conversation(summary.id());
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
  ObjectNode usageFields() {
    return usageFields(state.session());
  }

  ObjectNode usageFields(FileSession target) {
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
    Config active = state.config();
    node.put("contextLimit", active.maxContextTokens() == null ? 0 : active.maxContextTokens());
    return node;
  }

  UsageTotals currentTotals() {
    return totalsOf(state.session());
  }

  /** 一个会话的账本：文件此前记录的内容；没有文件时则是一本空账。 */
  static UsageTotals totalsOf(FileSession session) {
    return session == null ? UsageTotals.empty() : session.totals();
  }

  /**
   * 把当前会话重放成这条流当初会发出的事件，这样页面用一条代码路径就能渲染历史和实时回合。
   *
   * <p>重放的工具结果不带耗时：对话存的是模型看到的东西，而不是工具花了多久，凭空造一个 0 会被读成一次
   * 实测。
   */
  ObjectNode historyJson() {
    FileSession current = state.session();
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

  static ObjectNode replay(String type) {
    return Json.object().put("type", type).put("replay", true);
  }
  /**
   * 一次运行结束：最近的那棵树留在内存里供 {@code GET /api/trace} 读，另外（如果命令行给了文件）追加到
   * 那份 JSONL 里。
   *
   * <p>两部分都要，因为它们的用处不同：内存里那棵回答「刚刚那一次到底发生了什么」，而在文件里的那些能被
   * 拿走、被 diff、被另一个工具读——那正是「痕迹」这个词在这个项目里的意思。
   */
  void recordTrace(RunTrace trace) {
    lastTraces.put(trace.sessionId(), trace);
    Recorder.TraceSink sink = traceSink;
    if (sink != null) {
      sink.accept(trace);
    }
  }
  void publishStatus() {
    // 状态是关于一个对话的——屏幕上那个——所以它点名是哪一个，用的也是页面读出 id 的同一个地方：一个
    // 没有会话的状态会被归到无处。
    ObjectNode node = statusFields();
    node.put("sessionId", HubState.idOf(state.session()));
    state.publish(HubState.idOf(state.session()), "status", node);
    // 两个仪表跟着状态走：它们是全服务器的，而状态是这里发布得最频繁的东西（每个回合的一头一尾、每次
    // 开关、每次切换）。给它们各自一条定时线程，是拿一个专门的调度器去换两个整数。
    int queued = 0;
    int running = 0;
    for (Conversation conversation : state.conversations()) {
      queued += conversation.queued().size();
      if (conversation.running()) {
        running++;
      }
    }
    state.metrics().set(Metric.QUEUED_MESSAGES, queued);
    state.metrics().set(Metric.RUNNING_CONVERSATIONS, running);
  }

  void publishUsage() {
    state.publish(HubState.idOf(state.session()), "usage", usageFields());
  }

  /** 一个会话的用量，也就是一个回合在自己的账本变化时发布的东西。 */
  void publishUsage(String sessionId) {
    Conversation conversation = sessionId == null ? null : state.conversation(sessionId);
    FileSession target = conversation == null ? state.session() : conversation.session();
    ObjectNode node = usageFields(target);
    node.put("sessionId", sessionId == null ? "" : sessionId);
    state.publish(sessionId, "usage", node);
  }
}
