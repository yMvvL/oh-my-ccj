package com.ccj.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStoreTest {

  @TempDir Path dir;

  @Test
  void listReturnsNewestFirst() throws Exception {
    FileSession older = SessionStore.create(dir);
    older.append(new Message.User("older session"));
    older.close();
    FileSession newer = SessionStore.create(dir);
    newer.append(new Message.User("newer session"));
    newer.append(new Message.Assistant("reply", List.of()));
    newer.close();

    Files.setLastModifiedTime(older.file(), FileTime.fromMillis(1_000_000));
    Files.setLastModifiedTime(newer.file(), FileTime.fromMillis(2_000_000));

    List<SessionStore.Summary> sessions = SessionStore.list(dir);

    assertEquals(List.of(newer.id(), older.id()), sessions.stream().map(SessionStore.Summary::id).toList());
    assertEquals("newer session", sessions.get(0).preview());
    assertEquals(2, sessions.get(0).messageCount());
    assertEquals(1, sessions.get(1).messageCount());
  }

  @Test
  void listOfMissingDirectoryIsEmpty() {
    assertTrue(SessionStore.list(dir.resolve("nope")).isEmpty());
  }

  @Test
  void previewUsesFirstUserMessageAndTruncatesItToASingleLine() {
    FileSession session = SessionStore.create(dir);
    session.append(new Message.System("system first"));
    session.append(new Message.User("a".repeat(120) + "\nsecond line"));
    session.close();

    SessionStore.Summary summary = SessionStore.list(dir).get(0);

    assertEquals(2, summary.messageCount());
    assertTrue(summary.preview().endsWith("…"), summary.preview());
    assertTrue(summary.preview().length() <= 61, summary.preview());
    assertTrue(!summary.preview().contains("\n"), summary.preview());
  }

  @Test
  void sessionWithoutUserMessageGetsPlaceholderPreview() {
    FileSession session = SessionStore.create(dir);
    session.append(new Message.System("only system"));
    session.close();

    assertEquals("(no messages)", SessionStore.list(dir).get(0).preview());
  }

  @Test
  void deletingRemovesOneSessionAndRefusesTraversal() throws IOException {
    Path sessions = Files.createDirectories(dir.resolve("sessions"));
    FileSession keep = FileSession.create(sessions);
    keep.append(new Message.User("keep me"));
    FileSession drop = FileSession.create(sessions);
    drop.append(new Message.User("drop me"));
    assertEquals(2, SessionStore.list(sessions).size());

    assertTrue(SessionStore.delete(sessions, drop.id()));
    assertEquals(
        List.of(keep.id()),
        SessionStore.list(sessions).stream().map(SessionStore.Summary::id).toList());
    assertFalse(SessionStore.delete(sessions, drop.id()), "deleting twice is not an error, just nothing");

    assertThrows(IllegalArgumentException.class, () -> SessionStore.delete(sessions, "../escape"));
    assertThrows(IllegalArgumentException.class, () -> SessionStore.delete(sessions, "/etc/passwd"));
    assertThrows(IllegalArgumentException.class, () -> SessionStore.delete(sessions, null));
    assertEquals(1, SessionStore.list(sessions).size(), "the refusals must not have deleted anything");
  }

  @Test
  void deletingEverythingClearsTheWorkspace() throws IOException {
    Path sessions = Files.createDirectories(dir.resolve("sessions"));
    for (int i = 0; i < 3; i++) {
      FileSession session = FileSession.create(sessions);
      session.append(new Message.User("test residue " + i));
    }
    Files.writeString(sessions.resolve("notes.txt"), "not a session");

    assertEquals(3, SessionStore.deleteAll(sessions));

    assertEquals(0, SessionStore.list(sessions).size());
    assertTrue(Files.exists(sessions.resolve("notes.txt")), "only session files are removed");
    assertEquals(0, SessionStore.deleteAll(sessions));
    assertEquals(0, SessionStore.deleteAll(dir.resolve("missing")));
  }
}
