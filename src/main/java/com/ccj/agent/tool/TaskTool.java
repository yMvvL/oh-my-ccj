package com.ccj.agent.tool;

import com.ccj.agent.core.SubAgentReport;
import com.ccj.agent.core.SubAgentRole;
import com.ccj.agent.core.SubAgentRunner;
import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;

/**
 * Delegates work to a sub-agent and returns its report.
 *
 * <p>The reason this exists is context, not speed. An agent that reads thirty files to find the three
 * that matter has spent thirty files' worth of tokens in the conversation, and every later request
 * re-sends them — which is why long sessions end up needing {@code /compact} at all. A sub-agent reads
 * the thirty in its own conversation, which is thrown away, and reports the three. The main
 * conversation grows by a conclusion instead of by a search.
 *
 * <p>What the model gets back is a report, never the sub-agent's transcript: the whole value is that
 * the reading does not come back with it. And there is no {@code task} tool in a sub-agent's own
 * registry, so a sub-agent cannot delegate further — recursion is refused by construction rather than
 * by a depth counter.
 *
 * <p>A writing role writes where the main agent writes, and asks the same person for permission. The
 * sub-agent is invisible in what it reads; it is not invisible in what it does.
 */
public final class TaskTool implements Tool {

  /** Who a tool call is made on behalf of. */
  @FunctionalInterface
  public interface Runner {
    SubAgentReport run(SubAgentRole role, String task);
  }

  /**
   * Where a delegated run's cost goes.
   *
   * <p>A sub-agent's tokens are the parent conversation's tokens — the same model, the same account,
   * the same bill — so they are added to the session that delegated the work. Reporting the report
   * but not the cost would make the usage panel understate what was spent, and a number that is
   * quietly wrong is worse than no number.
   */
  @FunctionalInterface
  public interface UsageSink {
    void add(com.ccj.agent.core.UsageTotals spent);
  }

  private final Runner runner;
  private final UsageSink usage;
  private final boolean writesAllowed;

  /**
   * @param runner executes a sub-agent; supplied by the caller because the provider, the settings and
   *     the approver it needs belong to the conversation, not to a stateless tool
   * @param usage where the run's cost is added, or null to count nothing
   * @param writesAllowed false to restrict every role to reading, which is how a caller that does not
   *     want a delegated run to write at all says so
   */
  public TaskTool(Runner runner, UsageSink usage, boolean writesAllowed) {
    this.runner = runner;
    this.usage = usage;
    this.writesAllowed = writesAllowed;
  }

  @Override
  public String name() {
    return "task";
  }

  /**
   * Not read-only, and deliberately so: a delegated task may write, and the loop uses this flag to
   * decide what may overlap. Two sub-agents running at once is a question about files, and this tool
   * cannot promise it has none to touch.
   */
  @Override
  public boolean readOnly() {
    return false;
  }

  @Override
  public String description() {
    return "Delegate a self-contained piece of work to a sub-agent, which reads and works in its own"
        + " conversation and reports back only a summary. Use it to keep your own context small: send"
        + " it to find something across many files, to check work, or to produce a file, and you get"
        + " the conclusion instead of the reading. It cannot see this conversation, so the task must"
        + " be self-contained. `explore` and `verify` read only; `build` writes, and its changes ask"
        + " for the same permission yours do — tell it exactly what to produce, since it cannot ask"
        + " you a follow-up question.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "role": {
              "type": "string",
              "enum": ["explore", "verify", "build"],
              "description": "explore = find out; verify = check existing work and report, without changing it; build = produce a file."
            },
            "task": {
              "type": "string",
              "description": "What to do, self-contained. Say what you want back and how it should be shaped, since the sub-agent cannot ask a follow-up question."
            }
          },
          "required": ["role", "task"],
          "additionalProperties": false
        }""";
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) throws Exception {
    var args = ToolSupport.args(argumentsJson);
    String roleName = ToolSupport.requireText(args, "role");
    String task = ToolSupport.requireText(args, "task");
    SubAgentRole role =
        SubAgentRole.of(roleName)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "unknown role '" + roleName + "'; expected one of " + SubAgentRole.names()));
    if (role.writes() && !writesAllowed) {
      return ToolResult.error(
          "the '" + role.wireName() + "' role is not available in this conversation; use 'explore'"
              + " or 'verify', which read and report without changing anything");
    }
    // A run that failed still carries what it spent — SubAgentRunner attaches the tally on the failure
    // path too — so the cost is reported whatever the status, and the caller's own listener has
    // already counted the tokens this turn spent by itself.
    SubAgentReport report = runner.run(role, task);
    if (usage != null && report.usage() != null) {
      usage.add(report.usage());
    }
    String rendered = report.render(ctx.cwd().toString());
    if ("failed".equals(report.status())) {
      // A failed run is reported as an error result rather than a success, so the model sees it as
      // something that did not work and can decide what to do, instead of reading it as an answer.
      return ToolResult.error(rendered);
    }
    return ToolResult.ok(rendered);
  }
}
