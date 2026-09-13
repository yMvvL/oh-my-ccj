package com.ccj.agent.tool;

import com.ccj.agent.core.ToolRegistry;

/**
 * The standard tool set.
 *
 * <p>Registration order is the order the model sees the tools in, so it is fixed here rather than
 * left to whoever wires the session: read, write, edit, bash, glob, grep, restart.
 */
public final class Tools {

  private Tools() {}

  public static ToolRegistry standard() {
    return ToolRegistry.of(
        new ReadTool(),
        new WriteTool(),
        new EditTool(),
        new BashTool(),
        new GlobTool(),
        new GrepTool(),
        new RestartTool());
  }
}
