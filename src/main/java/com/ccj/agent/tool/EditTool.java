package com.ccj.agent.tool;

import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Exact-string editor.
 *
 * <p>Matching is literal, never regular expressions, so the model must quote the file as it really
 * is. The failure modes are the interesting ones: an absent match reports whether the text exists
 * with different whitespace, and an ambiguous match reports the count - both are what the model
 * needs to retry successfully instead of blindly guessing.
 */
public final class EditTool implements Tool {

  private static final long MAX_EDIT_BYTES = 32L * 1024 * 1024;
  private static final int PREVIEW_CONTEXT = 3;
  private static final int PREVIEW_MAX_LINES = 30;

  @Override
  public String name() {
    return "edit";
  }

  @Override
  public String description() {
    return "Replace an exact string in a UTF-8 file. old_string must match the file literally and "
        + "be unique unless replace_all is true.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "File to edit, relative to the session working directory or absolute."
            },
            "old_string": {
              "type": "string",
              "description": "Exact text to replace, including indentation. Must be unique unless replace_all is true."
            },
            "new_string": {
              "type": "string",
              "description": "Replacement text."
            },
            "replace_all": {
              "type": "boolean",
              "description": "Replace every occurrence instead of requiring exactly one. Defaults to false."
            }
          },
          "required": ["path", "old_string", "new_string"],
          "additionalProperties": false
        }""";
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) throws IOException {
    JsonNode args = ToolSupport.args(argumentsJson);
    Path file = ctx.resolve(ToolSupport.requireText(args, "path"));
    String oldString = ToolSupport.requireText(args, "old_string");
    String newString = ToolSupport.requireText(args, "new_string");
    boolean replaceAll = ToolSupport.optionalBool(args, "replace_all", false);
    String label = ToolSupport.display(ctx, file);

    if (!Files.exists(file)) {
      return ToolResult.error("file not found: " + label);
    }
    if (Files.isDirectory(file)) {
      return ToolResult.error(label + " is a directory");
    }
    if (oldString.isEmpty()) {
      return ToolResult.error(
          "old_string must not be empty; pass the exact text to replace, or use write to create"
              + " content from scratch");
    }
    long size = Files.size(file);
    if (size > MAX_EDIT_BYTES) {
      return ToolResult.error(
          "cannot edit " + label + ": file is " + size + " bytes, over the " + MAX_EDIT_BYTES
              + " byte edit limit");
    }
    String text = ToolSupport.decodeText(Files.readAllBytes(file));
    if (text == null) {
      return ToolResult.error("cannot edit " + label + ": not a UTF-8 text file (binary content)");
    }

    List<int[]> ranges = ToolSupport.findAll(text, oldString);
    if (ranges.isEmpty()) {
      int soft = ToolSupport.countNormalisedMatches(text, oldString);
      String hint =
          soft == 0
              ? "no region matches even after collapsing whitespace; read the file and copy the"
                  + " text verbatim"
              : soft
                  + " region(s) match once whitespace is normalised, so indentation or line breaks"
                  + " differ from the file";
      return ToolResult.error("no exact match for old_string in " + label + ": " + hint);
    }
    if (ranges.size() > 1 && !replaceAll) {
      return ToolResult.error(
          "old_string matches "
              + ranges.size()
              + " times in "
              + label
              + "; add more surrounding context to make it unique, or set replace_all=true to"
              + " change every occurrence");
    }

    StringBuilder detail =
        new StringBuilder(label)
            .append(" (")
            .append(ranges.size())
            .append(ranges.size() == 1 ? " replacement" : " replacements");
    if (!ctx.insideCwd(file)) {
      detail.append("; OUTSIDE session cwd ").append(ctx.cwd());
    }
    detail
        .append(")\n")
        .append(
            DiffPreview.replacements(text, ranges, newString, PREVIEW_CONTEXT, PREVIEW_MAX_LINES));
    if (!ctx.approve("edit", detail.toString())) {
      return ToolResult.error("rejected by user");
    }

    String updated =
        ranges.size() == 1
            ? text.substring(0, ranges.get(0)[0]) + newString + text.substring(ranges.get(0)[1])
            : replaceAll(text, ranges, newString);
    Files.write(file, updated.getBytes(StandardCharsets.UTF_8));
    return ToolResult.ok(
        "replaced "
            + ranges.size()
            + (ranges.size() == 1 ? " occurrence" : " occurrences")
            + " in "
            + label
            + "; file now has "
            + ToolSupport.lineCount(updated)
            + " lines");
  }

  /** Applies the replacements back to front so earlier offsets stay valid. */
  private static String replaceAll(String text, List<int[]> ranges, String newString) {
    StringBuilder builder = new StringBuilder(text);
    for (int i = ranges.size() - 1; i >= 0; i--) {
      int[] range = ranges.get(i);
      builder.replace(range[0], range[1], newString);
    }
    return builder.toString();
  }
}
