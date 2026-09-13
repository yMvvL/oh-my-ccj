package com.ccj.agent.session;

import com.ccj.agent.core.Message;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the sidebar needs about every session, without reading every session.
 *
 * <p>Listing used to parse every session file to find its title, which is the first user message and
 * therefore somewhere in the middle of the file. That is linear in the number of bytes on disk, and
 * the page re-reads the list after every finished turn — so a directory of long sessions made every
 * turn end with a stall measured in hundreds of milliseconds, on a path nobody could see.
 *
 * <p>This is a cache, not a second source of truth. Nothing here reaches the disk: a session the
 * index has never been told about is read once (by {@link SessionStore}) and then remembered, and an
 * entry is dropped the moment its file changes under it — which is what keeps a file deleted or
 * truncated by hand behaving exactly as it did before, since the answer the index would have given
 * no longer matches what is there.
 *
 * <p>The check is a file's modification time and size together, not either alone: a session that
 * receives a message and is written in the same millisecond keeps its mtime, and only the size
 * betrays it. Both are a stat, which is what makes this worth doing at all.
 *
 * <p>Not thread-safe by accident: the map is concurrent because a browser refresh and a finished turn
 * can ask at the same time, and the value under a key is immutable.
 */
final class SessionIndex {

  /** Everything a row needs: the file it describes and what the listing derived from it. */
  record Entry(Instant modified, long size, SessionStore.Summary summary) {}

  private final Map<Path, Entry> entries = new ConcurrentHashMap<>();

  /**
   * The summary for {@code file}, from the index when the file has not moved, and from
   * {@code derive} otherwise — which is the only path that reads the file.
   *
   * @param id the session id, which the file name no longer has to be parsed for
   * @param derive reads the file and builds its summary; called at most once per change
   */
  SessionStore.Summary summaryFor(Path file, String id, Instant modified, long size, Derive derive) {
    Entry known = entries.get(file);
    if (known != null && known.modified().equals(modified) && known.size() == size) {
      return known.summary();
    }
    SessionStore.Summary summary = derive.derive(id, file, modified);
    entries.put(file, new Entry(modified, size, summary));
    return summary;
  }

  /** Forgets {@code file}, so the next listing reads it again. */
  void forget(Path file) {
    entries.remove(file);
  }

  /**
   * Forgets everything under {@code directory} that is no longer on disk, so a session deleted while
   * a listing is not running does not keep its row alive through a stale entry.
   */
  void retain(Path directory, List<Path> present) {
    if (entries.isEmpty()) {
      return;
    }
    List<Path> live = new ArrayList<>(present);
    entries.keySet().removeIf(path -> path.startsWith(directory) && !live.contains(path));
  }

  /** Builds the summary a missing entry needs; separated so tests can count how often it runs. */
  @FunctionalInterface
  interface Derive {
    SessionStore.Summary derive(String id, Path file, Instant modified);
  }

  /** The messages a listing needs from a file, and nothing more than the first user message. */
  static String titleOf(List<Message> messages) {
    for (Message message : messages) {
      if (message instanceof Message.User user) {
        return SessionStore.flatten(user.text(), SessionStore.TITLE_LIMIT);
      }
    }
    return "";
  }
}
