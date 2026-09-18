package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChecksTest {

  @TempDir Path tmp;

  private Path config(String json) throws IOException {
    Path file = tmp.resolve("config.json");
    Files.writeString(file, json);
    return file;
  }

  @Test
  void aCheckAppliesToTheFilesItsGlobMatches() throws IOException {
    Path file =
        config(
            """
            {"checks": [{"glob": "**/*.java", "command": "mvn -q -o -DskipTests compile"}]}
            """);
    Path cwd = tmp.resolve("project");
    Files.createDirectories(cwd.resolve("src/main/java"));

    Checks checks = Checks.from(file);

    assertEquals(
        "mvn -q -o -DskipTests compile",
        checks.forPath(cwd.resolve("src/main/java/Foo.java"), cwd).orElseThrow().command());
    assertTrue(
        checks.forPath(cwd.resolve("README.md"), cwd).isEmpty(),
        "markdown 文件不是这个检查所针对的东西");
  }

  @Test
  void theFirstMatchingCheckWinsAndAFileOutsideTheProjectMatchesNothing() throws IOException {
    // 每次编辑只跑一个检查：把所有匹配的命令都跑一遍会把一行改动变成一个构建队列，而第二个检查
    // 的输出并不是模型最先需要的东西。
    Path file =
        config(
            """
            {"checks": [
              {"glob": "src/**", "command": "first"},
              {"glob": "**", "command": "second", "timeoutSeconds": 5}
            ]}
            """);
    Path cwd = tmp.resolve("project");
    Files.createDirectories(cwd.resolve("src"));
    Checks checks = Checks.from(file);

    assertEquals("first", checks.forPath(cwd.resolve("src/main/java/Foo.java"), cwd).orElseThrow().command());
    assertEquals("second", checks.forPath(cwd.resolve("pom.xml"), cwd).orElseThrow().command());
    assertEquals(5, checks.forPath(cwd.resolve("pom.xml"), cwd).orElseThrow().timeoutSeconds());
    assertTrue(
        checks.forPath(tmp.resolve("elsewhere/Notes.java"), cwd).isEmpty(),
        "检查是关于这个项目的陈述，而不是关于机器上每个文件的");
  }

  @Test
  void aPatternWrittenTheUsualWayAlsoMatchesAtTheTopOfTheProject() throws IOException {
    // PathMatcher 把 `**/*.java` 读作「某个目录里的 java 文件」，所以它匹配不到项目根下的
    // `Foo.java` —— 而 gitignore、.editorconfig 和 ripgrep 都匹配得到。一个静默地永不触发的检查
    // 与一个没有任何东西可报告的检查无法区分，于是开头的 `**/` 会按两种方式各试一次。
    Path file = config("{\"checks\": [{\"glob\": \"**/*.java\", \"command\": \"compile\"}]}");
    Path cwd = Files.createDirectories(tmp.resolve("project"));

    assertEquals(
        "compile",
        Checks.from(file).forPath(cwd.resolve("Foo.java"), cwd).orElseThrow().command(),
        "用户熟悉的那个模式必须匹配根目录下的文件");
    assertEquals(
        "compile",
        Checks.from(file).forPath(cwd.resolve("src/Foo.java"), cwd).orElseThrow().command(),
        "而且仍要匹配目录里的那个文件");
  }

  @Test
  void theChecksAreReadPerUseRatherThanHeld() throws IOException {
    // 用户在会话运行期间添加的检查，必须在下次编辑就生效，否则那个文件看起来就像什么也没做，
    // 一直要等到下次重启。
    Path file = config("{\"checks\": []}");
    Path cwd = tmp.resolve("project");
    Files.createDirectories(cwd);
    Checks checks = Checks.from(file);
    assertTrue(checks.forPath(cwd.resolve("Foo.java"), cwd).isEmpty());

    Files.writeString(file, "{\"checks\": [{\"glob\": \"**\", \"command\": \"added later\"}]}");

    assertEquals("added later", checks.forPath(cwd.resolve("Foo.java"), cwd).orElseThrow().command());
  }

  @Test
  void noFileNoBlockAndAnEmptyBlockAllDeclareNothing() throws IOException {
    assertTrue(Checks.from(tmp.resolve("missing.json")).declared().isEmpty());
    assertTrue(Checks.none().declared().isEmpty());
    assertTrue(Checks.from(config("{\"provider\": \"openai\"}")).declared().isEmpty());
    assertTrue(Checks.from(config("{\"checks\": []}")).declared().isEmpty());
    // 一个什么都没写的块不是命令：默认 glob 是每个文件。
    assertEquals("**", Checks.from(config("{\"checks\": [{\"command\": \"x\"}]}")).declared().get(0).glob());
  }

  @Test
  void aMalformedBlockIsReportedRatherThanIgnored() throws IOException {
    // 因为一个检查里打错字就静默地什么检查都不跑，是这张表容易犯的错：会话看起来一切正常，
    // 而编译器永远不吭声。
    IllegalArgumentException notAnArray =
        assertThrows(
            IllegalArgumentException.class, () -> Checks.from(config("{\"checks\": \"mvn compile\"}")).declared());
    assertTrue(notAnArray.getMessage().contains("checks"), notAnArray.getMessage());

    IllegalArgumentException noCommand =
        assertThrows(
            IllegalArgumentException.class,
            () -> Checks.from(config("{\"checks\": [{\"glob\": \"**/*.java\"}]}")).declared());
    assertTrue(noCommand.getMessage().contains("没有 'command'"), noCommand.getMessage());

    IllegalArgumentException badTimeout =
        assertThrows(
            IllegalArgumentException.class,
            () -> Checks.from(config("{\"checks\": [{\"command\": \"x\", \"timeoutSeconds\": \"soon\"}]}"))
                .declared());
    assertTrue(badTimeout.getMessage().contains("timeoutSeconds"), badTimeout.getMessage());
  }

  @Test
  void aTimeoutIsClampedToSomethingALongTurnCanLiveWith() throws IOException {
    Path file = config("{\"checks\": [{\"command\": \"x\", \"timeoutSeconds\": 99999}]}");
    assertEquals(600, Checks.from(file).declared().get(0).timeoutSeconds());
  }
}
