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
 * <p>A listing still reads what is actually on disk — there is no separate index file to fall out of
 * step with the directory, and deleting a session file by hand remains a supported way to forget it.
 * What is cached is the expensive part of that read: deriving a row's title means parsing the file
 * until the first user message, and the sidebar asks for the whole list after every finished turn.
 * {@link SessionIndex} holds those derivations per file and drops one the moment its file changes, so
 * the cost is paid once per session rather than once per listing.
 */
public final class SessionStore {

  /** Preview length; {@code --list-sessions} has a column for it. */
  private static final int PREVIEW_LIMIT = 60;

  /** Title length: enough of the first request to tell two sessions apart in a sidebar. */
  static final int TITLE_LIMIT = 64;

  /** What a session whose file cannot be read is called in a listing. */
  private static final String UNREADABLE = "(unreadable: the session file is damaged)";

  /**
   * Derivations kept per file, keyed by path.
   *
   * <p>Static because every caller reaches sessions through this class, and a per-instance cache
   * would be defeated by the CLI, the web hub and the tests each holding their own.
   */
  private static final SessionIndex INDEX = new SessionIndex();

  /**
   * One row of {@code --list-sessions}.
   *
   * @param title what the first user message said, for a list that has to be read rather than
   *     scanned by id; empty when no user message was ever written
   * @param preview first user message, flattened to a single truncated line
   * @param lastModified file modification time, which advances on every append
   */
  public record Summary(
      String id, String title, String preview, int messageCount, Instant lastModified, Path file) {

    public Summary {
      id = id == null ? "" : id;
      title = title == null ? "" : title;
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

  /**
   * Newest-first summaries; an absent directory is simply an empty history.
   *
   * <p>Every file is still stat'd on each call, which is what keeps the answer honest: a session that
   * grew, was truncated, or was deleted since the last listing is re-read rather than reported from
   * what used to be true.
   */
  public static List<Summary> list(Path sessionsDir) {
    if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
      return List.of();
    }
    List<Summary> summaries = new ArrayList<>();
    List<Path> present = new ArrayList<>();
    try (Stream<Path> entries = Files.list(sessionsDir)) {
      for (Path file : entries.toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith(FileSession.EXTENSION)) {
          continue;
        }
        String id = name.substring(0, name.length() - FileSession.EXTENSION.length());
        Instant modified;
        long size;
        try {
          modified = Files.getLastModifiedTime(file).toInstant();
          size = Files.size(file);
        } catch (IOException e) {
          // Vanished between the listing and the stat: not a session any more, and reporting a row
          // for a file that is gone is worse than one row fewer.
          INDEX.forget(file);
          continue;
        }
        present.add(file);
        summaries.add(INDEX.summaryFor(file, id, modified, size, SessionStore::derive));
      }
      // A file deleted without a listing in between keeps its derivation until this runs, so the
      // ones that are gone are dropped here rather than growing the map for the life of the process.
      INDEX.retain(sessionsDir, present);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list sessions in " + sessionsDir, e);
    }
    summaries.sort(
        Comparator.comparing(Summary::lastModified)
            .reversed()
            .thenComparing(Summary::id, Comparator.reverseOrder()));
    return List.copyOf(summaries);
  }

  /**
   * How many sessions are in a directory, counted from the directory rather than from
   * {@link #list}.
   *
   * <p>The workspace tree shows this number beside every workspace it knows about, and deriving it by
   * building full summaries would parse every session file on the machine to produce one integer per
   * folder. A directory entry is not a session — a partial file, an editor backup and a stray rename
   * all end up in there — so the extension is what decides.
   */
  public static int count(Path sessionsDir) {
    if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
      return 0;
    }
    int sessions = 0;
    try (Stream<Path> entries = Files.list(sessionsDir)) {
      for (Path file : entries.toList()) {
        if (file.getFileName().toString().endsWith(FileSession.EXTENSION)) {
          sessions++;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list sessions in " + sessionsDir, e);
    }
    return sessions;
  }

  /**
   * The part of a listing that costs a read: how many messages there are and what the first user
   * message said. Reached only when a file is new or has moved since the last time it was asked.
   */
  private static Summary derive(String id, Path file, Instant modified) {
    List<Message> messages;
    try {
      messages = FileSession.readAll(file);
    } catch (IllegalArgumentException e) {
      // One unreadable file must not hide every other session. The file is still a session, and it
      // is still the reason a listing exists — so it is listed as damaged, and opening it reports
      // the same error with the line number it failed on.
      return new Summary(id, UNREADABLE, UNREADABLE, 0, modified, file);
    }
    return new Summary(id, title(messages), preview(messages), messages.size(), modified, file);
  }

  private static String preview(List<Message> messages) {
    for (Message message : messages) {
      if (message instanceof Message.User user) {
        return truncate(user.text());
      }
    }
    return "(no messages)";
  }

  /**
   * The same first user message as {@link #preview}, one line and a little longer: a list keyed by
   * timestamp ids is a list nobody can pick from, so the sidebar reads this instead of the id.
   */
  private static String title(List<Message> messages) {
    for (Message message : messages) {
      if (message instanceof Message.User user) {
        return flatten(user.text(), TITLE_LIMIT);
      }
    }
    return "";
  }

  private static String truncate(String text) {
    String flat = flatten(text, PREVIEW_LIMIT);
    return flat.isEmpty() ? "(empty message)" : flat;
  }

  /** One line, at most {@code limit} characters, with an ellipsis when something was cut. */
  static String flatten(String text, int limit) {
    String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
    return flat.length() <= limit ? flat : flat.substring(0, limit).stripTrailing() + "…";
  }
}
