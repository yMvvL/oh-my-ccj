package com.ccj.agent.e2e;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 模型后端的脚本化替身。
 *
 * <p>同时会说两种线格式，因此同一个实例既能服务 OpenAI 兼容的测试，也能服务 Anthropic 的测试。
 * 应答按请求逐条排队，这正是测试能在没有真实模型参与的情况下钉住一段精确的多回合对话（先工具调用、
 * 后最终回答）的原因。
 */
final class MockModelServer implements AutoCloseable {

  record Recorded(String path, String authorization, String apiKey, String body) {}

  private final HttpServer server;
  private final ConcurrentLinkedQueue<String> responses = new ConcurrentLinkedQueue<>();
  private final List<Recorded> recorded = Collections.synchronizedList(new ArrayList<>());

  MockModelServer() throws IOException {
    this(0);
  }

  /** @param port {@code 0} 表示挑一个空闲端口 */
  MockModelServer(int port) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.createContext("/v1/chat/completions", exchange -> respond(exchange));
    server.createContext("/v1/messages", exchange -> respond(exchange));
    server.setExecutor(null);
    server.start();
  }

  String openAiBaseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  String anthropicBaseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** 为下一个到来的请求排队一个 SSE 响应体。 */
  void enqueue(String sseBody) {
    responses.add(sseBody);
  }

  /** 为下一个到来的请求排队一个失败响应。 */
  void enqueueError(int status, String body) {
    enqueue("\u0000" + status + "\u0000" + body);
  }

  int requestCount() {
    return recorded.size();
  }

  List<Recorded> recorded() {
    synchronized (recorded) {
      return List.copyOf(recorded);
    }
  }

  Recorded lastRequest() {
    synchronized (recorded) {
      return recorded.get(recorded.size() - 1);
    }
  }

  /** 所有请求体解析后的便捷视图。 */
  List<com.fasterxml.jackson.databind.JsonNode> requestJson() {
    return recorded().stream().map(r -> Json.parse(r.body())).toList();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void respond(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String auth = exchange.getRequestHeaders().getFirst("Authorization");
    String key = exchange.getRequestHeaders().getFirst("x-api-key");
    recorded.add(new Recorded(exchange.getRequestURI().getPath(), auth, key, body));

    String queued = responses.poll();
    if (queued == null) {
      send(exchange, 599, "text/plain", "没有可用的脚本应答了");
      return;
    }
    if (queued.startsWith("\u0000")) {
      String[] parts = queued.split("\u0000", 3);
      send(exchange, Integer.parseInt(parts[1]), "application/json", parts[2]);
      return;
    }
    send(exchange, 200, "text/event-stream", queued);
  }

  private static void send(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  // ---------------------------------------------------------------- OpenAI 线格式

  static String openAiText(String text) {
    StringBuilder sse = new StringBuilder();
    for (String piece : split(text)) {
      sse.append(chunk("{\"choices\":[{\"index\":0,\"delta\":{\"content\":" + quote(piece) + "}}]}"));
    }
    sse.append(
        chunk(
            "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}"));
    sse.append("data: [DONE]\n\n");
    return sse.toString();
  }

  /** 一次工具调用，其参数 JSON 分三块碎片化到达。 */
  static String openAiToolCall(String id, String name, String argumentsJson) {
    int a = argumentsJson.length() / 3;
    int b = 2 * argumentsJson.length() / 3;
    return openAiToolCalls(
        new String[][] {
          {id, name, argumentsJson.substring(0, a), argumentsJson.substring(a, b), argumentsJson.substring(b)}
        });
  }

  static String openAiToolCalls(String[]... calls) {
    StringBuilder sse = new StringBuilder();
    for (String[] call : calls) {
      String id = call[0];
      String name = call[1];
      sse.append(
          chunk(
              "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":"
                  + quote(id)
                  + ",\"type\":\"function\",\"function\":{\"name\":"
                  + quote(name)
                  + ",\"arguments\":\"\"}}]}}]}"));
      for (int i = 2; i < call.length; i++) {
        sse.append(
            chunk(
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                    + "\"function\":{\"arguments\":"
                    + quote(call[i])
                    + "}}]}}]}"));
      }
    }
    sse.append(
        chunk(
            "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":9}}"));
    sse.append("data: [DONE]\n\n");
    return sse.toString();
  }

  // ---------------------------------------------------------------- Anthropic 线格式

  static String anthropicText(String text) {
    StringBuilder sse = new StringBuilder();
    sse.append(
        event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\","
            + "\"usage\":{\"input_tokens\":13,\"output_tokens\":1}}}"));
    sse.append(
        event("content_block_start",
            "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
    for (String piece : split(text)) {
      sse.append(
          event("content_block_delta",
              "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\","
                  + "\"text\":"
                  + quote(piece)
                  + "}}"));
    }
    sse.append(event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
    sse.append(
        event("message_delta",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":6}}"));
    sse.append(event("message_stop", "{\"type\":\"message_stop\"}"));
    return sse.toString();
  }

  static String anthropicToolCall(String id, String name, String argumentsJson) {
    int a = argumentsJson.length() / 2;
    StringBuilder sse = new StringBuilder();
    sse.append(
        event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\","
            + "\"usage\":{\"input_tokens\":21,\"output_tokens\":1}}}"));
    sse.append(
        event("content_block_start",
            "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":"
                + quote(id)
                + ",\"name\":"
                + quote(name)
                + ",\"input\":{}}}"));
    sse.append(
        event("content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\","
                + "\"partial_json\":"
                + quote(argumentsJson.substring(0, a))
                + "}}"));
    sse.append(
        event("content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\","
                + "\"partial_json\":"
                + quote(argumentsJson.substring(a))
                + "}}"));
    sse.append(event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
    sse.append(
        event("message_delta",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                + "\"usage\":{\"output_tokens\":8}}"));
    sse.append(event("message_stop", "{\"type\":\"message_stop\"}"));
    return sse.toString();
  }

  // ---------------------------------------------------------------- 辅助方法

  private static String chunk(String json) {
    return "data: " + json + "\n\n";
  }

  private static String event(String name, String json) {
    return "event: " + name + "\n" + "data: " + json + "\n\n";
  }

  /** 把文本切成几段，好让测试检验增量累积，而不是一整块。 */
  private static List<String> split(String text) {
    if (text.length() <= 2) {
      return List.of(text);
    }
    int step = Math.max(1, text.length() / 3);
    List<String> pieces = new ArrayList<>();
    for (int i = 0; i < text.length(); i += step) {
      pieces.add(text.substring(i, Math.min(text.length(), i + step)));
    }
    return pieces;
  }

  private static String quote(String raw) {
    return Json.write(Json.mapper().getNodeFactory().textNode(raw));
  }
}
