package com.ccj.agent.tool;

import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Puts a newly built jar in place of the one this process is running from, so the rebuild the agent
 * just did takes effect.
 *
 * <p>Two facts make this an operation rather than a shell line. Writing over a jar a JVM is
 * executing kills that JVM — partway through, with a {@code NoClassDefFoundError} that says nothing
 * about the cause — so the new jar is <em>renamed</em> into place instead: same filesystem, so the
 * rename is atomic, and the running process keeps the inode it already opened. And a process cannot
 * replace its own code: once the swap is done this one is running bytes that are no longer on disk,
 * so it exits with a status only the launcher acts on, and the launcher starts the new jar. That is
 * why the ending is an explicit "restart me" rather than a crash or a success.
 *
 * <p>It is gated on approval like every other tool that changes something, and the approval detail
 * names both files: which jar is about to become which is the whole decision.
 *
 * <p>The run ends with this call: {@link ToolContext#endRun()} stops the loop where it stands. The
 * install is the last thing worth doing in this process, and letting the model ask for one more
 * step would only produce a failure to talk about.
 */
public final class RestartTool implements Tool {

  /** The jar the launcher runs, which is the only jar this project ships. */
  private static final String INSTALLED_JAR = "ccj.jar";

  /**
   * The jar a scratch build writes ({@code -Djar.name=ccj-next}), waiting to be swapped in. It has
   * to be a different filename from the installed one: the build that produces it truncates whatever
   * path it writes, and truncating the installed jar is what kills the process running from it.
   */
  private static final String STAGED_JAR = "ccj-next.jar";

  /**
   * The exit status that means "start me again". 75 is EX_TEMPFAIL: nothing here raises it, and it
   * has to survive the shell the launcher runs, which is why it is not one of the CLI's own codes
   * (0 success, 1 runtime failure, 2 usage error).
   */
  public static final int RESTART_EXIT = 75;

  /**
   * Whether this process is meant to end in a restart. A static flag rather than a return value
   * because the decision has to travel out of a tool call, through the loop and the run, into
   * {@code Cli}: threading one bit through every one of those signatures would be the tail wagging
   * the dog, and this is one process asking itself one question.
   */
  private static final AtomicBoolean RESTART_REQUESTED = new AtomicBoolean();

  /** True when a restart has been requested; the CLI checks it once its run is over. */
  public static boolean restartRequested() {
    return RESTART_REQUESTED.get();
  }

  /**
   * Forgets a request made by an earlier run in this JVM.
   *
   * <p>The flag answers a question about one run, but the process can outlive it — the test suite
   * and any embedder call {@code Cli} several times — and a request that survived into the next run
   * would tell it to exit 75 for a restart that run never asked for. So a run starts by saying "not
   * this one", which is what keeps the answer scoped to the run that made it.
   */
  public static void clearRequest() {
    RESTART_REQUESTED.set(false);
  }

  @Override
  public String name() {
    return "restart";
  }

  @Override
  public String description() {
    return "Install a freshly built jar in place of the one running and restart on it. Call this "
        + "after building this project, so the new code takes effect. This process exits and the "
        + "launcher starts the new jar; the session is on disk, so it can be resumed. Call it last.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "built": {
              "type": "string",
              "description": "The jar a scratch build produced: build with -Djar.name=ccj-next, which writes target/ccj-next.jar."
            }
          },
          "required": ["built"],
          "additionalProperties": false
        }""";
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) throws IOException {
    JsonNode args = ToolSupport.args(argumentsJson);
    Path built = ctx.resolve(ToolSupport.requireText(args, "built"));
    Path installed = ctx.cwd().resolve("target").resolve(INSTALLED_JAR);
    Path staged = ctx.cwd().resolve("target").resolve(STAGED_JAR);
    String self = ToolSupport.display(ctx, built);

    if (!Files.isRegularFile(built)) {
      return ToolResult.error(
          "no jar at "
              + self
              + " — build this project first with mvn -q -DskipTests -Djar.name=ccj-next package,"
              + " which writes "
              + STAGED_JAR);
    }
    if (built.toAbsolutePath().normalize().equals(installed.toAbsolutePath().normalize())) {
      return ToolResult.error(
          self
              + " is already the installed jar; pass the staged one ("
              + STAGED_JAR
              + ") so the swap has somewhere to come from");
    }
    if (!Files.isRegularFile(installed)) {
      return ToolResult.error(
          "no "
              + INSTALLED_JAR
              + " at "
              + ToolSupport.display(ctx, installed)
              + " — restart installs the jar the launcher runs, and there is none here");
    }
    if (!built.toAbsolutePath().normalize().equals(staged.toAbsolutePath().normalize())) {
      return ToolResult.error(
          self
              + " is not the jar a build stages ("
              + ToolSupport.display(ctx, staged)
              + "); installing some other jar would be a different thing entirely");
    }

    String detail =
        ToolSupport.display(ctx, built)
            + "  ->  "
            + ToolSupport.display(ctx, installed)
            + "\n(renamed over the running jar: the JVM keeps the file it already opened, which is"
            + " why this does not kill this process)\n"
            + "then this process exits "
            + RESTART_EXIT
            + ", and the launcher starts the new jar";
    if (!ctx.approve("restart", detail)) {
      return ToolResult.error("rejected by user");
    }

    try {
      Files.move(
          built, installed, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      // Two mounts between the two paths; the plain move is still a rename, not a write over it.
      Files.move(built, installed, StandardCopyOption.REPLACE_EXISTING);
    }
    RESTART_REQUESTED.set(true);
    // Nothing after this can be sent: the process is about to be replaced. Ending the run here is
    // what keeps the transcript from ending in a provider error nobody asked for.
    ctx.endRun();
    return ToolResult.ok("installed " + ToolSupport.display(ctx, installed) + "; restarting on it");
  }
}
