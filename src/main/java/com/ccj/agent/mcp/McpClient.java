package com.ccj.agent.mcp;

import com.ccj.agent.core.AgentException;
import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个 MCP 服务器，通过它的标准输入输出与之交谈。
 *
 * <p>协议是 JSON-RPC 2.0，每行一个 JSON 对象，这对一个客户端来说就是要用工具所需要的一切：
 * `initialize`、`tools/list`、`tools/call`。服务器也会说带 SSE 的 HTTP；本客户端不会，而且会
 * 明说这一点，而不是半支持它——由本进程启动的本地服务器，正是那种不需要端口、不需要 token、
 * 不需要网络的情形，而对于一台机器上的一个人来说，「带上你自己的工具」指的也就是它。
 *
 * <p>有三件事让它不止是一根管道。请求是按 <em>id</em> 应答的，因此一个读取线程把每个回复交给
 * 正在等它的调用方，而通知（没有 id 的消息）会被丢弃，不会被误当成答案。每个请求都有截止时间，
 * 因为一个卡住的服务器绝不能连带把代理也卡住。还有，进程的 stderr 会被抽干：往那里打日志的服务
 * 器很正常，而一根写满的管道会让服务器永远阻塞——那看起来就是一次毫无解释的卡死。
 */
public final class McpClient implements AutoCloseable {

  /** 服务器在被刚问到之后，有多久来回答一个请求。 */
  private static final long REQUEST_TIMEOUT_MILLIS = 30_000;

  /** 发出 `initialize` 之后，服务器有多久开始说话。 */
  private static final long HANDSHAKE_TIMEOUT_MILLIS = 20_000;

  /** 本客户端实现的协议修订版，也是被拒绝时回退到的那个版本。 */
  private static final String PROTOCOL_VERSION = "2025-06-18";

  private static final String FALLBACK_PROTOCOL_VERSION = "2024-11-05";

  /** 服务器在没人问它的情况下写超过这么多内容时，留给错误消息用的保留行数。 */
  private static final int STDERR_KEPT_LINES = 20;

  /**
   * 本客户端等一个答案等多久；按实例设置，好让测试把它缩短。
   *
   * <p>默认值是产品的值；这个参数之所以存在，是因为「一个永远不回答的服务器会被报告出来」是一条
   * 必须能被检验的主张，而不该为此写一个要跑三十秒的测试。
   */
  private final long requestTimeoutMillis;

  private final McpConfig.Server server;
  private final Process process;
  private final BufferedWriter toServer;
  private final AtomicLong nextId = new AtomicLong(1);
  private final Map<Long, CompletableFuture<JsonNode>> pending = new java.util.concurrent.ConcurrentHashMap<>();
  private final List<String> stderrTail = java.util.Collections.synchronizedList(new ArrayList<>());
  private volatile String protocolVersion = PROTOCOL_VERSION;
  private volatile boolean closed;

  private McpClient(McpConfig.Server server, Process process, long requestTimeoutMillis) {
    this.requestTimeoutMillis = requestTimeoutMillis;
    this.server = server;
    this.process = process;
    this.toServer =
        new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    Thread.ofVirtual().name("mcp-" + server.name()).start(this::readReplies);
    Thread.ofVirtual().name("mcp-" + server.name() + "-err").start(this::drainStderr);
  }

  /** 启动服务器并完成握手；否则带着一个值得一读的理由抛出。 */
  public static McpClient start(McpConfig.Server server) {
    return start(server, REQUEST_TIMEOUT_MILLIS);
  }

  /** 同上，但由调用方指定请求的截止时间；参见 {@link #requestTimeoutMillis}。 */
  static McpClient start(McpConfig.Server server, long requestTimeoutMillis) {
    List<String> argv = new ArrayList<>();
    argv.add(server.command());
    argv.addAll(server.args());
    Process process;
    try {
      ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(false);
      builder.environment().putAll(server.env());
      process = builder.start();
    } catch (IOException e) {
      throw new AgentException(
          "无法启动 MCP 服务器 '" + server.name() + "'（" + server.command() + "）：" + e, e);
    }
    McpClient client = new McpClient(server, process, requestTimeoutMillis);
    client.handshake();
    return client;
  }

  /** 服务器提供的工具，每个都带上模型看到的名字和调用它时用的 schema。 */
  public List<McpTool> tools() {
    JsonNode result = call("tools/list", Json.object());
    JsonNode tools = result.path("tools");
    if (!tools.isArray()) {
      throw new AgentException(
          "MCP 服务器 '" + server.name() + "' 对 tools/list 的应答里没有 tools 数组：" + result);
    }
    List<McpTool> discovered = new ArrayList<>();
    for (JsonNode tool : tools) {
      String name = tool.path("name").asText("");
      if (name.isBlank()) {
        throw new AgentException(
            "MCP 服务器 '" + server.name() + "' 列出的工具没有名字：" + tool);
      }
      JsonNode schema = tool.get("inputSchema");
      discovered.add(
          new McpTool(
              server,
              name,
              tool.path("description").asText(""),
              schema == null || schema.isNull() ? "{}" : Json.write(schema)));
    }
    return discovered;
  }

  /**
   * 调用一个工具，并返回服务器说了什么。
   *
   * <p>报告失败（`isError`）的工具会作为文本返回，交给调用方变成一条错误结果：一个 MCP 工具
   * 拒绝，和内置工具收到一个坏参数是同一种事件，模型有权读到它并作出反应。内容块按文本拼接；
   * 其他块类型（图片、内嵌资源）会被点名，而不是被悄悄丢掉，因为一个要了图片却什么都没拿到的
   * 模型，没法把这种情形和空答案区分开。
   */
  public String callTool(String tool, String argumentsJson) {
    ObjectNode params = Json.object();
    params.put("name", tool);
    params.set("arguments", argumentsJson == null || argumentsJson.isBlank()
        ? Json.object()
        : Json.parse(argumentsJson));
    JsonNode result = call("tools/call", params);
    StringBuilder text = new StringBuilder();
    for (JsonNode block : result.path("content")) {
      if (!text.isEmpty()) {
        text.append('\n');
      }
      String type = block.path("type").asText("");
      if ("text".equals(type)) {
        text.append(block.path("text").asText(""));
      } else {
        text.append("[").append(type.isBlank() ? "未知内容" : type + " 内容")
            .append("，本客户端不渲染]");
      }
    }
    if (result.path("isError").asBoolean(false)) {
      throw new McpToolFailure(
          text.isEmpty() ? "工具报告了失败，但没有给出任何消息" : text.toString());
    }
    return text.isEmpty() ? "（工具没有返回任何内容）" : text.toString();
  }

  /** 服务器报告为失败的工具；这条消息就是模型读到的东西。 */
  public static final class McpToolFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    McpToolFailure(String message) {
      super(message);
    }
  }

  @Override
  public void close() {
    closed = true;
    pending.forEach((id, future) -> future.completeExceptionally(new IOException("客户端已关闭")));
    pending.clear();
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    try {
      process.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 服务器进程还活着时为 true；调用方复用它之前检查的就是这个。 */
  public boolean alive() {
    return process.isAlive();
  }

  // ------------------------------------------------------------------ 协议

  private void handshake() {
    ObjectNode params = Json.object();
    params.put("protocolVersion", PROTOCOL_VERSION);
    ObjectNode client = params.putObject("clientInfo");
    client.put("name", "ccj");
    client.put("version", "0.1.0");
    params.putObject("capabilities");
    JsonNode result;
    try {
      result = request("initialize", params, HANDSHAKE_TIMEOUT_MILLIS);
    } catch (McpError refused) {
      // 唯一值得重试一次的情形：只讲旧修订版的服务器会明说。因任何其他原因失败的握手，都按原样
      // 报告。
      if (refused.getMessage() == null || !refused.getMessage().contains("protocol")) {
        throw refused;
      }
      params.put("protocolVersion", FALLBACK_PROTOCOL_VERSION);
      result = request("initialize", params, HANDSHAKE_TIMEOUT_MILLIS);
    }
    String agreed = result.path("protocolVersion").asText("");
    if (!agreed.isBlank()) {
      protocolVersion = agreed;
    }
    notify("notifications/initialized", Json.object());
  }

  private JsonNode call(String method, ObjectNode params) {
    return request(method, params, requestTimeoutMillis);
  }

  private JsonNode request(String method, ObjectNode params, long timeoutMillis) {
    if (closed) {
      throw new McpToolFailure("MCP 服务器 '" + server.name() + "' 已经关闭");
    }
    if (!process.isAlive()) {
      throw new McpToolFailure(
          "MCP 服务器 '" + server.name() + "' 已经不再运行" + whyStopped());
    }
    long id = nextId.getAndIncrement();
    ObjectNode request = Json.object();
    request.put("jsonrpc", "2.0");
    request.put("id", id);
    request.put("method", method);
    request.set("params", params);
    CompletableFuture<JsonNode> answer = new CompletableFuture<>();
    pending.put(id, answer);
    try {
      write(request);
      return answer.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      pending.remove(id);
      throw new McpToolFailure(
          "MCP 服务器 '"
              + server.name()
              + "' 在 "
              + (timeoutMillis < 1000 ? timeoutMillis + "ms" : timeoutMillis / 1000 + "s")
              + " 内没有回应 "
              + method);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pending.remove(id);
      throw new McpToolFailure("等待 " + method + " 时被中断");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof McpError error) {
        throw error;
      }
      throw new McpToolFailure(
          "MCP 服务器 '" + server.name() + "' 执行 " + method + " 失败：" + cause.getMessage());
    }
  }

  /** 服务器自己报告的错误，它的 code 会保留在消息里。 */
  private static final class McpError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int code;

    McpError(int code, String message) {
      super(message);
      this.code = code;
    }
  }

  private void notify(String method, ObjectNode params) {
    ObjectNode notification = Json.object();
    notification.put("jsonrpc", "2.0");
    notification.put("method", method);
    notification.set("params", params);
    try {
      write(notification);
    } catch (McpToolFailure ignored) {
      // 发不出去的通知，不算调用方所求之事的失败。
    }
  }

  private void write(ObjectNode message) {
    try {
      synchronized (toServer) {
        toServer.write(Json.write(message));
        toServer.write("\n");
        toServer.flush();
      }
    } catch (IOException e) {
      throw new McpToolFailure(
          "无法写入 MCP 服务器 '" + server.name() + "'：" + e + whyStopped());
    }
  }

  private void readReplies() {
    try (BufferedReader in =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        JsonNode message;
        try {
          message = Json.parse(line);
        } catch (IllegalArgumentException notJson) {
          // 打印出非消息内容的服务器，值得被报告一次，但不值得为此杀掉整个会话：那一行会被
          // 留下来，好让错误消息引用它。
          stderrTail.add("stdout: " + line);
          continue;
        }
        JsonNode idNode = message.get("id");
        if (idNode == null || idNode.isNull()) {
          continue; // 一条通知，不是答案
        }
        CompletableFuture<JsonNode> waiting = pending.remove(idNode.asLong());
        if (waiting == null) {
          continue;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
          waiting.completeExceptionally(
              new McpError(
                  error.path("code").asInt(0),
                  server.name() + " 应答了 " + error.path("message").asText("一个错误")));
        } else {
          waiting.complete(message.path("result"));
        }
      }
    } catch (IOException e) {
      // 管道关闭了：每个在等的调用方都会被告知，而不是各自等到截止时间。
      pending.forEach((id, future) -> future.completeExceptionally(e));
      pending.clear();
    }
  }

  private void drainStderr() {
    try (BufferedReader in =
        new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        stderrTail.add(line);
        while (stderrTail.size() > STDERR_KEPT_LINES) {
          stderrTail.remove(0);
        }
      }
    } catch (IOException ignored) {
      // 进程结束了；它写下的东西已经被保留下来了。
    }
  }

  /** 服务器最后在 stderr 上说了什么，供一条必须解释沉默的错误消息使用。 */
  private String whyStopped() {
    synchronized (stderrTail) {
      if (stderrTail.isEmpty()) {
        return "";
      }
      return " — 它最后说的是：" + String.join(" | ", List.copyOf(stderrTail));
    }
  }
}
