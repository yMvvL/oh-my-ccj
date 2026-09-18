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

    assertEquals("", SessionStore.list(empty).get(0).title(), "没有用户消息，就没有标题可编");
  }

  @Test
  void titleIsBoundedSoOnePastedProblemCannotBecomeTheRow() {
    FileSession session = SessionStore.create(dir);
    session.append(new Message.User("x".repeat(400)));
    session.close();

    String title = SessionStore.list(dir).get(0).title();

    assertTrue(title.length() <= 65, "标题有 " + title.length() + " 个字符");
    assertTrue(title.endsWith("…"), title);
  }

  @Test
  void sessionWithoutUserMessageGetsPlaceholderPreview() {
    FileSession session = SessionStore.create(dir);
    session.append(new Message.System("only system"));
    session.close();

    assertEquals("（暂无消息）", SessionStore.list(dir).get(0).preview());
  }

  @Test
  void aListingIsServedFromWhatWasDerivedOnceNotOncePerCall() throws IOException {
    // 侧栏在每个回合结束后都会重读这份列表，而推导一行意味着解析文件直到它的第一条用户消息——标题就是
    // 第一条用户消息，所以它位于一个长文件的中间某处。这里钉住的是：对一个没变过的目录再做一次列表，
    // 什么都不会读。
    //
    // 用时间断言在一台没上缓存的快机器上也会通过，所以改成把文件设成不可读：仍然去读它的列表会失败，而
    // 从已有的推导结果端出来的列表则不必去读。stat 对一个没有读权限的文件仍然有效，缓存正是建立在这上面。
    SessionStore.create(dir).append(new Message.User("count the reads"));
    assertEquals("count the reads", SessionStore.list(dir).get(0).title());

    Path file = Files.list(dir).findFirst().orElseThrow();
    Set<PosixFilePermission> readable = Files.getPosixFilePermissions(file);
    Files.setPosixFilePermissions(file, Set.of());
    try {
      for (int i = 0; i < 5; i++) {
        SessionStore.Summary row = SessionStore.list(dir).get(0);
        assertEquals("count the reads", row.title(), "这一行必须来自已经持有的推导结果");
        assertEquals(1, row.messageCount());
      }
    } finally {
      Files.setPosixFilePermissions(file, readable);
    }
  }

  @Test
  void aSessionThatGrewIsReReadEvenWhenItsTimestampDidNotMove() throws IOException {
    // 一条在上一条的同一毫秒内追加的消息会让 mtime 停在原地，所以只按时间缓存的表会永远报告旧的一行。
    // 抓住它的是大小。
    FileSession session = SessionStore.create(dir);
    session.append(new Message.User("first"));
    session.close();
    assertEquals(1, SessionStore.list(dir).get(0).messageCount());

    FileTime modified = Files.getLastModifiedTime(session.file());
    Files.writeString(
        session.file(),
        "{\"type\":\"assistant\",\"text\":\"second\",\"tool_calls\":[]}\n",
        java.nio.file.StandardOpenOption.APPEND);
    Files.setLastModifiedTime(session.file(), modified); // 同一个瞬间，刻意如此

    SessionStore.Summary row = SessionStore.list(dir).get(0);
    assertEquals(2, row.messageCount(), "追加的那条消息必须被数进去");
    assertFalse(row.title().isEmpty());
  }

  @Test
  void aFileChangedOrDeletedUnderTheListingIsBelieved() throws IOException {
    // 这里没有任何东西是第二个真相来源：被手工编辑或手工删除的文件，必须按它现在的样子被报告——正是这个
    // 承诺让 `rm session.jsonl` 成为遗忘一个会话的方式。
    Path file = dir.resolve("20260101-000000-abcd.jsonl");
    Files.writeString(file, "{\"type\":\"user\",\"text\":\"original\"}\n");
    assertEquals("original", SessionStore.list(dir).get(0).title());

    Files.writeString(file, "{\"type\":\"user\",\"text\":\"rewritten by hand\"}\n");
    assertEquals("rewritten by hand", SessionStore.list(dir).get(0).title());

    Files.delete(file);
    assertTrue(SessionStore.list(dir).isEmpty(), "被删掉的文件不再是一个会话");
  }

  @Test
  void theCountBesideAWorkspaceDoesNotParseAnything() throws IOException {
    // 工作区树给每个文件夹显示一个数字，而它过去为了拿到这个数字会构建每一份摘要。
    SessionStore.create(dir).append(new Message.User("one"));
    SessionStore.create(dir).append(new Message.User("two"));
    Files.writeString(dir.resolve("not-a-session.txt"), "ignored");

    assertEquals(2, SessionStore.count(dir));
    assertEquals(0, SessionStore.count(dir.resolve("nowhere")));
    assertEquals(0, SessionStore.count(null));
  }

  @Test
  void oneDamagedFileDoesNotHideTheSessionsBesideIt() throws IOException {
    // 这个格式每条消息追加一行，所以它能遭遇的唯一损坏就是最后一行不完整——而为一个这样的文件让整份列表
    // 失败，会把完全可读的会话藏起来。损坏的那个仍然会以损坏的样子被列出；打开它时仍然会指出那一行。
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
    assertTrue(broken.title().contains("无法读取"), broken.title());
    assertTrue(
        sessions.stream().anyMatch(s -> s.preview().equals("readable session")),
        "可读的会话仍然被列出：" + sessions);
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
    assertFalse(SessionStore.delete(sessions, drop.id()), "删两次不算错误，只是什么都没删");

    assertThrows(IllegalArgumentException.class, () -> SessionStore.delete(sessions, "../escape"));
    assertThrows(IllegalArgumentException.class, () -> SessionStore.delete(sessions, "/etc/passwd"));
    assertThrows(IllegalArgumentException.class, () -> SessionStore.delete(sessions, null));
    assertEquals(1, SessionStore.list(sessions).size(), "这些拒绝不能删掉任何东西");
  }

  @Test
  void aLongSessionIsListedWithoutDecodingEveryMessage() throws Exception {
    // 报告过的缺陷：跑着一个大会话时，侧栏变慢——每次列表都靠解码整个文件重新推导那一行，而正在被写入的
    // 会话每次追加都会变，于是缓存每次都落空。列表需要的是第一条用户消息和一个计数，别的什么都不要。
    Path sessions = Files.createDirectories(dir.resolve("sessions"));
    Path big = sessions.resolve("20260913-000000-big1.jsonl");
    StringBuilder content = new StringBuilder();
    content.append("{\"type\":\"user\",\"text\":\"the first thing asked\"}\n");
    // 一段足以让这件事显形的会话，带着像真实会话那样笨重的工具结果。
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
    assertEquals(10_001, listed.get(0).messageCount(), "每一条消息仍然被数进去");
    // 这个界限刻意留得松——它是回归防线，不是基准测试——但解码一个多兆字节文件里的 1 万条消息会以数量级
    // 的差距冲过去。
    assertTrue(
        elapsedMillis < 500,
        "列出 "
            + bytes
            + " 字节花了 "
            + elapsedMillis
            + "ms；一次列表不能解码整段会话");
  }

  @Test
  void accountingRecordsAreNotCountedAsMessages() throws Exception {
    FileSession session = SessionStore.create(dir);
    session.append(new Message.User("hello"));
    session.append(new Message.Assistant("hi", List.of()));
    session.totals(new UsageTotals(10, 20, 0, 1, 1, 0, 0, 5, false));
    session.close();

    assertEquals(2, SessionStore.list(dir).get(0).messageCount(), "usage 记录不是一条消息");
  }

  @Test
  void deletingASessionTakesItsPicturesWithIt() throws IOException {
    // 图片住在会话旁边，正是为了这句话能成立。被删掉的会话若把照片留在磁盘上，就会长出一个没人列出、
    // 也没人清理的目录。
    Path sessions = Files.createDirectories(dir.resolve("sessions"));
    FileSession kept = FileSession.create(sessions);
    kept.append(new Message.User("keep me"));
    FileSession dropped = FileSession.create(sessions);
    dropped.append(new Message.User("drop me"));

    AttachmentStore store = AttachmentStore.forSession(dropped.file());
    store.save("whiteboard.png", png());
    assertTrue(Files.exists(store.directory()));

    assertTrue(SessionStore.delete(sessions, dropped.id()));

    assertFalse(Files.exists(store.directory()), "图片随这段会话一起走了");
    assertTrue(SessionStore.list(sessions).stream().anyMatch(s -> s.id().equals(kept.id())));

    // 还有批量那条路，也就是工作区的「全部删除」所调用的。
    AttachmentStore other = AttachmentStore.forSession(kept.file());
    other.save("board.png", png());
    assertEquals(1, SessionStore.deleteAll(sessions));
    assertFalse(
        Files.exists(kept.file().getParent().resolve(kept.id() + AttachmentStore.DIRECTORY_SUFFIX)),
        "「全部删除」不是那种忘了图片的会话文件循环");
  }

  /** 真正算是 PNG 的最小东西：存储读的就是 magic number。 */
  private static byte[] png() {
    return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
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
    assertTrue(Files.exists(sessions.resolve("notes.txt")), "只有会话文件被移除");
    assertEquals(0, SessionStore.deleteAll(sessions));
    assertEquals(0, SessionStore.deleteAll(dir.resolve("missing")));
  }
}
