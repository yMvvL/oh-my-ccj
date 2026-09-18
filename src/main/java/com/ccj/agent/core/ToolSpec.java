package com.ccj.agent.core;

/** 向模型展示的工具描述：名称、说明文字、JSON Schema 参数。 */
public record ToolSpec(String name, String description, String parametersJson) {

  public ToolSpec {
    name = name == null ? "" : name;
    description = description == null ? "" : description;
    parametersJson = parametersJson == null ? "{}" : parametersJson;
  }
}
