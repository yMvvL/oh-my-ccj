package com.ccj.agent.workspace;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Workspace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 已知工作区的列表，存放在 {@code <home>/workspaces.json}。
 *
 * <p>{@code ccj} 启动时所在的那个工作区由启动目录播种，并继续使用顶层的 {@code <home>/sessions}
 * 目录，于是在工作区存在之前记录的会话留在原地一动不动。之后添加的工作区在
 * {@code <home>/workspaces/<name>/sessions} 下有各自的目录。
 *
 * <p>这里只住着登记表：忘掉一个工作区永远不会删掉会话文件，因为一个仅因某个条目离开了列表就悄悄抹去
 * 历史的工具，会是个糟糕的工具。
 */
public final class WorkspaceStore {

  private static final String FILE_NAME = "workspaces.json";
  private static final String LEGACY_SESSIONS = "sessions";

  private final Path home;
  private final Path file;
  private final Map<String, Workspace> workspaces = new LinkedHashMap<>();
  private String active;

  private WorkspaceStore(Path home) {
    this.home = home.toAbsolutePath().normalize();
    this.file = this.home.resolve(FILE_NAME);
  }

  /**
   * 载入登记表，首次使用时把启动目录播种为活动工作区。
   *
   * @param startingDir 前端启动时所在的目录；没有登记表时使用
   */
  public static WorkspaceStore open(Path home, Path startingDir) {
    WorkspaceStore store = new WorkspaceStore(home);
    if (!Files.isRegularFile(store.file)) {
      store.seed(startingDir);
      return store;
    }
    store.load();
    if (store.workspaces.isEmpty()) {
      store.seed(startingDir);
    } else if (store.active == null || !store.workspaces.containsKey(store.active)) {
      store.active = store.workspaces.keySet().iterator().next();
      store.save();
    }
    return store;
  }

  public Path home() {
    return home;
  }

  public Path file() {
    return file;
  }

  public synchronized List<Workspace> list() {
    return List.copyOf(workspaces.values());
  }

  public synchronized Workspace active() {
    return workspaces.get(active);
  }

  public synchronized String activeName() {
    return active;
  }

  public synchronized Optional<Workspace> find(String name) {
    return name == null ? Optional.empty() : Optional.ofNullable(workspaces.get(name.strip()));
  }

  /** 把 {@code name} 设为活动工作区。 */
  public synchronized Workspace activate(String name) {
    Workspace workspace = find(name).orElseThrow(() -> unknown(name));
    active = workspace.name();
    save();
    return workspace;
  }

  /** 以显式给出的名字注册一个工作区，必要时创建它的目录。 */
  public synchronized Workspace add(String name, Path path) {
    String clean = Workspace.requireValidName(name);
    if (workspaces.containsKey(clean)) {
      throw new IllegalArgumentException("名为 '" + clean + "' 的工作区已存在");
    }
    if (path == null) {
      throw new IllegalArgumentException("一个工作区需要一个目录");
    }
    Path directory = usableDirectory(path);
    Workspace workspace = new Workspace(clean, directory, sessionsDirFor(clean));
    workspaces.put(clean, workspace);
    if (active == null) {
      active = clean;
    }
    save();
    return workspace;
  }

  /**
   * 注册一个从选择器里挑出来的目录，以目录自身的名字给它命名。
   *
   * <p>挑一个文件夹已经说全了登记表需要的一切：文件夹就是名字，再要求一步、让用户为一个文件夹已经带着
   * 的名字再打一遍，是谁也不想要的一步。名字被占用时——同一个项目来两次，或者两个目录的最后一段相同
   * ——用下一个空闲的后缀，因为拒绝会把用户推回去，为一个已经有名字的东西打一个名字。
   */
  public synchronized Workspace add(Path path) {
    if (path == null) {
      throw new IllegalArgumentException("一个工作区需要一个目录");
    }
    Path directory = path.toAbsolutePath().normalize();
    String base = directory.getFileName() == null ? "" : directory.getFileName().toString();
    return addNamed(directory, base);
  }

  /**
   * 以 {@code base} 注册 {@code directory}，或用下一个空闲的 {@code base-2}、{@code base-3}……
   *
   * <p>落进登记表的名字、它的会话目录所依据的名字、以及错误消息里的名字都在一处决定，于是带后缀的工作区
   * 不会拿到别人的历史。
   */
  private Workspace addNamed(Path path, String base) {
    String wanted = base.length() > 40 ? base.substring(0, 40) : base;
    if (!Workspace.validName(wanted)) {
      throw new IllegalArgumentException(
          "文件夹名 '" + base + "' 不能作为工作区名：" + Workspace.RULE);
    }
    String clean = wanted;
    for (int suffix = 2; workspaces.containsKey(clean); suffix++) {
      String tail = "-" + suffix;
      clean = wanted.substring(0, Math.min(wanted.length(), 40 - tail.length())) + tail;
    }
    Path directory = usableDirectory(path);
    Workspace workspace = new Workspace(clean, directory, sessionsDirFor(clean));
    workspaces.put(clean, workspace);
    if (active == null) {
      active = clean;
    }
    save();
    return workspace;
  }

  /**
   * 目录将被存下来的样子，不存在时创建它。
   *
   * <p>创建是刻意为之：为一个还不存在的项目添加工作区是正常的想法，而一个空目录很便宜。
   */
  private Path usableDirectory(Path path) {
    Path directory = path.toAbsolutePath().normalize();
    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw new IllegalArgumentException("无法使用 " + directory + "：" + e.getMessage(), e);
    }
    if (!Files.isDirectory(directory)) {
      throw new IllegalArgumentException("不是目录：" + directory);
    }
    return directory;
  }

  /** 忘掉一个工作区。它的会话文件仍然留在磁盘上。 */
  public synchronized void remove(String name) {
    String clean = name == null ? "" : name.strip();
    if (!workspaces.containsKey(clean)) {
      throw unknown(clean);
    }
    if (clean.equals(active)) {
      throw new IllegalArgumentException(
          "无法移除活动工作区 '" + clean + "'；请先切换到另一个");
    }
    workspaces.remove(clean);
    save();
  }

  /** 新添加的工作区获得的会话目录。 */
  public Path sessionsDirFor(String name) {
    return home.resolve("workspaces").resolve(name).resolve(LEGACY_SESSIONS);
  }

  private IllegalArgumentException unknown(String name) {
    return new IllegalArgumentException(
        "没有名为 '"
            + (name == null ? "" : name.strip())
            + "' 的工作区；已知工作区："
            + String.join(", ", workspaces.keySet()));
  }

  private void seed(Path startingDir) {
    Path directory =
        (startingDir == null ? Path.of("") : startingDir).toAbsolutePath().normalize();
    String name = directory.getFileName() == null ? "default" : directory.getFileName().toString();
    if (!Workspace.validName(name)) {
      name = "default";
    }
    Workspace workspace = new Workspace(name, directory, home.resolve(LEGACY_SESSIONS));
    workspaces.put(workspace.name(), workspace);
    active = workspace.name();
    save();
  }

  private void load() {
    JsonNode root;
    try {
      root = Json.parse(Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取 " + file, e);
    }
    if (!root.isObject()) {
      throw new IllegalArgumentException(file + " 必须包含一个 JSON 对象");
    }
    active = root.path("active").isTextual() ? root.path("active").asText() : null;
    JsonNode entries = root.path("workspaces");
    if (entries.isArray()) {
      for (JsonNode entry : entries) {
        String name = entry.path("name").asText("");
        String path = entry.path("path").asText("");
        if (!Workspace.validName(name) || path.isBlank()) {
          continue; // 用不了的条目跳过，而不是致命
        }
        String sessions = entry.path("sessions").asText("");
        Path sessionsDir =
            sessions.isBlank() ? sessionsDirFor(name) : home.resolve(sessions).normalize();
        workspaces.put(name, new Workspace(name, Path.of(path), sessionsDir));
      }
    }
  }

  private void save() {
    ObjectNode root = Json.object();
    root.put("active", active);
    ArrayNode entries = root.putArray("workspaces");
    for (Workspace workspace : workspaces.values()) {
      ObjectNode entry = entries.addObject();
      entry.put("name", workspace.name());
      entry.put("path", workspace.path().toString());
      Path sessions = workspace.sessionsDir();
      if (sessions.equals(home.resolve(LEGACY_SESSIONS))) {
        entry.put("sessions", LEGACY_SESSIONS);
      } else if (sessions.equals(sessionsDirFor(workspace.name()))) {
        // 推导出的位置就是默认值，所以不写下来。
      } else {
        entry.put("sessions", home.relativize(sessions).toString());
      }
    }
    try {
      Files.createDirectories(home);
      Files.writeString(
          file,
          Json.writePretty(root) + "\n",
          StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("无法写入 " + file, e);
    }
  }

  /** 每个工作区的名字，供错误消息和测试使用。 */
  public synchronized List<String> names() {
    return new ArrayList<>(workspaces.keySet());
  }
}
