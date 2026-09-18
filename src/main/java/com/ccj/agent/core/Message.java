package com.ccj.agent.core;

import java.util.List;

/**
 * 与提供方无关的对话消息。
 *
 * <p>工具调用参数保持为原始 JSON 文本：核心从不解释它们，提供方负责把它们序列化上线，工具在被调用时自行
 * 解析。这样对话的模型就与任何特定厂商的 API 解耦。
 */
public sealed interface Message {

  record System(String text) implements Message {
    public System {
      text = text == null ? "" : text;
    }
  }

  record User(String text) implements Message {
    public User {
      text = text == null ? "" : text;
    }
  }

  /** 要么是散文，要么是工具调用，要么两者都有。没有工具调用的助手回合会结束循环。 */
  record Assistant(String text, List<ToolCall> toolCalls, List<Thinking> thinking)
      implements Message {
    public Assistant {
      text = text == null ? "" : text;
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
      thinking = thinking == null ? List.of() : List.copyOf(thinking);
    }

    /** 没有思考过程的回合；除了启用了推理档位的 Anthropic，每个回合都是这样。 */
    public Assistant(String text, List<ToolCall> toolCalls) {
      this(text, toolCalls, List.of());
    }

    public static Assistant text(String text) {
      return new Assistant(text, List.of());
    }

    public boolean hasToolCalls() {
      return !toolCalls.isEmpty();
    }
  }

  /**
   * 助手回合里的一个思考块，按 API 发出的原样保留。
   *
   * <p>当请求启用了扩展思考时，Anthropic 的 Messages API 要求把它们原样送回：一个 {@code thinking}
   * 块，连同证明模型确实产生过它的签名；或者一个 {@code redacted_thinking} 块的不透明负载。被涂改过的块
   * 里 {@code text}/{@code signature} 为空，正常的块里 {@code data} 为空，这样别的消息类型都不必知道自己
   * 载的是哪种形状。
   */
  record Thinking(String text, String signature, String data) {
    public Thinking {
      text = text == null ? "" : text;
      signature = signature == null ? "" : signature;
      data = data == null ? "" : data;
    }

    public static Thinking of(String text, String signature) {
      return new Thinking(text, signature, "");
    }

    public static Thinking redacted(String data) {
      return new Thinking("", "", data);
    }

    public boolean redacted() {
      return !data.isEmpty();
    }
  }

  record ToolResult(String toolCallId, String toolName, String content, boolean error)
      implements Message {
    public ToolResult {
      toolCallId = toolCallId == null ? "" : toolCallId;
      toolName = toolName == null ? "" : toolName;
      content = content == null ? "" : content;
    }

    public static ToolResult ok(ToolCall call, String content) {
      return new ToolResult(call.id(), call.name(), content, false);
    }

    public static ToolResult failed(ToolCall call, String content) {
      return new ToolResult(call.id(), call.name(), content, true);
    }

    /** 把一次执行结果包成发回模型的消息。 */
    public static ToolResult of(ToolCall call, com.ccj.agent.core.ToolResult result) {
      return new ToolResult(call.id(), call.name(), result.content(), result.error());
    }
  }

  /**
   * 早先的回合压缩成了什么，由模型写下，好让对话能在没有它们的情况下继续。
   *
   * <p>它是一个独立的消息类型，而不是把 {@link User} 消息装扮成历史，因为模型必须能分辨两者：摘要是对它
   * 看不到的工作由别人做的压缩，而把摘要当作自己的话，正是虚构的细节变成「记得的事实」的路径。出于同样的
   * 原因，它在线上渲染成一个带有明确标记的用户回合。
   *
   * <p>{@code covers} 是被替换掉的消息条数，{@code source} 指明它们仍所在的世代文件，这样当模型需要摘要
   * 没留下的细节时，可以 {@code read} 原件——{@code read} 是只读的、不需要审批，这才让这条路走得通。
   */
  record Summary(String text, int covers, String source) implements Message {
    public Summary {
      text = text == null ? "" : text;
      source = source == null ? "" : source;
    }

    public static Summary of(String text) {
      return new Summary(text, 0, "");
    }
  }

  /** 模型请求的一次工具调用。 */
  record ToolCall(String id, String name, String arguments) {
    public ToolCall {
      id = id == null ? "" : id;
      name = name == null ? "" : name;
      arguments = arguments == null ? "" : arguments;
    }
  }
}
