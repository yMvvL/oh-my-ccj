package com.ccj.agent.mcp;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 这台机器被告知过的 MCP 服务器。
 *
 * <pre>
 * { "servers": [
 *     {"name": "fs", "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]}
 * ] }
 * </pre>
 *
 * <p>它放在哪里、为什么：放在应用主目录、审批文件旁边，而不是项目里。一个服务器就是一条会被
 * 执行的命令，外加一组此后模型可以调用的工具，这不是一个仓库靠被克隆就能替用户做出的决定。
 *
 * <p>它带来的每个工具都叫 {@code mcp__<server>__<tool>}——服务器是名字的一部分，因为审批提示
 * 和规则必须能说出是哪一台在请求。一个只读作 {@code read_file} 的名字，会和内置工具无从区分，
 * 而「这是哪个程序」是任何看着的人问出的第一个问题。
 */
public final class McpConfig {

  /** 一个服务器：运行什么，以及叫它什么。 */
  public record Server(String name, String command, List<String> args, Map<String, String> env) {

    /** 这个服务器的每个工具都携带的前缀。 */
    public String toolPrefix() {
      return "mcp__" + name + "__";
    }
  }

  private static final int MAX_SERVERS = 16;

  private final List<Server> servers;

  private McpConfig(List<Server> servers) {
    this.servers = List.copyOf(servers);
  }

  /** 什么都没配置：最常见的情形，也是必须零成本的那一种。 */
  public static McpConfig none() {
    return new McpConfig(List.of());
  }

  /**
   * {@code file} 里点名的服务器。
   *
   * <p>格式错误的条目会抛错而不是被跳过：一个悄悄没启动的服务器，就是一组悄悄不存在的工具，
   * 而「模型为什么不用我的服务器」这个问题，不该靠读日志来回答。
   */
  public static McpConfig from(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return none();
    }
    JsonNode root;
    try {
      root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取 " + file, e);
    }
    if (!root.isObject()) {
      throw new IllegalArgumentException("MCP 文件必须是一个 JSON 对象：" + file);
    }
    JsonNode entries = root.get("servers");
    if (entries == null || entries.isNull()) {
      return none();
    }
    if (!entries.isArray()) {
      throw new IllegalArgumentException("'servers' 必须是数组，见 " + file);
    }
    if (entries.size() > MAX_SERVERS) {
      throw new IllegalArgumentException(
          "'servers' 里有 " + entries.size() + " 个条目；" + file + " 里的上限是 " + MAX_SERVERS);
    }
    List<Server> servers = new ArrayList<>();
    for (JsonNode entry : entries) {
      if (!entry.isObject()) {
        throw new IllegalArgumentException("每个 server 都必须是对象，见 " + file + "：" + entry);
      }
      String name = text(entry, "name", file);
      String command = text(entry, "command", file);
      if (name == null || command == null) {
        throw new IllegalArgumentException(
            "server 需要 'name' 和 'command'，见 " + file + "：" + entry);
      }
      if (name.contains("__") || name.contains(" ")) {
        throw new IllegalArgumentException(
            "'" + name + "' 不能作为服务器名：以它为前缀的工具名用 '__' 作分隔符，而空格会造出"
                + "没人打得出来的名字。请只用字母、数字、'-' 和 '_'：" + file);
      }
      List<String> args = new ArrayList<>();
      JsonNode argsNode = entry.get("args");
      if (argsNode != null && !argsNode.isNull()) {
        if (!argsNode.isArray()) {
          throw new IllegalArgumentException(
              "server 的 'args' 必须是数组，见 " + file + "：" + entry);
        }
        for (JsonNode arg : argsNode) {
          if (!arg.isTextual()) {
            throw new IllegalArgumentException(
                "'args' 的每个条目都必须是字符串，见 " + file + "：" + arg);
          }
          args.add(arg.asText());
        }
      }
      Map<String, String> env = new LinkedHashMap<>();
      JsonNode envNode = entry.get("env");
      if (envNode != null && !envNode.isNull()) {
        if (!envNode.isObject()) {
          throw new IllegalArgumentException(
              "server 的 'env' 必须是对象，见 " + file + "：" + entry);
        }
        envNode
            .fields()
            .forEachRemaining(
                pair -> {
                  if (!pair.getValue().isTextual()) {
                    throw new IllegalArgumentException(
                        "'env' 的每个值都必须是字符串，见 "
                            + file
                            + "："
                            + pair.getKey());
                  }
                  env.put(pair.getKey(), pair.getValue().asText());
                });
      }
      servers.add(new Server(name, command, List.copyOf(args), Map.copyOf(env)));
    }
    return new McpConfig(servers);
  }

  public List<Server> servers() {
    return servers;
  }

  public boolean isEmpty() {
    return servers.isEmpty();
  }

  private static String text(JsonNode entry, String field, Path file) {
    JsonNode node = entry.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException("server 的 '" + field + "' 必须是字符串，见 " + file);
    }
    String value = node.asText().strip();
    return value.isEmpty() ? null : value;
  }
}
