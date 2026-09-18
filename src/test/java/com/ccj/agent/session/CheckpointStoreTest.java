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
 * What a turn changed, and how far back it can be put.
 *
 * <p>The tests are about the two questions a checkpoint has to answer honestly: does undo restore
 * exactly the state the turn started from — including deleting a file the turn created — and does it
 * ever claim a state it does not hold.
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
    assertEquals("before\n", Files.readString(file), "the file is as the turn found it");
    assertEquals(0, store.undoableTurns(sessionFile), "and that turn is spent");
  }

  @Test
  void aFileTheTurnCreatedIsDeletedRatherThanEmptied() throws IOException {
    // "Put back what was there" is a different operation for a file that was not there: writing an
    // empty file would leave the workspace with a file nobody created.
    open();
    Path file = project.resolve("New.java");

    store.beginTurn(sessionFile, project);
    store.record(file, null);
    Files.writeString(file, "class New {}\n");
    store.endTurn();

    store.undoLastTurn(sessionFile, project);

    assertFalse(Files.exists(file), "a file the turn created is gone");
  }

  @Test
  void theStateThatMattersIsTheOneTheTurnStartedWith() throws IOException {
    // A file edited three times in one turn has one interesting previous state, and it is the first.
    // Recording each write would make undo restore the second edit, which is not "undo the turn".
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
    assertEquals(List.of(), store.undoLastTurn(sessionFile, project), "and then there is nothing");
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
    assertEquals("changed\n", Files.readString(outside), "nothing was recorded, so nothing was put back");
  }

  @Test
  void aWriteWithNoTurnOpenRecordsNothing() throws IOException {
    // The tools run on whatever thread the turn is on; a write from somewhere else — a background
    // thread, a sub-agent — must not land in another conversation's checkpoint.
    open();
    Path file = project.resolve("Foo.java");
    Files.writeString(file, "before\n");

    store.record(file, "before\n");

    assertEquals(0, store.undoableTurns(sessionFile), "no turn, no snapshot");
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
        "a checkpoint directory that grows without limit is a disk nobody is watching");
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

    assertEquals(0, store.undoableTurns(sessionFile), "a checkpoint of a huge file is not worth keeping");
    assertEquals(List.of(), store.undoLastTurn(sessionFile, project));
  }
}
