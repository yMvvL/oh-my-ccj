package com.ccj.agent.mcp;

import com.ccj.agent.core.Approver;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolRegistry;
import com.ccj.agent.core.ToolResult;
import com.ccj.agent.core.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 同一个协议的<em>另一头</em>：把本程序的工具讲给别的代理听。
 *
 * <p>它存在的理由是 {@link McpClient} 的另一半。既然这个代理能调用别人的工具，「别人的代理能调用我的工具」
 * 就是同一句话反过来读——而反过来读的那一句，正是「把自己当成工具交给别人」这件事的全部实现。一个装了
 * claude code 的人写一行配置就能用上这里的 {@code read}、{@code grep}、{@code bash}，而那一行配置指向的
 * 是这个程序。
 *
 * <p>三条规矩与客户端那边一字不差地对称，因为它们不是新决定，是同一个决定：
 *
 * <ul>
 *   <li><strong>审批者是同一个。</strong>每一个会写入或执行的调用，都经过调用方装进来的那个
 *       {@link Approver}——也就是同一个 {@code approvals.json}、同一套规则。这个进程的 stdin 是
 *       JSON-RPC 通道，所以没有终端可以问；没有规则的调用<em>默认被拒</em>，而不是被悄悄允许。放行只有两
 *       条路：调用方启动时带上 {@code --yolo}，或者用户自己往规则文件里写一条。
 *   <li><strong>工具自己说的话就是结果。</strong>工具从不抛给模型看；{@link ToolRegistry#execute}把坏参数、
 *       未知的名字和异常变成一条结果，这里再把它变成 {@code isError} 的内容块。所以一个幻觉出来的工具名
 *       是一条工具错误，而不是一次协议错误——调用方的模型有权读到它。
 *   <li><strong>它不能说「我结束了」。</strong>{@code restart} 不在这张表里：那个工具会结束这个进程，而
 *       一个远端的调用者对此一无所知——它会看到连接断掉，而不是一个结果。
 * </ul>
 *
 * <p>写出去的是 stdout，所以**什么都不能往 stdout 上打**：一句问候、一行进度、一个警告，都会变成一条
 * 解析不了的响应。本类只把应答写进去，别的都走 {@code notice} 那个流（stderr）。
 */
public final class McpServer {

  /** 本服务器实现的协议修订版，与 {@link McpClient} 默认要的那一个一致。 */
  static final String PROTOCOL_VERSION = "2025-06-18";

  private static final int PARSE_ERROR = -32700;
  private static final int INVALID_REQUEST = -32600;
  private static final int METHOD_NOT_FOUND = -32601;
  private static final int INTERNAL_ERROR = -32603;

  private final ToolRegistry tools;
  private final Path cwd;
  private final Approver approver;
  private final int outputLimitBytes;
  private final String version;

  public McpServer(
      ToolRegistry tools, Path cwd, Approver approver, int outputLimitBytes, String version) {
    this.tools = tools;
    this.cwd = cwd;
    this.approver = approver == null ? Approver.NEVER : approver;
    this.outputLimitBytes = outputLimitBytes;
    this.version = version;
  }

  /**
   * 一直读到输入结束，逐行应答；输入关闭就是这个客户端走了，正常退出。
   *
   * @return 退出码：0 表示客户端把 stdin 关了，1 表示读写本身失败了
   */
  public int serve(BufferedReader in, OutputStream out, PrintStream notice) {
    BufferedWriter writer =
        new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    try {
      String line;
      while ((line = in.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        String answer;
        try {
          answer = handle(line);
        } catch (RuntimeException e) {
          // 一个工具炸了不能让这个服务器也跟着炸：它的调用方还在等这条应答，而下次调用与这次无关。
          answer = failure(null, INTERNAL_ERROR, message(e));
        }
        if (answer == null) {
          continue; // 一条通知：它按定义不带应答
        }
        writer.write(answer);
        writer.write('\n');
        // 每一行都冲刷：调用方是一台正在等的机器，不是一个会缓冲的人。被挂起的应答与一条卡住的连接，
        // 从对方那边看是同一件事。
        writer.flush();
      }
      return 0;
    } catch (IOException e) {
      notice.println("error: MCP 通道断了：" + message(e));
      notice.flush();
      return 1;
    }
    // 两个流都不关：它们属于调用方（在这个进程里就是 stdin/stdout），而关闭一个自己在用的流，是那种在
    // 测试里看起来完全正常的错误。
  }

  /**
   * 处理一行请求，返回要写回去的那一行；一条通知（没有 id）返回 null。
   *
   * <p>公开，是为了让协议本身可以被直接测——把一个 JSON-RPC 的边角（坏 JSON、未知方法、通知）走一遍，
   * 不需要一个真的子进程，也不需要等一次握手。
   */
  public String handle(String line) {
    JsonNode request;
    try {
      request = Json.parse(line);
    } catch (IllegalArgumentException e) {
      // 一行读不出来的东西，其 id 也无从谈起：按规范回一个 id 为 null 的错误。
      return failure(null, PARSE_ERROR, "解析请求失败：" + e.getMessage());
    }
    JsonNode id = request.get("id");
    boolean notification = id == null || id.isNull();
    String method = request.path("method").asText("");
    if (method.isBlank()) {
      return notification ? null : failure(id, INVALID_REQUEST, "请求里没有 method");
    }
    if (notification) {
      // 一条通知按定义没有应答——包括握手之后的那一条 `notifications/initialized`。回一条应答会让客户端
      // 把它当成一条没有对应请求的消息。
      return null;
    }
    JsonNode result =
        switch (method) {
          // 协议方法名是别人的词，所以引号里的那个才是它：这几行里没有一个字符串是本程序自己起的名字。
          case "initialize" -> handshake();
          case "ping" -> Json.object();
          case "tools/list" -> listTools();
          case "tools/call" -> callTool(request.path("params"));
          default -> null;
        };
    if (result == null) {
      return failure(id, METHOD_NOT_FOUND, "没有这个方法：" + method + "（本服务器只提供 tools）");
    }
    return success(id, result);
  }

  /**
   * 握手：说清楚我们是谁、会什么，以及我们讲哪个修订版。
   *
   * <p>名字叫 handshake 而不是那个协议方法名，因为这个仓库自己的拼写规矩（见 CONVENTIONS 与它背后那个
   * 会失败的测试）把那个词当成美式拼法——协议的词只出现在 {@link #handle} 的那个 case 标签里，那里它是
   * 别人的名字。
   */
  private ObjectNode handshake() {
    ObjectNode result = Json.object();
    result.put("protocolVersion", PROTOCOL_VERSION);
    ObjectNode capabilities = result.putObject("capabilities");
    capabilities.putObject("tools");
    ObjectNode server = result.putObject("serverInfo");
    server.put("name", "ccj");
    server.put("version", version);
    return result;
  }

  private ObjectNode listTools() {
    ObjectNode result = Json.object();
    ArrayNode listed = result.putArray("tools");
    for (ToolSpec spec : tools.specs()) {
      ObjectNode tool = listed.addObject();
      tool.put("name", spec.name());
      tool.put("description", spec.description());
      tool.set("inputSchema", schemaOf(spec));
    }
    return result;
  }

  /**
   * 工具的 schema，按 JSON Schema 对象发出去。
   *
   * <p>一个解析不出来或者不是对象的东西会变成一张空 schema，而不是让整次 {@code tools/list} 失败：一个工具
   * 的形状说不清楚，与其余工具能不能被看见是两件事，而后者更要紧。
   */
  private static JsonNode schemaOf(ToolSpec spec) {
    try {
      JsonNode parsed = Json.parse(spec.parametersJson());
      if (parsed.isObject()) {
        return parsed;
      }
    } catch (IllegalArgumentException e) {
      // 落到下面那张空 schema 上。
    }
    ObjectNode empty = Json.object();
    empty.put("type", "object");
    empty.putObject("properties");
    return empty;
  }

  /**
   * 调用一个工具：跑它，然后把它说的变成内容块。
   *
   * <p>参数按原样交给工具——核心从不解析它们，而这里也不该开始解析：
   * {@link ToolRegistry#execute}对坏参数、未知名字与异常的处理，就是「工具失败了」这件事在本程序里的唯一
   * 说法，而它给出的那句话正是调用方的模型要读的。
   */
  private ObjectNode callTool(JsonNode params) {
    String name = params.path("name").asText("");
    if (tools.find(name).isEmpty()) {
      return content(
          ToolResult.error("未知工具 '" + name + "'；可用工具：" + String.join(", ", tools.names())));
    }
    JsonNode arguments = params.path("arguments");
    String argumentsJson =
        arguments.isObject() ? Json.write(arguments) : "{}";
    ToolContext context = new ToolContext(cwd, approver, outputLimitBytes);
    ToolResult result =
        tools.execute(new Message.ToolCall("mcp-" + name, name, argumentsJson), context);
    return content(result);
  }

  private static ObjectNode content(ToolResult result) {
    ObjectNode node = Json.object();
    ArrayNode blocks = node.putArray("content");
    blocks.addObject().put("type", "text").put("text", result.content());
    node.put("isError", result.error());
    return node;
  }

  private static String success(JsonNode id, JsonNode result) {
    ObjectNode answer = Json.object();
    answer.put("jsonrpc", "2.0");
    answer.set("id", id);
    answer.set("result", result);
    return Json.write(answer);
  }

  private static String failure(JsonNode id, int code, String message) {
    ObjectNode answer = Json.object();
    answer.put("jsonrpc", "2.0");
    if (id == null) {
      answer.putNull("id");
    } else {
      answer.set("id", id);
    }
    ObjectNode error = answer.putObject("error");
    error.put("code", code);
    error.put("message", message == null ? "失败" : message);
    return Json.write(answer);
  }

  private static String message(Throwable e) {
    String text = e.getMessage();
    return text == null || text.isBlank() ? e.getClass().getSimpleName() : text;
  }
}
