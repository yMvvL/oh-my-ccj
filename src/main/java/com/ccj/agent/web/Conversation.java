package com.ccj.agent.web;

import com.ccj.agent.core.AgentLoop;
import com.ccj.agent.core.Receipt;
import com.ccj.agent.session.AttachmentStore;
import com.ccj.agent.session.FileSession;
import java.nio.file.Path;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 一个对话的实时状态：它的会话文件、它正在跑的回合，以及它自己的账本。
 *
 * <p>服务器一次只跑一个回合的时候，这些都是 {@link AgentHub} 的实例状态，而那正是一个正在跑的回合把所有东西都
 * 锁住的原因。现在会忙的单位是对话，所以标记、循环和计数器都住在这里，第二个对话只是有自己的一份。
 *
 * <p>计数器就是会话文件已经在记录的那些：它们通过 {@link FileSession#totals()} 读写，所以并排运行的两个对话
 * 不会把 token 加到对方的账本上。
 *
 * <p>**它是从这个类还是内部类的时候搬出来的，以及为什么值得搬。** 它从前是 `AgentHub` 的一个内部类，于是它
 * 能随手抓住整个服务器：工作目录、审批台、事件流。搬出来之后它只剩两个外部依赖，而且都是构造时注入的回调——
 * **问**当前工作目录（而不是记住它），以及中止时顺带拒掉这个对话待批的审批。一个能回头抓住整个服务器的内部类，
 * 是那种「读它必须同时读另一半」的耦合；两个回调把这件事说清楚了：这个对话需要外界做的，就这两件。
 */
final class Conversation {

  /** 一个回合运行期间，一个对话最多可以积压多少条消息。见 {@link AgentHub#submit}。 */
  static final int MAX_QUEUED_MESSAGES = 16;

  private final String id;
  private final FileSession session;
  /**
   * 这个对话的工具将在哪里运行。
   *
   * <p>它是**问**出来的，不是记住的：活动工作区可能在一个回合运行期间改变，而切换到另一个工作区绝不能把一个
   * 已经做了一半的工作重定向到别的目录里。所以这个对话在它启动那一刻把答案抄下来（见 {@link #useCurrentCwd()}）。
   */
  private final Supplier<Path> activeCwd;
  /** 中止这个对话的回合时，顺带把待批的审批拒掉。理由见 {@link ApprovalDesk#denySession}。 */
  private final Consumer<String> denyPendingApprovals;

  private final AtomicBoolean busy = new AtomicBoolean();
  private final AtomicReference<AgentLoop> loop = new AtomicReference<>();
  /** 正在跑这个对话回合的线程，好让回调能被归到它身上。 */
  private final AtomicReference<Thread> owner = new AtomicReference<>();
  /**
   * 这个对话忙的时候敲进来的消息，最旧的在前。
   *
   * <p>有上界，而上界正是重点：一个无限增长的队列，就是在一个已经在跑东西的会话里失去控制的一条路，而十六条
   * 比任何人看着一个回合时会敲的都多。
   */
  private final Deque<String> queued = new ConcurrentLinkedDeque<>();
  /**
   * 最近一次自动压缩被拒绝时的提示尺寸，或者 0。
   *
   * <p>一次拒绝是一次没产出任何可用东西的模型调用——一段并不比它要替换的东西小的摘要——没有这个值，只要对话
   * 在预算上方徘徊，同一个调用就会在每个回合结束时再做一次。它会在对话从被拒绝的尺寸再长过一倍之后重试，那
   * 大致就是摘要开始划算的时候。
   */
  private volatile long autoCompactRefusedAt;
  /**
   * 这个对话最近一次运行的收据。
   *
   * <p>它在回合结束时成形，因为那一刻同时握着三样东西——转录、检查点、账本的前后快照——而它们散开之后就只能
   * 靠猜。
   */
  private volatile Receipt receipt;
  private volatile Path cwd;
  /**
   * 已经描述好、等着和用户下一句话一起发出去的那张图片。
   *
   * <p>挂在这个对话上，而不是挂在整个 hub 上：两个对话各持有自己的那一张，切换会话时各自看到自己的那一张，
   * 而不是看到另一个会话上传的图片。
   *
   * <p>描述与「发送」分开，是因为这两件事是分开的。上传一张图片是「看看这个」，而从输入框发出去才是「照这个
   * 做」——过去它们是一个动作，于是模型在用户还没说出想让它做什么之前，就已经拿着一段描述开始干活了。
   */
  /**
   * 包内可见，而不是 private：把它放进来、画出来、丢掉它的那三处都在 hub 里（上传、发送、丢弃），而它们
   * 需要读这个引用本身，不是一个「有没有图片」的布尔。搬过来的时候这是一处放宽，其它一个字没动。
   */
  final AtomicReference<Held> pendingPicture = new AtomicReference<>();

  Conversation(
      String id,
      FileSession session,
      Supplier<Path> activeCwd,
      Consumer<String> denyPendingApprovals) {
    this.id = id;
    this.session = session;
    this.activeCwd = activeCwd;
    this.denyPendingApprovals = denyPendingApprovals;
    this.cwd = activeCwd.get();
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

  Receipt receipt() {
    return receipt;
  }

  void receipt(Receipt value) {
    this.receipt = value;
  }

  /** 重新读取工作目录，现在启动的回合应当用它。 */
  void useCurrentCwd() {
    this.cwd = activeCwd.get();
  }

  /**
   * 这个对话的回合一旦被要求停下就返回 true。
   *
   * <p>这是子代理用来知道自己也该停下的依据：中止一个回合必须能到达这个回合委派出去的工作，否则用户的停止按钮
   * 会留下一个子代理继续跑着，而屏幕上没有任何东西说明这一点。
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
    // 在等人类的那个回合是靠回答那个问题被停下的，理由见 ApprovalDesk.denySession。
    denyPendingApprovals.accept(id);
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
   * <p>做了标记，而不是冒充打字打出来的。读者——以及模型——应当知道这条消息是一张图片，而路径正是让描述可核对
   * 的东西：图片仍在磁盘上，{@code read} 不需要审批，问一个被描述漏掉的细节是一次工具调用，而不是再上传一次。
   *
   * <p>它进到对话里的那段文本保持英文，因为模型会读到它：翻译会改变模型的行为。
   *
   * <p>它住在图片被持有的这个地方：它是这条消息的形状，而不是某个前端要画的样式——会话文件、压缩、重放与
   * 恢复都读这一段文本，谁都不能自己拼一份。
   */
  static String pictureMessage(AttachmentStore.Saved saved, String description) {
    return "[picture "
        + saved.path().getFileName()
        + "] "
        + description
        + "\n\n(The picture this describes is saved at "
        + saved.path()
        + "; read it if a detail the description dropped matters.)";
  }
}
