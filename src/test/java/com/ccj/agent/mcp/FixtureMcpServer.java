package com.ccj.agent.mcp;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * A tiny MCP server, used as the other end of the client's tests.
 *
 * <p>It is a real server started as a real process — not a stub of the client — because the things
 * worth testing about this client are the ones a stub cannot show: a handshake that arrives in two
 * pieces, an answer that belongs to an earlier request, a server that writes to stderr, one that dies
 * mid-call. The suite spawns it with the same mechanism the product uses, over the same pipe.
 *
 * <p>Its behaviour is chosen by the first argument, so one class can be several servers, and every
 * behaviour it can take is one the client has a rule about.
 */
public final class FixtureMcpServer {

  private FixtureMcpServer() {}

  public static void main(String[] args) throws IOException {
    String mode = args.length == 0 ? "normal" : args[0];
    if ("die".equals(mode)) {
      // A server that starts, says nothing, and exits: the handshake has no answer coming.
      return;
    }
    BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    BufferedWriter out =
        new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
    if ("noisy".equals(mode)) {
      // A server that logs to stderr, which a client must drain or the pipe fills and the server
      // stops dead: the failure looks like a hang with no explanation.
      for (int i = 0; i < 5000; i++) {
        System.err.println("fixture " + mode + " log line " + i
            + " padding padding padding padding padding padding padding padding");
      }
      System.err.flush();
    }
    String line;
    while ((line = in.readLine()) != null) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode message = Json.parse(line);
      if ("notifications/initialized".equals(message.path("method").asText(""))) {
        continue;
      }
      JsonNode id = message.get("id");
      if (id == null || id.isNull()) {
        continue;
      }
      ObjectNode reply = replyFor(message, mode);
      if (reply == null) {
        continue;
      }
      out.write(Json.write(reply));
      out.write("\n");
      out.flush();
    }
  }

  /** The whole reply, result or error, because a fixture has to be able to refuse something. */
  private static ObjectNode replyFor(JsonNode request, String mode) {
    String method = request.path("method").asText("");
    ObjectNode result = Json.object();
    result.put("jsonrpc", "2.0");
    result.set("id", request.get("id"));
    ObjectNode body = resultFor(request, mode);
    if (body == null) {
      return null;
    }
    if (body.has("error")) {
      result.set("error", body.get("error"));
    } else {
      result.set("result", body);
    }
    return result;
  }

  private static ObjectNode resultFor(JsonNode request, String mode) {
    String method = request.path("method").asText("");
    switch (method) {
      case "initialize":
        if ("old-protocol".equals(mode)
            && !"2024-11-05".equals(request.path("params").path("protocolVersion").asText(""))) {
          // The one refusal a client is allowed to retry: a server that only speaks the older
          // revision says so, and the client asks again in the language this one understands.
          ObjectNode refusal = Json.object();
          ObjectNode error = refusal.putObject("error");
          error.put("code", -32602);
          error.put("message", "unsupported protocol version");
          return refusal;
        }
        ObjectNode result = Json.object();
        result.put("protocolVersion", "2025-06-18");
        result.set("capabilities", Json.object());
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "fixture");
        info.put("version", mode);
        return result;
      case "tools/list":
        ObjectNode listing = Json.object();
        var tools = listing.putArray("tools");
        ObjectNode echo = tools.addObject();
        echo.put("name", "echo");
        echo.put("description", "Echo back the text it is given.");
        ObjectNode schema = echo.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("text").put("type", "string");
        schema.putArray("required").add("text");
        ObjectNode fail = tools.addObject();
        fail.put("name", "always_fails");
        fail.put("description", "Report failure with a message.");
        fail.putObject("inputSchema").put("type", "object");
        return listing;
      case "tools/call":
        String tool = request.path("params").path("name").asText("");
        ObjectNode call = Json.object();
        var content = call.putArray("content");
        if ("always_fails".equals(tool)) {
          content.addObject().put("type", "text").put("text", "the fixture was asked to fail");
          call.put("isError", true);
          return call;
        }
        if ("slow".equals(tool) || "slow".equals(mode)) {
          sleep(2000);
        }
        if ("blocks_on_slow".equals(tool)) {
          sleep(90_000);
        }
        content
            .addObject()
            .put("type", "text")
            .put("text", "echo: " + request.path("params").path("arguments").path("text").asText(""));
        return call;
      default:
        ObjectNode unknown = Json.object();
        ObjectNode error = unknown.putObject("error");
        error.put("code", -32601);
        error.put("message", "no such method: " + method);
        return unknown;
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
