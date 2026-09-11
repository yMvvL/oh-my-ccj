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
 * Line-oriented regex search.
 *
 * <p>This runs over whatever tree the model points at, so every candidate file is vetted cheaply
 * before being read: size cap, NUL probe, then a strict UTF-8 decode. Without those filters a
 * single stray binary in a source tree floods the conversation with garbage.
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
          "invalid regex '" + pattern + "': " + e.getDescription() + " at index " + e.getIndex());
    }
    PathMatcher filter = null;
    if (glob != null && !glob.isBlank()) {
      try {
        filter = FileSystems.getDefault().getPathMatcher("glob:" + glob);
      } catch (RuntimeException e) {
        return ToolResult.error("invalid glob '" + glob + "': " + e.getMessage());
      }
    }

    Path base = pathArg == null || pathArg.isBlank() ? ctx.cwd() : ctx.resolve(pathArg);
    if (!Files.exists(base)) {
      return ToolResult.error("path not found: " + ToolSupport.display(ctx, base));
    }

    List<String> hits = new ArrayList<>();
    long[] matched = new long[1];
    if (Files.isRegularFile(base)) {
      search(base, ToolSupport.display(ctx, base), regex, filter, null, maxResults, hits, matched);
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
                  matched));
    } else {
      return ToolResult.error("path is neither a file nor a directory: " + ToolSupport.display(ctx, base));
    }

    if (matched[0] == 0) {
      return ToolResult.ok(
          "no matches for /" + pattern + "/ under " + ToolSupport.display(ctx, base));
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
      out.append("... ").append(omitted).append(" more matches omitted ...\n");
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
      long[] matched)
      throws IOException {
    if (filter != null && !matchesFilter(filter, relative, file)) {
      return;
    }
    if (Files.size(file) > MAX_FILE_BYTES || ToolSupport.isBinaryFile(file)) {
      return;
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (CharacterCodingException e) {
      return;
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

  /** A glob filter matches either the path relative to the search root or the bare file name. */
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
