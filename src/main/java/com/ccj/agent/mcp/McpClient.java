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
 * One MCP server, spoken to over its standard input and output.
 *
 * <p>The protocol is JSON-RPC 2.0 with one JSON object per line, which is the whole of what a client
 * needs for tools: `initialize`, `tools/list`, `tools/call`. Servers also speak HTTP with SSE; this
 * client does not, and says so rather than half-supporting it — a local server started by this process
 * is the case that needs no ports, no tokens and no network, and it is what "bring your own tools"
 * means for one person on one machine.
 *
 * <p>Three things make this more than a pipe. A request is answered by <em>id</em>, so a reader thread
 * hands each reply to the caller waiting for it, and a notification (a message with no id) is dropped
 * rather than mistaken for an answer. Every request has a deadline, because a server that has wedged
 * must not wedge the agent with it. And the process's stderr is drained: a server that logs to it is
 * normal, and a full pipe would block the server forever — the failure looks like a hang with no
 * explanation at all.
 */
public final class McpClient implements AutoCloseable {

  /** How long a server gets to answer a request it was just asked. */
  private static final long REQUEST_TIMEOUT_MILLIS = 30_000;

  /** How long a server gets to start talking after `initialize` is sent. */
  private static final long HANDSHAKE_TIMEOUT_MILLIS = 20_000;

  /** The protocol revision this client implements, and the one it falls back to if refused. */
  private static final String PROTOCOL_VERSION = "2025-06-18";

  private static final String FALLBACK_PROTOCOL_VERSION = "2024-11-05";

  /** Kept for the error message when a server writes more than this without being asked. */
  private static final int STDERR_KEPT_LINES = 20;

  /**
   * How long this client waits for an answer, per instance so a test can shorten it.
   *
   * <p>The default is the product's; the parameter exists because "a server that never answers is
   * reported" is a claim that has to be checkable without a test that takes thirty seconds.
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

  /** Starts the server and completes the handshake, or throws with a reason worth reading. */
  public static McpClient start(McpConfig.Server server) {
    return start(server, REQUEST_TIMEOUT_MILLIS);
  }

  /** The same, with the request deadline named by the caller; see {@link #requestTimeoutMillis}. */
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
          "could not start MCP server '" + server.name() + "' (" + server.command() + "): " + e, e);
    }
    McpClient client = new McpClient(server, process, requestTimeoutMillis);
    client.handshake();
    return client;
  }

  /** The server's tools, each as the name the model sees and the schema it is called with. */
  public List<McpTool> tools() {
    JsonNode result = call("tools/list", Json.object());
    JsonNode tools = result.path("tools");
    if (!tools.isArray()) {
      throw new AgentException(
          "MCP server '" + server.name() + "' answered tools/list without a tools array: " + result);
    }
    List<McpTool> discovered = new ArrayList<>();
    for (JsonNode tool : tools) {
      String name = tool.path("name").asText("");
      if (name.isBlank()) {
        throw new AgentException(
            "MCP server '" + server.name() + "' listed a tool with no name: " + tool);
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
   * Calls one tool, and returns what the server said.
   *
   * <p>A tool that reports failure (`isError`) is returned as text for the caller to turn into an error
   * result: an MCP tool refusing is the same kind of event as a bad argument to a built-in, and the
   * model gets to read it and react. The content blocks are joined as text; other block types (an
   * image, an embedded resource) are named rather than dropped silently, because a model that asked
   * for a picture and got nothing would have no way to tell that from an empty answer.
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
        text.append("[").append(type.isBlank() ? "unknown content" : type)
            .append(" content, which this client does not render]");
      }
    }
    if (result.path("isError").asBoolean(false)) {
      throw new McpToolFailure(
          text.isEmpty() ? "the tool reported failure with no message" : text.toString());
    }
    return text.isEmpty() ? "(the tool returned no content)" : text.toString();
  }

  /** A tool the server reported as failed; the message is what the model reads. */
  public static final class McpToolFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    McpToolFailure(String message) {
      super(message);
    }
  }

  @Override
  public void close() {
    closed = true;
    pending.forEach((id, future) -> future.completeExceptionally(new IOException("client closed")));
    pending.clear();
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    try {
      process.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** True while the server process is alive, which is what a caller checks before reusing it. */
  public boolean alive() {
    return process.isAlive();
  }

  // ------------------------------------------------------------------ the protocol

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
      // The one case worth a second try: a server that only speaks the older revision says so. A
      // handshake that fails for any other reason is reported as it is.
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
      throw new McpToolFailure("MCP server '" + server.name() + "' was already closed");
    }
    if (!process.isAlive()) {
      throw new McpToolFailure(
          "MCP server '" + server.name() + "' is not running any more" + whyStopped());
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
          "MCP server '"
              + server.name()
              + "' did not answer "
              + method
              + " within "
              + (timeoutMillis < 1000 ? timeoutMillis + "ms" : timeoutMillis / 1000 + "s"));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pending.remove(id);
      throw new McpToolFailure("interrupted while waiting for " + method);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof McpError error) {
        throw error;
      }
      throw new McpToolFailure(
          "MCP server '" + server.name() + "' failed " + method + ": " + cause.getMessage());
    }
  }

  /** An error the server itself reported, with its code kept for the message. */
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
      // A notification that cannot be sent is not a failure of anything the caller asked for.
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
          "could not write to MCP server '" + server.name() + "': " + e + whyStopped());
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
          // A server that prints something which is not a message is worth reporting once, and not
          // worth killing the session over: the line is kept where the error message can quote it.
          stderrTail.add("stdout: " + line);
          continue;
        }
        JsonNode idNode = message.get("id");
        if (idNode == null || idNode.isNull()) {
          continue; // a notification, not an answer
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
                  server.name() + " answered " + error.path("message").asText("an error")));
        } else {
          waiting.complete(message.path("result"));
        }
      }
    } catch (IOException e) {
      // The pipe closed: every caller waiting is told, rather than waiting out its deadline.
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
      // The process ended; whatever it wrote is already kept.
    }
  }

  /** What the server last said on stderr, for an error message that has to explain a silence. */
  private String whyStopped() {
    synchronized (stderrTail) {
      if (stderrTail.isEmpty()) {
        return "";
      }
      return " — it last said: " + String.join(" | ", List.copyOf(stderrTail));
    }
  }
}
