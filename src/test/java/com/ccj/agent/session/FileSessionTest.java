package com.ccj.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
import com.ccj.agent.core.UsageTotals;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSessionTest {

  @TempDir Path dir;

  @Test
  void appendThenReopenYieldsIdenticalHistory() {
    FileSession session = FileSession.create(dir);
    session.append(new Message.System("You are ccj."));
    session.append(new Message.User("explain 日本語\nwith emoji 🚀"));
    session.append(
        new Message.Assistant(
            "sure", List.of(new Message.ToolCall("call-1", "read", "{\"path\":\"a.txt\"}"))));
    session.append(new Message.ToolResult("call-1", "read", "line\nline", false));
    List<Message> before = session.messages();
    session.close();

    FileSession reopened = FileSession.open(dir, session.id());

    assertEquals(session.id(), reopened.id());
    assertEquals(before, reopened.messages());
  }

  @Test
  void oneMessagePerLineAndAppendContinuesExistingFile() {
    FileSession session = FileSession.create(dir);
    session.append(new Message.User("one"));
    session.append(new Message.User("two"));
    session.close();

    FileSession reopened = FileSession.open(dir, session.id());
    reopened.append(new Message.Assistant("three", List.of()));
    reopened.close();

    FileSession again = FileSession.open(dir, session.id());
    assertEquals(3, again.messages().size());
    assertEquals(List.of(new Message.User("one"), new Message.User("two"), new Message.Assistant("three", List.of())),
        again.messages());
  }

  @Test
  void idIsTimestampPlusRandomSuffix() {
    FileSession session = FileSession.create(dir);

    assertTrue(session.id().matches("\\d{8}-\\d{6}-[0-9a-f]{4}"), session.id());
    assertEquals(dir.resolve(session.id() + ".jsonl"), session.file());
  }

  @Test
  void openingAnUnknownSessionFails() {
    assertThrows(IllegalArgumentException.class, () -> FileSession.open(dir, "20200101-000000-abcd"));
  }

  @Test
  void malformedLineFailsWithItsLineNumber() throws IOException {
    Path file = dir.resolve("20200101-000000-abcd.jsonl");
    Files.writeString(
        file,
        MessageCodec.toJson(new Message.User("ok")) + "\n{oops\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> FileSession.open(dir, "20200101-000000-abcd"));

    assertTrue(error.getMessage().contains(":2:"), error.getMessage());
  }

  @Test
  void aNewSessionLeavesNoFileUntilSomethingIsSaid() {
    Path sessions = dir.resolve("sessions");

    FileSession session = FileSession.create(sessions);

    assertFalse(Files.exists(session.file()), "an id is free; a file is a claim");
    assertFalse(Files.exists(sessions), "not even the directory yet");

    session.append(new Message.User("hello"));

    assertTrue(Files.exists(session.file()));
    assertEquals(1, FileSession.readAll(session.file()).size());
  }

  @Test
  void anEmptySessionNeverAppearsInTheListing() {
    Path sessions = dir.resolve("sessions");

    FileSession.create(sessions);
    FileSession.create(sessions);

    assertEquals(0, SessionStore.list(sessions).size(), "pressing new session must not pile up sessions");

    FileSession second = FileSession.create(sessions);
    second.append(new Message.User("real"));
    assertEquals(1, SessionStore.list(sessions).size());
  }

  @Test
  void totalsSurviveReopeningAndStayOutOfTheConversation() {
    Path sessions = dir.resolve("totals");
    FileSession session = FileSession.create(sessions);
    session.append(new Message.User("hello"));
    session.totals(new UsageTotals(100, 20, 80, 1, 2, 3, 1, 1500, true));
    session.append(new Message.Assistant("hi", List.of()));
    session.close();

    FileSession reopened = FileSession.open(sessions, session.id());

    assertEquals(2, reopened.messages().size(), "an accounting line is not a message");
    assertEquals(new UsageTotals(100, 20, 80, 1, 2, 3, 1, 1500, true), reopened.totals());
    assertEquals(0.8, reopened.totals().cacheHitRate(), 0.001);
    assertEquals(1, SessionStore.list(sessions).size());
    assertEquals(2, SessionStore.list(sessions).get(0).messageCount());
  }

  @Test
  void anUnreportedCacheIsNotZeroPercent() {
    assertEquals(null, UsageTotals.empty().cacheHitRate());
    assertEquals(0.0, new UsageTotals(50, 1, 0, 1, 1, 0, 0, 5, true).cacheHitRate());
  }

  @Test
  void clearingASessionAlsoClearsItsBooks() {
    Path sessions = dir.resolve("cleared");
    FileSession session = FileSession.create(sessions);
    session.append(new Message.User("hello"));
    session.totals(new UsageTotals(10, 1, 5, 1, 1, 0, 0, 10, true));

    session.clear();

    assertEquals(UsageTotals.empty(), session.totals());
    session.close();
    assertEquals(UsageTotals.empty(), FileSession.open(sessions, session.id()).totals());
  }
}
