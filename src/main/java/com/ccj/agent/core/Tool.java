package com.ccj.agent.core;

/**
 * A capability the model can invoke.
 *
 * <p>Implementations are stateless: everything they need about the environment arrives through
 * {@link ToolContext}. Returning {@link ToolResult#error} for expected failures (missing file, bad
 * arguments) keeps the loop alive so the model can correct itself; throwing is reserved for bugs.
 */
public interface Tool {

  String name();

  String description();

  /** JSON Schema object describing the arguments, as raw JSON text. */
  String parametersJson();

  ToolResult execute(String argumentsJson, ToolContext ctx) throws Exception;

  default ToolSpec spec() {
    return new ToolSpec(name(), description(), parametersJson());
  }
}
