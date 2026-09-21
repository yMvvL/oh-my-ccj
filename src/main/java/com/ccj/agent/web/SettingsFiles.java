package com.ccj.agent.web;

import com.ccj.agent.core.ApprovalRules;
import com.ccj.agent.core.Checks;
import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 设置面板写到磁盘上的那两样东西：审批规则，和配置文件里的编辑后检查。
 *
 * <p>路径与规则都是入参，而不是字段：写的是哪个文件、属于哪个项目，由调用的那一刻说了算，所以切换工作区
 * 不需要通知这里任何事。它也不知道会话与事件——它只读写文件。
 */
final class SettingsFiles {

  private SettingsFiles() {}

  /**
   * 设置面板需要的审批规则：哪一个项目、哪些条，以及它们的读法。
   *
   * <p>{@code describe} 是 {@link ApprovalRules#describe()} 的原文，面板把它直接印出来——规则的读法只有
   * 一处，面板不再造第二种说法。它与 {@code rules} 同序：先拒绝、再允许，本会话内的放行排在最后（它们不在
   * 文件里，因此也不在 {@code rules} 里）。
   *
   * @param rules 规则的来源；为 null 表示这个服务器没有接上规则文件
   * @param project 面板要显示的项目目录，{@code rules} 为 null 时这是唯一说得出项目的地方
   */
  static ObjectNode rulesJson(ApprovalRules rules, Path project) {
    ObjectNode node = Json.object();
    if (rules == null) {
      // 没有接上规则文件的 hub：如实说没有，而不是编一个路径出来。
      node.putNull("file");
      node.put("project", project.toString());
      node.putArray("rules");
      node.putArray("describe");
      return node;
    }
    node.put("file", rules.file().toString());
    node.put("project", rules.project().toString());
    ArrayNode list = node.putArray("rules");
    rules.deny().forEach(rule -> list.add(ruleJson(rule, "deny")));
    rules.allow().forEach(rule -> list.add(ruleJson(rule, "allow")));
    ArrayNode describe = node.putArray("describe");
    rules.describe().forEach(describe::add);
    return node;
  }

  private static ObjectNode ruleJson(ApprovalRules.Rule rule, String effect) {
    ObjectNode node = Json.object();
    node.put("effect", effect);
    node.put("tool", rule.tool());
    if (rule.command() == null) {
      node.putNull("command");
    } else {
      node.put("command", rule.command());
    }
    if (rule.path() == null) {
      node.putNull("path");
    } else {
      node.put("path", rule.path());
    }
    return node;
  }

  /**
   * 加一条或删一条审批规则，走 {@link ApprovalRules} 自己那条写入路径，所以面板写出来的文件与手编的完全
   * 一样。
   *
   * <p>改动立刻生效：规则每次工具调用都从文件里读，而这里写的就是那个文件。所以写完之后把同一个文件重新打开
   * 并设回去——一个没有生效的删除，比一个不能编辑的规则文件更糟。
   */
  static ObjectNode applyRules(JsonNode posted, ApprovalRules rules, Path project) {
    if (rules == null) {
      throw new IllegalArgumentException("这个服务器没有接上审批规则文件，所以规则改不了");
    }
    if (posted == null || !posted.isObject()) {
      throw new IllegalArgumentException("需要一个 JSON 对象");
    }
    String effect = posted.path("effect").asText("");
    boolean deny;
    if ("deny".equals(effect)) {
      deny = true;
    } else if ("allow".equals(effect)) {
      deny = false;
    } else {
      throw new IllegalArgumentException("effect 必须是 'allow' 或 'deny'");
    }
    String tool = AgentHub.text(posted, "tool");
    if (tool == null) {
      throw new IllegalArgumentException("字段 'tool' 是必需的：一条规则必须点名它拦住的工具");
    }
    ApprovalRules.Rule rule =
        new ApprovalRules.Rule(tool, AgentHub.text(posted, "command"), AgentHub.text(posted, "path"));
    switch (posted.path("action").asText("")) {
      case "add" -> rules.remember(deny, rule);
      case "remove" -> {
        if (!rules.forget(deny, rule)) {
          throw new IllegalArgumentException(
              "这条规则已经不在文件里了——它可能已被另一次改动删掉；列表已重新读取，请看着它再删一次");
        }
      }
      default -> throw new IllegalArgumentException("action 必须是 'add' 或 'remove'");
    }
    // **不**重开一份实例：判定每次调用都重读那个文件（`deny()`/`allow()` 里就是 `read()`），所以刚写下的
    // 规则下一次工具调用就会被看到，而重开会把同一个实例里那个「本会话内放行」的清单丢掉——面板上保存一条
    // 规则，于是变成了把本次会话已经答过的所有事情再问一遍。
    return rulesJson(rules, project);
  }

  /**
   * 设置面板需要的编辑后检查，以及它们所在的那个文件。
   *
   * <p>{@code timeoutSeconds} 一并回传，是因为 POST 是整份替换：面板得先看见一个检查的全部内容，才能在
   * 重写那一份时把它原样带回去。丢掉的字段会在下一次编辑时悄悄变成默认值。
   */
  static ObjectNode checksJson(Path file) {
    ObjectNode node = Json.object();
    if (file == null) {
      node.putNull("file");
      node.putArray("checks");
      return node;
    }
    node.put("file", file.toString());
    ArrayNode list = node.putArray("checks");
    for (Checks.Check check : Checks.from(file).declared()) {
      ObjectNode entry = list.addObject();
      entry.put("glob", check.glob());
      entry.put("command", check.command());
      entry.put("timeoutSeconds", check.timeoutSeconds());
    }
    return node;
  }

  /**
   * 整份替换配置文件里的 {@code checks} 键，其他键一个都不动。
   *
   * <p>校验用的是 {@link Checks} 自己的解析，所以面板上看到的那句话与工具运行时报告的那句话是同一句。它只认
   * 文件，错误消息里也要指名真正的配置文件，所以这里是先写进去再读回来自检：不合法就原样放回去——配置文件
   * 绝不能因为一次被拒绝的保存而变得读不出来。
   */
  static ObjectNode applyChecks(JsonNode posted, Path file) {
    if (file == null) {
      throw new IllegalArgumentException("这个服务器没有配置文件，所以检查改不了");
    }
    JsonNode block = posted == null || !posted.isObject() ? null : posted.get("checks");
    if (block == null) {
      throw new IllegalArgumentException(
          "需要一个 'checks' 数组：正文形如 {\"checks\": [{\"glob\": \"**/*.java\", \"command\":"
              + " \"mvn -q -o -DskipTests compile\"}]}");
    }
    JsonNode root;
    try {
      root = Files.isRegularFile(file) ? Json.parse(Files.readString(file)) : Json.object();
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取 " + file, e);
    }
    if (!root.isObject()) {
      throw new IllegalArgumentException("配置文件 " + file + " 必须包含一个 JSON 对象");
    }
    ObjectNode updated = (ObjectNode) root;
    if (block.isNull() || (block.isArray() && block.isEmpty())) {
      updated.remove("checks");
    } else {
      updated.set("checks", block);
    }
    byte[] before = readOrNull(file);
    writeConfig(file, updated);
    try {
      Checks.from(file).declared();
    } catch (RuntimeException e) {
      restore(file, before);
      throw e;
    }
    return checksJson(file);
  }

  /** 文件此刻的字节；不存在时为 null。 */
  private static byte[] readOrNull(Path file) {
    try {
      return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取 " + file, e);
    }
  }

  /** 把文件放回它原来的样子；它原来不存在时删掉。 */
  private static void restore(Path file, byte[] before) {
    try {
      if (before == null) {
        Files.deleteIfExists(file);
      } else {
        Files.write(file, before);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("无法还原 " + file, e);
    }
  }

  /** 写回配置文件：只动调用方改过的那个键，其余一切照旧，并像写设置那样限制为仅属主可读。 */
  private static void writeConfig(Path file, ObjectNode root) {
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(
          file,
          Json.writePretty(root) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      try {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException | IOException ignored) {
        // 不是 POSIX 文件系统；内容已经写下了，而那才是被要求的事。
      }
    } catch (IOException e) {
      throw new UncheckedIOException("无法写入 " + file, e);
    }
  }
}
