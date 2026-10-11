package com.ccj.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 可按名字寻址的工具集合，同时也是把工具失败变成结果的唯一场所。
 *
 * <p>模型幻觉出一个工具名、传了无效 JSON、或触发了异常，都不该把会话搞死：每一种都会变成一条错误结果，
 * 模型能读到它并作出反应。
 */
public final class ToolRegistry {

  private final Map<String, Tool> tools = new LinkedHashMap<>();

  public ToolRegistry register(Tool tool) {
    tools.put(tool.name(), tool);
    return this;
  }

  public Optional<Tool> find(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  public List<String> names() {
    return List.copyOf(tools.keySet());
  }

  public List<ToolSpec> specs() {
    List<ToolSpec> specs = new ArrayList<>(tools.size());
    for (Tool tool : tools.values()) {
      specs.add(tool.spec());
    }
    return specs;
  }

  /**
   * 这一整套工具在每个回合里要花掉多少估算 token：每个工具的名字、描述与手写 schema 加在一起。
   *
   * <p>它值得有一个方法，因为它不是一个装饰性的数字：工具表跟着这个请求每个回合都被重发一遍（提供方的前缀
   * 缓存顶多把它变便宜，不会让它变小），而「加一个 MCP 服务器值多少 token」「把描述写短一点能省多少」是
   * 两个只有先量得出来才答得上来的问题。{@code ccj --tools} 打印的就是它。
   */
  public int schemaTokens() {
    int tokens = 0;
    for (Tool tool : tools.values()) {
      tokens += TokenEstimate.of(tool.name() + tool.description() + tool.parametersJson());
    }
    return tokens;
  }

  public ToolResult execute(Message.ToolCall call, ToolContext ctx) {
    Tool tool = tools.get(call.name());
    if (tool == null) {
      return ToolResult.error(
          "未知工具 '" + call.name() + "'；可用工具：" + String.join(", ", names()));
    }
    String arguments = call.arguments();
    if (arguments.isBlank()) {
      arguments = "{}";
    }
    try {
      return tool.execute(arguments, ctx);
    } catch (IllegalArgumentException e) {
      return ToolResult.error(call.name() + " 的参数无效：" + e.getMessage());
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return ToolResult.error(call.name() + " 执行失败：" + message);
    }
  }

  public static ToolRegistry of(Tool... tools) {
    ToolRegistry registry = new ToolRegistry();
    for (Tool tool : tools) {
      registry.register(tool);
    }
    return registry;
  }
}
