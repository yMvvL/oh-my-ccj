package com.ccj.agent.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 运行一个子代理并返回它的报告。
 *
 * <p>一个真正的 {@link AgentLoop}、一个 {@link MemorySession}，以及一个把自己的事件扔掉的监听器。最后一点
 * 正是这个功能的要点：循环不是对代理的模拟，它<em>就是</em>一个代理——同样的工具、同样的审批机制、同样的修复
 * 与预算——但它做的任何事都不会到页面上。侧边栏里没有 id，线路上没有增量，磁盘上没有文件。用户读到的只有主
 * 代理的总结。
 *
 * <p>这个类真正要处理的是四件让那个隐形循环不至于变成麻烦的事：
 *
 * <ul>
 *   <li><b>注册表里没有 {@code task}。</b>递归是由构造方式拒绝的，而不是靠深度计数器——子代理要不到子代理，
 *       因为那个工具根本没被提供，于是没有参数可以搞错。
 *   <li><b>一个截止时间。</b>失控的子代理不能把主对话一直攥着。
 *   <li><b>取消。</b>中止主回合会停掉它启动的那些子代理，走的是工具为自己的长命令早已遵守的同一个标志。
 *   <li><b>每个文件只有一个写入者。</b>两个子代理不能同时持有同一个路径。并行是给读者的；两个写入者在同一个
 *       文件上相撞会悄悄丢掉其中一个结果，而那恰是谁也看不见的失败。
 * </ul>
 */
public final class SubAgentRunner {

  /**
   * 子代理可以跑多久。
   *
   * <p>是分钟而不是秒：一个读大目录树的探索者干的是真活儿，而一个砍掉有用运行的截止时间，比一个偶尔多等一会儿
   * 的截止时间更糟。它是针对失控情形的兜底，不是日程表。
   */
  public static final Duration DEFAULT_DEADLINE = Duration.ofMinutes(10);

  /**
   * 同一时间只有一个写入者，进程范围内。
   *
   * <p>早先的尝试把锁键在任务文本上，那不管用：两个代理说「write std.cpp」和「produce the reference
   * solution std.cpp」，是同一个文件、两个不同的字符串。判断两个任务是否会碰到同一个路径，需要理解它们的
   * 意思，而这不是这个类能做到的事。所以它不去试——它一次只放行一个写入运行。
   *
   * <p>代价是罕见情形下的延迟，而它避免的是悄悄丢掉一个结果：两个写入者在同一个文件上相撞会没掉一个，而且
   * 看着的人无从察觉。只读角色不受影响，仍然并行运行，而并行本来也正是值得拥有的地方。
   */
  private static final java.util.concurrent.locks.ReentrantLock WRITE_LOCK =
      new java.util.concurrent.locks.ReentrantLock(true);

  /**
   * 一个什么都没点名的角色。
   *
   * <p>用它而不是 {@code null}，是因为「跟随主对话」不是一处特殊情形，而是这块配置的默认：两样都缺席就
   * 意味着主对话的模型加主对话的档位，读起来与一条空设置完全一样。
   */
  private static final SubAgentChoice FOLLOW_MAIN = new SubAgentChoice(null, null);

  /**
   * 没有写入运行持有锁时为 true。
   *
   * <p>给测试用：锁是刻意进程范围的——它守的是一棵目录树，不是一个对象——而一个测量排队的测试必须知道，
   * 正在排队的是它自己，而不是在等某个更早的测试留下的、还在跑的运行。
   */
  public static boolean noWriterRunning() {
    return !WRITE_LOCK.isLocked();
  }

  private final Provider provider;
  private final ToolRegistry tools;
  private final AgentOptions options;
  /**
   * 每个角色被钉到的模型与档位，键是小写的角色名（和 {@link SubAgentRole#wireName()} 同一个写法，配置在
   * 收下时就把它规范成了小写）。
   *
   * <p>不在表里的角色跟随主对话。这正是这块配置的定位：一次收窄，而不是一次分叉——被点名的角色换了模型，
   * 换不掉的是钥匙、端点和审批者，因为一次委派不是另一个提供方。
   */
  private final Map<String, SubAgentChoice> subAgents;
  private final Path cwd;
  private final Duration deadline;
  private final BooleanSupplier cancelled;
  private final int outputLimitBytes;
  /**
   * 子代理请求许可时，请求如何到达用户。
   *
   * <p>用的是对话自己的审批器，不是 {@code Approver.ALWAYS}。子代理是隐形的，所以诱人的设计是让它一路
   * 免问——但一个不弹提示就能改文件的隐藏代理，正是这个功能绝不能变成的东西；而那个曾经让这说得过去的暂存
   * 目录，结果代价高于它的价值：主代理得手工提升文件，而一次没有在同一个回合里发生的提升就被丢掉了。
   *
   * <p>所以子代理会问，通过启动它的那段对话来问。提示出现在用户已经在看的转录里，归属于那段对话（子代理
   * 跑在它的线程上），中止回合就是回答它。子代理在它<em>读</em>什么上是隐形的；在它<em>做</em>什么上不是。
   */
  private final Approver approver;

  /**
   * @param provider 主对话所用的同一个提供方；子代理不是第二个模型
   * @param tools <em>完整</em>的注册表——每个角色在这里被挑出它获准使用的子集，所以调用方不会不小心把写入
   *     工具交给一个校验者
   * @param options 模型设置，从主对话复制，好让子代理以相同的温度和推理努力运行，而不是某个没人选过的默认值
   * @param subAgents 角色到它自己的模型与档位的表；没点名的角色跟随主对话，这也是不关心这件事的调用方
   *     所用的那个重载的默认
   * @param cwd 会话工作目录：相对路径据此解析，{@code insideCwd} 也据此判断
   * @param cancelled 由主回合回答，所以中止它也会中止这个
   */
  public SubAgentRunner(
      Provider provider,
      ToolRegistry tools,
      AgentOptions options,
      Map<String, SubAgentChoice> subAgents,
      Path cwd,
      BooleanSupplier cancelled,
      int outputLimitBytes,
      Approver approver) {
    this(
        provider,
        tools,
        options,
        subAgents,
        cwd,
        cancelled,
        outputLimitBytes,
        DEFAULT_DEADLINE,
        approver);
  }

  /**
   * 没有角色被钉到别处的运行的构造器。
   *
   * <p>每一个角色都跟随主对话——不是「没有模型」，而是主对话那一个。想要按角色收窄的调用方用上面那个。
   */
  public SubAgentRunner(
      Provider provider,
      ToolRegistry tools,
      AgentOptions options,
      Path cwd,
      BooleanSupplier cancelled,
      int outputLimitBytes,
      Approver approver) {
    this(
        provider,
        tools,
        options,
        Map.of(),
        cwd,
        cancelled,
        outputLimitBytes,
        DEFAULT_DEADLINE,
        approver);
  }

  public SubAgentRunner(
      Provider provider,
      ToolRegistry tools,
      AgentOptions options,
      Path cwd,
      BooleanSupplier cancelled,
      int outputLimitBytes,
      Duration deadline,
      Approver approver) {
    this(provider, tools, options, Map.of(), cwd, cancelled, outputLimitBytes, deadline, approver);
  }

  public SubAgentRunner(
      Provider provider,
      ToolRegistry tools,
      AgentOptions options,
      Map<String, SubAgentChoice> subAgents,
      Path cwd,
      BooleanSupplier cancelled,
      int outputLimitBytes,
      Duration deadline,
      Approver approver) {
    this.provider = provider;
    this.tools = tools;
    this.options = options;
    this.subAgents = subAgents == null ? Map.of() : Map.copyOf(subAgents);
    this.cwd = cwd == null ? Path.of("").toAbsolutePath() : cwd.toAbsolutePath().normalize();
    this.cancelled = cancelled == null ? () -> false : cancelled;
    this.outputLimitBytes = outputLimitBytes;
    this.deadline = deadline == null ? DEFAULT_DEADLINE : deadline;
    // 只在没有给出审批器时才用 ALWAYS，那正是没有用户可问的调用方想要的：一个测试，或者一个已经开着自动
    // 批准的运行。每个前端都会传对话自己的审批器。
    this.approver = approver == null ? Approver.ALWAYS : approver;
  }

  /**
   * 这个角色被钉到的模型与档位。
   *
   * <p>没有那样一条就是 {@link #FOLLOW_MAIN}：跟随主对话是默认，而 {@code subAgents} 里一条什么都没点
   * 的条目根本到不了这里——它在被读进来的时候就被丢掉了（见 {@link Config}）。
   */
  private SubAgentChoice pinnedFor(SubAgentRole role) {
    SubAgentChoice choice = subAgents.get(role.wireName());
    return choice == null ? FOLLOW_MAIN : choice;
  }

  /**
   * 被钉住的那个值，空白与缺席一样当作没钉。
   *
   * <p>逐字段决定，因为钉住的是这一栏而已：角色点了模型，它的档位仍然跟随主对话，反之亦然。而它的温度、
   * token 上限、上下文预算和审批者从来不经过这里——一次委派不是另一个提供方。
   */
  private static String orFollow(String pinned, String follow) {
    return pinned == null || pinned.isBlank() ? follow : pinned;
  }

  /**
   * 把一个子代理跑到底，并返回它报告的内容。
   *
   * <p>运行内部的失败从不抛异常：一个没能做成事的子代理会把这个报告出来，因为主代理无论如何都得继续工作，
   * 而异常会结束用户正在看的那个回合。
   *
   * @param role 子代理是做什么的，这决定它的工具
   * @param task 要做什么，用委派它的那个代理的说法
   * @param systemPrompt 基础提示词，null 表示使用内置的
   */
  public SubAgentReport run(SubAgentRole role, String task, String systemPrompt) {
    if (role == null || task == null || task.isBlank()) {
      return SubAgentReport.failed("任务需要一个角色，也需要要做的事");
    }
    // 用会话自己的工作目录，而不是它自己的暂存区。子代理在主代理工作的地方工作，向同一个人请求许可，所以
    // 没有什么要暂存，事后也没有什么要提升。
    //
    // 它所取代的那个暂存目录，只有在子代理以 `Approver.ALWAYS` 运行的时候才说得过去：它让一个无法审批的
    // 写入者远离项目。一旦审批走到用户面前，这个目录就不再买来安全，反而开始付出安全的代价——主代理得手工
    // 提升每个文件，而一次没有在同一回合发生的提升就被丢掉了，一个完成的文件就这样消失，屏幕上什么也没说。
    Path workDir = cwd;
    ToolRegistry subset = registryFor(role);
    ToolContext context = new ToolContext(workDir, approver, outputLimitBytes, this::stopped);
    MemorySession session = new MemorySession("sub-" + role.wireName());
    // 被点名的角色走它自己的模型与档位，其余的跟随主对话。钉住的只有这两栏：温度、token 上限、上下文预算
    // 和审批者仍然来自那段对话，因为一次委派不是另一个提供方，也没有第二份登录。
    SubAgentChoice pinned = pinnedFor(role);
    String model = orFollow(pinned.model(), options.model());
    AgentOptions subOptions =
        new AgentOptions(
            model,
            compose(role, systemPrompt, workDir),
            options.temperature(),
            options.maxTokens(),
            orFollow(pinned.reasoning(), options.reasoning()),
            options.maxContextTokens());
    // 报告只在这条运行用的不是主对话那个模型时才点名它：读报告的就是主模型，点它自己的名等于什么都没说，
    // 而点一个没人选过的名字，才是别人以为出了别的事的那种信息。
    String pinnedElsewhere = model == null || model.equals(options.model()) ? null : model;
    // 一个只计数的监听器：子代理的散文被刻意不转发——没人该读到自己没要过的转录——但它花了多少，是委派
    // 工作的那段对话必须能报告的事实。藏起它读的东西是特性；藏起花销就成了一个静静漏掉一半花费的 token
    // 计数。
    UsageTally tally = new UsageTally();
    AgentLoop loop = new AgentLoop(provider, subset, session, subOptions, context, tally);
    // 主回合的停止信号被转发给这个循环，而不是透传 ToolContext：AgentLoop 会把那个上下文的 cancelled
    // 回调换成自己的标志，所以用外层上下文搭出来的子代理永远听不到中止，会一直干下去而屏幕上什么也不说。
    // 轮询才是真正把两者连起来的东西。
    CancelWatch cancelWatch = new CancelWatch(cancelled, loop);
    // 截止时间从这里开始，而不是在拿到锁之后，所以它也覆盖了等待：一次排在另一个写入者后面的运行，已经在
    // 消耗调用方的耐心了；而一个要等活儿开始才计时的「十分钟」上限，并不是它自称的那个上限。
    Deadline expiry = new Deadline(deadline, loop);
    boolean holdsWriteLock = false;
    if (role.writes()) {
      // 用带超时的 tryLock 而不是普通的那种。等待锁不是 loop.abort() 能触及的状态——工作线程还没设置，
      // 也没有模型调用在飞——所以等待期间的中止或截止时间必须结束这次等待，而不是结束循环。在这之前的实测：
      // 一个被取消的任务仍然跑了，而截止时间没有覆盖排队。
      long waitMillis = Math.max(1, deadline.toMillis() - expiry.elapsedMillis());
      try {
        holdsWriteLock = WRITE_LOCK.tryLock(waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        cancelWatch.stop();
        return SubAgentReport.failed("子代理在等待写入时被取消");
      }
      if (!holdsWriteLock) {
        cancelWatch.stop();
        return SubAgentReport.failed(
            "子代理为另一个写入任务等了 "
                + human(deadline)
                + "，最终放弃，而不是越过自己的截止时间继续跑");
      }
      // 排队期间被取消：锁现在拿到了，但跑的理由已经没了。放在干活之前检查，因为 loop.run() 会清掉它否则
      // 能看到的那个中止标志。
      if (cancelled.getAsBoolean()) {
        WRITE_LOCK.unlock();
        cancelWatch.stop();
        return SubAgentReport.failed("子代理在等待写入时被取消");
      }
    }
    // 在活儿开始之前检查，而不是只靠轮询器：主回合已经被取消的子代理绝不能开始；而 100ms 的轮询与一个快
    // 提供方之间是一场赛跑——运行可能在第一次滴答之前就结束了，那会让取消看起来什么都没做。
    if (cancelled.getAsBoolean()) {
      if (holdsWriteLock) {
        WRITE_LOCK.unlock();
        holdsWriteLock = false;
      }
      cancelWatch.stop();
      return SubAgentReport.failed("子代理还没开始就被停止了");
    }
    try {
      AgentLoop.Result result = loop.run(task);
      // 结束之后再检查一次：短运行期间轮询器可能一次都没滴答过。
      if (cancelled.getAsBoolean()) {
        return SubAgentReport.failed("子代理还没完成就被停止了");
      }
      if (expiry.expired()) {
        return SubAgentReport.failed("子代理跑过了它的 " + human(deadline) + " 截止时间，已被停止");
      }
      if (result.aborted()) {
        return SubAgentReport.failed("子代理还没完成就被停止了");
      }
      // 报告取自会话而不是 Result.finalText：一次写完报告又说了别的话的运行，它的报告在更早的助手回合里，
      // 而它最后说的那句不一定就是它想交出来的东西。
      SubAgentReport report = SubAgentReport.parse(lastProse(session, result.finalText()));
      return report.withUsage(tally.totals()).in(workDir.toString()).withModel(pinnedElsewhere);
    } catch (RuntimeException e) {
      // 工具里的 bug，或者一个抛了异常的提供方：告诉主代理，而不是把它弄死。中断会以 AgentException
      // 的身份到这里——循环把它包起来，并自己清掉标志——所以被中止的子代理读起来就是失败的子代理，而从主
      // 代理的角度看它确实是。
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      // 一次失败了的运行仍然花了它花的那些，所以计数也跟着失败一起走——钉出去的那个模型的失败，和被点名的
      // 那个模型一样值得看清。
      return SubAgentReport.failed("子代理失败：" + message)
          .withUsage(tally.totals())
          .withModel(pinnedElsewhere);
    } finally {
      // 不关闭：循环没有任何要释放的东西，而会话按构造就在内存里——正是这一点让子代理读过的内容不进任何
      // 文件、不进任何列表。
      cancelWatch.stop();
      expiry.cancel();
      if (holdsWriteLock) {
        WRITE_LOCK.unlock();
      }
    }
  }

  /**
   * 把一个截止时间说成人话。
   *
   * <p>{@code Duration.toMinutes()} 会把 90 秒说成「1 分钟」，把 45 秒说成「0 分钟」——而报告是主代理
   * 唯一能读到这条上限的地方，一句「跑过了它的 0 分钟截止时间」会让人以为护栏坏了。
   */
  static String human(java.time.Duration deadline) {
    long seconds = deadline.toSeconds();
    if (seconds < 60) {
      return seconds + " 秒";
    }
    if (seconds % 60 == 0) {
      return (seconds / 60) + " 分钟";
    }
    return (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
  }

  /**
   * 会话里最后一段像样的散文，如果没有就用本次运行的最终文本。
   *
   * <p>从后往前找，因为用过工具的子代理，其会话是以工具结果结尾的：它写下的散文在一个或多个助手回合
   * 之前，而挑出最新的那段非空文本，才是找到报告而不是某个工具调用的参数的办法。
   */
  private static String lastProse(Session session, String fallback) {
    List<Message> messages = session.messages();
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof Message.Assistant assistant && !assistant.text().isBlank()) {
        // 一旦越过最后那几个回合，一个很旧的回答更可能是运行途中的一句话，而不是交接。三步足够应付
        // 「报告后面跟一句简短的 done」。
        if (messages.size() - i <= 3) {
          return assistant.text();
        }
      }
    }
    return fallback == null ? "" : fallback;
  }

  /**
   * 一个角色可以使用的工具，取自完整注册表，所以这里不构造任何新东西。
   *
   * <p>没有 {@code task}，这是让递归不可能、而不只是受限的原因：工具不在注册表里，所以子代理要不到，
   * 也就没有深度计数器可以搞错。
   *
   * <p>除此之外没有任何包装或限制。子代理通过启动它的那段对话请求许可，所以工具可以是真家伙：在这里划一条
   * 边界，就是多出一条得与第一条保持同步的规则，而第一条规则就是用户。
   */
  private ToolRegistry registryFor(SubAgentRole role) {
    ToolRegistry subset = new ToolRegistry();
    for (String name : role.toolNames()) {
      tools.find(name).ifPresent(subset::register);
    }
    return subset;
  }

  /**
   * 子代理运行所用的提示词：先是主提示词，然后是它的角色是什么，最后是它在哪儿工作。
   *
   * <p>基础提示词是保留而不是替换的。子代理和任何其他运行一样读文件、跑搜索，因为工作被委派出去了就丢掉
   * 「先看再改」或项目自己的 {@code CCJ.md} 规则，会让子代理表现得比派它出去的那个代理更差。
   */
  private String compose(SubAgentRole role, String systemPrompt, Path workDir) {
    StringBuilder out = new StringBuilder();
    // 走 Prompts.system，而不是把传入的提示词直接用上：项目自己的 CCJ.md 也是这个代理运行时的一部分，
    // 一个因为工作被委派就丢掉它的子代理，会表现得更差。调用方传的是对话「已配置」的提示词；摆放规则在
    // 这里施加，好让它们只有一个所在之处。
    // 工作目录用的是子代理实际会使用的那个，所以规则文件是从工具运行的地方读的，而不是从主对话碰巧所在的
    // 地方读的。
    String base = Prompts.system(systemPrompt, workDir);
    out.append(base).append("\n\n");
    out.append("You are a sub-agent: another agent delegated this task to you and will read only your"
        + " report, not your work. Nobody else can see what you read or run.\n");
    out.append(role.instruction()).append('\n');
    out.append("\nWork in: ").append(workDir).append('\n');
    if (role.writes()) {
      out.append(
          "Write the files you were asked for there, at final quality. Any change that needs"
              + " permission goes to the same person the main agent asks — say what you are doing and"
              + " why, because you cannot answer questions yourself.\n");
    }
    out.append('\n').append(SubAgentRunner.REPORT_FORMAT);
    return out.toString();
  }

  /**
   * 报告格式，写进提示词里。
   *
   * <p>固定而不自由散文，是因为文件清单是机器读的：正是它让主代理不用打开任何东西就能决定留下什么。
   * 发现是给读最终答案的人看的，所以要求它们带上路径和行号。
   */
  static final String REPORT_FORMAT =
      """
      Finish with a report in exactly this shape:

      STATUS: done | blocked | failed
      SUMMARY: one paragraph on what you found or produced.
      FILES:
        <path>  final|disposable  one line on what it is
      FINDINGS:
      The answer itself, with file paths and line numbers. If you were asked to check something, give
      the input, what was expected and what actually happened.

      List what you wrote, marking a file `final` when it is finished work meant to be used and
      `disposable` when you kept it only to work with (a brute force kept for comparison, a scratch
      generator). The main agent uses this list to tell your real output from your by-products, so
      getting it right is how your work is understood. Omit the FILES section if you wrote nothing.""".strip();

  /** 主回合已被取消，或本次运行耗尽了时间之后为 true。 */
  private boolean stopped() {
    return cancelled.getAsBoolean();
  }

  /**
   * 数一个子代理花了多少，并忽略它说的其他一切。
   *
   * <p>模型调用与父对话所做的是同一批，所以它们的 token 是真金白银。转发用量、丢掉散文，正是让两者都保持
   * 诚实的切法：读过的内容保持私密，账单不。
   */
  private static final class UsageTally implements AgentListener {
    private long input;
    private long output;
    private long cached;
    private int modelTurns;
    private int toolCalls;
    private int toolErrors;

    @Override
    public synchronized void onUsage(int inputTokens, int outputTokens, Integer cachedInputTokens) {
      input += inputTokens;
      output += outputTokens;
      cached += cachedInputTokens == null ? 0 : cachedInputTokens;
    }

    @Override
    public synchronized void onTurnStart(int step) {
      modelTurns++;
    }

    @Override
    public synchronized void onToolStart(Message.ToolCall call) {
      toolCalls++;
    }

    @Override
    public synchronized void onToolEnd(Message.ToolCall call, ToolResult result, long elapsedMillis) {
      if (result != null && result.error()) {
        toolErrors++;
      }
    }

    synchronized UsageTotals totals() {
      return UsageTotals.empty()
          .plus(
              (int) Math.min(Integer.MAX_VALUE, input),
              (int) Math.min(Integer.MAX_VALUE, output),
              (int) cached,
              // 任务文本算一个用户回合，子代理自己的回合算模型回合：它们是被当作「它们本来所做的工作」加进
              // 父账本的，而不是当成单独的一类。
              1,
              modelTurns,
              toolCalls,
              toolErrors,
              0);
    }
  }

  /**
   * 把外部的停止信号转发给子代理自己的循环。
   *
   * <p>需要它，是因为 {@link AgentLoop} 拥有自己的中止标志：它会把 {@link ToolContext} 的 cancelled
   * 回调换成 {@code this::isAborted}，所以拿到外层上下文的子代理会检查<em>它自己</em>的标志，永远不知道
   * 对话已经停了。这个失败是安静的——屏幕上的回合结束了，而子代理还在读文件——而这正是这个功能绝不能做的
   * 那类事。
   *
   * <p>用轮询而不是回调，因为没有钩子可注册：标志藏在一个方法后面，而每 100ms 问一次它的开销，相比一次
   * 模型调用什么都不是。
   */
  private static final class CancelWatch {
    private final Thread watcher;

    CancelWatch(java.util.function.BooleanSupplier outer, AgentLoop loop) {
      this.watcher =
          new Thread(
              () -> {
                while (!Thread.currentThread().isInterrupted()) {
                  if (outer.getAsBoolean()) {
                    loop.abort();
                    return;
                  }
                  try {
                    Thread.sleep(100);
                  } catch (InterruptedException e) {
                    return;
                  }
                }
              },
              "sub-agent-cancel-watch");
      watcher.setDaemon(true);
      watcher.start();
    }

    void stop() {
      watcher.interrupt();
    }
  }

  /**
   * 一个会取消它所盯着的循环的截止时间。
   *
   * <p>用守护线程，因为它绝不能成为 JVM 继续活着的理由：它存在的意义是打断一次运行，而一次已经结束的运行
   * 让它变得没有意义，而不是还欠着什么。
   */
  private static final class Deadline {
    private final Thread timer;
    private final long startedNanos = System.nanoTime();
    private volatile boolean expired;

    Deadline(Duration after, AgentLoop loop) {
      this.timer =
          new Thread(
              () -> {
                try {
                  Thread.sleep(after.toMillis());
                  expired = true;
                  loop.abort();
                } catch (InterruptedException e) {
                  // 因为运行先结束而被取消，这是正常情形。
                }
              },
              "sub-agent-deadline");
      timer.setDaemon(true);
      timer.start();
    }

    boolean expired() {
      return expired;
    }

    /** 这个截止时间已经跑了多久，好让一次等待能拿到剩下的时间。 */
    long elapsedMillis() {
      return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    void cancel() {
      timer.interrupt();
    }
  }
}
