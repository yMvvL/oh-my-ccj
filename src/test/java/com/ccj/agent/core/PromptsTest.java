package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The language setting, and the prompt it turns into.
 *
 * <p>The reasoning stream is model output, so the only lever over the language it is written in is the
 * system prompt. These cases pin the two halves that make that work: a language that is asked for is
 * named in the prompt — twice, in English and in the language itself — and "auto" adds nothing at all.
 */
class PromptsTest {

  @Test
  void autoLeavesThePromptExactlyAsItWas() {
    assertSame(Prompts.DEFAULT_SYSTEM, Prompts.system(null, null));
    assertSame(Prompts.DEFAULT_SYSTEM, Prompts.system(null, "auto"));
    assertSame(Prompts.DEFAULT_SYSTEM, Prompts.system("  ", "AUTO"));
    assertFalse(Prompts.system(null, "auto").contains("Language:"));
  }

  @Test
  void aChosenLanguageIsAddedToWhateverPromptIsInEffect() {
    String custom = "You are a build robot.";

    String prompt = Prompts.system(custom, "Simplified Chinese");

    assertTrue(prompt.startsWith(custom), prompt);
    assertTrue(prompt.contains("think in 简体中文"), prompt);
    assertTrue(prompt.contains("always reason and reply in 简体中文"), prompt);
    // The user's own words must not decide this, or the setting would only work when they write in
    // the language they asked for — which is the opposite of what it is for.
    assertTrue(prompt.contains("The language of the user's message does not change this"), prompt);
    // Paths, code and command output are the same in every language, and translating them would break
    // the work rather than help the reader.
    assertTrue(prompt.contains("never translated"), prompt);
    // And the rule is repeated in the target language: that sentence is what actually moves the
    // thinking stream, because a model takes its cue from the text in front of it.
    assertTrue(prompt.contains("请始终用简体中文思考和回答"), prompt);
  }

  @Test
  void everyOfferedLanguageCarriesItsOwnSentence() {
    for (String value : Prompts.languageValues()) {
      String prompt = Prompts.system(null, value);
      assertTrue(prompt.contains("Language:"), value);
      assertTrue(prompt.length() > Prompts.DEFAULT_SYSTEM.length(), value);
    }
    // English is the one language whose own sentence is the English rule itself.
    String english = Prompts.system(null, "English");
    assertTrue(english.contains("Always think and answer in English."), english);
  }

  @Test
  void theProjectsRulesLeadAndTheBuiltInRulesStillFollow() throws Exception {
    // Two things pinned at once, both of them reversals of an earlier design.
    //
    // The project's file comes *first*: it is the specific statement about this work, and a reader
    // meeting general instructions first has to hold them while being told the real ones.
    //
    // And the built-in rules are still there. They are not project preferences but the way this agent
    // works — "inspect before you change", "never claim something works unless a command proved it" —
    // and a project adding its own rules has not asked to stop being told those.
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("project-rules");
    try {
      java.nio.file.Files.writeString(
          dir.resolve(ProjectPrompt.FILE_NAME), "This project builds with `make check`.");

      String prompt = Prompts.system(null, "Simplified Chinese", dir);

      int project = prompt.indexOf("make check");
      int base = prompt.indexOf("You are ccj");
      int language = prompt.indexOf("Language:");
      assertTrue(project >= 0 && base >= 0 && language >= 0, prompt);
      assertTrue(project < base, "the project's rules lead the prompt:\n" + prompt);
      assertTrue(base < language, "the built-in rules follow them, and the language stays last:\n" + prompt);
      // The specific behaviours a project file must not be able to switch off by existing.
      assertTrue(prompt.contains("Inspect before you change"), prompt);
      assertTrue(prompt.contains("Never claim something works"), prompt);
    } finally {
      java.nio.file.Files.deleteIfExists(dir.resolve(ProjectPrompt.FILE_NAME));
      java.nio.file.Files.deleteIfExists(dir);
    }
  }

  @Test
  void aConfiguredPromptKeepsItsPlaceAfterTheProjectsRules() throws Exception {
    // With a configured prompt the same order holds: the project's file, the configured prompt, the
    // language. The configured text replaces the built-in one — that is what configuring it means —
    // but it does not displace the project's file, which is about this work rather than about ccj.
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("configured-and-project");
    try {
      java.nio.file.Files.writeString(
          dir.resolve(ProjectPrompt.FILE_NAME), "PROJECT: this module uses tabs.");

      String prompt = Prompts.system("CONFIGURED: answer in riddles.", "English", dir);

      int project = prompt.indexOf("PROJECT:");
      int configured = prompt.indexOf("CONFIGURED:");
      int language = prompt.indexOf("Language:");
      assertTrue(project >= 0 && configured >= 0 && language >= 0, prompt);
      assertTrue(project < configured, prompt);
      assertTrue(configured < language, prompt);
      assertFalse(prompt.contains("You are ccj"), "a configured prompt replaces the built-in one");
    } finally {
      java.nio.file.Files.deleteIfExists(dir.resolve(ProjectPrompt.FILE_NAME));
      java.nio.file.Files.deleteIfExists(dir);
    }
  }

  @Test
  void aDirectoryWithoutRulesLeavesThePromptExactlyAsItWas() throws Exception {
    // A working directory with no CCJ.md must produce exactly the prompt this build produced before
    // rules files existed, or every existing setup would change silently. With the read now confined
    // to one directory, this holds for any directory that has no file of its own — no matter what
    // lives above it, which is the point of not walking upwards.
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("no-rules");
    try {
      assertEquals(
          Prompts.system(null, "English"),
          Prompts.system(null, "English", dir),
          "no rules file in " + dir);
    } finally {
      java.nio.file.Files.deleteIfExists(dir);
    }
  }

  @Test
  void theDefaultPromptIsUsedWhenNoCustomOneIsConfigured() {
    String prompt = Prompts.system(null, "English");

    assertTrue(prompt.startsWith(Prompts.DEFAULT_SYSTEM), prompt);
    assertTrue(prompt.contains("think in English"), prompt);
  }

  @Test
  void aLanguageTheListDoesNotKnowIsStillAskedFor() {
    // The list is a convenience for the form, not a gate: "Klingon" is a name a model can follow.
    assertTrue(Prompts.system(null, "Klingon").contains("think in Klingon"));

    List<String> values = Prompts.languageValues();
    assertTrue(values.contains("Simplified Chinese"), values.toString());
    assertTrue(values.contains("English"), values.toString());
    assertEquals(values.size(), Prompts.languageChoices().size());
    assertEquals("简体中文", Prompts.languageChoices().get(0)[1], "the form shows the language's own name");
  }

  @Test
  void theLanguageIsKeptButNotInventedByConfig() {
    // Blank and "auto" are the same answer — "say nothing" — so they normalise to null, and a
    // language nobody listed is kept rather than silently dropped.
    assertEquals(null, Config.normalizeLanguage(null));
    assertEquals(null, Config.normalizeLanguage("   "));
    assertEquals(null, Config.normalizeLanguage("auto"));
    assertEquals(null, Config.normalizeLanguage("AUTO"));
    assertEquals("Simplified Chinese", Config.normalizeLanguage(" Simplified Chinese "));
    assertEquals("Klingon", Config.normalizeLanguage("Klingon"));
  }

  @Test
  void theLanguageSurvivesTheLayerThatResolvesEverythingElse() {
    Config resolved = Config.empty().merge(Config.empty()).language("Simplified Chinese").resolved();

    assertEquals("Simplified Chinese", resolved.language());
    assertEquals("Simplified Chinese", resolved.merge(Config.empty()).language());
  }
}
