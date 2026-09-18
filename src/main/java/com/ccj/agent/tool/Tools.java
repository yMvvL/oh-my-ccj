package com.ccj.agent.tool;

import com.ccj.agent.core.Checks;
import com.ccj.agent.core.ToolRegistry;

/**
 * 标准工具集。
 *
 * <p>注册顺序就是模型看到工具的顺序，所以它固定在这里，而不是留给装配会话的人：read、write、edit、
 * bash、glob、grep、fetch、restart。
 */
public final class Tools {

  private Tools() {}

  public static ToolRegistry standard() {
    return standard(Checks.none());
  }

  /**
   * 同一套工具，另外接上编辑后检查。
   *
   * <p>只有会改动文件的工具接受它：适用哪个检查，问的是被写入的那条路径，而读操作没有它改过的路径。
   */
  public static ToolRegistry standard(Checks checks) {
    return standard(checks, com.ccj.agent.session.CheckpointStore.none());
  }

  /** 同一套工具，另外提供一个存放文件先前内容的地方，以便退回一个回合。 */
  public static ToolRegistry standard(
      Checks checks, com.ccj.agent.session.CheckpointStore checkpoints) {
    return ToolRegistry.of(
        new ReadTool(),
        new WriteTool(checks, checkpoints),
        new EditTool(checks, checkpoints),
        new BashTool(),
        new GlobTool(),
        new GrepTool(),
        new FetchTool(),
        new RestartTool());
  }
}
