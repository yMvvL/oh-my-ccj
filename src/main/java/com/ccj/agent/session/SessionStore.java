package com.ccj.agent.session;

import com.ccj.agent.core.Message;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Entry point for the session directory: create, reopen and enumerate history.
 *
 * <p>Listing is a read of every session file rather than a separate index, which keeps the store
 * honest about what is actually on disk and means deleting a file by hand is a supported way to
 * forget a session.
 */
public final class SessionStore {

  /**
   * One row of {@code --list-sessions}.
   *
   * @param preview first user message, flattened to a single truncated line
   * @param lastModified file modification time, which advances on every append
   */
  public record Summary(
      String id, String preview, int messageCount, Instant lastModified, Path file) {

    public Summary {
      id = id == null ? "" : id;
      preview = preview == null ? "" : preview;
      lastModified = lastModified == null ? Instant.EPOCH : lastModified;
    }
  }

  private SessionStore() {}

  public static FileSession create(Path sessionsDir) {
    return FileSession.create(sessionsDir);
  }

  public static FileSession open(Path sessionsDir, String id) {
    return FileSession.open(sessionsDir, id);
  }

  /** Deletes one session file. Returns false when there was nothing to delete. */
  public static boolean delete(Path sessionsDir, String id) {
    if (!FileSession.isValidId(id)) {
      throw new IllegalArgumentException("invalid session id: " + id);
    }
    try {
      return Files.deleteIfExists(FileSession.fileFor(sessionsDir, id));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot delete session " + id, e);
    }
  }

  /**
   * Deletes every session in one directory — the cleanup path for a pile of test runs.
   *
   * @return how many files were removed
   */
  public static int deleteAll(Path sessionsDir) {
    if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
      return 0;
    }
    int deleted = 0;
    try (Stream<Path> entries = Files.list(sessionsDir)) {
      for (Path file : entries.toList()) {
        if (file.getFileName().toString().endsWith(FileSession.EXTENSION)) {
          Files.deleteIfExists(file);
          deleted++;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot clear sessions in " + sessionsDir, e);
    }
    return deleted;
  }

  /** Newest-first summaries; an absent directory is simply an empty history. */
  public static List<Summary> list(Path sessionsDir) {
    if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
      return List.of();
    }
    List<Summary> summaries = new ArrayList<>();
    try (Stream<Path> entries = Files.list(sessionsDir)) {
      for (Path file : entries.toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith(FileSession.EXTENSION)) {
          continue;
        }
        String id = name.substring(0, name.length() - FileSession.EXTENSION.length());
        List<Message> messages = FileSession.readAll(file);
        summaries.add(
            new Summary(
                id,
                preview(messages),
                messages.size(),
                Files.getLastModifiedTime(file).toInstant(),
                file));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list sessions in " + sessionsDir, e);
    }
    summaries.sort(
        Comparator.comparing(Summary::lastModified)
            .reversed()
            .thenComparing(Summary::id, Comparator.reverseOrder()));
    return List.copyOf(summaries);
  }

  private static String preview(List<Message> messages) {
    for (Message message : messages) {
      if (message instanceof Message.User user) {
        return truncate(user.text());
      }
    }
    return "(no messages)";
  }

  private static String truncate(String text) {
    String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
    if (flat.isEmpty()) {
      return "(empty message)";
    }
    return flat.length() <= 60 ? flat : flat.substring(0, 60).stripTrailing() + "…";
  }
}
