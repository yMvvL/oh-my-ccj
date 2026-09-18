package com.ccj.agent.tool;

import com.ccj.agent.core.ApprovalRequest;
import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
  /** The check that runs after a successful "edit": nothing unless the user declared one. */
  private final PostEditCheck check;

  public EditTool() {
    this(com.ccj.agent.core.Checks.none());
  }

  public EditTool(com.ccj.agent.core.Checks checks) {
    this.check = new PostEditCheck(checks);
  }

  private static final int PREVIEW_CONTEXT = 3;
  private static final int PREVIEW_MAX_LINES = 30;

  @Override
  public String name() {
    return "edit";
  }

  @Override
  public String description() {
    return "Replace an exact string in a UTF-8 file. old_string must match the file literally and "
        + "be unique unless replace_all is true. If the project configures a check for this file "
        + "type, it runs after the write and its verdict is appended to this result.";
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
    String refusal = ctx.refusal(ApprovalRequest.file("edit", file, detail.toString()));
    if (refusal != null) {
      return ToolResult.error(refusal);
    }

    // Re-read before writing, because the approval is a window in which somebody else can change the
    // file — the user in their editor, another conversation's turn, a formatter on save. The ranges
    // above were computed against the text as it was when the diff was shown, and applying them to
    // whatever is on disk now would write back a version that predates the other change: their edit
    // silently gone, with an approval prompt that showed a diff nobody could tell was stale.
    String current;
    try {
      current = ToolSupport.decodeText(Files.readAllBytes(file));
    } catch (IOException | RuntimeException e) {
      return ToolResult.error(label + " could not be re-read before editing: " + e.getMessage());
    }
    if (!current.equals(text)) {
      return ToolResult.error(
          "refused: "
              + label
              + " changed while this edit was waiting for approval. What is on disk now is not what"
              + " the diff showed, so applying it would discard the other change. Read the file again"
              + " and redo the edit against what is there now.");
    }

    String updated =
        ranges.size() == 1
            ? text.substring(0, ranges.get(0)[0]) + newString + text.substring(ranges.get(0)[1])
            : replaceAll(text, ranges, newString);
    // Written to a sibling and moved into place, so a crash or a full disk cannot leave the file
    // half-written: the failure mode of a partial write to a source file is worse than not editing it.
    writeAtomically(file, updated);
    return ToolResult.ok(
        "replaced "
            + ranges.size()
            + (ranges.size() == 1 ? " occurrence" : " occurrences")
            + " in "
            + label
            + "; file now has "
            + ToolSupport.lineCount(updated)
            + " lines"
            + check.afterEditing(file, ctx));
  }

  /**
   * Writes through a temporary file in the same directory, then renames it into place.
   *
   * <p>The rename is atomic within one filesystem, so a reader sees either the old file or the new
   * one — never a truncated mixture. Same directory because a rename across filesystems is a copy,
   * which is the non-atomic thing this exists to avoid.
   */
  private static void writeAtomically(Path file, String content) throws IOException {
    Path parent = file.getParent();
    Path staged =
        Files.createTempFile(parent == null ? Path.of(".") : parent, ".ccj-edit", ".tmp");
    try {
      Files.write(staged, content.getBytes(StandardCharsets.UTF_8));
      // Copied onto the sibling so the replacement keeps the original's permissions: a fresh temp
      // file is 0600, and silently tightening a file the user had made readable is a change nobody
      // asked for.
      try {
        Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(file));
      } catch (UnsupportedOperationException | IOException ignored) {
        // Not a POSIX filesystem, or the permissions cannot be read: the content is what matters.
      }
      Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      Files.deleteIfExists(staged);
      throw e;
    }
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
