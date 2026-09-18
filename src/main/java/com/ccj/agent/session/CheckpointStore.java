package com.ccj.agent.session;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What a turn changed, kept so the turn can be taken back.
 *
 * <p>Approval answers one question — "may this run" — and a checkpoint answers the other one, the one
 * people actually mean when they hesitate before letting an agent work: *may it be undone*. Without
 * it the honest description of a session is "the agent wrote files and you approved each write",
 * which is a lot of promise for one press of a button; with it the promise is bounded, and a person
 * can leave the guard where it belongs instead of turning approval off to compensate.
 *
 * <p>Layout, beside the session and keyed by session id:
 *
 * <pre>
 * &lt;sessions&gt;/&lt;id&gt;.checkpoints/&lt;turn&gt;/manifest.json
 * &lt;sessions&gt;/&lt;id&gt;.checkpoints/&lt;turn&gt;/0.blob
 * </pre>
 *
 * <p>The manifest names what was touched, project-relative, with the blob that holds its previous
 * content and whether it existed at all — a turn that creates a file is undone by deleting it, and
 * that is a different operation from writing back what was there.
 *
 * <p><strong>Thread-scoped, and that is the design rather than a detail.</strong> A turn runs on one
 * thread; the tools that write run on it; several conversations may run at once, each on its own. A
 * single field saying "the turn in progress" would attribute one conversation's writes to another's
 * checkpoint, and a thread-local says exactly what is true: the turn is what is running here, now.
 */
public final class CheckpointStore {

  /** Beyond this, a file is not snapshotted and the turn says so: a checkpoint of a film is not worth keeping. */
  static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

  /** How many turns are kept. Older ones are pruned when a turn begins. */
  static final int KEEP_TURNS = 20;

  /** The turn this thread is running, or null when it is not running one. */
  private static final ThreadLocal<Turn> CURRENT = new ThreadLocal<>();

  private final boolean recording;

  private CheckpointStore(boolean recording) {
    this.recording = recording;
  }

  /** A store that records nothing: what a tool gets when nobody wired one in. */
  public static CheckpointStore none() {
    return new CheckpointStore(false);
  }

  /** The real thing. */
  public static CheckpointStore recording() {
    return new CheckpointStore(true);
  }

  /** One turn in progress on this thread. */
  private static final class Turn {
    private final Path directory;
    private final Path project;
    private final List<Entry> entries = new ArrayList<>();
    private int blobs;

    Turn(Path directory, Path project) {
      this.directory = directory;
      this.project = project;
    }

    /**
     * The turn's directory, made when there is something to put in it.
     *
     * <p>Lazily, because a turn that changed nothing must leave nothing behind: a directory per turn
     * whether or not it holds a snapshot is a directory per turn in the count of what can be undone,
     * and "three turns can be undone" when only one of them changed a file is a lie the user only
     * discovers by pressing the button.
     */
    Path directory() throws IOException {
      Files.createDirectories(directory);
      return directory;
    }
  }

  /** One file as it was before the turn touched it. */
  private record Entry(String path, String blob, boolean existed) {}

  /**
   * Opens a turn on this thread, for the conversation whose session file this is.
   *
   * <p>Called by whoever starts a turn — the web hub, the REPL — and closed by {@link #endTurn()}.
   * Nothing is recorded between the two ends unless a tool asks, and a tool that runs with no turn
   * open records nothing: a background thread is not the turn, and pretending otherwise is how a
   * checkpoint ends up holding the wrong conversation's state.
   */
  public void beginTurn(Path sessionFile, Path project) {
    endTurn();
    if (!recording || sessionFile == null || project == null) {
      return;
    }
    Path root = sessionFile.toAbsolutePath().normalize();
    Path directory =
        root.resolveSibling(root.getFileName().toString().replace(".jsonl", "") + ".checkpoints");
    // A checkpoint that cannot be written must not stop the turn: the work is what was asked for, and
    // being able to undo it is a courtesy. See Turn.directory for why nothing is created here.
    CURRENT.set(
        new Turn(directory.resolve(Integer.toString(nextTurn(directory))),
            project.toAbsolutePath().normalize()));
    prune(directory);
  }

  /** Closes this thread's turn, writing what it holds. */
  public void endTurn() {
    Turn turn = CURRENT.get();
    CURRENT.remove();
    if (turn == null || turn.entries.isEmpty()) {
      return;
    }
    ObjectNode manifest = Json.object();
    manifest.put("project", turn.project.toString());
    ArrayNode files = manifest.putArray("files");
    for (Entry entry : turn.entries) {
      ObjectNode node = files.addObject();
      node.put("path", entry.path());
      node.put("blob", entry.blob());
      node.put("existed", entry.existed());
    }
    try {
      Path directory = turn.directory();
      Files.writeString(
          directory.resolve("manifest.json"), Json.writePretty(manifest) + "\n",
          StandardCharsets.UTF_8);
      // Pruned here as well as at the start, because this is where a turn adds a directory: pruning
      // only at the start leaves one turn more than the bound until the next one begins.
      prune(directory.getParent());
    } catch (IOException e) {
      // As above: losing the ability to undo one turn is not worth failing the turn over.
    }
  }

  /**
   * Remembers a file as it was, before a tool overwrites it.
   *
   * @param file the file about to be written
   * @param previous its content now, or null when it does not exist yet
   */
  public void record(Path file, String previous) {
    Turn turn = CURRENT.get();
    if (turn == null || file == null) {
      return;
    }
    String relative = relative(file, turn.project);
    if (relative == null) {
      return; // outside the project: not this conversation's business to undo
    }
    for (Entry entry : turn.entries) {
      if (entry.path().equals(relative)) {
        return; // already snapshotted this turn: the state that matters is the one the turn started with
      }
    }
    if (previous != null && previous.length() > MAX_FILE_BYTES) {
      return;
    }
    String blob = turn.blobs++ + ".blob";
    try {
      Files.writeString(turn.directory().resolve(blob), previous == null ? "" : previous,
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      return;
    }
    turn.entries.add(new Entry(relative, blob, previous != null));
  }

  /**
   * Puts back what the most recent recorded turn changed, and forgets that turn.
   *
   * @return the project-relative paths that were restored, newest turn only
   */
  public List<String> undoLastTurn(Path sessionFile, Path project) {
    Path directory = directoryFor(sessionFile);
    if (directory == null || !Files.isDirectory(directory)) {
      return List.of();
    }
    List<Integer> turns = turnNumbers(directory);
    if (turns.isEmpty()) {
      return List.of();
    }
    int newest = turns.get(turns.size() - 1);
    Path turn = directory.resolve(Integer.toString(newest));
    JsonNode manifest;
    try {
      Path file = turn.resolve("manifest.json");
      if (!Files.isRegularFile(file)) {
        // A turn that recorded nothing: nothing to undo, and its directory goes so the next undo
        // reaches the turn before it.
        deleteRecursively(turn);
        return List.of();
      }
      manifest = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException | IllegalArgumentException e) {
      return List.of();
    }
    List<String> restored = new ArrayList<>();
    for (JsonNode entry : manifest.path("files")) {
      Path target = project.resolve(entry.path("path").asText()).normalize();
      if (!target.startsWith(project.toAbsolutePath().normalize())) {
        continue; // a manifest that names somewhere else is not one to obey
      }
      try {
        if (entry.path("existed").asBoolean(false)) {
          Path blob = turn.resolve(entry.path("blob").asText());
          Files.createDirectories(target.getParent());
          Files.writeString(target, Files.readString(blob, StandardCharsets.UTF_8),
              StandardCharsets.UTF_8);
        } else {
          Files.deleteIfExists(target);
        }
        restored.add(entry.path("path").asText());
      } catch (IOException e) {
        // One file that cannot be restored must not stop the others.
      }
    }
    deleteRecursively(turn);
    return List.copyOf(restored);
  }

  /** How many turns can be undone, newest first, for a caller that wants to say so. */
  public int undoableTurns(Path sessionFile) {
    Path directory = directoryFor(sessionFile);
    return directory == null ? 0 : turnNumbers(directory).size();
  }

  /** The directory a session's checkpoints live in, or null when there is no session file. */
  public static Path directoryFor(Path sessionFile) {
    if (sessionFile == null) {
      return null;
    }
    Path root = sessionFile.toAbsolutePath().normalize();
    return root.resolveSibling(root.getFileName().toString().replace(".jsonl", "") + ".checkpoints");
  }

  private static String relative(Path file, Path project) {
    Path target = file.toAbsolutePath().normalize();
    if (!target.startsWith(project)) {
      return null;
    }
    return project.relativize(target).toString().replace('\\', '/');
  }

  private static int nextTurn(Path directory) {
    List<Integer> turns = turnNumbers(directory);
    return turns.isEmpty() ? 1 : turns.get(turns.size() - 1) + 1;
  }

  private static List<Integer> turnNumbers(Path directory) {
    List<Integer> numbers = new ArrayList<>();
    if (!Files.isDirectory(directory)) {
      return numbers;
    }
    try (var entries = Files.list(directory)) {
      entries.forEach(
          path -> {
            try {
              numbers.add(Integer.parseInt(path.getFileName().toString()));
            } catch (NumberFormatException notATurn) {
              // Something else in the directory; not ours to interpret.
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + directory, e);
    }
    numbers.sort(Integer::compareTo);
    return numbers;
  }

  private static void prune(Path directory) {
    List<Integer> turns = turnNumbers(directory);
    for (int i = 0; i < turns.size() - KEEP_TURNS; i++) {
      deleteRecursively(directory.resolve(Integer.toString(turns.get(i))));
    }
  }

  private static void deleteRecursively(Path path) {
    if (!Files.exists(path)) {
      return;
    }
    try (var walk = Files.walk(path)) {
      for (Path entry : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(entry);
      }
    } catch (IOException ignored) {
      // Best effort: a leftover directory is a smaller problem than a failed turn or undo.
    }
  }
}
