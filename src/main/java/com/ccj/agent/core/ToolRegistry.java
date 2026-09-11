package com.ccj.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Name-addressable set of tools plus the single place where tool failures become results.
 *
 * <p>A model that hallucinates a tool name, passes invalid JSON, or triggers an exception must not
 * kill the session: each of those turns into an error result the model gets to read and react to.
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

  public ToolResult execute(Message.ToolCall call, ToolContext ctx) {
    Tool tool = tools.get(call.name());
    if (tool == null) {
      return ToolResult.error(
          "unknown tool '" + call.name() + "'; available tools: " + String.join(", ", names()));
    }
    String arguments = call.arguments();
    if (arguments.isBlank()) {
      arguments = "{}";
    }
    try {
      return tool.execute(arguments, ctx);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("invalid arguments for " + call.name() + ": " + e.getMessage());
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return ToolResult.error(call.name() + " failed: " + message);
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
