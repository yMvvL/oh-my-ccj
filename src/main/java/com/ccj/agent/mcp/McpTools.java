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
 * 已配置的 MCP 服务器所提供的工具，注册进本次运行所用的工具集。
 *
 * <p>分两个阶段，因为启动时就把每个配置的服务器都拉起来，会让代理为它从不伸手去用的能力买单
 * ——一台配置了四个服务器的机器，会为了回答「这个文件说了什么」而派生四个进程。所以
 * {@link #discover} 把每个服务器启动一次、问它有什么、再关掉；{@link #bind} 则在某个工具第一
 * 次被调用时，启动它真正所属的那个服务器，并为整次运行把它留着。
 *
 * <p>起不来的服务器会报告到 stderr 并被跳过：配置文件里一个坏掉的条目，不能让代理起不来，但也
 * 不能一声不吭——不存在的能力和没人提过的能力，从一次对话里看是一模一样的。
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

  /** 读取配置，并询问每个服务器它提供什么。 */
  public static McpTools discover(Path file) {
    McpTools tools = new McpTools(McpConfig.from(file), file);
    // 服务器是子进程，而子进程会比父进程活得久：没有这个钩子，退出 ccj 会给每个服务器留下
    // 一个没人可汇报的进程。
    Runtime.getRuntime().addShutdownHook(new Thread(tools::close, "mcp-shutdown"));
    for (McpConfig.Server server : tools.config.servers()) {
      McpClient client = null;
      try {
        client = McpClient.start(server);
        tools.discovered.addAll(client.tools());
      } catch (RuntimeException e) {
        tools.complaints.add("MCP 服务器 '" + server.name() + "'：" + e.getMessage());
      } finally {
        if (client != null) {
          client.close();
        }
      }
    }
    return tools;
  }

  /** 要注册的工具，每个都绑定到一个在首次被调用时才启动的客户端。 */
  public List<Tool> tools() {
    List<Tool> bound = new ArrayList<>(discovered.size());
    for (McpTool tool : discovered) {
      bound.add(tool.boundTo(this));
    }
    return bound;
  }

  /** 某个服务器正在运行的客户端；首次使用时启动，并为整次运行保留。 */
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

  /** 发现过程中出了什么问题，供能把它打印出来的调用方使用。 */
  public List<String> complaints() {
    return List.copyOf(complaints);
  }

  /** 本次配置来自哪个文件，供一条必须指明去哪里看的消息使用。 */
  public Path file() {
    return file;
  }

  /** 什么都没配置时为 true；这是必须零成本的那一种情形。 */
  public boolean isEmpty() {
    return config.isEmpty();
  }

  /**
   * 注册每一个已发现的工具，并返回注册表以便链式调用。
   *
   * <p>已经被占用的名字不会被注册，而是被报告出来：服务器不能靠把自己的某个工具取成同名来
   * 遮蔽 `read` 或 `bash`，而一个悄悄替换掉内置工具的工具，是这件事最糟糕的版本——模型会继续
   * 调用 `read`，拿到的却是别人的程序。
   */
  public ToolRegistry registerInto(ToolRegistry registry) {
    tools()
        .forEach(
            tool -> {
              if (registry.find(tool.name()).isPresent()) {
                complaints.add(
                    "MCP 工具 '"
                        + tool.name()
                        + "' 会遮蔽本代理已有的某个工具，因此没有注册它");
                return;
              }
              registry.register(tool);
            });
    return registry;
  }

  /** 停掉本次运行启动的每一个服务器。 */
  public void close() {
    running.values().forEach(McpClient::close);
    running.clear();
  }

  public static String describe(Path home) {
    return "MCP 服务器在 " + home.resolve("mcp.json") + " 中声明";
  }

}
