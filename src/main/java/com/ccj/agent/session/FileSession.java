package com.ccj.agent.session;

import com.ccj.agent.core.Message;
import com.ccj.agent.core.Session;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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

  private final String id;
  private final Path file;
  private final List<Message> history;
  private Writer writer;

  public FileSession(String id, Path file, List<Message> history) {
    this.id = id;
    this.file = file;
    this.history = new ArrayList<>(history == null ? List.of() : history);
  }

  /** Generates a fresh id and creates its (empty) file under {@code sessionsDir}. */
  public static FileSession create(Path sessionsDir) {
    String id = newId();
    Path file = fileFor(sessionsDir, id);
    try {
      Files.createDirectories(sessionsDir);
      Files.writeString(
          file,
          "",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot create session file " + file, e);
    }
    return new FileSession(id, file, List.of());
  }

  /** Reopens an existing session, restoring its full history. */
  public static FileSession open(Path sessionsDir, String id) {
    if (id == null || !SAFE_ID.matcher(id).matches()) {
      throw new IllegalArgumentException("invalid session id: " + id);
    }
    Path file = fileFor(sessionsDir, id);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("no session '" + id + "' in " + sessionsDir);
    }
    return new FileSession(id, file, readAll(file));
  }

  /** Timestamp plus a random suffix, so sessions created in the same second stay distinct. */
  public static String newId() {
    return ID_TIME.format(LocalDateTime.now())
        + "-"
        + String.format("%04x", RANDOM.nextInt(0x10000));
  }

  public static Path fileFor(Path sessionsDir, String id) {
    return sessionsDir.resolve(id + EXTENSION);
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
      if (line.isBlank()) {
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
    try {
      Writer out = writer();
      out.write(MessageCodec.toJson(message));
      out.write('\n');
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot append to session file " + file, e);
    }
  }

  /** Empties the conversation on disk and in memory; the session id survives. */
  public void clear() {
    close();
    history.clear();
    try {
      Files.writeString(
          file,
          "",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot clear session file " + file, e);
    }
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

  private Writer writer() throws IOException {
    if (writer == null) {
      Files.createDirectories(file.getParent());
      writer =
          Files.newBufferedWriter(
              file,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND);
    }
    return writer;
  }
}
