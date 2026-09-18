package com.ccj.agent.mcp;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools the configured MCP servers offer, registered into the tool set this run uses.
 *
 * <p>Two phases, because starting every configured server at startup would make the agent pay for
 * capabilities it never reaches for — a machine with four servers would spawn four processes to answer
 * "what does this file say". So {@link #discover} starts each server once, asks it what it has, and
 * shuts it down again; {@link #bind} starts the one a tool actually belongs to, the first time one of
 * its tools is called, and keeps it for the run.
 *
 * <p>A server that will not start is reported on stderr and skipped: one broken entry in a
 * configuration file must not stop the agent from starting, but it must not be silent either — a
 * capability that is not there and a capability nobody mentioned look identical from inside a
 * conversation.
 */
public final class McpTools implements McpTool.Clients {

  private final McpConfig config;
  private final Path file;
  private final List<McpTool> discovered = new ArrayList<>();
  private final Map<String, McpClient> running = new LinkedHashMap<>();
  private final List<String> complaints = new ArrayList<>();

  private McpTools(McpConfig config, Path file) {
    this.config = config;
    this.file = file;
  }

  /** Reads the configuration and asks every server what it offers. */
  public static McpTools discover(Path file) {
    McpTools tools = new McpTools(McpConfig.from(file), file);
    // A server is a child process, and a child outlives its parent: without this hook, quitting ccj
    // leaves one process per server running with no parent to report to.
    Runtime.getRuntime().addShutdownHook(new Thread(tools::close, "mcp-shutdown"));
    for (McpConfig.Server server : tools.config.servers()) {
      McpClient client = null;
      try {
        client = McpClient.start(server);
        tools.discovered.addAll(client.tools());
      } catch (RuntimeException e) {
        tools.complaints.add("MCP server '" + server.name() + "': " + e.getMessage());
      } finally {
        if (client != null) {
          client.close();
        }
      }
    }
    return tools;
  }

  /** The tools to register, each bound to a client that is started when it is first called. */
  public List<Tool> tools() {
    List<Tool> bound = new ArrayList<>(discovered.size());
    for (McpTool tool : discovered) {
      bound.add(tool.boundTo(this));
    }
    return bound;
  }

  /** The running client for a server, started on first use and kept for the run. */
  @Override
  public synchronized McpClient forServer(McpConfig.Server server) {
    McpClient existing = running.get(server.name());
    if (existing != null && existing.alive()) {
      return existing;
    }
    McpClient started = McpClient.start(server);
    running.put(server.name(), started);
    return started;
  }

  /** What went wrong while discovering, for the caller that can print it. */
  public List<String> complaints() {
    return List.copyOf(complaints);
  }

  /** The configuration file this came from, for a message that has to say where to look. */
  public Path file() {
    return file;
  }

  /** True when nothing was configured, which is the case that must cost nothing. */
  public boolean isEmpty() {
    return config.isEmpty();
  }

  /**
   * Registers every discovered tool, and returns the registry for chaining.
   *
   * <p>A name that is already taken is not registered and is reported instead: a server must not be
   * able to shadow `read` or `bash` by naming one of its tools the same, and a tool that silently
   * replaced a built-in is the worst version of that — the model would keep calling `read` and get
   * somebody else's program.
   */
  public ToolRegistry registerInto(ToolRegistry registry) {
    tools()
        .forEach(
            tool -> {
              if (registry.find(tool.name()).isPresent()) {
                complaints.add(
                    "MCP tool '"
                        + tool.name()
                        + "' would shadow a tool this agent already has, so it was not registered");
                return;
              }
              registry.register(tool);
            });
    return registry;
  }

  /** Stops every server this run started. */
  public void close() {
    running.values().forEach(McpClient::close);
    running.clear();
  }

  public static String describe(Path home) {
    return "MCP servers are declared in " + home.resolve("mcp.json");
  }

}
