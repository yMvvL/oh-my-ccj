package com.ccj.agent.tool;

import com.ccj.agent.core.Checks;
import com.ccj.agent.core.ToolContext;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The check that runs by itself after an edit, and the line it puts into the tool's own result.
 *
 * <p>The point is *when* it runs: inside the `edit` or `write` call that caused it, so the compiler's
 * verdict is part of the same step as the change. A model that broke something finds out before it
 * says it is done, and a weak model — which is where this matters most — converges in one pass
 * instead of three.
 *
 * <p>Three things keep it cheap enough to do that. It runs at most one check per edit: the first
 * whose glob matches. It reports a bounded excerpt, not the whole build log, because the tool result
 * is a prompt the user pays for on every later turn. And a check that passes says so in one line —
 * the model should know the ground is solid, and the user watching the tool card should see that
 * something ran.
 */
final class PostEditCheck {

  /** How much of a failing check's output reaches the model, before the context's own cap. */
  private static final int REPORT_BYTES = 4 * 1024;

  private final Checks checks;

  PostEditCheck(Checks checks) {
    this.checks = checks == null ? Checks.none() : checks;
  }

  /**
   * Runs the check that applies to {@code edited}, if any, and renders what to append to the tool's
   * result — or an empty string when no check applies, which is the common case.
   *
   * <p>A failure to read the config is reported rather than swallowed: a check block with a typo in
   * it would otherwise look exactly like a project with no checks at all.
   */
  String afterEditing(Path edited, ToolContext ctx) {
    Optional<Checks.Check> applicable;
    try {
      applicable = checks.forPath(edited, ctx.cwd());
    } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
      return "\n\n[check] the configured checks could not be read: " + e.getMessage();
    }
    if (applicable.isEmpty()) {
      return "";
    }
    Checks.Check check = applicable.get();
    // Nothing starts once the turn has been aborted: a build is work, and work nobody is waiting
    // for is the thing an abort exists to stop.
    if (ctx.isCancelled()) {
      return "";
    }
    ProcessRunner.Result result;
    try {
      result =
          ProcessRunner.run(
              check.command(),
              ctx.cwd(),
              check.timeoutSeconds(),
              Math.min(REPORT_BYTES, ctx.outputLimitBytes()),
              ctx);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "\n\n[check] " + check.command() + " — interrupted";
    }
    return "\n\n[check] " + check.command() + " — " + verdict(result, check);
  }

  private static String verdict(ProcessRunner.Result result, Checks.Check check) {
    if (!result.finished()) {
      return result.cancelled()
          ? "stopped: the turn was aborted"
          : "timed out after " + check.timeoutSeconds() + "s; the process tree was killed";
    }
    if (result.exitCode() == 0) {
      // "exit 0" rather than "clean": a command's own scope can be narrower than the glob that
      // decided to run it — measured, `mvn -q -o -DskipTests compile` exits 0 for a broken file at
      // the repository root because Maven only compiles `src/main/java` — and a word like "clean"
      // would turn that into a claim the command never made.
      return "exit 0 (" + result.millis() + "ms)";
    }
    String output = result.output().strip();
    return "exit code "
        + result.exitCode()
        + " ("
        + result.millis()
        + "ms) — fix this before going on:\n"
        + (output.isEmpty() ? "(no output)" : output);
  }
}
