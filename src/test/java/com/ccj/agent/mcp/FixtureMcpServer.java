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
 * 一个很小的 MCP 服务器，用作客户端测试的另一端。
 *
 * <p>它是一个以真实进程启动的真实服务器——不是客户端的桩——因为关于这个客户端值得测的东西，
 * 恰恰是桩展示不出来的：分两段到达的握手、属于更早请求的答案、往 stderr 写东西的服务器、
 * 调用途中死掉的服务器。测试套件用产品所用的同一套机制、同一根管道把它派生出来。
 *
 * <p>它的行为由第一个参数选择，因此一个类可以扮演好几个服务器，而它所能采取的每种行为，都是
 * 客户端有应对规则的那一种。
 */
public final class FixtureMcpServer {

  private FixtureMcpServer() {}

  public static void main(String[] args) throws IOException {
    String mode = args.length == 0 ? "normal" : args[0];
    if ("die".equals(mode)) {
      // 一个启动、什么都不说、然后退出的服务器：那场握手没有任何答案会来。
      return;
    }
    BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    BufferedWriter out =
        new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
    if ("noisy".equals(mode)) {
      // 一个往 stderr 打日志的服务器，客户端必须抽干它，否则管道写满、服务器当场停摆：那失败
      // 看起来就是一次毫无解释的卡死。
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

  /** 完整的回复，或者是结果或者是错误，因为 fixture 必须能拒绝点什么。 */
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
          // 客户端唯一被允许重试的那次拒绝：只讲旧修订版的服务器会明说，客户端于是用对方听得懂
          // 的语言再问一次。
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
