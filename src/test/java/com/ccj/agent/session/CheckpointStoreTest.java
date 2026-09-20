package com.ccj.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 一个回合改了什么，以及能往回放多远。
 *
 * <p>这些测试围绕检查点必须诚实回答的两个问题：undo 是否精确恢复了回合开始时的状态——包括删掉回合创建
 * 的文件——以及它是否声称过自己并不持有的状态。
 */
class CheckpointStoreTest {

  @TempDir Path tmp;

  private Path project;
  private Path sessionFile;
  private CheckpointStore store;

  private void open() throws IOException {
    project = Files.createDirectories(tmp.resolve("project"));
    Path sessions = Files.createDirectories(tmp.resolve("sessions"));
    sessionFile = sessions.resolve("20260919-000000-abcd.jsonl");
    Files.writeString(sessionFile, "");
    store = CheckpointStore.recording();
  }

  @Test
  void undoPutsBackWhatTheTurnChanged() throws IOException {
    open();
    Path file = project.resolve("src/Foo.java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "before\n");

    store.beginTurn(sessionFile, project);
    store.record(file, "before\n");
    Files.writeString(file, "after\n");
    store.endTurn();

    assertEquals(List.of("src/Foo.java"), store.undoLastTurn(sessionFile, project));
    assertEquals("before\n", Files.readString(file), "文件是回合发现它时的样子");
    assertEquals(0, store.undoableTurns(sessionFile), "而那个回合就此用掉");
  }

  @Test
  void aFileTheTurnCreatedIsDeletedRatherThanEmptied() throws IOException {
    // 「把原来就在的东西放回去」对一个本来不存在的文件是另一回事：写一个空文件会让工作区里留下一个没人
    // 创建过的文件。
    open();
    Path file = project.resolve("New.java");

    store.beginTurn(sessionFile, project);
    store.record(file, null);
    Files.writeString(file, "class New {}\n");
    store.endTurn();

    store.undoLastTurn(sessionFile, project);

    assertFalse(Files.exists(file), "回合创建的文件不在了");
  }

  @Test
  void theStateThatMattersIsTheOneTheTurnStartedWith() throws IOException {
    // 一个在回合里被改了三次的文件，只有一个有意思的先前状态，那就是第一个。逐个记录每次写入会让 undo
    // 恢复成第二次编辑，那不是「撤销这个回合」。
    open();
    Path file = project.resolve("Foo.java");
    Files.writeString(file, "v0\n");

    store.beginTurn(sessionFile, project);
    store.record(file, "v0\n");
    Files.writeString(file, "v1\n");
    store.record(file, "v1\n");
    Files.writeString(file, "v2\n");
    store.endTurn();

    store.undoLastTurn(sessionFile, project);

    assertEquals("v0\n", Files.readString(file));
  }

  @Test
  void undoGoesBackOneTurnAtATime() throws IOException {
    open();
    Path file = project.resolve("Foo.java");
    Files.writeString(file, "first\n");

    store.beginTurn(sessionFile, project);
    store.record(file, "first\n");
    Files.writeString(file, "second\n");
    store.endTurn();

    store.beginTurn(sessionFile, project);
    store.record(file, "second\n");
    Files.writeString(file, "third\n");
    store.endTurn();

    assertEquals(2, store.undoableTurns(sessionFile));
    store.undoLastTurn(sessionFile, project);
    assertEquals("second\n", Files.readString(file));
    store.undoLastTurn(sessionFile, project);
    assertEquals("first\n", Files.readString(file));
    assertEquals(List.of(), store.undoLastTurn(sessionFile, project), "然后就什么都没有了");
  }

  @Test
  void aFileOutsideTheProjectIsNotThisConversationsToUndo() throws IOException {
    open();
    Path outside = tmp.resolve("elsewhere/Secret.java");
    Files.createDirectories(outside.getParent());
    Files.writeString(outside, "mine\n");

    store.beginTurn(sessionFile, project);
    store.record(outside, "mine\n");
    Files.writeString(outside, "changed\n");
    store.endTurn();

    assertEquals(List.of(), store.undoLastTurn(sessionFile, project));
    assertEquals("changed\n", Files.readString(outside), "什么都没记录，所以什么都没放回去");
  }

  @Test
  void aWriteWithNoTurnOpenRecordsNothing() throws IOException {
    // 工具跑在回合所在的那条线程上；来自别处的写入——后台线程、子代理——不能落进另一段会话的检查点。
    open();
    Path file = project.resolve("Foo.java");
    Files.writeString(file, "before\n");

    store.record(file, "before\n");

    assertEquals(0, store.undoableTurns(sessionFile), "没有回合，就没有快照");
  }

  @Test
  void theLastTwentyTurnsAreKeptAndOlderOnesGo() throws IOException {
    open();
    Path file = project.resolve("Foo.java");

    for (int turn = 0; turn < 25; turn++) {
      Files.writeString(file, "turn " + turn + "\n");
      store.beginTurn(sessionFile, project);
      store.record(file, "turn " + turn + "\n");
      store.endTurn();
    }

    assertEquals(
        CheckpointStore.KEEP_TURNS,
        store.undoableTurns(sessionFile),
        "一个无限增长的检查点目录，是一块没人在看的磁盘");
  }

  @Test
  void aTurnThatChangedNothingLeavesNothingBehind() throws IOException {
    open();

    store.beginTurn(sessionFile, project);
    store.endTurn();

    assertEquals(0, store.undoableTurns(sessionFile));
  }

  @Test
  void anOversizedFileIsNotSnapshottedAndSaysSoByOmittingIt() throws IOException {
    open();
    Path file = project.resolve("huge.txt");
    String big = "x".repeat((int) CheckpointStore.MAX_FILE_BYTES + 1);
    Files.writeString(file, big);

    store.beginTurn(sessionFile, project);
    store.record(file, big);
    store.endTurn();

    assertEquals(0, store.undoableTurns(sessionFile), "巨大文件的检查点不值得留");
    assertEquals(List.of(), store.undoLastTurn(sessionFile, project));
  }

  @Test
  void previewOfNothingUndoableIsAnEmptyListAndZeroTurns() throws IOException {
    open();

    ObjectNode preview = store.previewUndo(sessionFile, project);

    assertEquals(List.of(), changes(preview), "没有回合，就没有会动的文件");
    assertEquals(0, preview.path("turns").asInt());
    assertFalse(
        Files.exists(CheckpointStore.directoryFor(sessionFile)),
        "看一眼不该在磁盘上留下检查点目录");
  }

  @Test
  void previewNamesEachFileAndWhatWillHappenToIt() throws IOException {
    open();
    Path existing = project.resolve("notes.txt");
    Files.writeString(existing, "before\n");
    Path created = project.resolve("src/New.java");
    Files.createDirectories(created.getParent());

    store.beginTurn(sessionFile, project);
    store.record(existing, "before\n");
    Files.writeString(existing, "after\n");
    store.record(created, null);
    Files.writeString(created, "class New {}\n");
    store.endTurn();

    ObjectNode preview = store.previewUndo(sessionFile, project);

    assertEquals(List.of("notes.txt:restore", "src/New.java:delete"), changes(preview));
    assertEquals(1, preview.path("turns").asInt(), "这一次也算在里面");
  }

  @Test
  void previewCountsTheTurnItDescribesAndTheOlderOnesBehindIt() throws IOException {
    open();
    Path file = project.resolve("notes.txt");
    for (String turn : List.of("one", "two")) {
      Files.writeString(file, turn + "\n");
      store.beginTurn(sessionFile, project);
      store.record(file, turn + "\n");
      Files.writeString(file, turn + "-after\n");
      store.endTurn();
    }

    ObjectNode preview = store.previewUndo(sessionFile, project);

    assertEquals(2, preview.path("turns").asInt(), "这一次，加上它后面还剩下的那一次");
    assertEquals(List.of("notes.txt:restore"), changes(preview), "清单只说最新那个回合");
  }

  @Test
  void previewLeavesTheDiskAloneAndAgreesWithTheUndoThatFollows() throws IOException {
    open();
    Path file = project.resolve("notes.txt");
    Files.writeString(file, "before\n");

    store.beginTurn(sessionFile, project);
    store.record(file, "before\n");
    Files.writeString(file, "after\n");
    store.endTurn();

    List<String> untouched = snapshot(tmp);
    ObjectNode preview = store.previewUndo(sessionFile, project);

    assertEquals(untouched, snapshot(tmp), "预览不改磁盘上的任何一个字节，也不建目录");
    assertEquals(List.of("notes.txt:restore"), changes(preview));
    assertEquals(List.of("notes.txt"), store.undoLastTurn(sessionFile, project), "退回做了预览说过的事");
    assertEquals("before\n", Files.readString(file));
    assertEquals(
        0,
        store.previewUndo(sessionFile, project).path("turns").asInt(),
        "退回了，预览也就不再承诺什么");
  }

  @Test
  void previewDoesNotPromiseAFileUndoWouldSkip() throws IOException {
    // 会话目录之外的文件不归这个对话退回；清单要是列了它，人就得到按下按钮之后才发现有些东西没回来。
    open();
    Path outside = tmp.resolve("elsewhere/Secret.java");
    Files.createDirectories(outside.getParent());
    Files.writeString(outside, "mine\n");

    store.beginTurn(sessionFile, project);
    store.record(outside, "mine\n");
    Files.writeString(outside, "changed\n");
    store.endTurn();

    assertEquals(List.of(), changes(store.previewUndo(sessionFile, project)));
  }

  /** 清单里的每个条目读成 {@code path:action}，好让断言一眼看得出会动什么。 */
  private static List<String> changes(JsonNode preview) {
    List<String> changes = new ArrayList<>();
    for (JsonNode file : preview.path("files")) {
      changes.add(file.path("path").asText() + ":" + file.path("action").asText());
    }
    return changes;
  }

  /** 目录树上的每个路径，连同大小与修改时间——预览必须让这些一个字都不变。 */
  private static List<String> snapshot(Path root) throws IOException {
    try (var walk = Files.walk(root)) {
      List<String> entries = new ArrayList<>();
      for (Path path : walk.toList()) {
        String state = Files.isDirectory(path) ? "dir" : Files.size(path) + "B";
        entries.add(
            root.relativize(path) + " " + state + " " + Files.getLastModifiedTime(path).toMillis());
      }
      entries.sort(null);
      return entries;
    }
  }
}
