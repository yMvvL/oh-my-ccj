package com.ccj.agent.session;

import com.ccj.agent.core.Message;
import com.ccj.agent.core.Session;
import com.ccj.agent.core.UsageTotals;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Session backed by one append-only JSONL file.
 *
 * <p>Every {@link #append} writes one line and flushes immediately: a session that is killed
 * mid-turn (Ctrl-C, crash, the machine sleeping) still has every message that was already handed to
 * the loop, which is what makes {@code --resume} trustworthy. Opening the writer lazily in append
 * mode means the file keeps whatever a previous process wrote.
 */
public final class FileSession implements Session {

  public static final String EXTENSION = ".jsonl";

  private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  /**
   * The suffix a compacted generation's file carries: {@code <id>.g1.jsonl} is what a session looked
   * like after its first compaction.
   *
   * <p>Generation 0 is the plain {@code <id>.jsonl} — the original — and it is deliberately not named
   * {@code .g0}: sessions written before compaction existed keep the name they already had, and a
   * reader that does not know about generations still sees a session file it can open.
   */
  private static final Pattern GENERATION = Pattern.compile("\\.g([1-9][0-9]*)\\.jsonl$");

  /** Line prefix that marks an accounting record rather than a conversation message. */
  public static final String USAGE_TYPE = "usage";

  private final String id;
  /** The newest generation: what is read, appended to, and replaced by a compaction. */
  private Path file;
  private final List<Message> history;
  private OutputStream writer;
  private UsageTotals totals = UsageTotals.empty();

  public FileSession(String id, Path file, List<Message> history) {
    this(id, file, history, UsageTotals.empty());
  }

  public FileSession(String id, Path file, List<Message> history, UsageTotals totals) {
    this.id = id;
    this.file = file;
    this.history = new ArrayList<>(history == null ? List.of() : history);
    this.totals = totals == null ? UsageTotals.empty() : totals;
  }

  /**
   * Generates a fresh id for {@code sessionsDir} and leaves the file to {@link #append}.
   *
   * <p>Creating the file eagerly would litter the session list with empty sessions every time a
   * front end starts or the user presses "new session" — an id costs nothing, a file is a claim
   * that something was said.
   */
  public static FileSession create(Path sessionsDir) {
    String id = newId();
    return new FileSession(id, fileFor(sessionsDir, id), List.of());
  }

  /**
   * True for an id that can safely become a file name. Deleting goes through this too: an id is
   * user-supplied over HTTP, and {@code ../} must never reach the filesystem.
   */
  public static boolean isValidId(String id) {
    return id != null && SAFE_ID.matcher(id).matches();
  }

  /** Reopens an existing session, restoring its full history. */
  public static FileSession open(Path sessionsDir, String id) {
    if (!isValidId(id)) {
      throw new IllegalArgumentException("invalid session id: " + id);
    }
    Path file = newestFile(sessionsDir, id);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("no session '" + id + "' in " + sessionsDir);
    }
    // The newest generation is the conversation: earlier ones are still on disk, and still readable,
    // but they are what this session used to be. Opening the newest is what makes a compaction look
    // like the same session continuing rather than a different one appearing.
    return new FileSession(id, file, readAll(file), readTotals(file));
  }

  /** Timestamp plus a random suffix, so sessions created in the same second stay distinct. */
  public static String newId() {
    return ID_TIME.format(LocalDateTime.now())
        + "-"
        + String.format("%04x", RANDOM.nextInt(0x10000));
  }

  /** Generation 0: the file a session writes to until something compacts it. */
  public static Path fileFor(Path sessionsDir, String id) {
    return sessionsDir.resolve(id + EXTENSION);
  }

  /** The file holding generation {@code generation}, which must be at least 1. */
  public static Path generationFile(Path sessionsDir, String id, int generation) {
    if (generation < 1) {
      throw new IllegalArgumentException("a generation file starts at 1, got " + generation);
    }
    return sessionsDir.resolve(id + ".g" + generation + EXTENSION);
  }

  /**
   * Every generation of one session that is on disk, in ascending order, with generation 0 first.
   *
   * <p>Read from the directory rather than carried in memory, because the point of generation files is
   * that they outlive the process: a session reopened tomorrow has to find the generations an earlier
   * run left, and a file copied or deleted by hand has to be believed.
   */
  public static List<Integer> generations(Path sessionsDir, String id) {
    if (!isValidId(id)) {
      throw new IllegalArgumentException("invalid session id: " + id);
    }
    if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
      return List.of();
    }
    List<Integer> found = new ArrayList<>();
    if (Files.isRegularFile(fileFor(sessionsDir, id))) {
      found.add(0);
    }
    try (var entries = Files.list(sessionsDir)) {
      for (Path entry : entries.toList()) {
        String name = entry.getFileName().toString();
        if (!name.startsWith(id + ".g")) {
          continue;
        }
        var matcher = GENERATION.matcher(name);
        if (matcher.find()) {
          found.add(Integer.parseInt(matcher.group(1)));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read generations of " + id + " in " + sessionsDir, e);
    }
    found.sort(Integer::compareTo);
    return List.copyOf(found);
  }

  /**
   * The generation a session is currently being read and written as: the newest one that exists.
   *
   * <p>Zero when nothing is on disk yet, which is what a session that has never been written looks
   * like — {@code create} mints an id and leaves the file to the first append.
   */
  public static int newestGeneration(Path sessionsDir, String id) {
    List<Integer> all = generations(sessionsDir, id);
    return all.isEmpty() ? 0 : all.get(all.size() - 1);
  }

  /** The path the newest generation lives at, whether or not it has been written yet. */
  public static Path newestFile(Path sessionsDir, String id) {
    int generation = newestGeneration(sessionsDir, id);
    return generation == 0 ? fileFor(sessionsDir, id) : generationFile(sessionsDir, id, generation);
  }

  /** True for a file name that is one generation of {@code id}, including generation 0. */
  public static boolean isSessionFile(String fileName, String id) {
    if (fileName.equals(id + EXTENSION)) {
      return true;
    }
    return fileName.startsWith(id + ".g") && GENERATION.matcher(fileName).find();
  }

  /**
   * Reads every message in {@code file}, in order. A missing file reads as empty history; a
   * malformed line fails with its line number rather than silently truncating the conversation.
   */
  public static List<Message> readAll(Path file) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      return List.of();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read session file " + file, e);
    }
    List<Message> messages = new ArrayList<>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank() || isUsageLine(line)) {
        continue;
      }
      try {
        messages.add(MessageCodec.fromJson(line));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(file + ":" + (i + 1) + ": " + e.getMessage(), e);
      }
    }
    return messages;
  }

  /** True for an accounting line, which is not part of the conversation. */
  private static boolean isUsageLine(String line) {
    return line.contains("\"type\":\"" + USAGE_TYPE + "\"");
  }

  /** The last accounting record in the file, or nothing when the session never recorded any. */
  public static UsageTotals readTotals(Path file) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      return UsageTotals.empty();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read session file " + file, e);
    }
    UsageTotals found = UsageTotals.empty();
    for (String line : lines) {
      if (isUsageLine(line)) {
        found = MessageCodec.totalsFromJson(line);
      }
    }
    return found;
  }

  @Override
  public UsageTotals totals() {
    return totals;
  }

  @Override
  public void totals(UsageTotals updated) {
    this.totals = updated == null ? UsageTotals.empty() : updated;
    writeLine(MessageCodec.totalsToJson(this.totals));
  }

  @Override
  public String id() {
    return id;
  }

  public Path file() {
    return file;
  }

  @Override
  public List<Message> messages() {
    return List.copyOf(history);
  }

  @Override
  public void append(Message message) {
    history.add(message);
    writeLine(MessageCodec.toJson(message));
  }

  /** Empties the conversation on disk and in memory; the session id survives. */
  public void clear() {
    close();
    history.clear();
    totals = UsageTotals.empty();
    if (!Files.exists(file)) {
      return; // nothing on disk yet; the file appears with the next append
    }
    try {
      Files.writeString(
          file,
          "",
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot clear session file " + file, e);
    }
  }

  /**
   * Replaces this session's conversation with {@code messages}, as the next generation.
   *
   * <p>A new file rather than a rewrite of this one, which is what makes a compaction reversible: the
   * generation it replaces is left exactly as it was, so a summary that turned out to be too lossy is
   * recoverable by opening the older file. It also keeps the append-only story intact — a compaction
   * that is killed halfway leaves a complete file behind rather than a truncated one.
   *
   * <p>The id does not change, so everything keyed on it (the sidebar, {@code --resume}, the workspace
   * registry) keeps pointing at the same session; only which file is "the conversation" moves.
   *
   * <p>Written through a temporary file and renamed, because a half-written generation would be a
   * session that parses as a shorter conversation than it is — the one failure a reader cannot detect.
   *
   * @return the path of the new generation
   */
  public Path compactInto(List<Message> messages, UsageTotals updated) {
    close();
    Path directory = file.getParent();
    List<Integer> existing = generations(directory, id);
    int next = (existing.isEmpty() ? 0 : existing.get(existing.size() - 1)) + 1;
    Path target = generationFile(directory, id, next);
    Path staged = directory.resolve(target.getFileName() + ".tmp");
    try {
      Files.createDirectories(directory);
      StringBuilder body = new StringBuilder();
      for (Message message : messages) {
        body.append(MessageCodec.toJson(message)).append('\n');
      }
      if (updated != null && !updated.isEmpty()) {
        body.append(MessageCodec.totalsToJson(updated)).append('\n');
      }
      Files.writeString(
          staged,
          body.toString(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      // Same directory, so this is an atomic rename: a reader either sees the whole generation or no
      // file at all, never a partial one.
      Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      try {
        Files.deleteIfExists(staged);
      } catch (IOException ignored) {
        // The staging file is a leftover either way; the exception below is the one that matters.
      }
      throw new UncheckedIOException("cannot compact session " + id, e);
    }
    this.file = target;
    this.history.clear();
    this.history.addAll(messages);
    this.totals = updated == null ? UsageTotals.empty() : updated;
    return target;
  }

  @Override
  public void close() {
    if (writer == null) {
      return;
    }
    try {
      writer.close();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot close session file " + file, e);
    } finally {
      writer = null;
    }
  }

  /**
   * Appends one line with a single {@code write} call.
   *
   * <p>The buffered alternative splits a line longer than its buffer across several system calls,
   * and a listing that happens to read the file in between (<code>GET /api/sessions</code> while a
   * turn is running) sees half a JSON object and fails on it. One write per line keeps a reader
   * from ever observing a line that is still being written; the flush keeps the durability promise
   * that a killed process loses nothing it was already handed.
   */
  private void writeLine(String line) {
    byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
    try {
      OutputStream out = writer();
      out.write(bytes);
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot append to session file " + file, e);
    }
  }

  private OutputStream writer() throws IOException {
    if (writer == null) {
      Files.createDirectories(file.getParent());
      writer =
          Files.newOutputStream(
              file,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND);
    }
    return writer;
  }
}
