package com.ccj.agent.mcp;

import com.ccj.agent.core.ApprovalRequest;
import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;

/**
 * One tool a server offers, as a tool this agent can call.
 *
 * <p>The name is `mcp__<server>__<tool>`, and the three parts are all load-bearing: the model can see
 * where a capability came from, the transcript does not pretend a remote tool is a built-in, and an
 * approval rule can name one server (`mcp__fs__*`) or one tool of it without describing the others.
 *
 * <p>Approval is not optional and not delegated: a server's own idea of what it may do is not this
 * program's, so every call goes through the same `Approver` as `bash`, with the tool's own name as the
 * subject of the request. A server that wanted to run unasked could not, which is the only way a
 * user-installed program can be allowed to exist here.
 */
public final class McpTool implements Tool {

  /**
   * Where a tool gets the client it calls through.
   *
   * <p>A seam rather than a field because starting a server is deferred: the registry holds tools
   * whose server is not running yet, and the first call is what pays for the process. Implemented by
   * {@link McpTools}, which keeps one client per server alive for the run.
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
   * The same tool, bound to a running client.
   *
   * <p>Discovery and calling are separated because a client is not started until a tool of its server
   * is actually used: an agent that never calls a remote tool never pays for a process, and a machine
   * with no MCP servers configured pays nothing at all.
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
    // Approval first: a server that is started before the question is asked has already run a
    // process, and "nothing was sent" would stop being true.
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

  /** The client this tool needs before it can run, which is what a registry start is for. */
  McpConfig.Server server() {
    return server;
  }
}
