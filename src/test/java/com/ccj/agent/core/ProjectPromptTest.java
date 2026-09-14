package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rules a directory keeps for the agent that works in it.
 *
 * <p>A prompt can only say what is true everywhere. What is true about <em>this</em> project — the
 * build command, the module that must not be touched, the way its tests are run — belongs next to
 * the code it is about, where it moves with the project instead of living in one machine's config
 * file.
 *
 * <p>Two properties are the ones worth defending, because both were the opposite at some point:
 *
 * <ul>
 *   <li><b>The read is exactly one directory deep.</b> A file above the working directory is not
 *       consulted. The answer to "what prompt is this run using" has to be something a user can work
 *       out by looking at the folder they started in — a walk to the root would let a stray file in a
 *       home directory redefine the agent for every project beneath it.
 *   <li><b>An empty file is not rules.</b> It has to mean "nothing here", the same as no file at all,
 *       so that saving a placeholder cannot quietly strip the agent of its built-in rules.
 * </ul>
 */
class ProjectPromptTest {

  @TempDir Path root;

  private Path write(Path dir, String name, String content) throws Exception {
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    return dir;
  }

  @Test
  void aDirectoryWithNoRulesAddsNothing() {
    assertEquals("", ProjectPrompt.from(root));
  }

  @Test
  void theRulesInTheWorkingDirectoryAreRead() throws Exception {
    write(root, ProjectPrompt.FILE_NAME, "Run tests with `mvn -o test`.\n");

    String rules = ProjectPrompt.from(root);

    assertTrue(rules.contains("mvn -o test"), rules);
    // Returned as written, with no heading: with one file at one known location there is nothing to
    // disambiguate, and a heading would cost prompt on every request to say which of one it was.
    assertFalse(rules.contains("Rules from"), rules);
    assertEquals("Run tests with `mvn -o test`.", rules, "and stripped of surrounding blank lines");
  }

  @Test
  void aFileInAParentDirectoryIsNotUsed() throws Exception {
    // The reversal this pins down: the walk upwards is gone. The rule is "the directory you are
    // working in", which is predictable from one folder; a walk to the root would let a file in a home
    // directory apply to every project under it without any project asking for it.
    write(root, ProjectPrompt.FILE_NAME, "Top-level rule: never force-push.");
    Path nested = Files.createDirectories(root.resolve("a/b/c"));

    assertEquals("", ProjectPrompt.from(nested), "a parent's rules are not this directory's");
  }

  @Test
  void aCloserFileDoesNotMergeWithTheOneAboveIt() throws Exception {
    write(root, ProjectPrompt.FILE_NAME, "GENERAL: keep commits small.");
    Path nested = write(root.resolve("sub"), ProjectPrompt.FILE_NAME, "SPECIFIC: this module uses tabs.");

    String rules = ProjectPrompt.from(nested);

    assertTrue(rules.contains("SPECIFIC"), rules);
    assertFalse(rules.contains("GENERAL"), "and it is not combined with the parent's: " + rules);
  }

  @Test
  void anEmptyOrBlankFileContributesNothing() throws Exception {
    // Saving an empty file — an editor not yet typed into — must not read as "no rules of any kind",
    // because the built-in rules follow the file and their absence is not something the user asked for.
    write(root, ProjectPrompt.FILE_NAME, "\n\n   \n");

    assertEquals("", ProjectPrompt.from(root));
  }

  @Test
  void aFileThatCannotBeReadIsNotAnError() throws Exception {
    // The prompt is a help, not a dependency: a file the process cannot read must not stop it from
    // starting, and the worst case is the behaviour a prompt-less run had before this existed.
    Path file = write(root, ProjectPrompt.FILE_NAME, "secret").resolve(ProjectPrompt.FILE_NAME);
    file.toFile().setReadable(false);
    try {
      assertEquals("", ProjectPrompt.from(root));
    } finally {
      file.toFile().setReadable(true);
    }
  }

  @Test
  void anOversizedFileIsCutAndSaysSo() throws Exception {
    // The system prompt is sent on every request and — unlike the conversation — it is not part of
    // the context budget's projection, so a huge rules file is a request that grows without anything
    // being able to trim it. Cutting it with a marker is the honest answer: silently sending
    // megabytes, or silently dropping the file, are both worse.
    write(root, ProjectPrompt.FILE_NAME, "x".repeat(ProjectPrompt.LIMIT_CHARS + 10_000));

    String rules = ProjectPrompt.from(root);

    assertTrue(rules.length() <= ProjectPrompt.LIMIT_CHARS + 200, "kept to the limit: " + rules.length());
    assertTrue(rules.contains("cut short"), "and says the rules are incomplete");
  }

  @Test
  void nothingIsFoundWhenTheNameIsOnlySimilar() throws Exception {
    // CCJ.md is the file this project reads, and near-misses are not it: guessing at other names is
    // how a directory ends up with rules the user never agreed to.
    write(root, "CCJ.markdown", "not this one");
    write(root, "ccj.md", "nor case variants of another name");
    write(root, "AGENTS.md", "nor another tool's file");

    assertEquals("", ProjectPrompt.from(root));
  }

  @Test
  void aNullOrMissingDirectoryIsNotAnError() {
    assertEquals("", ProjectPrompt.from(null));
    assertEquals("", ProjectPrompt.from(root.resolve("does-not-exist")));
  }

  @Test
  void theDirectoryItselfIsReadNotTheFileBesideIt() throws Exception {
    // The path is resolved against the directory as given: a relative path, a path with a trailing
    // slash and a path with `..` in it all have to find the same file.
    write(root, ProjectPrompt.FILE_NAME, "FOUND: the rules are here.");

    assertTrue(ProjectPrompt.from(root.resolve(".")).contains("FOUND"));
    assertTrue(ProjectPrompt.from(root.resolve("sub/..")).contains("FOUND"));
  }
}
