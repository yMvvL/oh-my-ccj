package com.ccj.agent.core;

/** Tool description as advertised to the model: name, prose, JSON Schema parameters. */
public record ToolSpec(String name, String description, String parametersJson) {

  public ToolSpec {
    name = name == null ? "" : name;
    description = description == null ? "" : description;
    parametersJson = parametersJson == null ? "{}" : parametersJson;
  }
}
