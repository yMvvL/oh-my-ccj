package com.ccj.agent.core;

import java.nio.file.Path;

/**
 * Everything a tool needs to know about the environment it runs in.
 *
 * @param cwd working directory the session was started in; relative tool paths resolve against it
 * @param approver side-effect gate
 * @param outputLimitBytes cap applied to any bulk output a tool hands back to the model
 */
public record ToolContext(Path cwd, Approver approver, int outputLimitBytes) {

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
  }

  public static ToolContext of(Path cwd) {
    return new ToolContext(cwd, Approver.ALWAYS, 0);
  }

  /** Resolves a user- or model-supplied path against {@link #cwd}. */
  public Path resolve(String path) {
    Path p = Path.of(path);
    return (p.isAbsolute() ? p : cwd.resolve(p)).normalize();
  }

  public boolean approve(String title, String detail) {
    return approver.approve(title, detail);
  }

  /** True when {@code path} stays inside the session working directory. */
  public boolean insideCwd(Path path) {
    return path.toAbsolutePath().normalize().startsWith(cwd);
  }
}
