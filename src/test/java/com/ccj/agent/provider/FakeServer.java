package com.ccj.agent.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * 给提供方测试用的脚本化 HTTP 服务器。
 *
 * <p>每一段响应体都作为独立的 chunk 刷出，这样测试就能证明客户端能重新拼好那些在任意字节偏移处被切开、
 * 而不只落在帧边界上的文本。
 */
final class FakeServer implements AutoCloseable {

  /**
   * 一份预置的响应：状态码、content type、按独立 chunk 写出的响应体片段，以及测试需要引发的任何额外响
   * 应首部。
   */
  record Reply(
      int status, String contentType, List<String> chunks, Map<String, String> headers) {

    static Reply sse(String script) {
      return new Reply(200, "text/event-stream", split(script, 19), Map.of());
    }

    static Reply json(int status, String body) {
      return json(status, body, Map.of());
    }

    /** 带额外首部的 JSON 回复，例如 429 上的 {@code Retry-After}。 */
    static Reply json(int status, String body, Map<String, String> headers) {
      return new Reply(status, "application/json", List.of(body), Map.copyOf(headers));
    }

    static Reply status(int status) {
      return json(status, "{}");
    }

    private static List<String> split(String text, int size) {
      List<String> chunks = new ArrayList<>();
      for (int start = 0; start < text.length(); start += size) {
        chunks.add(text.substring(start, Math.min(text.length(), start + size)));
      }
      return List.copyOf(chunks);
    }
  }

  private final HttpServer server;
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private final List<String> paths = new CopyOnWriteArrayList<>();
  private final List<Map<String, List<String>>> headers = new CopyOnWriteArrayList<>();
  private final AtomicInteger requests = new AtomicInteger();
  private volatile IntFunction<Reply> responder;

  private FakeServer(HttpServer server) {
    this.server = server;
  }

  /** 启动一个按顺序回答脚本化回复的服务器；最后一条会被重复使用。 */
  static FakeServer start(Reply... script) {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      FakeServer fake = new FakeServer(server);
      fake.script(script);
      server.createContext("/", fake::handle);
      server.start();
      return fake;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void script(Reply... script) {
    if (script.length == 0) {
      throw new IllegalArgumentException("至少需要一条脚本化回复");
    }
    this.responder = index -> script[Math.min(index, script.length - 1)];
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  int count() {
    return requests.get();
  }

  String body(int index) {
    return bodies.get(index);
  }

  String path(int index) {
    return paths.get(index);
  }

  String header(int index, String name) {
    List<String> values = headers.get(index).get(name);
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    int index = requests.getAndIncrement();
    bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    paths.add(exchange.getRequestURI().getPath());
    Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    exchange.getRequestHeaders().forEach((name, values) -> copy.put(name, List.copyOf(values)));
    headers.add(copy);
    Reply reply = responder.apply(index);
    exchange.getResponseHeaders().set("content-type", reply.contentType());
    reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
    exchange.sendResponseHeaders(reply.status(), 0);
    try (OutputStream out = exchange.getResponseBody()) {
      for (String chunk : reply.chunks()) {
        out.write(chunk.getBytes(StandardCharsets.UTF_8));
        out.flush();
      }
    }
  }
}
