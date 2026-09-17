package com.ccj.agent.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Runs a sub-agent and returns its report.
 *
 * <p>One real {@link AgentLoop}, one {@link MemorySession}, and a listener that throws its events
 * away. That last part is the feature: the loop is not a simulation of an agent, it <em>is</em> one —
 * same tools, same approval machinery, same repair and budgeting — but nothing it does reaches the
 * page. No id in the sidebar, no deltas on the wire, no file on disk. The user reads the main agent's
 * summary and nothing else.
 *
 * <p>What this class is really for is the four things that keep that invisible loop from becoming a
 * problem:
 *
 * <ul>
 *   <li><b>No {@code task} in the registry.</b> Recursion is refused by construction rather than by a
 *       depth counter — a sub-agent cannot ask for a sub-agent because the tool is not offered, so
 *       there is no limit to get wrong.
 *   <li><b>A deadline.</b> A runaway sub-agent must not hold the main conversation open.
 *   <li><b>Cancellation.</b> Aborting the main turn stops the sub-agents it started, through the same
 *       flag the tools already honour for their own long commands.
 *   <li><b>One writer per file.</b> Two sub-agents cannot hold the same path at once. Parallelism is
 *       for readers; two writers racing on one file loses one of the results silently, which is
 *       precisely the failure nobody can see.
 * </ul>
 */
public final class SubAgentRunner {

  /**
   * How long a sub-agent may run.
   *
   * <p>Minutes rather than seconds: an explorer reading a large tree does real work, and a deadline
   * that cuts off useful runs is worse than one that occasionally waits. It is a backstop against the
   * runaway case, not a schedule.
   */
  public static final Duration DEFAULT_DEADLINE = Duration.ofMinutes(10);

  /**
   * One writer at a time, process-wide.
   *
   * <p>An earlier attempt keyed a lock on the task text, which does not work: two agents saying
   * "write std.cpp" and "produce the reference solution std.cpp" are the same file and different
   * strings. Deciding whether two tasks will touch the same path needs to understand what they mean,
   * and that is not something this class can do. So it does not try — it allows one writing run at a
   * time.
   *
   * <p>The cost is latency on a rare case, and the thing it prevents is losing a result silently: two
   * writers racing on one file leaves one of them gone, and nobody watching can see it happen.
   * Read-only roles are unaffected and still run in parallel, which is where the parallelism was
   * worth having anyway.
   */
  private static final java.util.concurrent.locks.ReentrantLock WRITE_LOCK =
      new java.util.concurrent.locks.ReentrantLock(true);

  /**
   * True when no writing run holds the lock.
   *
   * <p>For tests: the lock is process-wide on purpose — it guards a directory tree, not an object —
   * and a test that measures queueing has to know it is the one queueing rather than waiting behind a
   * run some earlier test left going.
   */
  public static boolean noWriterRunning() {
    return !WRITE_LOCK.isLocked();
  }

  private final Provider provider;
  private final ToolRegistry tools;
  private final AgentOptions options;
  private final Path cwd;
  private final Duration deadline;
  private final BooleanSupplier cancelled;
  private final int outputLimitBytes;
  /**
   * How a sub-agent's request for permission reaches the user.
   *
   * <p>The conversation's own approver, not {@code Approver.ALWAYS}. A sub-agent is invisible, so the
   * tempting design is to let it through without asking — but a hidden agent editing files with no
   * prompt is the thing this feature must not become, and the staging directory that used to make
   * that defensible turned out to cost more than it was worth: the main agent had to promote files by
   * hand, and a promotion that did not happen in the same turn was thrown away.
   *
   * <p>So a sub-agent asks, through the conversation that started it. The prompt arrives in the
   * transcript the user is already watching, attributed to that conversation (the sub-agent runs on
   * its thread), and aborting the turn answers it. The sub-agent is invisible in what it *reads*; it is
   * not invisible in what it *does*.
   */
  private final Approver approver;

  /**
   * @param provider the same provider the main conversation uses; a sub-agent is not a second model
   * @param tools the <em>full</em> registry — each role gets the subset it is allowed, chosen here, so
   *     a caller cannot accidentally hand a verifier the write tools
   * @param options model settings, copied from the main conversation so the sub-agent runs at the
   *     same temperature and reasoning effort rather than at some default nobody chose
   * @param cwd the session working directory: what relative paths resolve against, and what
   *     {@code insideCwd} judges against
   * @param cancelled answered by the main turn, so aborting it aborts this too
   */
  public SubAgentRunner(
      Provider provider,
      ToolRegistry tools,
      AgentOptions options,
      Path cwd,
      BooleanSupplier cancelled,
      int outputLimitBytes,
      Approver approver) {
    this(provider, tools, options, cwd, cancelled, outputLimitBytes, DEFAULT_DEADLINE, approver);
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
    this.provider = provider;
    this.tools = tools;
    this.options = options;
    this.cwd = cwd == null ? Path.of("").toAbsolutePath() : cwd.toAbsolutePath().normalize();
    this.cancelled = cancelled == null ? () -> false : cancelled;
    this.outputLimitBytes = outputLimitBytes;
    this.deadline = deadline == null ? DEFAULT_DEADLINE : deadline;
    // ALWAYS only when no approver was given, which is what a caller with no user to ask wants: a
    // test, or a run with auto-approve already on. Every front end passes the conversation's own.
    this.approver = approver == null ? Approver.ALWAYS : approver;
  }

  /**
   * Runs one sub-agent to completion and returns what it reported.
   *
   * <p>Never throws for a failure inside the run: a sub-agent that could not do its job reports that,
   * because the main agent has to keep working either way and an exception would end the turn the
   * user is watching.
   *
   * @param role what the sub-agent is for, which decides its tools
   * @param task what to do, in the words of the agent that delegated it
   * @param systemPrompt the base prompt, or null for the built-in one
   */
  public SubAgentReport run(SubAgentRole role, String task, String systemPrompt) {
    if (role == null || task == null || task.isBlank()) {
      return SubAgentReport.failed("a task needs a role and something to do");
    }
    // The session's own working directory, not a staging area of its own. A sub-agent works where the
    // main agent works and asks the same person for permission, so there is nothing to stage and
    // nothing to promote afterwards.
    //
    // The staging directory this replaces was defensible only while sub-agents ran with
    // `Approver.ALWAYS`: it kept an unapprovable writer away from the project. Once the approval
    // travels to the user, the directory stops buying safety and starts costing it — the main agent
    // had to promote every file by hand, and a promotion that did not happen in the same turn was
    // discarded, which is how a finished file disappeared with nothing on screen to say so.
    Path workDir = cwd;
    ToolRegistry subset = registryFor(role);
    ToolContext context = new ToolContext(workDir, approver, outputLimitBytes, this::stopped);
    MemorySession session = new MemorySession("sub-" + role.wireName());
    AgentOptions subOptions =
        new AgentOptions(
            options.model(),
            compose(role, systemPrompt, workDir),
            options.temperature(),
            options.maxTokens(),
            options.reasoning(),
            options.maxContextTokens());
    // A counting listener: the sub-agent's prose is deliberately not forwarded — nobody should read a
    // transcript they did not ask for — but what it spends is a fact the conversation that delegated
    // the work has to be able to report. Hiding the reading is the feature; hiding the cost would be a
    // token count that quietly omits half of what was spent.
    UsageTally tally = new UsageTally();
    AgentLoop loop = new AgentLoop(provider, subset, session, subOptions, context, tally);
    // The main turn's stop signal is forwarded to this loop rather than passed through ToolContext:
    // AgentLoop replaces that context's cancelled callback with its own flag, so a sub-agent built
    // from the outer context would never hear about an abort and would keep working with nothing on
    // screen to say so. Polling is what actually connects the two.
    CancelWatch cancelWatch = new CancelWatch(cancelled, loop);
    // The deadline starts here rather than after the lock, so it covers the waiting too: a run queued
    // behind another writer is already consuming the caller's patience, and a "ten minute" limit that
    // begins counting only once the work starts is not the limit it says it is.
    Deadline expiry = new Deadline(deadline, loop);
    boolean holdsWriteLock = false;
    if (role.writes()) {
      // A timed tryLock rather than a plain one. Waiting on a lock is not a state loop.abort() can
      // reach — the worker thread is not set yet and no model call is in flight — so an abort or a
      // deadline during the wait has to end the wait, not the loop. Measured before this: a cancelled
      // task still ran, and the deadline did not cover the queueing.
      long waitMillis = Math.max(1, deadline.toMillis() - expiry.elapsedMillis());
      try {
        holdsWriteLock = WRITE_LOCK.tryLock(waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        cancelWatch.stop();
        return SubAgentReport.failed("the sub-agent was cancelled while waiting to write");
      }
      if (!holdsWriteLock) {
        cancelWatch.stop();
        return SubAgentReport.failed(
            "the sub-agent waited "
                + deadline.toSeconds()
                + "s for another writing task and gave up rather than running past its deadline");
      }
      // Cancelled while queued: the lock is held now, but the reason to run is gone. Checked before
      // the work because loop.run() clears the abort flag it would otherwise have seen.
      if (cancelled.getAsBoolean()) {
        WRITE_LOCK.unlock();
        cancelWatch.stop();
        return SubAgentReport.failed("the sub-agent was cancelled while waiting to write");
      }
    }
    // Checked before the work starts, not only by the poller: a sub-agent whose main turn was already
    // cancelled must not begin, and a 100ms poll is a race against a fast provider — the run can
    // finish before the first tick, which makes cancellation look like it did nothing.
    if (cancelled.getAsBoolean()) {
      if (holdsWriteLock) {
        WRITE_LOCK.unlock();
        holdsWriteLock = false;
      }
      cancelWatch.stop();
      return SubAgentReport.failed("the sub-agent was stopped before it started");
    }
    try {
      AgentLoop.Result result = loop.run(task);
      // And checked again after: the poller may not have ticked during a short run.
      if (cancelled.getAsBoolean()) {
        return SubAgentReport.failed("the sub-agent was stopped before it finished");
      }
      if (expiry.expired()) {
        return SubAgentReport.failed(
            "the sub-agent ran past its " + deadline.toMinutes() + " minute deadline and was stopped");
      }
      if (result.aborted()) {
        return SubAgentReport.failed("the sub-agent was stopped before it finished");
      }
      // The report comes from the session rather than from Result.finalText: a run that wrote its
      // report and then said something else has its report in an earlier assistant turn, and the
      // last thing it said is not necessarily the thing it meant to hand over.
      SubAgentReport report = SubAgentReport.parse(lastProse(session, result.finalText()));
      return report.withUsage(tally.totals()).in(workDir.toString());
    } catch (RuntimeException e) {
      // A bug in a tool, or a provider that threw: the main agent is told rather than killed. An
      // interrupt arrives here as an AgentException — the loop wraps it and clears the flag itself —
      // so an aborted sub-agent reads as a failed one, which is what it is from the main agent's side.
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      // A run that failed still cost what it cost, so the tally travels with the failure too.
      return SubAgentReport.failed("the sub-agent failed: " + message).withUsage(tally.totals());
    } finally {
      // No close: the loop holds nothing to release, and the session is in memory by construction —
      // that is what keeps the sub-agent's reading out of every file and every listing.
      cancelWatch.stop();
      expiry.cancel();
      if (holdsWriteLock) {
        WRITE_LOCK.unlock();
      }
    }
  }

  /**
   * The last substantial prose in the session, or the run's final text when there is none.
   *
   * <p>Walks backwards because a sub-agent that used tools has a session ending in tool results: the
   * prose it wrote is one or more assistant turns earlier, and picking the newest non-empty one is
   * what finds the report rather than a tool call's arguments.
   */
  private static String lastProse(Session session, String fallback) {
    List<Message> messages = session.messages();
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof Message.Assistant assistant && !assistant.text().isBlank()) {
        // Once past the first few trailing turns, a very old answer is more likely to be a mid-run
        // remark than the handover. Three steps is enough for a report followed by a short "done".
        if (messages.size() - i <= 3) {
          return assistant.text();
        }
      }
    }
    return fallback == null ? "" : fallback;
  }

  /**
   * The tools a role may use, taken from the full registry so nothing new is constructed here.
   *
   * <p>No {@code task}, which is what makes recursion impossible rather than merely limited: the tool
   * is not in the registry, so a sub-agent cannot ask for one and there is no depth counter to get
   * wrong.
   *
   * <p>Nothing is wrapped or restricted beyond that. A sub-agent asks for permission through the
   * conversation that started it, so the tools can be the real ones: a boundary here would be a second
   * rule to keep in step with the first, and the first is the user.
   */
  private ToolRegistry registryFor(SubAgentRole role) {
    ToolRegistry subset = new ToolRegistry();
    for (String name : role.toolNames()) {
      tools.find(name).ifPresent(subset::register);
    }
    return subset;
  }

  /**
   * The prompt a sub-agent runs with: the main one, then what its role is, then where it works.
   *
   * <p>The base prompt is kept rather than replaced. A sub-agent reads files and runs searches like
   * any other run, and dropping "inspect before you change" or the project's own {@code CCJ.md} rules
   * because the work was delegated would make a sub-agent behave worse than the agent that sent it.
   */
  private String compose(SubAgentRole role, String systemPrompt, Path workDir) {
    StringBuilder out = new StringBuilder();
    // Through Prompts.system rather than used as given: the project's own CCJ.md and the language
    // rule are part of what this agent runs with, and a sub-agent that lost them because its work was
    // delegated would behave worse than the agent that sent it. The caller passes the conversation's
    // *configured* prompt; the placement rules are applied here so there is one place they live.
    // The working directory is the one the sub-agent will actually use, so a rules file is read from
    // where the tools run rather than from wherever the main conversation happens to be.
    String base = Prompts.system(systemPrompt, null, workDir);
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
   * The report format, written into the prompt.
   *
   * <p>Fixed rather than free prose because the file list is machine-read: it is what lets the main
   * agent decide what to keep without opening anything. The findings are for whoever reads the final
   * answer, so they are asked to carry paths and line numbers.
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

  /** True once the main turn was cancelled, or this run ran out of time. */
  private boolean stopped() {
    return cancelled.getAsBoolean();
  }

  /**
   * Counts what a sub-agent spent, and ignores everything else it said.
   *
   * <p>The model calls are the same ones the parent conversation makes, so their tokens are real
   * money. Forwarding the usage while dropping the prose is the split that keeps both honest: the
   * reading stays private, the bill does not.
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
              // The task text is one user turn, and the sub-agent's own turns are model turns: they
              // are added to the parent's books as the work they were, not as a separate category.
              1,
              modelTurns,
              toolCalls,
              toolErrors,
              0);
    }
  }

  /**
   * Forwards an outside stop signal to a sub-agent's own loop.
   *
   * <p>Needed because {@link AgentLoop} owns its abort flag: it replaces the {@link ToolContext}'s
   * cancelled callback with {@code this::isAborted}, so a sub-agent handed the outer context would
   * check <em>its own</em> flag and never learn that the conversation was stopped. The failure is
   * quiet — the turn ends on screen while the sub-agent keeps reading files — which is exactly the
   * kind of thing this feature must not do.
   *
   * <p>Polling rather than a callback because there is no hook to register on: the flag lives behind
   * a method, and the cost of asking it every 100ms is nothing next to a model call.
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
   * A deadline that cancels the loop it is watching.
   *
   * <p>A daemon thread because it must never be the reason a JVM stays alive: it exists to interrupt
   * a run, and a run that has already ended makes it pointless, not pending.
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
                  // Cancelled because the run finished first, which is the normal case.
                }
              },
              "sub-agent-deadline");
      timer.setDaemon(true);
      timer.start();
    }

    boolean expired() {
      return expired;
    }

    /** How long this deadline has been running, so a wait can be given what is left of it. */
    long elapsedMillis() {
      return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    void cancel() {
      timer.interrupt();
    }
  }
}
