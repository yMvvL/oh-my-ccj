package com.ccj.agent.mcp;

import com.ccj.agent.core.ApprovalRequest;
import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;

/**
 * 服务器提供的一个工具，作为这个代理可以调用的工具。
 *
 * <p>名字是 `mcp__<server>__<tool>`，三个部分都承重：模型能看见一个能力来自哪里，转录不会假装
 * 一个远端工具是内置的，而一条审批规则可以点名某个服务器（`mcp__fs__*`）或它的某一个工具，
 * 而不用去描述其余的工具。
 *
 * <p>审批不是可选项，也不能被委托出去：服务器自己对「它能做什么」的看法不是本程序的看法，因此
 * 每次调用都和 `bash` 一样经过同一个 `Approver`，以工具自己的名字作为请求的对象。一个想不经
 * 询问就运行的服务器做不到——这是用户安装的程序能被允许存在于这里的唯一方式。
 */
public final class McpTool implements Tool {

  /**
   * 工具从何处拿到它调用所用的客户端。
   *
   * <p>做成接缝而不是字段，是因为启动服务器是延后的：注册表持有的工具，其服务器还没在运行，
   * 而第一次调用才是为那个进程付账的时刻。由 {@link McpTools} 实现，它在本次运行期间为每个
   * 服务器保活一个客户端。
   */
  public interface Clients {
    McpClient forServer(McpConfig.Server server);
  }

  private final McpConfig.Server server;
  private final Clients clients;
  private final String tool;
  private final String description;
  private final String parametersJson;

  McpTool(McpConfig.Server server, String tool, String description, String parametersJson) {
    this.server = server;
    this.clients = null;
    this.tool = tool;
    this.description = description;
    this.parametersJson = parametersJson;
  }

  /**
   * 同一个工具，绑定到一个运行中的客户端。
   *
   * <p>发现与调用是分开的，因为在其服务器的某个工具真正被用到之前，客户端不会被启动：一个从不
   * 调用远端工具的代理，永远不会为一个进程付账，而一台没有配置任何 MCP 服务器的机器，则什么
   * 都不用付。
   */
  private McpTool(McpTool discovered, Clients clients) {
    this.server = discovered.server;
    this.clients = clients;
    this.tool = discovered.tool;
    this.description = discovered.description;
    this.parametersJson = discovered.parametersJson;
  }

  McpTool boundTo(Clients clients) {
    return new McpTool(this, clients);
  }

  @Override
  public String name() {
    return server.toolPrefix() + tool;
  }

  @Override
  public String description() {
    return (description == null || description.isBlank() ? tool : description)
        + " (offered by the MCP server '"
        + server.name()
        + "')";
  }

  @Override
  public String parametersJson() {
    return parametersJson;
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) {
    // 先审批：一个在问题被问出口之前就启动的服务器，已经运行了一个进程，而「什么都没发送」
    // 就不再为真。
    String refusal = ctx.refusal(ApprovalRequest.tool(name(), name() + " " + argumentsJson));
    if (refusal != null) {
      return ToolResult.error(refusal);
    }
    try {
      return ToolResult.ok(clients.forServer(server).callTool(tool, argumentsJson));
    } catch (McpClient.McpToolFailure failure) {
      return ToolResult.error(failure.getMessage());
    }
  }

  /** 这个工具运行前所需的客户端；注册表的启动正是为它准备的。 */
  McpConfig.Server server() {
    return server;
  }
}
