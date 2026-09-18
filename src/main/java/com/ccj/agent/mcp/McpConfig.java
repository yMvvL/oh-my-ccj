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
 * The MCP servers this machine has been told about.
 *
 * <pre>
 * { "servers": [
 *     {"name": "fs", "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]}
 * ] }
 * </pre>
 *
 * <p>Where this lives, and why: in the application home, beside the approvals file, rather than in the
 * project. A server is a command that gets executed and a set of tools the model may then call, which
 * is not a decision a repository should be able to make on the user's behalf by being cloned.
 *
 * <p>Every tool it brings is named {@code mcp__<server>__<tool>} — the server is part of the name
 * because an approval prompt and a rule have to be able to say which one is asking. A name that read
 * just {@code read_file} would be indistinguishable from a built-in, and "which program is this" is
 * the first question anybody watching asks.
 */
public final class McpConfig {

  /** One server: what to run, and what to call it. */
  public record Server(String name, String command, List<String> args, Map<String, String> env) {

    /** The prefix every one of this server's tools carries. */
    public String toolPrefix() {
      return "mcp__" + name + "__";
    }
  }

  private static final int MAX_SERVERS = 16;

  private final List<Server> servers;

  private McpConfig(List<Server> servers) {
    this.servers = List.copyOf(servers);
  }

  /** Nothing configured: the common case, and the one that must cost nothing. */
  public static McpConfig none() {
    return new McpConfig(List.of());
  }

  /**
   * The servers named in {@code file}.
   *
   * <p>A malformed entry throws rather than being skipped: a server that silently does not start is a
   * set of tools that silently is not there, and "why is the model not using my server" is not a
   * question anybody should have to answer by reading a log.
   */
  public static McpConfig from(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return none();
    }
    JsonNode root;
    try {
      root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
    if (!root.isObject()) {
      throw new IllegalArgumentException("the MCP file must be a JSON object: " + file);
    }
    JsonNode entries = root.get("servers");
    if (entries == null || entries.isNull()) {
      return none();
    }
    if (!entries.isArray()) {
      throw new IllegalArgumentException("'servers' must be an array in " + file);
    }
    if (entries.size() > MAX_SERVERS) {
      throw new IllegalArgumentException(
          "'servers' holds " + entries.size() + " entries; the limit is " + MAX_SERVERS + " in " + file);
    }
    List<Server> servers = new ArrayList<>();
    for (JsonNode entry : entries) {
      if (!entry.isObject()) {
        throw new IllegalArgumentException("each server must be an object in " + file + ": " + entry);
      }
      String name = text(entry, "name", file);
      String command = text(entry, "command", file);
      if (name == null || command == null) {
        throw new IllegalArgumentException(
            "a server needs a 'name' and a 'command' in " + file + ": " + entry);
      }
      if (name.contains("__") || name.contains(" ")) {
        throw new IllegalArgumentException(
            "'" + name + "' cannot be a server name: the tool names it prefixes use '__' as their"
                + " separator, and spaces make a name nobody can type. Use letters, digits, '-' and"
                + " '_': " + file);
      }
      List<String> args = new ArrayList<>();
      JsonNode argsNode = entry.get("args");
      if (argsNode != null && !argsNode.isNull()) {
        if (!argsNode.isArray()) {
          throw new IllegalArgumentException("server 'args' must be an array in " + file + ": " + entry);
        }
        for (JsonNode arg : argsNode) {
          if (!arg.isTextual()) {
            throw new IllegalArgumentException(
                "every entry of 'args' must be a string in " + file + ": " + arg);
          }
          args.add(arg.asText());
        }
      }
      Map<String, String> env = new LinkedHashMap<>();
      JsonNode envNode = entry.get("env");
      if (envNode != null && !envNode.isNull()) {
        if (!envNode.isObject()) {
          throw new IllegalArgumentException("server 'env' must be an object in " + file + ": " + entry);
        }
        envNode
            .fields()
            .forEachRemaining(
                pair -> {
                  if (!pair.getValue().isTextual()) {
                    throw new IllegalArgumentException(
                        "every value of 'env' must be a string in " + file + ": " + pair.getKey());
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
      throw new IllegalArgumentException("server '" + field + "' must be a string in " + file);
    }
    String value = node.asText().strip();
    return value.isEmpty() ? null : value;
  }
}
