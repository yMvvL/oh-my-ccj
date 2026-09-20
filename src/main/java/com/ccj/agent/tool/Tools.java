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

  /**
   * 同一套工具，另外提供一个存放文件先前内容的地方，以便退回一个回合。
   *
   * <p>不指明 shell，也就是按平台默认，见 {@link #standard(Checks, com.ccj.agent.session.CheckpointStore,
   * String)}。
   */
  public static ToolRegistry standard(
      Checks checks, com.ccj.agent.session.CheckpointStore checkpoints) {
    return standard(checks, checkpoints, null);
  }

  /**
   * 同一套工具，另外指明用哪个程序跑命令。
   *
   * <p>它是一个参数，而不是让工具自己去读配置：`tool/` 不认识配置文件，也不知道那个值从哪个 flag 或
   * 环境变量来——那是 {@code Cli} 的事。而 null 一路传到底、由 {@link ProcessRunner#resolve} 按平台给出
   * 默认值，所以「没配置时跑的是什么」只有一处说法，两处会跑命令的地方（`bash` 工具与编辑后检查）也
   * 不可能各拿到一个。
   *
   * @param shell 跑命令的程序；null 或空白表示按平台默认（POSIX 上是 {@code /bin/bash}，Windows 上是
   *     {@code COMSPEC}）
   */
  public static ToolRegistry standard(
      Checks checks, com.ccj.agent.session.CheckpointStore checkpoints, String shell) {
    return ToolRegistry.of(
        new ReadTool(),
        new WriteTool(checks, checkpoints, shell),
        new EditTool(checks, checkpoints, shell),
        new BashTool(shell),
        new GlobTool(),
        new GrepTool(),
        new FetchTool(),
        new RestartTool());
  }
}
