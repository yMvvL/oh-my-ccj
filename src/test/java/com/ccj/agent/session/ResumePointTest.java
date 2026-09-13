package com.ccj.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The note a process leaves for the next one: which conversation to open again.
 *
 * <p>A restart replaces the process, and a fresh process opens a fresh session — so the work the
 * user was watching disappears from the screen at exactly the moment they asked for it to be
 * updated. This is the one fact that has to survive the handover. It is a plain file in the home
 * directory rather than an environment variable or a flag, because the launcher re-runs the same
 * command and knows nothing about sessions.
 */
class ResumePointTest {

  @TempDir Path home;

  @Test
  void nothingIsRememberedUntilSomethingRemembersIt() {
    assertTrue(ResumePoint.read(home).isEmpty());
  }

  @Test
  void whatWasWrittenIsWhatIsRead() {
    ResumePoint.write(home, "20260913-133000-abcd");

    assertEquals(Optional.of("20260913-133000-abcd"), ResumePoint.read(home));
  }

  @Test
  void writingAgainReplacesThePreviousAnswer() {
    ResumePoint.write(home, "20260913-133000-abcd");
    ResumePoint.write(home, "20260913-134500-1234");

    assertEquals(Optional.of("20260913-134500-1234"), ResumePoint.read(home));
  }

  @Test
  void clearingItMeansNone() {
    ResumePoint.write(home, "20260913-133000-abcd");

    ResumePoint.clear(home);

    assertTrue(ResumePoint.read(home).isEmpty());
    assertFalse(Files.exists(ResumePoint.file(home)));
  }

  @Test
  void aDamagedNoteIsIgnoredRatherThanFatal() throws Exception {
    // The note is a convenience, not a record: a process must never refuse to start because it is
    // unreadable or was overwritten by something else.
    Path file = ResumePoint.file(home);
    Files.writeString(file, "not a session id at all\nsecond line\n", StandardCharsets.UTF_8);
    assertTrue(ResumePoint.read(home).isEmpty());

    Files.writeString(file, "", StandardCharsets.UTF_8);
    assertTrue(ResumePoint.read(home).isEmpty());

    Files.delete(file);
    Files.createDirectories(file);
    assertTrue(ResumePoint.read(home).isEmpty(), "a directory where the note belongs is just absent");
  }

  @Test
  void anIdThatCouldNotBeAFileNameIsNeverStored() {
    // Storing it would be a claim that the next process can open it, and it cannot: an id is a file
    // name, and this one is a path.
    ResumePoint.write(home, "../../etc/passwd");
    ResumePoint.write(home, "");
    ResumePoint.write(home, null);

    assertTrue(ResumePoint.read(home).isEmpty());
    assertFalse(Files.exists(home.getParent().resolve("etc").resolve("passwd")));
  }

  @Test
  void onlyTheFirstLineCounts() throws Exception {
    Files.writeString(
        ResumePoint.file(home), "20260913-133000-abcd\ngarbage after it\n", StandardCharsets.UTF_8);

    assertEquals(Optional.of("20260913-133000-abcd"), ResumePoint.read(home));
  }

  @Test
  void surroundingWhitespaceIsNotPartOfTheId() throws Exception {
    Files.writeString(
        ResumePoint.file(home), "  20260913-133000-abcd  \n", StandardCharsets.UTF_8);

    assertEquals(Optional.of("20260913-133000-abcd"), ResumePoint.read(home));
  }
}
