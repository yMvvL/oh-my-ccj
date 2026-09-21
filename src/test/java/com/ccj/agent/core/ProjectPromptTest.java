package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 一个目录为在其中工作的代理留下的规则。
 *
 * <p>提示词只能说出放之四海皆准的东西。关于*这个*项目的真相 —— 构建命令、不许动的模块、跑测试的
 * 方式 —— 应该待在它所描述的代码旁边，这样它会随项目一起搬动，而不是活在某一台机器的配置文件里。
 *
 * <p>有两条性质值得捍卫，因为它们都曾经是相反的：
 *
 * <ul>
 *   <li><b>读取恰好只有一层目录深。</b>工作目录之上的文件不会被查看。「这次运行用的是哪个提示词」
 *       这个问题的答案，必须能让用户看一眼自己启动时所在的文件夹就推出来 —— 一路走到根目录，
 *       会让某个家目录里一个乱放的文件，把其下每个项目的代理都重新定义一遍。
 *   <li><b>一个空文件不算规则。</b>它必须意味着「这里什么都没有」，与根本没有这个文件一样，
 *       这样保存一个占位文件就不会悄悄剥掉代理的内置规则。
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
    // 按原样返回，不加标题：一个文件、一个已知的位置，没有任何需要区分的东西，而一个标题会在
    // 每次请求都用提示词去说明它是两者中的哪一个。
    assertFalse(rules.contains("Rules from"), rules);
    assertEquals("Run tests with `mvn -o test`.", rules, "并且去掉周围空行");
  }

  @Test
  void aFileInAParentDirectoryIsNotUsed() throws Exception {
    // 这里钉住的是那次反转：向上查找已经取消了。规则是「你正在工作的那个目录」，从一个文件夹就能
    // 预判；一路查到根目录，会让家目录里的一个文件未经任何项目同意就作用于其下的每个项目。
    write(root, ProjectPrompt.FILE_NAME, "Top-level rule: never force-push.");
    Path nested = Files.createDirectories(root.resolve("a/b/c"));

    assertEquals("", ProjectPrompt.from(nested), "父目录的规则不是这个目录的");
  }

  @Test
  void aCloserFileDoesNotMergeWithTheOneAboveIt() throws Exception {
    write(root, ProjectPrompt.FILE_NAME, "GENERAL: keep commits small.");
    Path nested = write(root.resolve("sub"), ProjectPrompt.FILE_NAME, "SPECIFIC: this module uses tabs.");

    String rules = ProjectPrompt.from(nested);

    assertTrue(rules.contains("SPECIFIC"), rules);
    assertFalse(rules.contains("GENERAL"), "而且它不与父目录的那一份合并：" + rules);
  }

  @Test
  void anEmptyOrBlankFileContributesNothing() throws Exception {
    // 保存一个空文件 —— 一个还没敲进任何内容的编辑器 —— 绝不能读作「什么规则都没有」，因为内置
    // 规则跟随这个文件，而它们的缺席并不是用户要求的结果。
    write(root, ProjectPrompt.FILE_NAME, "\n\n   \n");

    assertEquals("", ProjectPrompt.from(root));
  }

  @Test
  void aFileThatCannotBeReadIsNotAnError() throws Exception {
    // 提示词是助力，不是依赖：一个进程读不了的文件不能阻碍它启动，最坏的情况也只是回到这个功能
    // 存在之前、没有提示词时的行为。
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
    // 系统提示词每次请求都会发出，而且 —— 与对话不同 —— 它不属于上下文预算的投影范围，所以一个
    // 巨大的规则文件就是一个不断膨胀、却没有任何东西能裁剪它的请求。用一个标记把它剪断是诚实的
    // 答案：静默地发出几兆字节，或静默地丢掉这个文件，都更糟。
    write(root, ProjectPrompt.FILE_NAME, "x".repeat(ProjectPrompt.LIMIT_CHARS + 10_000));

    String rules = ProjectPrompt.from(root);

    assertTrue(rules.length() <= ProjectPrompt.LIMIT_CHARS + 200, "保持在限制之内：" + rules.length());
    assertTrue(rules.contains("cut short"), "并且说明这些规则并不完整");
  }

  @Test
  void nothingIsFoundWhenTheNameIsOnlySimilar() throws Exception {
    // CCJ.md 才是这个项目读取的文件，而近似命中不算：去猜别的名字，就是一个目录最终带上用户从未
    // 同意过的规则的方式。
    write(root, "CCJ.markdown", "not this one");
    write(root, "AGENTS.md", "nor another tool's file");

    if (caseSensitive(root)) {
      write(root, "ccj.md", "nor case variants of another name");
      assertEquals("", ProjectPrompt.from(root));
    } else {
      // 在大小写不敏感的文件系统上（macOS 默认的 APFS 就是），`ccj.md` **就是** `CCJ.md`：是文件系统
      // 说这个名字的文件存在。所以这一台机器上的事实不是「近似名字被忽略」，而是「同一个名字被找到」——
      // 把它写下来，好让一个平台的真相不会被另一个平台上的绿光照成没发生过。
      write(root, "ccj.md", "the same name, different case");
      assertEquals("the same name, different case", ProjectPrompt.from(root));
    }
  }

  /** 这个临时目录所在的文件系统区分大小写吗？ */
  private static boolean caseSensitive(Path directory) throws IOException {
    Path probe = Files.writeString(directory.resolve("CaseProbe.tmp"), "x");
    try {
      return !Files.exists(directory.resolve("cASEpROBE.tmp"));
    } finally {
      Files.deleteIfExists(probe);
    }
  }

  @Test
  void aNullOrMissingDirectoryIsNotAnError() {
    assertEquals("", ProjectPrompt.from(null));
    assertEquals("", ProjectPrompt.from(root.resolve("does-not-exist")));
  }

  @Test
  void theDirectoryItselfIsReadNotTheFileBesideIt() throws Exception {
    // 路径是针对所给的那个目录解析的：一个相对路径、一个带尾随斜杠的路径、一个里面含 `..` 的
    // 路径，都必须找到同一个文件。
    write(root, ProjectPrompt.FILE_NAME, "FOUND: the rules are here.");

    assertTrue(ProjectPrompt.from(root.resolve(".")).contains("FOUND"));
    assertTrue(ProjectPrompt.from(root.resolve("sub/..")).contains("FOUND"));
  }
}
