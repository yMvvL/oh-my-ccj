package com.ccj.agent.web;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The HTTP face of {@link AgentHub}: static page, JSON endpoints, and one long-lived SSE stream.
 *
 * <p>Two decisions worth knowing:
 *
 * <ul>
 *   <li>Requests are served by a cached pool. The default executor is a single thread, so one open
 *       SSE stream would freeze every other request.
 *   <li>The token, when set, is accepted from the query string and then remembered in an HttpOnly
 *       cookie. That keeps the browser's own {@code EventSource} and {@code fetch} calls working
 *       without any client-side token plumbing.
 * </ul>
 *
 * <p>It is not a public server: nothing here is designed to face the internet, and the CLI refuses
 * to bind a non-loopback address without a token.
 */
public final class HttpApi implements AutoCloseable {

  private static final String TOKEN_COOKIE = "ccj_token";
  private static final long HEARTBEAT_MILLIS = 15_000;

  private final AgentHub hub;
  private final HttpServer server;
  private final String token;
  private final ExecutorService workers =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "ccj-web-http");
            thread.setDaemon(true);
            return thread;
          });

  private HttpApi(AgentHub hub, InetSocketAddress bind, String token) throws IOException {
    this.hub = hub;
    this.token = token == null || token.isBlank() ? null : token.strip();
    this.server = HttpServer.create(bind, 0);
    server.setExecutor(workers);
    register();
  }

  public static HttpApi start(AgentHub hub, InetSocketAddress bind, String token) throws IOException {
    HttpApi api = new HttpApi(hub, bind, token);
    api.server.start();
    return api;
  }

  /** The port actually bound — useful when the caller asked for 0. */
  public int port() {
    return server.getAddress().getPort();
  }

  /** A URL a human can click, carrying the token when one is required. */
  public String url() {
    String host = server.getAddress().getAddress().isAnyLocalAddress()
        ? "127.0.0.1"
        : server.getAddress().getHostString();
    if (host.contains(":") && !host.startsWith("[")) {
      host = "[" + host + "]";
    }
    String base = "http://" + host + ":" + server.getAddress().getPort() + "/";
    return token == null ? base : base + "?token=" + token;
  }

  @Override
  public void close() {
    server.stop(0);
    workers.shutdownNow();
  }

  private void register() {
    server.createContext("/", exchange -> route(exchange));
  }

  private void route(HttpExchange exchange) throws IOException {
    try {
      if (!authorized(exchange)) {
        unauthorized(exchange);
        return;
      }
      String path = exchange.getRequestURI().getPath();
      switch (path) {
        case "/", "/index.html" -> page(exchange);
        case "/app.js" -> staticResource(exchange, "/web/app.js", "text/javascript; charset=utf-8");
        case "/style.css" -> staticResource(exchange, "/web/style.css", "text/css; charset=utf-8");
        case "/api/status" -> get(exchange, hub.status());
        case "/api/sessions" -> get(exchange, Json.object().set("sessions", hub.sessionsJson()));
        case "/api/events" -> events(exchange);
        case "/api/message" -> message(exchange);
        case "/api/abort" -> abort(exchange);
        case "/api/approval" -> approval(exchange);
        case "/api/auto-approve" -> autoApprove(exchange);
        case "/api/session" -> session(exchange);
        default -> error(exchange, 404, "no such endpoint: " + path);
      }
    } catch (IllegalArgumentException e) {
      error(exchange, 400, e.getMessage());
    } catch (IllegalStateException e) {
      error(exchange, 409, e.getMessage());
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      error(exchange, 500, message);
    }
  }

  // ------------------------------------------------------------------ endpoints

  private void message(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "POST required");
      return;
    }
    String text = Json.parse(readBody(exchange)).path("text").asText("");
    if (text.isBlank()) {
      error(exchange, 400, "field 'text' is required");
      return;
    }
    if (!hub.submit(text)) {
      error(exchange, 409, "a turn is already running");
      return;
    }
    respond(exchange, 202, Json.object().put("accepted", true));
  }

  private void abort(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "POST required");
      return;
    }
    respond(exchange, 200, Json.object().put("aborted", hub.abort()));
  }

  private void approval(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "POST required");
      return;
    }
    JsonNode body = Json.parse(readBody(exchange));
    String id = body.path("id").asText("");
    boolean allow = body.path("allow").asBoolean(false);
    boolean remember = body.path("remember").asBoolean(false);
    if (id.isBlank()) {
      error(exchange, 400, "field 'id' is required");
      return;
    }
    if (!hub.resolveApproval(id, allow, remember)) {
      error(exchange, 404, "no pending approval with id " + id);
      return;
    }
    respond(exchange, 200, Json.object().put("resolved", true).put("allow", allow));
  }

  private void autoApprove(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "POST required");
      return;
    }
    JsonNode body = Json.parse(readBody(exchange));
    if (!body.has("enabled")) {
      error(exchange, 400, "field 'enabled' is required");
      return;
    }
    hub.setAutoApprove(body.path("enabled").asBoolean(false));
    respond(exchange, 200, Json.object().put("autoApprove", hub.autoApprove()));
  }

  private void session(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "POST required");
      return;
    }
    JsonNode body = Json.parse(readBody(exchange));
    String action = body.path("action").asText("");
    switch (action) {
      case "new" -> hub.newSession();
      case "resume" -> hub.resumeSession(body.path("id").asText(""));
      default -> {
        error(exchange, 400, "action must be 'new' or 'resume'");
        return;
      }
    }
    respond(exchange, 200, hub.status());
  }

  // ------------------------------------------------------------------ SSE

  private void events(HttpExchange exchange) throws IOException {
    Headers headers = exchange.getResponseHeaders();
    headers.add("Content-Type", "text/event-stream; charset=utf-8");
    headers.add("Cache-Control", "no-cache, no-transform");
    headers.add("Connection", "keep-alive");
    exchange.sendResponseHeaders(200, 0);

    OutputStream out = exchange.getResponseBody();
    SseClient client = new SseClient(out, lastEventId(exchange));
    List<AgentHub.Event> missed = hub.subscribe(client);
    try {
      for (AgentHub.Event event : missed) {
        if (event.id() > client.lastSent()) {
          client.send(event);
        }
      }
      // A browser that just connected has no state yet, and the replay buffer may be empty (fresh
      // server, idle session). The contract promises a status event on connect; this is it.
      hub.publishStatus();
      while (!client.closed()) {
        Thread.sleep(HEARTBEAT_MILLIS);
        client.heartbeat();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      hub.unsubscribe(client);
      exchange.close();
    }
  }

  private static long lastEventId(HttpExchange exchange) {
    String raw = exchange.getRequestHeaders().getFirst("Last-Event-ID");
    if (raw == null || raw.isBlank()) {
      return 0;
    }
    try {
      return Long.parseLong(raw.strip());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** A subscriber that writes straight to the socket under its own lock. */
  private static final class SseClient implements Consumer<AgentHub.Event> {

    private final OutputStream out;
    private final Object lock = new Object();
    private volatile boolean closed;
    private long lastSent;

    SseClient(OutputStream out, long lastSent) {
      this.out = out;
      this.lastSent = lastSent;
    }

    @Override
    public void accept(AgentHub.Event event) {
      send(event);
    }

    void send(AgentHub.Event event) {
      write(
          "id: "
              + event.id()
              + "\ndata: "
              + Json.write(event.payload())
              + "\n\n");
      if (!closed) {
        lastSent = Math.max(lastSent, event.id());
      }
    }

    void heartbeat() {
      write(": ping\n\n");
    }

    private void write(String frame) {
      synchronized (lock) {
        if (closed) {
          return;
        }
        try {
          out.write(frame.getBytes(StandardCharsets.UTF_8));
          out.flush();
        } catch (IOException e) {
          closed = true;
        }
      }
    }

    boolean closed() {
      return closed;
    }

    long lastSent() {
      return lastSent;
    }
  }

  // ------------------------------------------------------------------ plumbing

  private void page(HttpExchange exchange) throws IOException {
    if (token != null && token.equals(queryParam(exchange, "token"))) {
      exchange
          .getResponseHeaders()
          .add("Set-Cookie", TOKEN_COOKIE + "=" + token + "; HttpOnly; SameSite=Strict; Path=/");
    }
    staticResource(exchange, "/web/index.html", "text/html; charset=utf-8");
  }

  private void staticResource(HttpExchange exchange, String resource, String contentType)
      throws IOException {
    try (InputStream in = HttpApi.class.getResourceAsStream(resource)) {
      if (in == null) {
        error(exchange, 404, "missing resource " + resource + " (was the jar built before it was added?)");
        return;
      }
      byte[] bytes = in.readAllBytes();
      exchange.getResponseHeaders().add("Content-Type", contentType);
      exchange.getResponseHeaders().add("Cache-Control", "no-store");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  private void get(HttpExchange exchange, ObjectNode body) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "GET required");
      return;
    }
    respond(exchange, 200, body);
  }

  private void respond(HttpExchange exchange, int status, ObjectNode body) throws IOException {
    byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private void error(HttpExchange exchange, int status, String message) throws IOException {
    respond(exchange, status, Json.object().put("error", message == null ? "failed" : message));
  }

  private String readBody(HttpExchange exchange) throws IOException {
    return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
  }

  private boolean authorized(HttpExchange exchange) {
    if (token == null) {
      return true;
    }
    if (token.equals(queryParam(exchange, "token"))) {
      return true;
    }
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    if (header != null && header.startsWith("Bearer ") && token.equals(header.substring(7).strip())) {
      return true;
    }
    List<String> cookies = exchange.getRequestHeaders().get("Cookie");
    if (cookies != null) {
      for (String cookie : cookies) {
        for (String pair : cookie.split(";")) {
          int eq = pair.indexOf('=');
          if (eq > 0
              && TOKEN_COOKIE.equals(pair.substring(0, eq).strip())
              && token.equals(pair.substring(eq + 1).strip())) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private void unauthorized(HttpExchange exchange) throws IOException {
    respond(
        exchange,
        401,
        Json.object()
            .put(
                "error",
                "this server requires a token; open the URL printed by ccj (it carries ?token=…),"
                    + " or send Authorization: Bearer <token>"));
  }

  private static String queryParam(HttpExchange exchange, String name) {
    String query = exchange.getRequestURI().getRawQuery();
    if (query == null || query.isBlank()) {
      return null;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && name.equals(pair.substring(0, eq))) {
        return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }
}
