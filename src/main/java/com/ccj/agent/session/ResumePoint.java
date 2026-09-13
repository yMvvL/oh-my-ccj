package com.ccj.agent.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * The conversation the next process should open, written down before this one goes away.
 *
 * <p>A restart replaces the process on purpose: {@code restart} installs a freshly built jar and the
 * launcher starts it again. The new process opens a new session, because that is what a front end
 * does when nothing tells it otherwise — so the conversation the user was watching vanishes at the
 * exact moment the work they asked for lands. The session file is on disk and resumable, but
 * "resumable" is not the same as "already there", and asking someone to find the row they were just
 * looking at is a worse answer than remembering it for them.
 *
 * <p>This is a note, not a record. It holds one session id, it is overwritten on every restart, and
 * it is read only to answer "where were we". A damaged or missing note is therefore never an error:
 * the worst case is the behaviour a front end had before this existed.
 */
public final class ResumePoint {

  private static final String FILE_NAME = "resume";

  private ResumePoint() {}

  /** Where the note lives for a given home directory. */
  public static Path file(Path home) {
    return home.resolve(FILE_NAME);
  }

  /**
   * Remembers {@code sessionId} for the next process.
   *
   * <p>An id that could not name a session file is refused rather than stored: writing it down would
   * be a promise that the next process can open it, and {@link FileSession#isValidId} is what decides
   * whether that is true.
   */
  public static void write(Path home, String sessionId) {
    if (sessionId == null || !FileSession.isValidId(sessionId)) {
      return;
    }
    Path file = file(home);
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          sessionId + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }

  /** The session to open again, or empty when there is none to open. */
  public static Optional<String> read(Path home) {
    Path file = file(home);
    String text;
    try {
      if (!Files.isRegularFile(file)) {
        return Optional.empty();
      }
      text = Files.readString(file, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      // Unreadable is the same answer as absent: this note never blocks a start.
      return Optional.empty();
    }
    int newline = text.indexOf('\n');
    if (newline >= 0) {
      text = text.substring(0, newline).strip();
    }
    return FileSession.isValidId(text) ? Optional.of(text) : Optional.empty();
  }

  /** Forgets the note, so the next process starts fresh. */
  public static void clear(Path home) {
    try {
      Files.deleteIfExists(file(home));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot delete " + file(home), e);
    }
  }
}
