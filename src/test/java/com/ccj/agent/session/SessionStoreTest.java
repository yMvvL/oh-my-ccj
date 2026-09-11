package com.ccj.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
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
}
