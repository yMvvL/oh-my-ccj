package com.ccj.agent.tool;

import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 在会话目录树上做 glob 匹配。
 *
 * <p>模式匹配的是以正斜杠分隔的相对路径，因此在每个平台上的行为都一致；开头的 {@code **}{@code /} 也能
 * 匹配顶层文件——用户期望 {@code **}{@code /*.java} 包含那个就放在工作目录里的文件。结果按修改时间排序，
 * 因为当会话在找它刚碰过的那个文件时，最新的胜出。
 */
public final class GlobTool implements Tool {

  private static final int MAX_RESULTS = 200;

  @Override
  public String name() {
    return "glob";
  }

  @Override
  public boolean readOnly() {
    return true;
  }

  @Override
  public String description() {
    return "Find files by glob pattern, e.g. **/*.java or src/**/*.md. Results are newest first. "
        + "Skips target/, .git/, node_modules/ and .idea/.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "pattern": {
              "type": "string",
              "description": "Glob pattern matched against paths relative to the search root."
            },
            "path": {
              "type": "string",
              "description": "Directory to search. Defaults to the session working directory."
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
    Path base = pathArg == null || pathArg.isBlank() ? ctx.cwd() : ctx.resolve(pathArg);
    if (!Files.isDirectory(base)) {
      return ToolResult.error("path 不是目录: " + ToolSupport.display(ctx, base));
    }

    List<PathMatcher> matchers;
    try {
      matchers = matchersFor(pattern);
    } catch (RuntimeException e) {
      return ToolResult.error("glob 模式 '" + pattern + "' 无效: " + e.getMessage());
    }

    PriorityQueue<Found> newest = new PriorityQueue<>(Comparator.comparingLong(Found::modified));
    long[] matched = new long[1];
    ToolSupport.walkFiles(
        base,
        (file, relative) -> {
          if (!matches(matchers, relative)) {
            return;
          }
          matched[0]++;
          long modified = Files.getLastModifiedTime(file).toMillis();
          if (newest.size() < MAX_RESULTS) {
            newest.offer(new Found(file, modified));
          } else if (newest.peek().modified() < modified) {
            newest.poll();
            newest.offer(new Found(file, modified));
          }
        });

    List<Found> found = new ArrayList<>(newest);
    found.sort(
        Comparator.comparingLong(Found::modified)
            .reversed()
            .thenComparing(entry -> entry.path().toString()));

    StringBuilder out = new StringBuilder();
    int bytes = 0;
    int shown = 0;
    for (Found entry : found) {
      String line = ToolSupport.display(ctx, entry.path()) + "\n";
      int length = ToolSupport.utf8Length(line);
      if (bytes + length > ctx.outputLimitBytes()) {
        break;
      }
      out.append(line);
      bytes += length;
      shown++;
    }
    long omitted = matched[0] - shown;
    if (omitted > 0) {
      out.append("... 省略了 ").append(omitted).append(" 个匹配 ...\n");
    }
    if (shown == 0) {
      return ToolResult.ok(
          matched[0] == 0
              ? "'" + pattern + "' 在 " + ToolSupport.display(ctx, base) + " 下没有任何匹配的文件"
              : "有 "
                  + matched[0]
                  + " 个文件匹配 '"
                  + pattern
                  + "'，但它们的路径放不进 "
                  + ctx.outputLimitBytes()
                  + " 字节的输出上限");
    }
    return ToolResult.ok(out.toString());
  }

  private record Found(Path path, long modified) {}

  private static List<PathMatcher> matchersFor(String pattern) {
    List<PathMatcher> matchers = new ArrayList<>(2);
    matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
    if (pattern.startsWith("**/")) {
      matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3)));
    }
    return matchers;
  }

  private static boolean matches(List<PathMatcher> matchers, Path relative) {
    Path candidate = Path.of(ToolSupport.slashed(relative));
    for (PathMatcher matcher : matchers) {
      if (matcher.matches(candidate)) {
        return true;
      }
    }
    return false;
  }
}
