package com.ccj.agent.core;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 代理循环：用户输入进来，工具调用被运行，散文输出。
 *
 * <p>每次迭代是一个模型回合。只说话的回合结束本次运行；带工具调用的回合会把每个调用都执行并回喂，然后再问
 * 模型一次。没有步数上限——回合结束于模型给出回答，或者有人调用 {@link #abort()}——所以一件长活儿永远不会
 * 在某个武断的步骤上被砍断，而失控的活儿由盯着它的人来停。
 */
public final class AgentLoop {

  /**
   * @param finalText 本次运行最后一段非空白的助手散文
   * @param steps 消耗掉的模型回合数
   * @param aborted 当 {@link #abort()} 提前结束了本次运行时为 true
   */
  public record Result(String finalText, int steps, boolean aborted) {}

  private final Provider provider;
  private final ToolRegistry tools;
  private final Session session;
  private final AgentOptions options;
  private final ToolContext toolContext;
  private final AgentListener listener;
  private final Object listenerLock = new Object();
  private final AtomicBoolean aborted = new AtomicBoolean();
  /**
   * 由「结果会让本次运行剩下的部分毫无意义」的工具设置。它是与 {@code aborted} 分开的标志，因为这既不是
   * 失败，也不是提前停下的请求：它意味着活儿干完了，所以回合就地结束，模型一并要求的那些调用被记为未运行。
   * 工具自己的结果已经在会话里；本次运行的最终文本仍是模型最后写下的那段散文。
   */
  private final AtomicBoolean ended = new AtomicBoolean();
  /**
   * 跑这个循环的线程，好让 {@link #abort()} 能打断一次阻塞的模型调用。
   *
   * <p>在 {@link #run} 期间设置：循环是单线程的，一次运行也不可重入，所以一个引用就够了；退出时清掉，这样
   * 运行结束后的 abort 什么都不会做。
   */
  private final java.util.concurrent.atomic.AtomicReference<Thread> worker =
      new java.util.concurrent.atomic.AtomicReference<>();
  /**
   * 循环阻塞在模型上期间为 true。
   *
   * <p>{@link #abort()} 发出的中断必须落在<em>那个</em>等待上，而不是别处。工具本来就有自己的停法——
   * {@code bash} 轮询 {@link ToolContext#isCancelled()} 并杀掉自己的进程树——改为打断它们，会把一次干净
   * 的「用户已中止」变成工具还得为此道歉的 {@code InterruptedException}。所以中断是瞄准了的：只有在这个
   * 标志置位时才发出，而那正是循环没有别的事可做的时刻。
   */
  private final AtomicBoolean awaitingModel = new AtomicBoolean();
  /** 本次运行已经大声说过它不得不修复历史之后为 true。 */
  private boolean repairNoticed;

  public AgentLoop(
      Provider provider,
      ToolRegistry tools,
      Session session,
      AgentOptions options,
      ToolContext toolContext,
      AgentListener listener) {
    this.provider = Objects.requireNonNull(provider, "provider");
    this.tools = Objects.requireNonNull(tools, "tools");
    this.session = Objects.requireNonNull(session, "session");
    this.options = Objects.requireNonNull(options, "options");
    this.listener = listener == null ? AgentListener.NOOP : listener;
    ToolContext given = toolContext == null ? ToolContext.of(null) : toolContext;
    // 工具看到的是本次运行自己的中止标志：长命令会在用户开口时停下，而不是把回合一直攥到超时。
    this.toolContext =
        new ToolContext(
            given.cwd(),
            given.approver(),
            given.outputLimitBytes(),
            this::isAborted,
            () -> ended.set(true));
  }

  public AgentLoop(
      Provider provider, ToolRegistry tools, Session session, AgentOptions options, ToolContext ctx) {
    this(provider, tools, session, options, ctx, AgentListener.NOOP);
  }

  public Session session() {
    return session;
  }

  /** 请求本次运行在当前步骤一允许时就停下。 */
  public void abort() {
    aborted.set(true);
    // 有三样东西可能正攥着循环，而光靠标志只能碰到第一样：
    //
    //  - 步骤边界或即将启动的工具：标志在那里被检查；
    //  - 会轮询 `isAborted()` 的工具（bash 每 150ms 一次）：它会杀掉自己的进程树；
    //  - 模型调用本身，那是对一条可能跑上几分钟的流的阻塞读取。没有任何东西轮询它，所以过去一个「步骤之间」
    //    等待回复的回合会无视请求，直到整个答复都到了——这正是「停止」感觉失灵的原因。
    //
    // 打断线程才能碰到最后那种情况。阻塞读取是可中断的操作，于是提供方抛异常，循环沿着它正常的中止路径解开。
    //
    // 中断只瞄准模型的等待：工具是被它自己轮询的标志停下的，转而打断它会把「用户已中止」变成它还得上报的异常。
    // 中断状态由收到它的人清除，所以循环出来是干净的。
    if (awaitingModel.get()) {
      Thread working = worker.get();
      if (working != null && working != Thread.currentThread()) {
        working.interrupt();
      }
    }
  }

  public boolean isAborted() {
    return aborted.get();
  }

  public Result run(String userInput) {
    aborted.set(false);
    ended.set(false);
    session.append(new Message.User(userInput));

    String finalText = "";
    int step = 0;
    worker.set(Thread.currentThread());
    try {
      return loop(userInput, finalText, step);
    } finally {
      worker.set(null);
      // 中止会打断这个线程，而中断状态会一直置位，直到有人清掉它。留着不管，它会破坏这个线程*接下来*要做的
      // 事——web 服务器会把这些线程交还给线程池——所以一次运行总是干干净净地离开。
      Thread.interrupted();
    }
  }

  private Result loop(String userInput, String finalText, int step) {
    while (true) {
      if (aborted.get()) {
        return new Result(finalText, step, true);
      }

      listener.onTurnStart(step);
      Message.Assistant assistant;
      try {
        assistant = callModel();
      } catch (AgentException e) {
        // 被中止打断的模型调用不是失败的回合：是用户要求它停下的，为此报一句「请求失败」，等于告诉他们自己
        // 的操作弄坏了什么。
        if (aborted.get() && interrupted(e)) {
          return new Result(finalText, step, true);
        }
        throw e;
      }
      session.append(assistant);
      listener.onAssistant(assistant);

      if (!assistant.text().isBlank()) {
        finalText = assistant.text();
      }

      if (!assistant.hasToolCalls()) {
        return new Result(finalText, step + 1, aborted.get());
      }

      List<Message.ToolCall> calls = assistant.toolCalls();
      for (int index = 0; index < calls.size(); ) {
        if (aborted.get()) {
          // 助手回合已经在会话里了，而两种线路格式都要求它做过的每个调用各有一条结果——所以从未运行的调用
          // 就按未运行记下来。把它们落下，正是让一段被中断的对话再也无法继续的原因。
          recordUnrun(calls.subList(index, calls.size()));
          return new Result(finalText, step + 1, true);
        }
        // 一串只读调用是唯一可以重叠的东西：它们什么都不改，所以串行跑唯一买到的是延迟。任何写入或执行的
        // 东西都在本线程上按模型要求的顺序执行。
        int end = index;
        while (end < calls.size() && readOnly(calls.get(end))) {
          end++;
        }
        if (end == index) {
          executeTool(calls.get(index++));
        } else {
          executeReadOnly(calls.subList(index, end));
          index = end;
        }
        if (ended.get()) {
          // 一个工具结束了本次运行（它装了个 jar 以便重启）。要求做的活儿干完了，而模型一并要求的东西
          // 永远不会被发送——所以那些调用按未运行记下来，和 aborted 记法一样，因为一段有调用却没有结果的
          // 对话，没有任何提供方会再接受。
          recordUnrun(calls.subList(index, calls.size()));
          return new Result(finalText, step + 1, false);
        }
      }
      step++;
    }
  }

  private Message.Assistant callModel() {
    // 一次中断留下的非法历史再也发不出去，所以它在被投影之前先修复：文件保留自己的字节，请求得到那些丢失的
    // 结果。
    SessionRepair.Result repaired = SessionRepair.apply(session.messages());
    if (repaired.repaired() && !repairNoticed) {
      // 每次运行只说一次：修复是每一步都从会话推导出来的，每个步骤重复同一条通知只会是噪音。
      repairNoticed = true;
      listener.onNotice(repaired.notice());
    }
    // 会话保留每一条消息；这里决定一次请求携带什么。一段长到超出模型上下文的对话不是要报告的错误，而是一次
    // 要做的投影。
    ContextBudget.Result budget =
        ContextBudget.apply(repaired.messages(), options.contextBudget());
    String notice = budget.notice();
    if (notice != null) {
      listener.onNotice(notice);
    }
    Provider.Request request =
        new Provider.Request(
            options.model(),
            options.systemPrompt(),
            budget.messages(),
            tools.specs(),
            options.temperature(),
            options.maxTokens(),
            options.reasoning());
    try {
      // 在调用期间置位：这是 abort 的中断有用武之地的窗口，也是唯一的窗口。
      awaitingModel.set(true);
      if (aborted.get()) {
        // 中止落在循环的检查与这次调用之间：没有这一段，它发出的中断就没人接收，请求还会为一次已经结束的
        // 运行发出去。在这里失败，会沿着与被中断的调用相同的路径解开。
        throw new InterruptedException("请求发出之前就被中止了");
      }
      return provider.complete(request, this::forward);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AgentException(provider.name() + " 请求被中断", e);
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      throw new AgentException(provider.name() + " 请求失败：" + message, e);
    } finally {
      awaitingModel.set(false);
    }
  }

  /**
   * 当一次失败就是 {@link #abort()} 发出的中断、而不是出了别的错时为 true。
   *
   * <p>中断是以提供方异常的形式往外走的，所以光看类型分辨不出两者：必须沿着 cause 链走一遍。只在
   * {@code aborted.get()} 成立时检查这一点，可以让来自别处的一次真正中断——比如一个正在关闭的执行器——
   * 照旧被报告为它本来的失败。
   */
  private static boolean interrupted(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof InterruptedException) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }

  private void forward(Provider.Event event) {
    switch (event) {
      case Provider.Event.TextDelta d -> listener.onText(d.text());
      case Provider.Event.ReasoningDelta d -> listener.onReasoning(d.text());
      // 提供方在参数组装完成之前就宣告了一次调用；前端在调用真正即将运行时才画出卡片（见 executeTool），
      // 所以这个线路层的信号只是告知性的。
      case Provider.Event.ToolCallStart ignored -> {}
      case Provider.Event.Usage u -> {
        // 账本记在这里，而不是记在每个前端里：会话是循环写的，而提供方报的数字是它自己说的。两个前端各记
        // 一遍就是两个会分叉的总额——而此前终端**一个 token 都没记过**，于是 `--max-total-tokens` 在 REPL
        // 里永远不触发，花的钱也永远不会出现在任何一个数字里。
        session.totals(
            session
                .totals()
                .plus(u.inputTokens(), u.outputTokens(), u.cachedInputTokens(), 0, 0, 0, 0, 0));
        listener.onUsage(u.inputTokens(), u.outputTokens(), u.cachedInputTokens());
        listener.onNotice(usageNotice(u));
      }
      case Provider.Event.Retry r -> listener.onNotice(
          "正在重试 " + provider.name() + "（第 " + r.attempt() + " 次尝试）：" + r.reason());
    }
  }

  /** 一行可读的信息：提示词大小、其中有多少被提供方缓存、回复大小。 */
  private static String usageNotice(Provider.Event.Usage usage) {
    StringBuilder text = new StringBuilder("token：").append(usage.inputTokens()).append(" 输入");
    if (usage.cachedInputTokens() != null && usage.inputTokens() > 0) {
      int percent = Math.round(usage.cachedInputTokens() * 100f / usage.inputTokens());
      text.append("（缓存 ").append(percent).append("%）");
    }
    return text.append(" / ").append(usage.outputTokens()).append(" 输出").toString();
  }

  private void executeTool(Message.ToolCall call) {
    long started = System.nanoTime();
    notifyToolStart(call);
    ToolResult result = tools.execute(call, toolContext);
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    notifyToolEnd(call, result, elapsedMillis);
    session.append(Message.ToolResult.of(call, result));
  }

  /** 模型要求的工具只读、因此可以挨着另一个一起跑时为 true。 */
  private boolean readOnly(Message.ToolCall call) {
    return tools.find(call.name()).map(Tool::readOnly).orElse(false);
  }

  /**
   * 记下被中断拦在运行之前的那些调用。它们的结果会说明这一点，因为另一种选择是一段下一条消息永远发不出去
   * 的会话——也因为「未运行」正是模型需要读到的事实。监听器也会看到它们，这样前端会把它打开的卡片合上，
   * 而不是让它在一次永远完不成的调用上转下去。耗时记为 -1：没有数字可报，而编一个零会被读成一次测量。
   */
  private void recordUnrun(List<Message.ToolCall> calls) {
    for (Message.ToolCall call : calls) {
      Message.ToolResult result =
          new Message.ToolResult(call.id(), call.name(), SessionRepair.NOT_RUN, true);
      notifyToolStart(call);
      notifyToolEnd(call, new ToolResult(result.content(), true), -1);
      session.append(result);
    }
  }

  /**
   * 同时跑一串只读调用，每个一个虚拟线程，并按模型要求的顺序追加结果：一份取决于哪个文件先跑完的转录，会是
   * 一份谁也复现不了的转录。调用开始和结束时都会展示出来，这样前端画出的卡片与串行运行时相同。
   */
  private void executeReadOnly(List<Message.ToolCall> batch) {
    if (batch.size() == 1) {
      executeTool(batch.get(0));
      return;
    }
    // 每个调用一个槽位，由它自己的线程写入、由闩锁发布：改用别的依据排序结果——一个 id、到达顺序——就会
    // 依赖模型的 id 唯一，也依赖哪个文件先跑完，而转录读起来必须和请求一样。
    Outcome[] outcomes = new Outcome[batch.size()];
    CountDownLatch done = new CountDownLatch(batch.size());
    for (int i = 0; i < batch.size(); i++) {
      int slot = i;
      Message.ToolCall call = batch.get(i);
      Thread.ofVirtual()
          .name("ccj-tool-" + call.name())
          .start(
              () -> {
                try {
                  long started = System.nanoTime();
                  notifyToolStart(call);
                  ToolResult result = tools.execute(call, toolContext);
                  long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
                  notifyToolEnd(call, result, elapsedMillis);
                  outcomes[slot] = new Outcome(call, result, elapsedMillis);
                } finally {
                  done.countDown();
                }
              });
    }
    try {
      done.await();
    } catch (InterruptedException e) {
      // 等待期间被中断：已经跑完的仍然会在下面被记下，其余的由循环在看到中止时记为「未运行」。在这里把跑完
      // 的丢掉，会让一个调用毫无理由地没有结果。
      Thread.currentThread().interrupt();
    }
    for (Outcome outcome : outcomes) {
      if (outcome != null) {
        session.append(Message.ToolResult.of(outcome.call(), outcome.result()));
      }
    }
    // 线程什么都没产出的调用——被中断、线程死了——仍然欠着一条结果，而只有当每个调用都有结果时，会话才保持
    // 可发送。
    for (int i = 0; i < outcomes.length; i++) {
      if (outcomes[i] == null) {
        recordUnrun(List.of(batch.get(i)));
      }
    }
  }

  /** 一次跑完的只读调用，正在回到要求它的那次运行。 */
  private record Outcome(Message.ToolCall call, ToolResult result, long elapsedMillis) {}

  /**
   * 并行运行时可能同时触发的两个回调。它们在这里串行化，而不是在每个前端里做：渲染器是一份状态，把它交给
   * 两个线程就是等着慢磁盘来触发的 bug。
   */
  private void notifyToolStart(Message.ToolCall call) {
    synchronized (listenerLock) {
      listener.onToolStart(call);
    }
  }

  private void notifyToolEnd(Message.ToolCall call, ToolResult result, long elapsedMillis) {
    synchronized (listenerLock) {
      listener.onToolEnd(call, result, elapsedMillis);
    }
  }
}
