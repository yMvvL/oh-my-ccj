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

  /**
   * True for a tool that only reads: it changes nothing, never asks for approval, and is therefore
   * safe to run at the same time as another one. The loop uses this to decide what may overlap —
   * an agent reading six files in one turn should not pay for six round trips.
   */
  default boolean readOnly() {
    return false;
  }

  default ToolSpec spec() {
    return new ToolSpec(name(), description(), parametersJson());
  }
}
