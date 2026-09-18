package com.ccj.agent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 一个目录为在其中工作的代理保留的规则，读自 {@value #FILE_NAME}。
 *
 * <p>系统提示词只能说出处处成立的事。而关于<em>这个</em>项目成立的事——构建命令、绝不能碰的模块、测试怎么
 * 跑——属于它所述代码的旁边，这样它会随项目一起走，而不是留在一台机器的配置文件里，到了下一台机器就被忘掉。
 *
 * <p>两个决定塑造了读取方式：
 *
 * <ul>
 *   <li><b>只读工作目录自己。</b>树中更高处的文件是刻意<em>不</em>查的。规则是「代理使用它工作所在目录里的
 *       规则，在别处就用配置好的提示词」，这是一句用户看着一个文件夹就能预判的陈述。一路走到文件系统根目录，
 *       意味着家目录里一个放错的文件会悄悄为它下面每个项目重定义这个代理，而用户得往上翻找才知道为什么。
 *   <li><b>它存在时，由它领衔提示词。</b>这个文件是项目自己关于「这里怎么干活」的陈述，所以先读它，内置规则
 *       跟在后面——顺序及其理由见 {@link Prompts#system(String, String, Path)}。
 * </ul>
 *
 * <p>它是帮助，绝不是依赖：文件不存在、读不了、或是空的，都意味着「没有项目规则」，而那正是这个功能出现之前
 * 一次运行的表现。
 */
public final class ProjectPrompt {

  /**
   * 本项目读取的这个名字。刻意只用一个名字：去猜别的工具的文件名，会把用户从未为 ccj 写过的规则摆到模型
   * 面前。
   */
  public static final String FILE_NAME = "CCJ.md";

  /**
   * 规则文件有多少内容可以进入提示词。
   *
   * <p>系统提示词在<em>每一次</em>请求里都会被发送，而且与对话不同，它不属于任何会做裁剪的东西：
   * {@link ContextBudget} 投影的是消息，所以一份过大的提示词就是一个只会增长、没有任何东西能裁它的请求。
   * 对「每个回合都该读的规则」来说这个上限很宽裕（英文约 8k token），而超过它的文件会被截断并留下标记，而不是
   * 被默默接受或默默丢掉。
   */
  public static final int LIMIT_CHARS = 32_000;

  /** 截断时写的话，好让模型知道自己读到的规则是不完整的。 */
  static final String CUT_MARKER =
      "\n\n... (cut short: this rules file did not fit the prompt limit) ...";

  private ProjectPrompt() {}

  /**
   * {@code directory} 保留的规则；它没有保留任何规则时为 {@code ""}。
   *
   * <p>文本按写下的原样返回。不会另加点名文件的标题：只有一个文件、一个已知位置时，没有什么需要消歧，而一个
   * 标题会每次请求都花提示词去说明这段文本来自那唯一一个文件中的哪一个。
   *
   * @return 规则，作为提示词文本；没有规则时为 {@code ""}
   */
  public static String from(Path directory) {
    if (directory == null) {
      return "";
    }
    Path file = directory.toAbsolutePath().normalize().resolve(FILE_NAME);
    if (!isReadableRegularFile(file)) {
      return "";
    }
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      // 读不了与不存在是同一个答案：提示词是帮助，而一个进程读不了的文件，绝不能成为它拒绝启动的理由。
      return "";
    }
    if (text.isBlank()) {
      // 一个存在但什么都没说的文件不是规则。这件事在这里比在向上遍历时更要紧：保存一个空的 CCJ.md——一个还
      // 没被敲进去任何东西的编辑器、一个有人正准备填的占位文件——绝不能被读成「这个项目没有任何规则」，因为
      // 内置规则仍然跟在后面，而这次运行否则会与一次配置过的运行无从区分。
      return "";
    }
    String trimmed = text.strip();
    if (trimmed.length() > LIMIT_CHARS) {
      return trimmed.substring(0, Math.max(0, LIMIT_CHARS - CUT_MARKER.length())) + CUT_MARKER;
    }
    return trimmed;
  }

  private static boolean isReadableRegularFile(Path file) {
    try {
      return Files.isRegularFile(file) && Files.isReadable(file);
    } catch (RuntimeException e) {
      return false;
    }
  }
}
