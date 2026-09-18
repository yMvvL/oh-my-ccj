package com.ccj.agent.tool;

import com.ccj.agent.core.Checks;
import com.ccj.agent.core.ToolRegistry;

/**
 * The standard tool set.
 *
 * <p>Registration order is the order the model sees the tools in, so it is fixed here rather than
 * left to whoever wires the session: read, write, edit, bash, glob, grep, fetch, restart.
 */
public final class Tools {

  private Tools() {}

  public static ToolRegistry standard() {
    return standard(Checks.none());
  }

  /**
   * The same set with the post-edit checks wired in.
   *
   * <p>Only the tools that change a file take them: which check applies is a question about the path
   * that was written, and a read has no path it changed.
   */
  public static ToolRegistry standard(Checks checks) {
    return ToolRegistry.of(
        new ReadTool(),
        new WriteTool(checks),
        new EditTool(checks),
        new BashTool(),
        new GlobTool(),
        new GrepTool(),
        new FetchTool(),
        new RestartTool());
  }
}
