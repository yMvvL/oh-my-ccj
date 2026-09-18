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
 * 登记表决定会话住在哪里，所以它的规则钉在这里：你启动时所在的工作区保留顶层的会话目录，后来添加的拿到
 * 自己的目录，而任何可能弄丢历史的事都不会偶然发生。
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
        "登记表存在之前就有工作区，它的会话留在原地");
    assertTrue(Files.isRegularFile(tmp.resolve("workspaces.json")), "登记表被写下来了");
  }

  @Test
  void addedWorkspacesGetTheirOwnSessionsDirectory() throws IOException {
    WorkspaceStore store = WorkspaceStore.open(tmp, startDir());
    Path project = tmp.resolve("other");

    Workspace added = store.add("other", project);

    assertTrue(Files.isDirectory(project), "添加一个工作区会创建它的目录");
    assertEquals(tmp.resolve("workspaces/other/sessions"), added.sessionsDir());
    assertFalse(Files.exists(added.sessionsDir()), "会话目录随第一条消息出现");
    assertEquals("proj", store.activeName(), "添加不会切换");
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
        "旧的映射经得起在文件里转一趟");
  }

  @Test
  void addingADirectoryNamesTheWorkspaceAfterTheFolder() throws IOException {
    WorkspaceStore store = WorkspaceStore.open(tmp, startDir());
    Path project = Files.createDirectories(tmp.resolve("my-project"));

    Workspace added = store.add(project);

    assertEquals("my-project", added.name(), "文件夹给工作区命名");
    assertEquals(project, added.path());
    assertEquals(tmp.resolve("workspaces/my-project/sessions"), added.sessionsDir());
    assertEquals("proj", store.activeName(), "添加一个挑出来的文件夹不会切换");
  }

  @Test
  void aTakenFolderNameGetsASuffixInsteadOfAnError() throws IOException {
    WorkspaceStore store = WorkspaceStore.open(tmp, startDir());
    Path one = Files.createDirectories(tmp.resolve("one/api"));
    Path two = Files.createDirectories(tmp.resolve("two/api"));

    assertEquals("api", store.add(one).name());
    assertEquals("api-2", store.add(two).name(), "第二个 api 没有被拒绝，只是加了后缀");
    assertEquals(List.of("proj", "api", "api-2"), store.names());

    // 后缀让名字留在这个 40 字符的天花板之内，而不是顶出去。
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

    // 挑中的文件夹是真实的文件夹，而真实文件夹有空格和 CJK 名字。拒绝它们会让选择器对这些人毫无用处，
    // 所以挡在外面的只有会改变名字作为路径的含义的那些。
    assertEquals("my project", store.add(Files.createDirectories(tmp.resolve("my project"))).name());
    assertEquals(".config", store.add(Files.createDirectories(tmp.resolve(".config"))).name());
    assertEquals("数学", store.add(Files.createDirectories(tmp.resolve("数学"))).name());
    assertEquals("конспект-2026", store.add(Files.createDirectories(tmp.resolve("конспект-2026"))).name());
    assertEquals(List.of("proj", "my project", ".config", "数学", "конспект-2026"), store.names());
  }

  @Test
  void aNameThatWouldChangeWhatThePathMeansIsRefused() throws IOException {
    WorkspaceStore store = WorkspaceStore.open(tmp, startDir());

    // 名字既是一个目录名也是一个参数：开头的短横会被读成开关，而分隔符和点目录正是会把会话落点挪走的
    // 那些。只以点开头的名字和别的工作区一样——它是一个真实的文件夹，有一个真实的名字。
    for (String odd : List.of("-dashed", "..", ".", "a/b")) {
      IllegalArgumentException refused =
          assertThrows(
              IllegalArgumentException.class,
              () -> store.add(odd, tmp.resolve("x")),
              "必须拒绝 " + odd);
      assertTrue(refused.getMessage().contains("路径分隔符"), refused.getMessage());
    }
    IllegalArgumentException empty =
        assertThrows(IllegalArgumentException.class, () -> store.add("   ", tmp.resolve("x")));
    assertTrue(empty.getMessage().contains("路径分隔符"), empty.getMessage());
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
        "忘掉一个工作区不能删掉历史");
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

    assertEquals(List.of("proj"), reopened.names(), "可用的条目活了下来，其余的被忽略");
  }

  @Test
  void anEmptyRegistryIsReseeded() throws IOException {
    Path start = startDir();
    Files.writeString(tmp.resolve("workspaces.json"), "{\"workspaces\": []}");

    WorkspaceStore store = WorkspaceStore.open(tmp, start);

    assertEquals("proj", store.activeName());
  }
}
