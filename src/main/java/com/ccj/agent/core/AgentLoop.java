package com.ccj.agent.core;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The agent loop: user input in, tool calls executed, prose out.
 *
 * <p>Each iteration is one model turn. A turn that only talks ends the run; a turn with tool calls
 * has every call executed and fed back, then the model is asked again. There is no step ceiling — a
 * turn ends when the model answers or when someone calls {@link #abort()} — so a long piece of work
 * is never cut off at an arbitrary step, and a runaway one is stopped by the person watching it.
 */
public final class AgentLoop {

  /**
   * @param finalText last non-blank assistant prose of the run
   * @param steps model turns consumed
   * @param aborted true when {@link #abort()} cut the run short
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
   * Set by a tool whose result makes the rest of the run pointless. A separate flag from
   * {@code aborted} because it is not a failure and not a request to stop early: it means the work
   * is done, so the turn ends where it stands and the calls the model asked for alongside it are
   * recorded as not run. The tool's own result is in the session; the run's final text stays
   * whatever prose the model last produced.
   */
  private final AtomicBoolean ended = new AtomicBoolean();
  /**
   * The thread running this loop, so {@link #abort()} can interrupt a blocking model call.
   *
   * <p>Set for the duration of {@link #run}: the loop is single-threaded and a run is not reentrant,
   * so one reference is enough, and it is cleared on the way out so an abort after the run does
   * nothing at all.
   */
  private final java.util.concurrent.atomic.AtomicReference<Thread> worker =
      new java.util.concurrent.atomic.AtomicReference<>();
  /**
   * True while the loop is blocked on the model.
   *
   * <p>The interrupt {@link #abort()} sends must reach <em>that</em> wait and nothing else. Tools are
   * how they are stopped already — {@code bash} polls {@link ToolContext#isCancelled()} and kills its
   * own process tree — and interrupting them instead turns a clean "aborted by the user" into an
   * {@code InterruptedException} the tool has to apologise for. So the interrupt is aimed: it is sent
   * only when this is set, which is exactly while the loop has nothing else it could be doing.
   */
  private final AtomicBoolean awaitingModel = new AtomicBoolean();
  /** True once this run has said out loud that it had to repair the history. */
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
    // The run's own abort flag is what the tools see: a long command stops when the user asks,
    // instead of holding the turn until its timeout expires.
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

  /** Requests that the run stop as soon as the current step allows it. */
  public void abort() {
    aborted.set(true);
    // Three things can be holding the loop, and the flag alone only reaches the first:
    //
    //  - a step boundary or a tool about to start: the flag is checked there;
    //  - a tool that polls `isAborted()` (bash does, every 150ms): it kills its process tree;
    //  - the model call itself, which is a blocking read of a stream that can run for minutes. Nothing
    //    polls it, so a turn "between steps" waiting for the reply used to ignore the request until
    //    the whole answer had arrived — which is what made stop feel broken.
    //
    // Interrupting the thread is what reaches that last case. A blocking read is an interruptible
    // operation, so the provider throws and the loop unwinds through its normal abort path.
    //
    // The interrupt is aimed at the model wait only: a tool is stopped by the flag it polls, and
    // interrupting it instead would turn "aborted by the user" into an exception it has to report.
    // The interrupt status is cleared by whoever receives it, so the loop is left clean.
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
      // An abort interrupts this thread, and the interrupt stays set until someone clears it. Left
      // set, it would break the *next* thing this thread does — the web server hands these threads
      // back to a pool — so a run always leaves it clean.
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
        // A model call cut short by an abort is not a failed turn: the user asked for it to stop, and
        // reporting "request failed" for that would be telling them their own action broke something.
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
          // The assistant turn is already in the session, and both wire formats demand a result for
          // every call it made — so the calls that never ran are recorded as such. Leaving them out
          // is what makes an interrupted conversation impossible to continue.
          recordUnrun(calls.subList(index, calls.size()));
          return new Result(finalText, step + 1, true);
        }
        // A run of read-only calls is the one thing that may overlap: they change nothing, so the
        // only thing a serial run buys is latency. Anything that writes or executes is executed on
        // this thread, in the order the model asked for it.
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
          // A tool ended the run (it installed a jar to restart on). The work asked for is done, and
          // whatever the model asked for alongside it will never be sent — so those calls are
          // recorded as not run, exactly as an abort records them, because a conversation with a
          // call and no result is one no provider will accept again.
          recordUnrun(calls.subList(index, calls.size()));
          return new Result(finalText, step + 1, false);
        }
      }
      step++;
    }
  }

  private Message.Assistant callModel() {
    // A history that an interruption left invalid can never be sent again, so it is repaired before
    // it is projected: the file keeps its bytes, the request gets the results that went missing.
    SessionRepair.Result repaired = SessionRepair.apply(session.messages());
    if (repaired.repaired() && !repairNoticed) {
      // Once per run: the repair is derived from the session on every step, and repeating the same
      // notice per step would be noise.
      repairNoticed = true;
      listener.onNotice(repaired.notice());
    }
    // The session keeps every message; this decides what one request carries. A conversation that
    // outgrew the model's context is not an error to report, it is a projection to make.
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
      // Marked for the duration of the call: this is the window in which abort's interrupt has
      // somewhere useful to land, and the only one.
      awaitingModel.set(true);
      if (aborted.get()) {
        // The abort landed between the loop's check and this call: without this the interrupt it
        // sent had no one to receive it, and the request would go out for a run that is already
        // over. Failing here unwinds through the same path as an interrupted call.
        throw new InterruptedException("aborted before the request was sent");
      }
      return provider.complete(request, this::forward);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AgentException(provider.name() + " request interrupted", e);
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      throw new AgentException(provider.name() + " request failed: " + message, e);
    } finally {
      awaitingModel.set(false);
    }
  }

  /**
   * True when a failure is the interrupt {@link #abort()} sent, rather than something that went
   * wrong.
   *
   * <p>The interrupt travels out as a provider exception, so the type alone cannot tell them apart:
   * the causes have to be walked. Checking this only under {@code aborted.get()} keeps a genuine
   * interrupt from elsewhere — a shutting-down executor, say — reported as the failure it is.
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
      // The provider announces a call before its arguments are assembled; front ends render the
      // card when the call is actually about to run (see executeTool), so this wire-level signal is
      // informational only.
      case Provider.Event.ToolCallStart ignored -> {}
      case Provider.Event.Usage u -> {
        listener.onUsage(u.inputTokens(), u.outputTokens(), u.cachedInputTokens());
        listener.onNotice(usageNotice(u));
      }
      case Provider.Event.Retry r -> listener.onNotice(
          "retrying " + provider.name() + " (attempt " + r.attempt() + "): " + r.reason());
    }
  }

  /** One readable line: prompt size, how much of it the provider had cached, reply size. */
  private static String usageNotice(Provider.Event.Usage usage) {
    StringBuilder text = new StringBuilder("tokens: ").append(usage.inputTokens()).append(" in");
    if (usage.cachedInputTokens() != null && usage.inputTokens() > 0) {
      int percent = Math.round(usage.cachedInputTokens() * 100f / usage.inputTokens());
      text.append(" (").append(percent).append("% cached)");
    }
    return text.append(" / ").append(usage.outputTokens()).append(" out").toString();
  }

  private void executeTool(Message.ToolCall call) {
    long started = System.nanoTime();
    notifyToolStart(call);
    ToolResult result = tools.execute(call, toolContext);
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    notifyToolEnd(call, result, elapsedMillis);
    session.append(Message.ToolResult.of(call, result));
  }

  /** True when the model asked for a tool that only reads, and may therefore run beside another. */
  private boolean readOnly(Message.ToolCall call) {
    return tools.find(call.name()).map(Tool::readOnly).orElse(false);
  }

  /**
   * Records the calls an interruption stopped before they ran. Their result says so, because the
   * alternative is a session whose next message can never be sent — and because "not run" is the
   * truth the model needs to read. The listener sees them too, so a front end closes the card it
   * opened instead of leaving it spinning on a call that will never finish. The timing is -1: there
   * is no number to report, and inventing a zero would read as a measurement.
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
   * Runs a run of read-only calls at the same time, one virtual thread each, and appends the results
   * in the order the model asked for them: a transcript that depended on which file finished first
   * would be a transcript nobody can reproduce. The calls are shown as they start and end, so a
   * front end draws the same cards it draws for a serial run.
   */
  private void executeReadOnly(List<Message.ToolCall> batch) {
    if (batch.size() == 1) {
      executeTool(batch.get(0));
      return;
    }
    // One slot per call, written by its own thread and published by the latch: ordering the results
    // by anything else — an id, an arrival — would depend on the model's ids being unique and on
    // which file finished first, and the transcript has to read like the request did.
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
      // Interrupted while waiting: whatever finished is still recorded below, and the loop records
      // the rest as "not run" when it sees the abort. Dropping the finished ones here would leave a
      // call without a result for no reason at all.
      Thread.currentThread().interrupt();
    }
    for (Outcome outcome : outcomes) {
      if (outcome != null) {
        session.append(Message.ToolResult.of(outcome.call(), outcome.result()));
      }
    }
    // A call whose thread produced nothing — an interrupt, a thread that died — still owes a result,
    // and the session only stays sendable if every call has one.
    for (int i = 0; i < outcomes.length; i++) {
      if (outcomes[i] == null) {
        recordUnrun(List.of(batch.get(i)));
      }
    }
  }

  /** One finished read-only call, on its way back to the run that asked for it. */
  private record Outcome(Message.ToolCall call, ToolResult result, long elapsedMillis) {}

  /**
   * The two callbacks a parallel run can fire at once. They are serialised here rather than in every
   * front end: a renderer is a piece of state, and handing it two threads is a bug waiting for a
   * slow disk to happen.
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
