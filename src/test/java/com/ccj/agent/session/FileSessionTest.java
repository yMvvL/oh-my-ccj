package com.ccj.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
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
}
