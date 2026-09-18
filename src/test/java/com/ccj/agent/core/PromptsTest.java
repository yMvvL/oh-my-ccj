package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 语言设置，以及它变成的那个提示词。
 *
 * <p>推理流是模型的输出，所以对它所使用的语言唯一能施力的地方就是系统提示词。这些用例钉住让它
 * 奏效的两半：被要求的语言会在提示词里点名 —— 两次，一次用英文、一次用它自己的语言 —— 而
 * "auto" 什么都不加。
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
    // 用户自己的话不能决定这件事，否则这个设置只在用户用他所要求的语言书写时才生效 —— 那正好与
    // 它的用途相反。
    assertTrue(prompt.contains("The language of the user's message does not change this"), prompt);
    // 路径、代码和命令输出在任何语言里都是一样的，翻译它们只会破坏工作，而不是帮到读者。
    assertTrue(prompt.contains("never translated"), prompt);
    // 而且这条规则会再用目标语言重复一遍：真正撬动思考流的是那句话，因为模型会从眼前的文本里
    // 获取线索。
    assertTrue(prompt.contains("请始终用简体中文思考和回答"), prompt);
  }

  @Test
  void everyOfferedLanguageCarriesItsOwnSentence() {
    for (String value : Prompts.languageValues()) {
      String prompt = Prompts.system(null, value);
      assertTrue(prompt.contains("Language:"), value);
      assertTrue(prompt.length() > Prompts.DEFAULT_SYSTEM.length(), value);
    }
    // 英语是唯一一门「它自己的句子就是那条英语规则本身」的语言。
    String english = Prompts.system(null, "English");
    assertTrue(english.contains("Always think and answer in English."), english);
  }

  @Test
  void theProjectsRulesLeadAndTheBuiltInRulesStillFollow() throws Exception {
    // 一次钉住两件事，两件都是对早先设计的反转。
    //
    // 项目的文件排在*最前*：它是关于这项工作的专门陈述，而先读到通用指令的读者，必须一边记着它们，
    // 一边才被告知真正的规则。
    //
    // 而内置规则仍然在那里。它们不是项目偏好，而是这个代理的做事方式 —— 「改之前先查看」、
    // 「除非有命令证明了，否则绝不断言某样东西能用」—— 一个添加了自己规则的项目，并没有要求不再
    // 被告知这些。
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("project-rules");
    try {
      java.nio.file.Files.writeString(
          dir.resolve(ProjectPrompt.FILE_NAME), "This project builds with `make check`.");

      String prompt = Prompts.system(null, "Simplified Chinese", dir);

      int project = prompt.indexOf("make check");
      int base = prompt.indexOf("You are ccj");
      int language = prompt.indexOf("Language:");
      assertTrue(project >= 0 && base >= 0 && language >= 0, prompt);
      assertTrue(project < base, "项目的规则排在提示词最前：\n" + prompt);
      assertTrue(base < language, "内置规则跟在它们后面，而语言留在最后：\n" + prompt);
      // 一个项目文件仅凭着存在，绝不能关掉的那些具体行事方式。
      assertTrue(prompt.contains("Inspect before you change"), prompt);
      assertTrue(prompt.contains("Never claim something works"), prompt);
    } finally {
      java.nio.file.Files.deleteIfExists(dir.resolve(ProjectPrompt.FILE_NAME));
      java.nio.file.Files.deleteIfExists(dir);
    }
  }

  @Test
  void aConfiguredPromptKeepsItsPlaceAfterTheProjectsRules() throws Exception {
    // 有配置好的提示词时，顺序依旧：项目文件、配置的提示词、语言。配置的文本会替换内置文本 ——
    // 配置它就是这个意思 —— 但它不会挤掉项目的文件，因为那份文件说的是这项工作，而不是 ccj。
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
      assertFalse(prompt.contains("You are ccj"), "配置好的提示词会替换内置的那份");
    } finally {
      java.nio.file.Files.deleteIfExists(dir.resolve(ProjectPrompt.FILE_NAME));
      java.nio.file.Files.deleteIfExists(dir);
    }
  }

  @Test
  void aDirectoryWithoutRulesLeavesThePromptExactlyAsItWas() throws Exception {
    // 一个没有 CCJ.md 的工作目录，必须产出这个构建在规则文件存在之前所产出的、一模一样的提示词，
    // 否则每个既有配置都会悄悄改变。现在读取被限定在一层目录内，所以对任何没有自己文件的目录，
    // 这一点都成立 —— 无论其上方有什么，而这正是不向上查找的意义。
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("no-rules");
    try {
      assertEquals(
          Prompts.system(null, "English"),
          Prompts.system(null, "English", dir),
          "目录 " + dir + " 里没有规则文件");
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
    // 这份清单是给表单图方便的，不是一道门槛：「Klingon」也是模型能照做的一个名字。
    assertTrue(Prompts.system(null, "Klingon").contains("think in Klingon"));

    List<String> values = Prompts.languageValues();
    assertTrue(values.contains("Simplified Chinese"), values.toString());
    assertTrue(values.contains("English"), values.toString());
    assertEquals(values.size(), Prompts.languageChoices().size());
    assertEquals("简体中文", Prompts.languageChoices().get(0)[1], "表单会显示这门语言自己的名字");
  }

  @Test
  void theLanguageIsKeptButNotInventedByConfig() {
    // 空白与 "auto" 是同一个答案 —— 「什么都别说」—— 所以它们都规范化为 null，而一个没人列出的
    // 语言会被保留，而不是悄悄丢掉。
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
