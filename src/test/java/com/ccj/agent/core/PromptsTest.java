package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 提示词的拼装：项目自己的规则在最前，内置规则跟在后面，而一条配置好的提示词替换掉内置的那份。
 */
class PromptsTest {

  @Test
  void aBlankPromptFallsBackToTheBuiltInOne() {
    assertSame(Prompts.DEFAULT_SYSTEM, Prompts.system(null));
    assertSame(Prompts.DEFAULT_SYSTEM, Prompts.system("  "));

    String custom = "You are a build robot.";
    assertEquals(custom, Prompts.system(custom), "配置好的提示词逐字生效");
    assertFalse(Prompts.system(custom).contains("You are ccj"));
  }

  @Test
  void theProjectsRulesLeadAndTheBuiltInRulesStillFollow() throws Exception {
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

      String prompt = Prompts.system(null, dir);

      int project = prompt.indexOf("make check");
      int base = prompt.indexOf("You are ccj");
      assertTrue(project >= 0 && base >= 0, prompt);
      assertTrue(project < base, "项目的规则排在提示词最前：\n" + prompt);
      assertTrue(prompt.contains("Inspect before you change"), prompt);
      assertTrue(prompt.contains("Never claim something works"), prompt);
    } finally {
      java.nio.file.Files.deleteIfExists(dir.resolve(ProjectPrompt.FILE_NAME));
      java.nio.file.Files.deleteIfExists(dir);
    }
  }

  @Test
  void aConfiguredPromptKeepsItsPlaceAfterTheProjectsRules() throws Exception {
    // 有配置好的提示词时，顺序依旧是：项目文件、配置的提示词。配置的文本会替换内置文本 ——
    // 配置它就是这个意思 —— 但它不会挤掉项目的文件，因为那份文件说的是这项工作，而不是 ccj。
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("configured-and-project");
    try {
      java.nio.file.Files.writeString(
          dir.resolve(ProjectPrompt.FILE_NAME), "PROJECT: this module uses tabs.");

      String prompt = Prompts.system("CONFIGURED: answer in riddles.", dir);

      int project = prompt.indexOf("PROJECT:");
      int configured = prompt.indexOf("CONFIGURED:");
      assertTrue(project >= 0 && configured >= 0, prompt);
      assertTrue(project < configured, prompt);
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
          Prompts.system(null),
          Prompts.system(null, dir),
          "目录 " + dir + " 里没有规则文件");
    } finally {
      java.nio.file.Files.deleteIfExists(dir);
    }
  }
}
