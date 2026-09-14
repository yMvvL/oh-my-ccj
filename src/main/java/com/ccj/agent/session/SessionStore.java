package com.ccj.agent.session;

import com.ccj.agent.core.Message;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    // Every generation goes, not just the newest: the older ones are this conversation too, and
    // leaving them behind would resurrect the session on the next listing under the same id.
    boolean removed = false;
    try {
      for (int generation : FileSession.generations(sessionsDir, id)) {
        Path file =
            generation == 0
                ? FileSession.fileFor(sessionsDir, id)
                : FileSession.generationFile(sessionsDir, id, generation);
        removed |= Files.deleteIfExists(file);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot delete session " + id, e);
    }
    return removed;
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
        if (!idOf(file.getFileName().toString()).isEmpty()) {
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
   * <p>A session has <em>one</em> row however many generation files it has: a compaction writes a new
   * file but does not create a new session, so listing per file would make every compaction look like
   * a second conversation appearing out of nowhere. The newest generation is the one whose contents,
   * size and timestamp the row reports, because that is the one {@link FileSession#open} reads.
   *
   * <p>Every generation file is still stat'd on each call, which is what keeps the answer honest: a
   * session that grew, was compacted, or was deleted since the last listing is reported as it is now
   * rather than from what used to be true.
   */
  public static List<Summary> list(Path sessionsDir) {
    if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
      return List.of();
    }
    // id -> the newest generation of it, which is the file the row describes.
    Map<String, Path> newest = new LinkedHashMap<>();
    List<Path> present = new ArrayList<>();
    try (Stream<Path> entries = Files.list(sessionsDir)) {
      for (Path file : entries.toList()) {
        String id = idOf(file.getFileName().toString());
        if (id.isEmpty()) {
          continue;
        }
        int generation = generationOf(file.getFileName().toString(), id);
        Path known = newest.get(id);
        if (known == null || generation > generationOf(known.getFileName().toString(), id)) {
          newest.put(id, file);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list sessions in " + sessionsDir, e);
    }

    List<Summary> summaries = new ArrayList<>(newest.size());
    for (Map.Entry<String, Path> entry : newest.entrySet()) {
      String id = entry.getKey();
      Path file = entry.getValue();
      Instant modified;
      long size;
      try {
        modified = Files.getLastModifiedTime(file).toInstant();
        size = Files.size(file);
      } catch (IOException e) {
        // Vanished between the listing and the stat: not a session any more, and reporting a row for a
        // file that is gone is worse than one row fewer.
        INDEX.forget(file);
        continue;
      }
      present.add(file);
      summaries.add(INDEX.summaryFor(file, id, modified, size, SessionStore::derive));
    }
    // A file deleted without a listing in between keeps its derivation until this runs, so the ones
    // that are gone are dropped here rather than growing the map for the life of the process.
    INDEX.retain(sessionsDir, present);
    summaries.sort(
        Comparator.comparing(Summary::lastModified)
            .reversed()
            .thenComparing(Summary::id, Comparator.reverseOrder()));
    return List.copyOf(summaries);
  }

  /**
   * The session id a file name belongs to, or empty when the name is not a session file.
   *
   * <p>Both a plain {@code <id>.jsonl} and a generation {@code <id>.g1.jsonl} map to {@code <id>},
   * which is what lets a listing collapse them into one row.
   */
  static String idOf(String fileName) {
    if (!fileName.endsWith(FileSession.EXTENSION)) {
      return "";
    }
    String stem = fileName.substring(0, fileName.length() - FileSession.EXTENSION.length());
    int marker = stem.lastIndexOf(".g");
    if (marker > 0) {
      String digits = stem.substring(marker + 2);
      if (!digits.isEmpty() && digits.chars().allMatch(Character::isDigit)) {
        stem = stem.substring(0, marker);
      }
    }
    return FileSession.isValidId(stem) ? stem : "";
  }

  /** The generation a session file holds: 0 for the plain name, n for {@code <id>.gn.jsonl}. */
  private static int generationOf(String fileName, String id) {
    String stem = fileName.substring(0, fileName.length() - FileSession.EXTENSION.length());
    if (stem.equals(id)) {
      return 0;
    }
    try {
      return Integer.parseInt(stem.substring(stem.lastIndexOf(".g") + 2));
    } catch (RuntimeException e) {
      return 0;
    }
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
    // Counted by id, so the number beside a workspace is how many conversations there are rather than
    // how many files they occupy — a compacted session is one session, not two.
    Set<String> ids = new HashSet<>();
    try (Stream<Path> entries = Files.list(sessionsDir)) {
      for (Path file : entries.toList()) {
        String id = idOf(file.getFileName().toString());
        if (!id.isEmpty()) {
          ids.add(id);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list sessions in " + sessionsDir, e);
    }
    return ids.size();
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
