package com.ccj.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
