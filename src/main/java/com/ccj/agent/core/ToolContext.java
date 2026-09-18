package com.ccj.agent.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Everything a tool needs to know about the environment it runs in.
 *
 * @param cwd working directory the session was started in; relative tool paths resolve against it
 * @param approver side-effect gate
 * @param outputLimitBytes cap applied to any bulk output a tool hands back to the model
 * @param cancelled answered by the run that owns this context: true once someone asked it to stop.
 *     A tool that can take minutes — a shell command is the obvious one — checks it instead of
 *     running to completion, so "stop" means stop rather than "wait for the timeout"
 * @param endsRun called by a tool whose result makes the rest of the run pointless. Installing a
 *     jar to restart on is the case that needs it: this process is about to die, and asking the
 *     model for a follow-up turn it will never get to send helps nobody
 */
public record ToolContext(
    Path cwd,
    Approver approver,
    int outputLimitBytes,
    BooleanSupplier cancelled,
    Runnable endsRun) {

  public ToolContext {
    if (cwd == null) {
      cwd = Path.of("").toAbsolutePath();
    }
    cwd = cwd.toAbsolutePath().normalize();
    if (approver == null) {
      approver = Approver.ALWAYS;
    }
    if (outputLimitBytes <= 0) {
      outputLimitBytes = 32 * 1024;
    }
    if (cancelled == null) {
      cancelled = () -> false;
    }
    if (endsRun == null) {
      endsRun = () -> {};
    }
  }

  /** A context nothing can cancel, which is what a plain tool call wants. */
  public ToolContext(Path cwd, Approver approver, int outputLimitBytes) {
    this(cwd, approver, outputLimitBytes, () -> false, null);
  }

  /** A context whose run is never ended by a tool, for the callers that have no run to end. */
  public ToolContext(
      Path cwd, Approver approver, int outputLimitBytes, BooleanSupplier cancelled) {
    this(cwd, approver, outputLimitBytes, cancelled, null);
  }

  public static ToolContext of(Path cwd) {
    return new ToolContext(cwd, Approver.ALWAYS, 0);
  }

  /** True once the run that owns this context was asked to stop. */
  public boolean isCancelled() {
    return cancelled.getAsBoolean();
  }

  /**
   * Tells the run that owns this context that the turn is over. A tool that installs a jar to
   * restart on is the only caller today: the process is about to exit, so the model must not be
   * asked for another step.
   */
  public void endRun() {
    endsRun.run();
  }

  /** Resolves a user- or model-supplied path against {@link #cwd}. */
  public Path resolve(String path) {
    Path p = Path.of(path);
    return (p.isAbsolute() ? p : cwd.resolve(p)).normalize();
  }

  /**
   * Asks for permission, and returns the refusal to hand back — or null when it was allowed.
   *
   * <p>An answer rather than the request's text, because an approval has four outcomes now: yes once,
   * yes for this session, yes always, and two kinds of no that read differently to whoever is looking
   * at the transcript later.
   */
  public String refusal(ApprovalRequest request) {
    ApprovalAnswer answer = approver.approve(request);
    return answer.allowed() ? null : answer.refusal();
  }

  /**
   * True when {@code path} stays inside the session working directory.
   *
   * <p>A lexical answer to "does this name look inside?" is not the same question. A symlink that
   * sits in the working directory and points out of it escapes while its name still says otherwise —
   * and the approval prompt is exactly what that check exists to inform. So both sides are resolved
   * first: as much of the path as exists, with the rest kept as written, which is what lets a file
   * that is about to be created still be judged. Comparing real paths on both sides also keeps a
   * working directory reached through a symlink (macOS {@code /tmp}) from making every path look
   * foreign.
   */
  public boolean insideCwd(Path path) {
    Path target = resolveExisting(path.toAbsolutePath().normalize());
    Path base = resolveExisting(cwd);
    return target.startsWith(base);
  }

  /**
   * The path with its longest existing prefix resolved to a real path and the rest appended as
   * written. A path that resolves nowhere at all is returned unchanged, so the answer degrades to
   * comparing what the caller asked for.
   */
  private static Path resolveExisting(Path path) {
    Path prefix = path;
    Path tail = null;
    while (prefix != null) {
      Path real = realPath(prefix);
      if (real != null) {
        return tail == null ? real : real.resolve(tail);
      }
      Path name = prefix.getFileName();
      if (name == null) {
        return path;
      }
      tail = tail == null ? name : name.resolve(tail);
      prefix = prefix.getParent();
    }
    return path;
  }

  /** The resolved path, or null when it does not exist or cannot be resolved. */
  private static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return null;
    }
  }
}
