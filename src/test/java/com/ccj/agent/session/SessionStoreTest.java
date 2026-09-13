package com.ccj.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
import com.ccj.agent.core.UsageTotals;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
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
  void titleIsTheFirstUserMessageOnOneLineAndIsEmptyWithoutOne() throws IOException {
    FileSession named = SessionStore.create(dir);
    named.append(new Message.System("system first"));
    named.append(new Message.User("  rename the session\n   list to say what each task was  "));
    named.close();

    SessionStore.Summary summary = SessionStore.list(dir).get(0);
    assertEquals("rename the session list to say what each task was", summary.title());

    Path empty = Files.createDirectories(dir.resolve("elsewhere"));
    FileSession systemOnly = FileSession.create(empty);
    systemOnly.append(new Message.System("only system"));
    systemOnly.close();

    assertEquals("", SessionStore.list(empty).get(0).title(), "no user message, no title to invent");
  }

  @Test
  void titleIsBoundedSoOnePastedProblemCannotBecomeTheRow() {
    FileSession session = SessionStore.create(dir);
    session.append(new Message.User("x".repeat(400)));
    session.close();

    String title = SessionStore.list(dir).get(0).title();

    assertTrue(title.length() <= 65, "title is " + title.length() + " chars");
    assertTrue(title.endsWith("…"), title);
  }

  @Test
  void sessionWithoutUserMessageGetsPlaceholderPreview() {
    FileSession session = SessionStore.create(dir);
    session.append(new Message.System("only system"));
    session.close();

    assertEquals("(no messages)", SessionStore.list(dir).get(0).preview());
  }

  @Test
  void aListingIsServedFromWhatWasDerivedOnceNotOncePerCall() throws IOException {
    // The sidebar re-reads this list after every finished turn, and deriving a row means parsing the
    // file until its first user message — the title is the first user message, so it is somewhere in
    // the middle of a long file. What is pinned here is that a second listing of an unchanged
    // directory reads nothing at all.
    //
    // A timing assertion would pass on a fast machine with no cache, so the file is made unreadable
    // instead: a listing that still reads it fails, and a listing served from the derivation it
    // already has does not have to. stat still works on a file with no read permission, which is
    // exactly what the cache is built on.
    SessionStore.create(dir).append(new Message.User("count the reads"));
    assertEquals("count the reads", SessionStore.list(dir).get(0).title());

    Path file = Files.list(dir).findFirst().orElseThrow();
    Set<PosixFilePermission> readable = Files.getPosixFilePermissions(file);
    Files.setPosixFilePermissions(file, Set.of());
    try {
      for (int i = 0; i < 5; i++) {
        SessionStore.Summary row = SessionStore.list(dir).get(0);
        assertEquals("count the reads", row.title(), "the row must come from the derivation already held");
        assertEquals(1, row.messageCount());
      }
    } finally {
      Files.setPosixFilePermissions(file, readable);
    }
  }

  @Test
  void aSessionThatGrewIsReReadEvenWhenItsTimestampDidNotMove() throws IOException {
    // A message appended within the same millisecond as the last one leaves mtime where it was, so a
    // cache keyed on time alone would report the old row forever. Size is what catches it.
    FileSession session = SessionStore.create(dir);
    session.append(new Message.User("first"));
    session.close();
    assertEquals(1, SessionStore.list(dir).get(0).messageCount());

    FileTime modified = Files.getLastModifiedTime(session.file());
    Files.writeString(
        session.file(),
        "{\"type\":\"assistant\",\"text\":\"second\",\"tool_calls\":[]}\n",
        java.nio.file.StandardOpenOption.APPEND);
    Files.setLastModifiedTime(session.file(), modified); // the same instant, deliberately

    SessionStore.Summary row = SessionStore.list(dir).get(0);
    assertEquals(2, row.messageCount(), "the appended message must be counted");
    assertFalse(row.title().isEmpty());
  }

  @Test
  void aFileChangedOrDeletedUnderTheListingIsBelieved() throws IOException {
    // Nothing here is a second source of truth: a file edited by hand, or removed by hand, has to be
    // reported as it is now — that is the promise that makes `rm session.jsonl` a way to forget one.
    Path file = dir.resolve("20260101-000000-abcd.jsonl");
    Files.writeString(file, "{\"type\":\"user\",\"text\":\"original\"}\n");
    assertEquals("original", SessionStore.list(dir).get(0).title());

    Files.writeString(file, "{\"type\":\"user\",\"text\":\"rewritten by hand\"}\n");
    assertEquals("rewritten by hand", SessionStore.list(dir).get(0).title());

    Files.delete(file);
    assertTrue(SessionStore.list(dir).isEmpty(), "a deleted file is not a session any more");
  }

  @Test
  void theCountBesideAWorkspaceDoesNotParseAnything() throws IOException {
    // The workspace tree shows a number per folder, and it used to build every summary to get it.
    SessionStore.create(dir).append(new Message.User("one"));
    SessionStore.create(dir).append(new Message.User("two"));
    Files.writeString(dir.resolve("not-a-session.txt"), "ignored");

    assertEquals(2, SessionStore.count(dir));
    assertEquals(0, SessionStore.count(dir.resolve("nowhere")));
    assertEquals(0, SessionStore.count(null));
  }

  @Test
  void oneDamagedFileDoesNotHideTheSessionsBesideIt() throws IOException {
    // The format appends one line per message, so the only corruption it can suffer is a partial
    // last line — and failing a whole listing over one would hide sessions that are perfectly
    // readable. The damaged one is still shown, as damaged; opening it still names the line.
    FileSession readable = SessionStore.create(dir);
    readable.append(new Message.User("readable session"));
    readable.close();
    FileSession damaged = SessionStore.create(dir);
    damaged.append(new Message.User("this one gets cut mid-line"));
    damaged.close();
    String lines = Files.readString(damaged.file());
    Files.writeString(damaged.file(), lines.substring(0, lines.length() - 12));

    List<SessionStore.Summary> sessions = SessionStore.list(dir);

    assertEquals(2, sessions.size(), sessions.toString());
    SessionStore.Summary broken =
        sessions.stream().filter(s -> s.id().equals(damaged.id())).findFirst().orElseThrow();
    assertTrue(broken.title().contains("unreadable"), broken.title());
    assertTrue(
        sessions.stream().anyMatch(s -> s.preview().equals("readable session")),
        "the readable session is still listed: " + sessions);
    assertThrows(IllegalArgumentException.class, () -> FileSession.open(dir, damaged.id()));
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
  void aLongSessionIsListedWithoutDecodingEveryMessage() throws Exception {
    // Reported bug: with a big session running, the sidebar got slow — every listing re-derived the
    // row by decoding the whole file, and a session being written changes on every append, so the
    // cache missed every time. A listing needs the first user message and a count, and nothing else.
    Path sessions = Files.createDirectories(dir.resolve("sessions"));
    Path big = sessions.resolve("20260913-000000-big1.jsonl");
    StringBuilder content = new StringBuilder();
    content.append("{\"type\":\"user\",\"text\":\"the first thing asked\"}\n");
    // A conversation of the size that made this visible, with bulky tool results like a real one.
    for (int i = 0; i < 5_000; i++) {
      content
          .append("{\"type\":\"assistant\",\"text\":\"step ")
          .append(i)
          .append("\",\"tool_calls\":[]}\n");
      content
          .append("{\"type\":\"tool_result\",\"tool_call_id\":\"c")
          .append(i)
          .append("\",\"tool_name\":\"bash\",\"content\":\"")
          .append("output ".repeat(200))
          .append("\",\"error\":false}\n");
    }
    Files.writeString(big, content.toString());
    long bytes = Files.size(big);

    long started = System.nanoTime();
    List<SessionStore.Summary> listed = SessionStore.list(sessions);
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

    assertEquals(1, listed.size());
    assertEquals("the first thing asked", listed.get(0).title());
    assertEquals(10_001, listed.get(0).messageCount(), "every message is still counted");
    // The bound is loose on purpose — this is a regression guard, not a benchmark — but decoding
    // 10k messages of a multi-megabyte file would blow past it by an order of magnitude.
    assertTrue(
        elapsedMillis < 500,
        "listing "
            + bytes
            + " bytes took "
            + elapsedMillis
            + "ms; a listing must not decode the conversation");
  }

  @Test
  void accountingRecordsAreNotCountedAsMessages() throws Exception {
    FileSession session = SessionStore.create(dir);
    session.append(new Message.User("hello"));
    session.append(new Message.Assistant("hi", List.of()));
    session.totals(new UsageTotals(10, 20, 0, 1, 1, 0, 0, 5, false));
    session.close();

    assertEquals(2, SessionStore.list(dir).get(0).messageCount(), "the usage record is not a message");
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
