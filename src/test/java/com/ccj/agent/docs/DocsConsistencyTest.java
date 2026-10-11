package com.ccj.agent.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.cli.CliOptions;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.ToolSpec;
import com.ccj.agent.tool.Tools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/USAGE.md} 里那两张表是**代码事实的抄写**：命令行选项表是 {@link CliOptions#usage()} 的逐字
 * 副本，工具表是每个工具的 {@code parametersJson()} 的副本。
 *
 * <p>抄写会漂移，而漂移的代价不对称：代码改了而表没改时，读到它的人（以及照着它写的脚本）会照着一张说过
 * 时话的纸做事。[CONVENTIONS](../docs/CONVENTIONS.md) 把这条约定写成「README 功能表每一行都是一个主张」，
 * 却把它交给评审——而评审是这个仓库里唯一没有机器守着的一环。这两个用例就是那台机器：它们不生成文档，它们
 * 让漂移**构建失败**，而失败消息说出该改哪儿。
 *
 * <p>它们断言的是发布出去的字节（真文档、真 usage、真 schema），不是某份副本，理由与
 * {@code src/test/js/*.mjs} 相同。
 */
class DocsConsistencyTest {

  private static final Path USAGE = Path.of("docs/USAGE.md");

  @Test
  void theUsageBlockIsExactlyWhatTheCliPrints() throws IOException {
    String doc = Files.readString(USAGE);
    String usage = CliOptions.usage();
    assertTrue(
        doc.contains(usage.stripTrailing()),
        "docs/USAGE.md 的「用法」一节必须逐字等于 CliOptions.usage() 打印出来的东西；"
            + "改了 usage() 就一起改 docs/USAGE.md，否则读者会照着一张说过时话的纸做事。");
  }

  @Test
  void theToolTableListsExactlyTheRegisteredToolsAndTheirParameters() throws IOException {
    Map<String, String> documented = documentedTools(Files.readString(USAGE));
    Map<String, ToolSpec> actual = new LinkedHashMap<>();
    for (ToolSpec spec : Tools.standard().specs()) {
      actual.put(spec.name(), spec);
    }

    // 名字与顺序：表格是模型看到的顺序的说明，顺序本身也是事实（ToolsTest 钉的是注册顺序）。
    assertEquals(
        new ArrayList<>(actual.keySet()),
        new ArrayList<>(documented.keySet()),
        "工具表里的工具与注册的顺序都必须一致");

    for (Map.Entry<String, ToolSpec> entry : actual.entrySet()) {
      ToolSpec spec = entry.getValue();
      assertEquals(
          parametersOf(spec.parametersJson()),
          documented.get(entry.getKey()),
          "工具 "
              + entry.getKey()
              + " 的参数表与它的 schema 不一致；参数就是文档里那一行，改名要一起改");
    }
  }

  @Test
  void theEnvironmentTableListsExactlyTheVariablesTheCodeReads() throws IOException {
    String doc = Files.readString(USAGE);
    Set<String> documented = documentedEnvironment(doc);
    Set<String> read = environmentVariablesInSource();

    // 两个方向都要比，因为两种漂移的害处不同：文档里写了代码不读的变量，读者会以为设它有用；代码读了而
    // 文档不写的变量，读者根本不知道它存在——而那正是这次检查抓到的第一件事（`CCJ_SHELL` 与子代理那两个
    // 从来没被写进这张表）。
    Set<String> undocumented = new TreeSet<>(read);
    undocumented.removeAll(documented);
    Set<String> phantom = new TreeSet<>(documented);
    phantom.removeAll(read);

    assertTrue(
        undocumented.isEmpty(),
        "这些环境变量代码会读，而 docs/USAGE.md 的「配置」一节没有列："
            + undocumented
            + "——把它们的含义写进那张表；用户看不到的开关等于不存在。");
    assertTrue(
        phantom.isEmpty(),
        "这些环境变量文档里列了，而 src/main/java 里没有任何地方读："
            + phantom
            + "——要么代码丢了它们，要么他们只是纸面上的。");
  }

  /**
   * {@code docs/USAGE.md}「配置」一节那张表里的变量名。
   *
   * <p>拼出来的那些在文档里写成 {@code CCJ_SUBAGENT_<角色>_MODEL}，而代码里的字面量只有前缀
   * （{@code "CCJ_SUBAGENT_"} 加角色的键）：所以 {@code <} 之后的部分在这里被切掉，两边比的是同一个东西。
   */
  private static Set<String> documentedEnvironment(String doc) {
    Set<String> names = new TreeSet<>();
    Matcher row = Pattern.compile("(?m)^\\| `CCJ_[^|]*\\|.*$").matcher(section(doc, "## 配置"));
    while (row.find()) {
      // 整行都是候选：一行的第一个变量名在第一个反引号对里，而它同样是这张表的一部分——只取「第一个之外」
      // 的那半行，恰好会把每一行的头一个变量漏掉（这一版第一稿就是这么错的）。
      Matcher name = Pattern.compile("`(CCJ_[A-Z0-9_<>\\u4e00-\\u9fff]+)`").matcher(row.group(0));
      while (name.find()) {
        String found = name.group(1);
        int placeholder = found.indexOf('<');
        names.add(placeholder < 0 ? found : found.substring(0, placeholder));
      }
    }
    assertTrue(names.size() >= 10, "配置一节该列出环境变量，实际是 " + names);
    return names;
  }

  /**
   * {@code src/main/java} 里出现的每一个 {@code CCJ_*} 字面量。
   *
   * <p>扫源码而不是问配置类要一张清单：这些名字散在 {@code Config}、{@code AppPaths}、{@code Cli} 与
   * {@code Wallpapers} 里，而「代码到底会读哪些」这个问题的答案只有一个来源——代码本身。
   */
  private static Set<String> environmentVariablesInSource() throws IOException {
    Path main = Path.of("src", "main", "java");
    Set<String> names = new TreeSet<>();
    try (var walk = Files.walk(main)) {
      for (Path file : walk.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
        Matcher literal = Pattern.compile("\"(CCJ_[A-Z0-9_]*)\"").matcher(Files.readString(file));
        while (literal.find()) {
          names.add(literal.group(1));
        }
      }
    }
    assertTrue(names.size() >= 10, "源码里该有一批 CCJ_* 环境变量，实际是 " + names);
    return names;
  }

  /**
   * {@code docs/USAGE.md}「工具」一节里那张表：工具名 → 它那一行列出的参数，按 schema 的顺序、可选参数带
   * {@code ?}。
   *
   * <p>只读那一节的表，而不是整份文件里所有的表格行：别的表格（按包分的测试数等）与工具 schema 无关。
   */
  private static Map<String, String> documentedTools(String doc) {
    String section = section(doc, "## 工具");
    Map<String, String> out = new LinkedHashMap<>();
    Matcher row = Pattern.compile("(?m)^\\| `([^`]+)` \\| ([^|]+) \\|").matcher(section);
    while (row.find()) {
      out.put(row.group(1), plainParameters(row.group(2)));
    }
    assertTrue(out.size() >= 8, "工具表至少该有八个工具：" + out.keySet());
    return out;
  }

  /** 单元格里的反引号是排版，不是内容：`path`, `offset?` 与 path, offset? 说同一件事。 */
  private static String plainParameters(String cell) {
    List<String> parts = new ArrayList<>();
    for (String part : cell.split(",")) {
      parts.add(part.replace("`", "").strip());
    }
    return String.join(", ", parts);
  }

  /** 一节的内容，从它那一行标题到下一个同级或更高级的标题。 */
  private static String section(String doc, String heading) {
    int start = doc.indexOf(heading);
    assertTrue(start >= 0, "docs/USAGE.md 里找不到 " + heading);
    Matcher next = Pattern.compile("(?m)^#{1,3} ").matcher(doc.substring(start + heading.length()));
    int end = next.find() ? start + heading.length() + next.start() : doc.length();
    return doc.substring(start, end);
  }

  /**
   * 一份 schema 的参数，按它声明的顺序，可选的那些带上 {@code ?}——文档用的就是这个记号。
   *
   * <p>「可选」问的是 schema 自己：不在 {@code required} 里就是可选。这样那一个问号也是一个被检查的主张，
   * 而不是装饰。
   */
  private static String parametersOf(String parametersJson) {
    var root = Json.parse(parametersJson);
    List<String> names = new ArrayList<>();
    root.path("properties").fieldNames().forEachRemaining(names::add);
    List<String> required = new ArrayList<>();
    root.path("required").forEach(node -> required.add(node.asText()));

    List<String> rendered = new ArrayList<>();
    for (String name : names) {
      rendered.add(required.contains(name) ? name : name + "?");
    }
    return String.join(", ", rendered);
  }
}
