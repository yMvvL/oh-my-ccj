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
 * The list of known workspaces, kept in {@code <home>/workspaces.json}.
 *
 * <p>The workspace {@code ccj} was started in is seeded from the starting directory and keeps using
 * the top-level {@code <home>/sessions} directory, so conversations recorded before workspaces
 * existed stay exactly where they were. Workspaces added later get their own directory under
 * {@code <home>/workspaces/<name>/sessions}.
 *
 * <p>Only the registry lives here: forgetting a workspace never deletes conversation files, because
 * a tool that silently removes history because an entry left a list would be a bad tool.
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
   * Loads the registry, seeding the starting directory as the active workspace on first use.
   *
   * @param startingDir the directory the front end was started in; used when there is no registry
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

  /** Makes {@code name} the active workspace. */
  public synchronized Workspace activate(String name) {
    Workspace workspace = find(name).orElseThrow(() -> unknown(name));
    active = workspace.name();
    save();
    return workspace;
  }

  /** Registers a workspace under an explicit name, creating its directory if needed. */
  public synchronized Workspace add(String name, Path path) {
    String clean = Workspace.requireValidName(name);
    if (workspaces.containsKey(clean)) {
      throw new IllegalArgumentException("a workspace named '" + clean + "' already exists");
    }
    if (path == null) {
      throw new IllegalArgumentException("a workspace needs a directory");
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
   * Registers a directory picked from a chooser, naming it after the directory itself.
   *
   * <p>Picking a folder says everything the registry needs: the folder is the name, and a second step
   * that asks for a name the folder already carries is a step nobody wants. When the name is taken —
   * the same project twice, or two directories that share a last segment — the next free suffix is
   * used, because refusing would send the user back to type a name for something that already has one.
   */
  public synchronized Workspace add(Path path) {
    if (path == null) {
      throw new IllegalArgumentException("a workspace needs a directory");
    }
    Path directory = path.toAbsolutePath().normalize();
    String base = directory.getFileName() == null ? "" : directory.getFileName().toString();
    return addNamed(directory, base);
  }

  /**
   * Registers {@code directory} under {@code base}, or the next free {@code base-2}, {@code base-3}…
   *
   * <p>The name that lands in the registry, the name its session directory is derived from and the
   * name in the error message are decided in one place, so a suffixed workspace cannot end up with
   * another one's history.
   */
  private Workspace addNamed(Path path, String base) {
    String wanted = base.length() > 40 ? base.substring(0, 40) : base;
    if (!Workspace.validName(wanted)) {
      throw new IllegalArgumentException(
          "the folder name '" + base + "' cannot be a workspace name: " + Workspace.RULE);
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
   * The directory as it will be stored, created if it does not exist.
   *
   * <p>Creating it is deliberate: adding a workspace for a project that does not exist yet is a
   * normal thing to want, and an empty directory is cheap.
   */
  private Path usableDirectory(Path path) {
    Path directory = path.toAbsolutePath().normalize();
    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw new IllegalArgumentException("cannot use " + directory + ": " + e.getMessage(), e);
    }
    if (!Files.isDirectory(directory)) {
      throw new IllegalArgumentException("not a directory: " + directory);
    }
    return directory;
  }

  /** Forgets a workspace. Its session files stay on disk. */
  public synchronized void remove(String name) {
    String clean = name == null ? "" : name.strip();
    if (!workspaces.containsKey(clean)) {
      throw unknown(clean);
    }
    if (clean.equals(active)) {
      throw new IllegalArgumentException(
          "cannot remove the active workspace '" + clean + "'; switch to another one first");
    }
    workspaces.remove(clean);
    save();
  }

  /** The sessions directory a newly added workspace gets. */
  public Path sessionsDirFor(String name) {
    return home.resolve("workspaces").resolve(name).resolve(LEGACY_SESSIONS);
  }

  private IllegalArgumentException unknown(String name) {
    return new IllegalArgumentException(
        "no workspace named '"
            + (name == null ? "" : name.strip())
            + "'; known workspaces: "
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
      throw new UncheckedIOException("cannot read " + file, e);
    }
    if (!root.isObject()) {
      throw new IllegalArgumentException(file + " must contain a JSON object");
    }
    active = root.path("active").isTextual() ? root.path("active").asText() : null;
    JsonNode entries = root.path("workspaces");
    if (entries.isArray()) {
      for (JsonNode entry : entries) {
        String name = entry.path("name").asText("");
        String path = entry.path("path").asText("");
        if (!Workspace.validName(name) || path.isBlank()) {
          continue; // an entry we cannot use is skipped rather than fatal
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
        // The derived location is the default, so it is not written down.
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
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }

  /** Names of every workspace, for error messages and tests. */
  public synchronized List<String> names() {
    return new ArrayList<>(workspaces.keySet());
  }
}
