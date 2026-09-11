package com.ccj.agent.core;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The agent loop: user input in, tool calls executed, prose out.
 *
 * <p>Each iteration is one model turn. A turn that only talks ends the run; a turn with tool calls
 * has every call executed and fed back, then the model is asked again. The loop is bounded by
 * {@link AgentOptions#maxSteps()} and can be interrupted from another thread via {@link #abort()}.
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
  private final AtomicBoolean aborted = new AtomicBoolean();

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
    this.toolContext = Objects.requireNonNull(toolContext, "toolContext");
    this.listener = listener == null ? AgentListener.NOOP : listener;
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
  }

  public boolean isAborted() {
    return aborted.get();
  }

  public Result run(String userInput) {
    aborted.set(false);
    session.append(new Message.User(userInput));

    String finalText = "";
    int step = 0;

    while (step < options.maxSteps()) {
      if (aborted.get()) {
        return new Result(finalText, step, true);
      }

      listener.onTurnStart(step);
      Message.Assistant assistant = callModel();
      session.append(assistant);
      listener.onAssistant(assistant);

      if (!assistant.text().isBlank()) {
        finalText = assistant.text();
      }

      if (!assistant.hasToolCalls()) {
        return new Result(finalText, step + 1, aborted.get());
      }

      for (Message.ToolCall call : assistant.toolCalls()) {
        if (aborted.get()) {
          return new Result(finalText, step + 1, true);
        }
        executeTool(call);
      }
      step++;
    }

    listener.onNotice(
        "stopped after " + options.maxSteps() + " steps without a final answer (raise maxSteps to continue)");
    return new Result(finalText, step, aborted.get());
  }

  private Message.Assistant callModel() {
    Provider.Request request =
        new Provider.Request(
            options.model(),
            options.systemPrompt(),
            session.messages(),
            tools.specs(),
            options.temperature(),
            options.maxTokens(),
            options.reasoning());
    try {
      return provider.complete(request, this::forward);
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      throw new AgentException(provider.name() + " request failed: " + message, e);
    }
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
    listener.onToolStart(call);
    ToolResult result = tools.execute(call, toolContext);
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    listener.onToolEnd(call, result, elapsedMillis);
    session.append(Message.ToolResult.of(call, result));
  }
}
