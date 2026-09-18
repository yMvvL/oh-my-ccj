package com.ccj.agent.core;

/**
 * 模型可以调用的一项能力。
 *
 * <p>实现都是无状态的：关于环境的一切都通过 {@link ToolContext} 传进来。对可预期的失败（文件不存在、
 * 参数不对）返回 {@link ToolResult#error}，可以保住循环，让模型自行纠正；抛异常只留给真正的 bug。
 */
public interface Tool {

  String name();

  String description();

  /** 描述参数的 JSON Schema 对象，以原始 JSON 文本给出。 */
  String parametersJson();

  ToolResult execute(String argumentsJson, ToolContext ctx) throws Exception;

  /**
   * 只读工具为 true：它不改变任何东西，从不请求审批，因此可以安全地与其他工具同时运行。循环据此决定哪些
   * 调用可以重叠——一个回合里要读六个文件的代理不该为此付出六个来回的代价。
   */
  default boolean readOnly() {
    return false;
  }

  default ToolSpec spec() {
    return new ToolSpec(name(), description(), parametersJson());
  }
}
