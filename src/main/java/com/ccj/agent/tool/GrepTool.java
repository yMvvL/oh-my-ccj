package com.ccj.agent.tool;

import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 按行进行的正则搜索。
 *
 * <p>它跑在模型指向的任何目录树上，所以每个候选文件在被读取之前都先经过便宜的甄别：体积上限、NUL 探测，
 * 然后是严格的 UTF-8 解码。没有这些过滤，源码树里一个走失的二进制文件就会用垃圾冲垮对话。
 */
public final class GrepTool implements Tool {

  private static final int DEFAULT_MAX_RESULTS = 100;
  private static final int MAX_MAX_RESULTS = 10_000;
  private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
  private static final int MAX_LINE_CHARS = 400;

  @Override
  public String name() {
    return "grep";
  }

  @Override
  public boolean readOnly() {
    return true;
  }

  @Override
  public String description() {
    return "Search file contents with a Java regular expression. Returns path:line:text matches. "
        + "Skips binary files, files over 2 MiB, target/, .git/, node_modules/ and .idea/.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "pattern": {
              "type": "string",
              "description": "Java regular expression to find in each line."
            },
            "path": {
              "type": "string",
              "description": "File or directory to search. Defaults to the session working directory."
            },
            "glob": {
              "type": "string",
              "description": "Only search files whose path or file name matches this glob, e.g. *.java."
            },
            "ignore_case": {
              "type": "boolean",
              "description": "Case-insensitive matching. Defaults to false."
            },
            "max_results": {
              "type": "integer",
              "description": "Maximum matches to return. Defaults to 100."
            }
          },
          "required": ["pattern"],
          "additionalProperties": false
        }""";
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) throws IOException {
    JsonNode args = ToolSupport.args(argumentsJson);
    String pattern = ToolSupport.requireNonBlank(args, "pattern");
    String pathArg = ToolSupport.optionalText(args, "path");
    String glob = ToolSupport.optionalText(args, "glob");
    boolean ignoreCase = ToolSupport.optionalBool(args, "ignore_case", false);
    int maxResults =
        ToolSupport.optionalInt(args, "max_results", DEFAULT_MAX_RESULTS, 1, MAX_MAX_RESULTS);

    Pattern regex;
    try {
      regex =
          Pattern.compile(
              pattern,
              ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
    } catch (PatternSyntaxException e) {
      return ToolResult.error(
          "正则 '" + pattern + "' 无效: " + e.getDescription() + "（位置 " + e.getIndex() + "）");
    }
    PathMatcher filter = null;
    if (glob != null && !glob.isBlank()) {
      try {
        filter = FileSystems.getDefault().getPathMatcher("glob:" + glob);
      } catch (RuntimeException e) {
        return ToolResult.error("glob '" + glob + "' 无效: " + e.getMessage());
      }
    }

    Path base = pathArg == null || pathArg.isBlank() ? ctx.cwd() : ctx.resolve(pathArg);
    if (!Files.exists(base)) {
      return ToolResult.error("找不到路径: " + ToolSupport.display(ctx, base));
    }

    List<String> hits = new ArrayList<>();
    List<String> unreadable = new ArrayList<>();
    long[] matched = new long[1];
    if (Files.isRegularFile(base)) {
      search(
          base,
          ToolSupport.display(ctx, base),
          regex,
          filter,
          null,
          maxResults,
          hits,
          matched,
          unreadable);
    } else if (Files.isDirectory(base)) {
      PathMatcher fileFilter = filter;
      ToolSupport.walkFiles(
          base,
          (file, relative) ->
              search(
                  file,
                  ToolSupport.display(ctx, file),
                  regex,
                  fileFilter,
                  ToolSupport.slashed(relative),
                  maxResults,
                  hits,
                  matched,
                  unreadable));
    } else {
      return ToolResult.error("路径既不是文件也不是目录: " + ToolSupport.display(ctx, base));
    }

    String skipped =
        unreadable.isEmpty()
            ? ""
            : "（有 " + unreadable.size() + " 个文件读不了）";
    if (matched[0] == 0) {
      return ToolResult.ok(
          "/" + pattern + "/ 在 " + ToolSupport.display(ctx, base) + " 下没有匹配" + skipped);
    }

    StringBuilder out = new StringBuilder();
    int bytes = 0;
    int shown = 0;
    for (String hit : hits) {
      int length = ToolSupport.utf8Length(hit) + 1;
      if (bytes + length > ctx.outputLimitBytes()) {
        break;
      }
      out.append(hit).append('\n');
      bytes += length;
      shown++;
    }
    long omitted = matched[0] - shown;
    if (omitted > 0) {
      out.append("... 省略了 ").append(omitted).append(" 个匹配 ...\n");
    }
    if (!unreadable.isEmpty()) {
      // 点出其中几个就够采取行动了；全部列出会把匹配埋掉。
      out.append("... 有 ")
          .append(unreadable.size())
          .append(" 个文件读不了: ")
          .append(String.join(", ", unreadable.subList(0, Math.min(3, unreadable.size()))))
          .append(unreadable.size() > 3 ? ", …" : "")
          .append('\n');
    }
    return ToolResult.ok(out.toString());
  }

  private static void search(
      Path file,
      String label,
      Pattern regex,
      PathMatcher filter,
      String relative,
      int maxResults,
      List<String> hits,
      long[] matched,
      List<String> unreadable) {
    if (filter != null && !matchesFilter(filter, relative, file)) {
      return;
    }
    List<String> lines;
    try {
      if (Files.size(file) > MAX_FILE_BYTES || ToolSupport.isBinaryFile(file)) {
        return;
      }
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (CharacterCodingException e) {
      return;
    } catch (IOException e) {
      // 一个读不了的文件——权限位、遍历中途消失的文件、块设备——不能把此前找到的所有匹配都丢掉。
      // 改为在结果里点出它的名字。
      unreadable.add(label);
      return;
    }
    for (String line : lines) {
      if (line.indexOf('\0') >= 0) {
        // 8 KiB 的头部探测看不到藏在更后面的 NUL；带这种字节的行不是文本，引用它就会把原始字节放进
        // 对话里。
        return;
      }
    }
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.endsWith("\r")) {
        line = line.substring(0, line.length() - 1);
      }
      if (!regex.matcher(line).find()) {
        continue;
      }
      matched[0]++;
      if (hits.size() < maxResults) {
        hits.add(label + ":" + (i + 1) + ":" + clip(line));
      }
    }
  }

  /** glob 过滤器匹配搜索根下的相对路径，或者光秃秃的文件名。 */
  private static boolean matchesFilter(PathMatcher filter, String relative, Path file) {
    if (relative != null && filter.matches(Path.of(relative))) {
      return true;
    }
    Path name = file.getFileName();
    return name != null && filter.matches(name);
  }

  private static String clip(String line) {
    return line.length() <= MAX_LINE_CHARS
        ? line
        : line.substring(0, MAX_LINE_CHARS) + "...";
  }
}
