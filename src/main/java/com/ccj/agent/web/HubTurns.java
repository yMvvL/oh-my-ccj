package com.ccj.agent.web;

import com.ccj.agent.core.AgentListener;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.AgentLoop;
import com.ccj.agent.core.AgentOptions;
import com.ccj.agent.core.Checks;
import com.ccj.agent.core.Compaction;
import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Receipt;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Prompts;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.SpendLimit;
import com.ccj.agent.core.SubAgentRunner;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolRegistry;
import com.ccj.agent.core.ToolResult;
import com.ccj.agent.core.ToolSpec;
import com.ccj.agent.core.TokenEstimate;
import com.ccj.agent.core.UsageTotals;
import com.ccj.agent.obs.MeteredApprover;
import com.ccj.agent.obs.Metric;
import com.ccj.agent.obs.Recorder;
import com.ccj.agent.obs.RunTrace;
import com.ccj.agent.session.CheckpointStore;
import com.ccj.agent.tool.TaskTool;
import com.ccj.agent.session.FileSession;
import com.ccj.agent.core.SessionRepair;
import com.ccj.agent.ui.ToolSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 回合的执行：开始、跑、中止、压缩、收回。
 *
 * <p>4.8 搬出来的第六块，也是最大的一块。它带着两样**属于它自己**的东西过来：跑回合用的那个线程池，以及
 * 一次回合适不适合被收回所依赖的检查点。它们跟着走，是因为只有这一块在动它们——而从前它们躺在一个两千多行
 * 的类里，读者只能靠读完整个类来确认这一点。
 *
 * <p>它拿到的仍然只是 {@link HubState} 一个参数：一个回合要的「现在用什么模型、屏幕上哪个会话、事件往哪发」
 * 都在那个窄面上。
 */
final class HubTurns {

  private final HubState state;

  HubTurns(HubState state) {
    this.state = state;
  }

  /**
   * {@link #close()} 最多等多久让正在跑的回合收尾。
   *
   * <p>五秒是一个「够一个正在写会话文件的回合写完」的界，而不是「够一个长命令跑完」的界：被中断的工具命令
   * 只看循环的中止标志，等它等不来。
   */
  private static final long SHUTDOWN_GRACE_SECONDS = 5;

  /** 每个正在跑的回合一个：干着活的会话拿到自己的线程，而不是服务器的线程。 */
  private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

  /** 一个回合此前的文件内容存到哪，这样这个回合才可以被收回。 */
  private final CheckpointStore checkpoints = CheckpointStore.recording();

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
            state.tools(),
            options,
            // 角色表来自当前生效的配置：把 explore 钉到便宜模型是一次运行的选择，不是一次会话的选择。
            active.subAgents(),
            conversation.cwd(),
            conversation::aborting,
            active.outputLimitBytes(),
            // 这个对话自己的审批人，这样子代理的许可请求会出现在用户已经在看的转录里。它在本回合的线程
            // 上运行，所以 `currentTurnSession` 会把它归到这里，而中止这个回合就会回答它。
            state.approvals().gate());
    ToolRegistry registry = new ToolRegistry();
    for (String name : state.tools().names()) {
      state.tools().find(name).ifPresent(registry::register);
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

  /**
   * 在前端正看着的会话里启动一个回合。那个会话已经有一个在跑时返回 false。
   *
   * <p>按会话拒绝而不是按服务器拒绝，正是重点：一个对话里的长任务绝不能挡住用户在另一个对话里开始工作。
   * 仍然拒绝的，是<em>同一个</em>对话里的第二个回合——两个写入者往同一份转录里追加，正是毁掉它的东西。
   */
  public AgentHub.Submit submit(String text) {
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
  public AgentHub.Submit submit(String text, String pictureName) {
    if (state.closed()) {
      throw new IllegalStateException("web 会话正在关闭");
    }
    FileSession current = state.session();
    if (current == null) {
      throw new IllegalStateException("没有打开的会话");
    }
    Conversation conversation =
        state.conversationFor(current);
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
    if (state.provider().get() == null) {
      throw new IllegalStateException("没有配置模型——打开「设置」添加一个");
    }
    // 花费上限在**开始之前**判定，而不是在花掉了之后：一个跑了一半才被拦下的回合，既花了钱又没有结果，
    // 而用户能做的补救（提高上限、改用便宜模型）都得在下一次开始之前做。
    String overBudget =
        SpendLimit.refusal(
            current.totals().inputTokens() + current.totals().outputTokens(),
            state.config().maxTotalTokens());
    if (overBudget != null) {
      throw new IllegalStateException(overBudget);
    }
    String composed =
        held == null ? text.strip() : held.block() + (text.isBlank() ? "" : "\n\n" + text.strip());
    if (conversation.begin()) {
      conversation.pendingPicture.compareAndSet(held, null);
      startTurn(conversation, composed);
      return AgentHub.Submit.STARTED;
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
    state.publish(conversation.id(), "picture", Json.object().putNull("name"));
    state.publishStatus();
    return AgentHub.Submit.QUEUED;
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
    state.publish(current.id(), "user", Json.object().put("text", text));
    state.publishStatus();
    threads.submit(() -> runTurn(conversation, text));
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
    FileSession current = state.session();
    if (current == null) {
      throw new IllegalStateException("没有打开的会话");
    }
    Conversation conversation = state.conversation(current.id());
    if (conversation != null && conversation.running()) {
      throw new IllegalStateException("还有回合在跑；预览退回之前请先中止它");
    }
    return checkpoints.previewUndo(current.file(), state.cwd());
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
    if (state.closed()) {
      throw new IllegalStateException("web 会话正在关闭");
    }
    Provider current = state.provider().get();
    if (current == null) {
      throw new IllegalStateException("没有配置模型——打开「设置」添加一个");
    }
    FileSession shown = state.session();
    if (shown == null) {
      throw new IllegalStateException("没有打开的会话");
    }
    String id = shown.id();
    Conversation conversation =
        state.conversationFor(shown);
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
    Config active = state.config();
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

  /** 请求屏幕上这个会话的回合在下一个安全点停下。 */
  public boolean abort() {
    FileSession current = state.session();
    Conversation conversation = current == null ? null : state.conversation(current.id());
    return conversation != null && conversation.abort();
  }

  /**
   * 停掉屏幕上的回合，并丢掉排在它后面的东西。
   *
   * <p>两件事都做，因为中止是事情出错时按下的按钮：留下四条消息等着被中止的回合一松手就开始，与停下来
   * 正好相反。数量会被发布，所以一条被丢掉的消息是一个可见事件，而不是一片寂静。
   */
  public boolean abortAll() {
    FileSession current = state.session();
    Conversation conversation = current == null ? null : state.conversation(current.id());
    if (conversation == null) {
      return false;
    }
    int dropped = conversation.clearQueue();
    boolean stopped = conversation.abort();
    if (dropped > 0) {
      state.publish(
          conversation.id(),
          "notice",
          Json.object()
              .put("text", "已中止；丢掉了 " + dropped + " 条排队的消息"));
    }
    return stopped || dropped > 0;
  }

  /** 请求某个具名会话的回合停下，无论它是不是屏幕上的那个。 */
  public boolean abort(String sessionId) {
    Conversation conversation = sessionId == null ? null : state.conversation(sessionId);
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
            state.config().outputLimitBytes() == null ? 0 : state.config().outputLimitBytes(),
            state.checks(),
            conversation.cwd()));
    conversation.addElapsed((System.nanoTime() - started) / 1_000_000);
    // 在回合边界做持久化，既让文件不至于每来一个 token 就长一点，也仍然扛得住被杀：最坏情况下丢的是最后
    // 一个回合，而绝不是整个会话。
    conversation.persist();
    state.publishUsage(conversation.id());
    state.publishStatus();
    if (failure != null) {
      state.publish(conversation.id(), "error", Json.object().put("message", FormFields.message(failure)));
    } else {
      state.publish(
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
    Config active = state.config();
    Integer budget = active.maxContextTokens();
    if (state.closed() || budget == null || budget <= 0 || state.provider().get() == null) {
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
      ObjectNode result = runCompaction(conversation, state.provider().get());
      state.publish(
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
      state.publish(
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
    if (state.closed()) {
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
    Provider current = state.provider().get();
    if (current == null) {
      throw new IllegalStateException("没有配置模型——打开「设置」添加一个");
    }
    Config active = state.config();
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
    state.metrics().set(
        Metric.PREFIX_TOKENS,
        TokenEstimate.of(options.systemPrompt()) + registry.schemaTokens());
    ToolContext context =
        new ToolContext(
            conversation.cwd(),
            // 审批不经过循环的回调（它发生在一次工具调用的里面），所以它自己带一层计时。
            new MeteredApprover(state.metrics(), state.approvals().gate()),
            active.outputLimitBytes());
    String sessionId = conversation.id();
    return new AgentLoop(
        current,
        registry,
        conversation.session(),
        options,
        context,
        new Recorder(state.metrics(), sessionId, state::recordTrace, new WebListener(conversation, events())));
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
        state.publish(sessionId, type, payload);
      }

      @Override
      public void publishUsage(String sessionId) {
        state.publishUsage(sessionId);
      }
    };
  }

  /** 这个服务器到底跑不跑子代理。 */
  private boolean subAgentsAllowed() {
    return state.subAgents();
  }

  /**
   * 把上一回合改动过的东西放回去。
   *
   * <p>这是人们在让代理靠近自己的文件之前会问的那个问题——「可以退回吗」——的答案，也是审批可以被
   * 留在它该在的位置、而不必为了补偿被关掉的原因。有回合在跑时拒绝：在它底下做退回会恢复模型正在思考
   * 的文件，那比两种状态中的任何一种都糟。
   */
  public ObjectNode undoTurn() {
    FileSession current = state.session();
    if (current == null) {
      throw new IllegalStateException("没有打开的会话");
    }
    Conversation conversation = state.conversation(current.id());
    if (conversation != null && conversation.running()) {
      throw new IllegalStateException("还有回合在跑；退回上一个回合之前请先中止它");
    }
    List<String> restored = checkpoints.undoLastTurn(current.file(), state.cwd());
    if (restored.isEmpty()) {
      state.publish(
          current.id(),
          "notice",
          Json.object().put("text", "没有可退回的东西：还没有哪个回合改动过文件"));
    } else {
      state.publish(
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
  /** 停下来，并等一等被中断的回合真的停下。 */
  void close() {
    threads.shutdownNow();
    // 等一等被中断的回合真的停下，而不是把「关掉」当成「说了一句关掉」：会话文件是每一条消息一次 write，
    // 所以 close() 之后还有人在写，就意味着「关掉之后文件还在变」。
    //
    // 这件事是被一次真实的失败逼出来的（`WebConversationConcurrencyTest` 里 JUnit 删不掉自己的临时目录：
    // 一个还在收尾的回合刚好又往 `sessions/` 里写了一个文件）。等待是有上界的，因为一个正卡在长命令里的
    // 工具不会被线程中断叫醒——它只看循环的中止标志——而 close() 不该为了它挂住五分钟。
    try {
      if (!threads.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
        // 说不出口也要说：这里不抛，因为一个关不下去的回合不该让进程退不掉；但也不该假装它停了。
        java.util.logging.Logger.getLogger(HubTurns.class.getName())
            .warning("关闭时有回合没有在 " + SHUTDOWN_GRACE_SECONDS + " 秒内停下");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
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
    int generation = FileSession.newestGeneration(state.sessionsDir(), session.id());
    state.publish(
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
    state.publishUsage(conversation.id());
    state.publishStatus();
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
  /** 见 {@link ApprovalDesk#approver}。 */
  public Approver approver() {
    return state.approvals().approver();
  }
}
