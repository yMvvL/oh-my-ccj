package com.ccj.agent.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The registry decides where sessions live, so its rules are pinned here: the workspace you started
 * in keeps the top-level sessions directory, added ones get their own, and nothing that could lose
 * history happens by accident.
 */
class WorkspaceStoreTest {

  @TempDir Path tmp;

  private Path startDir() throws IOException {
    return Files.createDirectories(tmp.resolve("proj"));
  }

  @Test
  void seedsTheStartingDirectoryAndKeepsTheLegacySessionsDirectory() throws IOException {
    Path start = startDir();

    WorkspaceStore store = WorkspaceStore.open(tmp, start);

    assertEquals("proj", store.activeName());
    assertEquals(start, store.active().path());
    assertEquals(
        tmp.resolve("sessions"),
        store.active().sessionsDir(),
        "a workspace that existed before the registry keeps its conversations where they were");
    assertTrue(Files.isRegularFile(tmp.resolve("workspaces.json")), "the registry is written down");
  }

  @Test
  void addedWorkspacesGetTheirOwnSessionsDirectory() throws IOException {
    WorkspaceStore store = WorkspaceStore.open(tmp, startDir());
    Path project = tmp.resolve("other");

    Workspace added = store.add("other", project);

    assertTrue(Files.isDirectory(project), "adding a workspace creates the directory");
    assertEquals(tmp.resolve("workspaces/other/sessions"), added.sessionsDir());
    assertFalse(Files.exists(added.sessionsDir()), "the sessions directory appears with the first message");
    assertEquals("proj", store.activeName(), "adding does not switch");
  }

  @Test
  void activationIsRememberedAcrossReopening() throws IOException {
    Path start = startDir();
    WorkspaceStore store = WorkspaceStore.open(tmp, start);
    store.add("other", tmp.resolve("other"));
    store.activate("other");

    WorkspaceStore reopened = WorkspaceStore.open(tmp, start);

    assertEquals("other", reopened.activeName());
    assertEquals(List.of("proj", "other"), reopened.names());
    assertEquals(
        reopened.find("proj").orElseThrow().sessionsDir(), tmp.resolve("sessions"),
        "the legacy mapping survives a round trip through the file");
  }

  @Test
  void addingADirectoryNamesTheWorkspaceAfterTheFolder() throws IOException {
    WorkspaceStore store = WorkspaceStore.open(tmp, startDir());
    Path project = Files.createDirectories(tmp.resolve("my-project"));

    Workspace added = store.add(project);

    assertEquals("my-project", added.name(), "the folder names the workspace");
    assertEquals(project, added.path());
    assertEquals(tmp.resolve("workspaces/my-project/sessions"), added.sessionsDir());
    assertEquals("proj", store.activeName(), "adding a picked folder does not switch");
  }

  @Test
  void aTakenFolderNameGetsASuffixInsteadOfAnError() throws IOException {
    WorkspaceStore store = WorkspaceStore.open(tmp, startDir());
    Path one = Files.createDirectories(tmp.resolve("one/api"));
    Path two = Files.createDirectories(tmp.resolve("two/api"));

    assertEquals("api", store.add(one).name());
    assertEquals("api-2", store.add(two).name(), "the second api is not refused, it is suffixed");
    assertEquals(List.of("proj", "api", "api-2"), store.names());

    // The suffix keeps names inside the 40-character ceiling rather than pushing past it.
    Path longPath = Files.createDirectories(tmp.resolve("three/" + "n".repeat(40)));
    assertEquals(40, store.add(longPath).name().length());
    Path longPathTwo = Files.createDirectories(tmp.resolve("four/" + "n".repeat(40)));
    String suffixed = store.add(longPathTwo).name();
    assertTrue(suffixed.length() <= 40 && suffixed.endsWith("-2"), suffixed);
    assertEquals(2, store.names().stream().filter(name -> name.startsWith("n")).count());
  }

  @Test
  void aFolderIsNamedWhateverTheUserCalledIt() throws IOException {
    WorkspaceStore store = WorkspaceStore.open(tmp, startDir());

    // A chosen folder is a real folder, and real folders have spaces and CJK names. Refusing them
    // would make the chooser useless for those people, so what stays out is only what would change
    // what the name means as a path.
    assertEquals("my project", store.add(Files.createDirectories(tmp.resolve("my project"))).name());
    assertEquals(".config", store.add(Files.createDirectories(tmp.resolve(".config"))).name());
    assertEquals("数学", store.add(Files.createDirectories(tmp.resolve("数学"))).name());
    assertEquals("конспект-2026", store.add(Files.createDirectories(tmp.resolve("конспект-2026"))).name());
    assertEquals(List.of("proj", "my project", ".config", "数学", "конспект-2026"), store.names());
  }

  @Test
  void aNameThatWouldChangeWhatThePathMeansIsRefused() throws IOException {
    WorkspaceStore store = WorkspaceStore.open(tmp, startDir());

    // A name is a directory name and an argument: a leading dash reads as a flag, and the separators
    // and the dot entries are the ones that would move where the sessions land. A name that only
    // starts with a dot is a workspace like any other — it is a real folder, with a real name.
    for (String odd : List.of("-dashed", "..", ".", "a/b")) {
      IllegalArgumentException refused =
          assertThrows(
              IllegalArgumentException.class,
              () -> store.add(odd, tmp.resolve("x")),
              "must refuse " + odd);
      assertTrue(refused.getMessage().contains("path separator"), refused.getMessage());
    }
    IllegalArgumentException empty =
        assertThrows(IllegalArgumentException.class, () -> store.add("   ", tmp.resolve("x")));
    assertTrue(empty.getMessage().contains("path separator"), empty.getMessage());
  }

  @Test
  void rejectsBadNamesDuplicatesAndUnusableActiveRemoval() throws IOException {
    WorkspaceStore store = WorkspaceStore.open(tmp, startDir());
    store.add("api", tmp.resolve("api"));

    assertThrows(IllegalArgumentException.class, () -> store.add("a/b", tmp.resolve("x")));
    assertThrows(IllegalArgumentException.class, () -> store.add("", tmp.resolve("x")));
    assertThrows(IllegalArgumentException.class, () -> store.add("api", tmp.resolve("elsewhere")));
    assertThrows(IllegalArgumentException.class, () -> store.activate("nope"));
    assertThrows(IllegalArgumentException.class, () -> store.remove("nope"));
    assertThrows(IllegalArgumentException.class, () -> store.remove("proj"));

    IllegalArgumentException unknown =
        assertThrows(IllegalArgumentException.class, () -> store.activate("nope"));
    assertTrue(unknown.getMessage().contains("proj"), unknown.getMessage());
  }

  @Test
  void removingAWorkspaceLeavesItsConversationsOnDisk() throws IOException {
    WorkspaceStore store = WorkspaceStore.open(tmp, startDir());
    Workspace api = store.add("api", tmp.resolve("api"));
    Files.createDirectories(api.sessionsDir());
    Files.writeString(api.sessionsDir().resolve("20260912-000000-abcd.jsonl"), "");

    store.remove("api");

    assertEquals(List.of("proj"), store.names());
    assertTrue(
        Files.isRegularFile(api.sessionsDir().resolve("20260912-000000-abcd.jsonl")),
        "forgetting a workspace must not delete history");
  }

  @Test
  void aCorruptEntryIsSkippedRatherThanFatal() throws IOException {
    Path start = startDir();
    WorkspaceStore.open(tmp, start);
    Files.writeString(
        tmp.resolve("workspaces.json"),
        """
        {"active": "proj",
         "workspaces": [
           {"name": "proj", "path": "%s", "sessions": "sessions"},
           {"name": "-dashed", "path": "/nope"},
           {"name": "noPath"}
         ]}
        """
            .formatted(start));

    WorkspaceStore reopened = WorkspaceStore.open(tmp, start);

    assertEquals(List.of("proj"), reopened.names(), "a usable entry survives, the rest are ignored");
  }

  @Test
  void anEmptyRegistryIsReseeded() throws IOException {
    Path start = startDir();
    Files.writeString(tmp.resolve("workspaces.json"), "{\"workspaces\": []}");

    WorkspaceStore store = WorkspaceStore.open(tmp, start);

    assertEquals("proj", store.activeName());
  }
}
