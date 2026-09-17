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
        "a markdown file is not what that check is about");
  }

  @Test
  void theFirstMatchingCheckWinsAndAFileOutsideTheProjectMatchesNothing() throws IOException {
    // One check per edit: running every matching command would turn a one-line change into a build
    // queue, and the second check's output is not what the model needs first.
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
        "a check is a statement about this project, not about every file on the machine");
  }

  @Test
  void aPatternWrittenTheUsualWayAlsoMatchesAtTheTopOfTheProject() throws IOException {
    // PathMatcher reads `**/*.java` as "a java file inside some directory", so it does not match
    // `Foo.java` at the project root — while gitignore, .editorconfig and ripgrep all do. A check
    // that silently never fires is indistinguishable from a check with nothing to report, so the
    // leading `**/` is tried both ways.
    Path file = config("{\"checks\": [{\"glob\": \"**/*.java\", \"command\": \"compile\"}]}");
    Path cwd = Files.createDirectories(tmp.resolve("project"));

    assertEquals(
        "compile",
        Checks.from(file).forPath(cwd.resolve("Foo.java"), cwd).orElseThrow().command(),
        "the pattern the user knows must match the file at the root");
    assertEquals(
        "compile",
        Checks.from(file).forPath(cwd.resolve("src/Foo.java"), cwd).orElseThrow().command(),
        "and still match the one inside a directory");
  }

  @Test
  void theChecksAreReadPerUseRatherThanHeld() throws IOException {
    // A check the user adds while a session is running has to take effect on the next edit, or the
    // file looks like it did nothing until the next restart.
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
    // A block that names nothing is not a command: the default glob is every file.
    assertEquals("**", Checks.from(config("{\"checks\": [{\"command\": \"x\"}]}")).declared().get(0).glob());
  }

  @Test
  void aMalformedBlockIsReportedRatherThanIgnored() throws IOException {
    // Silently running no checks because one has a typo is the failure this table is prone to: the
    // session looks healthy and the compiler never speaks.
    IllegalArgumentException notAnArray =
        assertThrows(
            IllegalArgumentException.class, () -> Checks.from(config("{\"checks\": \"mvn compile\"}")).declared());
    assertTrue(notAnArray.getMessage().contains("checks"), notAnArray.getMessage());

    IllegalArgumentException noCommand =
        assertThrows(
            IllegalArgumentException.class,
            () -> Checks.from(config("{\"checks\": [{\"glob\": \"**/*.java\"}]}")).declared());
    assertTrue(noCommand.getMessage().contains("no 'command'"), noCommand.getMessage());

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
