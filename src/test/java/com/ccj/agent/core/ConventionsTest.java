package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/CONVENTIONS.md} 中拼写那一半的可校验版本。
 *
 * <p>一条只有审查者才能执行的约定，就是一条会走样的约定，而这一条已经走样了：这棵树把它自己的词
 * 按英式拼写（{@code normalise}、{@code summarise}、{@code serialise}），却有少数几处漂成了美式 ——
 * {@code ToolSummary.summarize}、"failed to serialize JSON"，以及同一类里的
 * {@code Config.normalizeLanguage}，它紧挨着 {@code Config.normaliseReasoning}。这些对没特意去
 * 找它们的读者都是不可见的。
 *
 * <p>被检查的是源码树，而不是发布出去的产品，而且只查本仓库自己拥有的那些词。平台的拼写是被
 * 引用而不是被翻译：{@code Path.normalize}、{@code overscroll-behavior} 和
 * {@code text-align: center} 是别人写下的东西的名字，下面的豁免清单正是这棵树用到的那些。
 */
class ConventionsTest {

  private static final Path MAIN = Path.of("src", "main", "java");

  /**
   * 本仓库按英式拼写的那些词的美式写法。
   *
   * <p>{@code normalize} 被有意排除在这份清单之外：{@link Path#normalize()} 是 JDK 自己的方法，
   * 被调用了三十多次，所以这个词出现在树里是合理的，一条针对它的规则就等于一条针对标准库的规则。
   */
  private static final List<String> AMERICAN = List.of(
      "summarize", "summarizes", "summarized", "summarizing", "summarization",
      "serialize", "serializes", "serialized", "serializing", "serialization",
      "organize", "organized", "organizing", "organization",
      "recognize", "recognized", "recognizing",
      "initialize", "initialized", "initializing", "initialization",
      // `synchronized` 是 Java 关键字，而不是这棵树自己选的词，所以它不在清单上：一条针对它的
      // 规则就等于一条针对这门语言的规则。
      "behavior", "color", "center", "flavor", "license");

  /**
   * 借用来的拼写在这里才是正确的地方：引号里的名字，或者某个平台 API。
   *
   * <p>`initialize` 和 `notifications/initialized` 是 MCP 协议的方法名 —— 是线上协议这么说的，
   * 不是这棵树 —— 所以引用它们的行，并不是在把我们的什么词拼错。
   */
  private static final List<String> BORROWED = List.of(
      "\"initialize\"",
      "`initialize`",
      "notifications/initialized",
      "overscroll-behavior",
      "blockedEntities",
      "Path.normalize",
      "normalize()",
      "text-align: center",
      "align-items: center",
      "justify-content: center",
      "background-color",
      "border-color",
      "accent-color",
      "currentColor",
      "color-scheme",
      "getColor",
      "setColor",
      "color:");

  @Test
  void theTreeSpellsItsOwnWordsTheBritishWay() throws IOException {
    assertTrue(Files.isDirectory(MAIN), "请从仓库根目录运行：" + MAIN.toAbsolutePath());

    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(MAIN)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = source.split("\n", -1);
        for (int line = 0; line < lines.length; line++) {
          for (String american : AMERICAN) {
            Matcher matcher = Pattern.compile("\\b" + american + "\\b", Pattern.CASE_INSENSITIVE)
                .matcher(lines[line]);
            while (matcher.find()) {
              if (!borrowed(lines[line])) {
                offenders.add(file + ":" + (line + 1) + " — '" + matcher.group() + "': "
                    + lines[line].strip());
              }
            }
          }
        }
      }
    }

    assertTrue(
        offenders.isEmpty(),
        "英式拼写属于这棵树（docs/CONVENTIONS.md，*拼写与语气*）；"
            + "只有在平台拥有那个词的地方才借用平台的拼写：\n"
            + String.join("\n", offenders));
  }

  /** 当这一行引用了别人拥有的名字时为真，那样它的拼写就不该由我们来改。 */
  private static boolean borrowed(String line) {
    for (String name : BORROWED) {
      if (line.contains(name)) {
        return true;
      }
    }
    return false;
  }
}
