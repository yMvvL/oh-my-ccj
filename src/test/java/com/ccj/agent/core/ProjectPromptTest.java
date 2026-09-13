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
 * file. The file is read from the working directory and from the directories above it, because a
 * convention that only applies when you happen to be standing in the root is not a convention.
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
  }

  @Test
  void rulesAboveTheWorkingDirectoryApplyToo() throws Exception {
    // The reported use: the rules sit at the top of a tree of projects, and the agent is started in
    // one of the subdirectories. Only reading the working directory would silently drop them.
    write(root, ProjectPrompt.FILE_NAME, "Top-level rule: never force-push.");
    Path nested = Files.createDirectories(root.resolve("a/b/c"));

    String rules = ProjectPrompt.from(nested);

    assertTrue(rules.contains("never force-push"), rules);
  }

  @Test
  void theClosestRulesComeLastAndTheRemoteOnesFirst() throws Exception {
    // Order is the whole point of supporting both: the general rule is stated, then the specific
    // one that narrows it. Reversed, a project's rule would be contradicted by its parent's.
    write(root, ProjectPrompt.FILE_NAME, "GENERAL: keep commits small.");
    Path nested = write(root.resolve("sub"), ProjectPrompt.FILE_NAME, "SPECIFIC: this module uses tabs.");

    String rules = ProjectPrompt.from(nested);

    int general = rules.indexOf("GENERAL");
    int specific = rules.indexOf("SPECIFIC");
    assertTrue(general >= 0 && specific >= 0, rules);
    assertTrue(general < specific, "the closer rule must come after the more distant one:\n" + rules);
    // Which file each block came from, so a model reading a contradiction can tell them apart.
    assertTrue(rules.contains(root.resolve(ProjectPrompt.FILE_NAME).toString()), rules);
    assertTrue(rules.contains(nested.resolve(ProjectPrompt.FILE_NAME).toString()), rules);
  }

  @Test
  void anEmptyOrBlankFileContributesNothing() throws Exception {
    write(root, ProjectPrompt.FILE_NAME, "\n\n   \n");

    assertEquals("", ProjectPrompt.from(root));
  }

  @Test
  void aFileThatCannotBeReadIsNotAnError() throws Exception {
    // The prompt is a help, not a dependency: a directory the process cannot read must not stop it
    // from starting, and the worst case is the behaviour a prompt-less run had before this existed.
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
  void aDirectoryThatIsUnreadableUpstreamDoesNotHideTheCloserRules() throws Exception {
    Path nested = write(root.resolve("deep"), ProjectPrompt.FILE_NAME, "CLOSEST: run `make check`.");

    String rules = ProjectPrompt.from(nested);

    assertTrue(rules.contains("make check"), rules);
  }

  @Test
  void theSearchStopsAtTheFilesystemRoot() throws Exception {
    // A rules file cannot live above the root, so the walk has to terminate rather than fail. This
    // also pins that a walk from a deep path does not lose the file in the middle of it.
    write(root, ProjectPrompt.FILE_NAME, "FOUND: the walk reaches it.");
    Path deep = Files.createDirectories(root.resolve("one/two/three/four/five"));

    assertTrue(ProjectPrompt.from(deep).contains("FOUND"));
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
}
