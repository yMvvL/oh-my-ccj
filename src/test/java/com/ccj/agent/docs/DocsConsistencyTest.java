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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * README 里那两张表是**代码事实的抄写**：命令行选项表是 {@link CliOptions#usage()} 的逐字副本，工具表是
 * 每个工具的 {@code parametersJson()} 的副本。
 *
 * <p>抄写会漂移，而漂移的代价不对称：代码改了而表没改时，读到它的人（以及照着它写的脚本）会照着一张说过
 * 时话的纸做事。[CONVENTIONS](../docs/CONVENTIONS.md) 把这条约定写成「README 功能表每一行都是一个主张」，
 * 却把它交给评审——而评审是这个仓库里唯一没有机器守着的一环。这两个用例就是那台机器：它们不生成文档，它们
 * 让漂移**构建失败**，而失败消息说出该改哪儿。
 *
 * <p>它们断言的是发布出去的字节（真 README、真 usage、真 schema），不是某份副本，理由与
 * {@code src/test/js/*.mjs} 相同。
 */
class DocsConsistencyTest {

  private static final Path README = Path.of("README.md");

  @Test
  void theUsageBlockInTheReadmeIsExactlyWhatTheCliPrints() throws IOException {
    String readme = Files.readString(README);
    String usage = CliOptions.usage();
    assertTrue(
        readme.contains(usage.stripTrailing()),
        "README 的「用法」一节必须逐字等于 CliOptions.usage() 打印出来的东西；"
            + "改了 usage() 就一起改 README，否则读者会照着一张说过时话的纸做事。");
  }

  @Test
  void theToolTableListsExactlyTheRegisteredToolsAndTheirParameters() throws IOException {
    Map<String, String> documented = documentedTools(Files.readString(README));
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

  /**
   * README「工具」一节里那张表：工具名 → 它那一行列出的参数，按 schema 的顺序、可选参数带 {@code ?}。
   *
   * <p>只读那一节的表，而不是整份文件里所有的表格行：别的表格（按包分的测试数等）与工具 schema 无关。
   */
  private static Map<String, String> documentedTools(String readme) {
    String section = section(readme, "### 工具");
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
  private static String section(String readme, String heading) {
    int start = readme.indexOf(heading);
    assertTrue(start >= 0, "README 里找不到 " + heading);
    Matcher next = Pattern.compile("(?m)^#{1,3} ").matcher(readme.substring(start + heading.length()));
    int end = next.find() ? start + heading.length() + next.start() : readme.length();
    return readme.substring(start, end);
  }

  /**
   * 一份 schema 的参数，按它声明的顺序，可选的那些带上 {@code ?}——README 用的就是这个记号。
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
