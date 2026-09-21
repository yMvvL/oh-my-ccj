package com.ccj.agent.web;

import com.ccj.agent.core.ApprovalAnswer;
import com.ccj.agent.core.ApprovalRequest;
import com.ccj.agent.core.ApprovalRules;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.RuleApprover;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 审批台：一个回合在等人类作答时所站的地方。
 *
 * <p>状态自己持有——悬着哪些请求、下一个 id、自动审批那个开关，以及一个请求可以等多久——而不是由
 * {@link AgentHub} 把它们凑齐再传进来：审批按 id 作答，而「这个 id 还在等吗」这个问题只有在拿着那张桌子
 * 的人手里才有答案。
 *
 * <p>它向外要的几样东西全是构造时注入的回调：往哪条流上说话、怎么说一句状态、此刻是哪个回合的会话在
 * 问，以及用户的规则从哪里来。它没有 {@link AgentHub} 的引用，所以这里没有任何东西能回头去读一个会话或
 * 一个回合。
 */
final class ApprovalDesk {

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

  /** 往外说话的口子，形状与 {@code AgentHub} 那条私有的事件方法一样：一条点名会话的事件。 */
  @FunctionalInterface
  interface EventSink {
    void publish(String sessionId, String type, ObjectNode payload);
  }

  private final AtomicInteger nextApprovalId = new AtomicInteger();
  /**
   * 在等人类的审批，按它们的 id 键存放。
   *
   * <p>是整个服务器一份，而不是每个对话一份：审批按 id 作答，来自任何一个注意到它的页面，而一个在后台
   * 回合里需要人类的请求，正是这件事绝不能弄错的情形。
   */
  private final Map<String, Pending> pendingApprovals = new ConcurrentHashMap<>();
  private final AtomicBoolean autoApprove = new AtomicBoolean();

  /** 用户在这个项目里的规则从哪里来；调用方手里有应用 home 而这里没有，所以它是注入的。 */
  private final Supplier<ApprovalRules> rules;
  /** 状态是关于一个对话的，只有 {@link AgentHub} 造得出来，所以这里只负责要求它发一条。 */
  private final Runnable publishStatus;
  private final EventSink events;
  /** 这条线程正在跑其回合的那个会话，也就是一个请求该被归到哪里。 */
  private final Supplier<String> currentTurnSession;

  ApprovalDesk(
      boolean autoApprove,
      Supplier<ApprovalRules> rules,
      Runnable publishStatus,
      EventSink events,
      Supplier<String> currentTurnSession) {
    this.autoApprove.set(autoApprove);
    this.rules = rules;
    this.publishStatus = publishStatus;
    this.events = events;
    this.currentTurnSession = currentTurnSession;
  }

  /** 留给想在无浏览器的情况下断言审批契约的测试。 */
  Approver approver() {
    return this::askApproval;
  }

  /**
   * 审批链：先由规则回答，再由人回答，而每一个自动给出的回答都在转录里说出来——一次静悄悄发生的审批
   * 是没人能审计的审批。
   */
  Approver gate() {
    ApprovalRules active = rules.get();
    if (active == null) {
      return this::askApproval;
    }
    return new RuleApprover(
        active,
        this::askApproval,
        text ->
            events.publish(
                currentTurnSession.get(), "notice", Json.object().put("text", text)));
  }

  /**
   * 回答一个待处理的审批。id 未知或已经回答过时返回 false。
   *
   * <p>{@code remember} 过去的意思是「把整个会话切成自动审批」，在一个布尔关口下它也只能是这个意思：
   * 对「别再问我这个了」的回答变成了「别再问我任何事了」。它现在的意思就是那个人在按钮上读到的——这个
   * 请求，在本会话余下的时间里——而「永远别再问」该去的地方是规则文件。
   */
  boolean resolveApproval(String id, ApprovalAnswer answer) {
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
        case "never" -> ApprovalAnswer.DENY_ALWAYS;
        case "allow", "once", "yes" -> ApprovalAnswer.ALLOW_ONCE;
        case "deny", "no" -> ApprovalAnswer.DENY;
        default -> throw new IllegalArgumentException(
            "未知的审批回答 '" + posted + "'；请使用 deny、never、once、session 或 always");
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
   *
   * <p>包内可见，是因为那条状态快照就在隔壁：它得能列出屏幕上这个对话正在等什么。
   */
  record Pending(
      String sessionId,
      String title,
      String detail,
      ApprovalRequest request,
      CompletableFuture<ApprovalAnswer> answer) {}

  boolean autoApprove() {
    return autoApprove.get();
  }

  void setAutoApprove(boolean enabled) {
    if (autoApprove.getAndSet(enabled) != enabled) {
      publishStatus.run();
    }
  }

  /**
   * 此刻悬着的审批，按 id。
   *
   * <p>给出来的是那张活的表，和从前直接读那个字段一样：调用方只读它，用来列出屏幕上这个对话正在等什么。
   */
  Map<String, Pending> pending() {
    return pendingApprovals;
  }

  /**
   * 阻塞循环线程，直到浏览器作答。在调用内部发布，是让代理的请求可见的原因；超时返回 false，是让它不
   * 至于永远等下去的原因。
   */
  private ApprovalAnswer askApproval(ApprovalRequest request) {
    if (autoApprove.get()) {
      return ApprovalAnswer.ALLOW_ONCE;
    }
    String sessionId = currentTurnSession.get();
    String id = "ap-" + nextApprovalId.incrementAndGet();
    CompletableFuture<ApprovalAnswer> answer = new CompletableFuture<>();
    pendingApprovals.put(id, new Pending(sessionId, request.title(), request.detail(), request, answer));
    // 还要发一个状态，这样一个没在看这个对话的页面也能知道有东西在等——而一个*正在*看它的页面，能从它
    // 进来时索要的状态里重建提示。
    publishStatus.run();
    events.publish(
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
      events.publish(
          sessionId,
          "approval-closed",
          Json.object()
              .put("id", id)
              .put("allow", answered.allowed())
              // 它是四个回答里的哪一个：重新渲染转录的页面会说「本会话内允许」，而不是从一个布尔值去猜。
              .put("answer", answered.name().toLowerCase(java.util.Locale.ROOT)));
      return answered;
    } catch (TimeoutException e) {
      events.publish(
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

  /** 让这张桌子空下来：还在等人类的请求立刻被答复为「否」，这样它们所在的回合能收尾而不是被丢下。 */
  void denyAll() {
    for (Pending waiting : pendingApprovals.values()) {
      waiting.answer().complete(ApprovalAnswer.DENY);
    }
    pendingApprovals.clear();
  }

  /**
   * 拒绝一个对话自己的请求。
   *
   * <p>一个在等人类的回合，是靠回答那个问题来停下的：光有标志会让它一直阻塞到审批超时，那不是用户所想
   * 的任何意义上的「停下」。
   */
  void denySession(String sessionId) {
    for (Map.Entry<String, Pending> entry : pendingApprovals.entrySet()) {
      if (sessionId.equals(entry.getValue().sessionId())) {
        entry.getValue().answer().complete(ApprovalAnswer.DENY);
      }
    }
  }
}
