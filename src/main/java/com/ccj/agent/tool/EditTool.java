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
import java.util.ArrayList;
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
    return "Replace exact strings in a UTF-8 file. Either one change (old_string/new_string), or "
        + "several at once (edits: [{old_string, new_string, replace_all}]) which are matched, "
        + "approved and written together — all of them or none. Text must match the file literally. "
        + "If the project configures a check for this file type, it runs after the write and its "
        + "verdict is appended to this result.";
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
            },
            "edits": {
              "type": "array",
              "description": "Several changes to one file, applied together: one approval, one write, and nothing written unless every one of them matches. Use this instead of calling edit once per change. Each entry is {old_string, new_string, replace_all?} and must not overlap another.",
              "items": {
                "type": "object",
                "properties": {
                  "old_string": {
                    "type": "string",
                    "description": "Exact text to replace, including indentation."
                  },
                  "new_string": {
                    "type": "string",
                    "description": "Replacement text."
                  },
                  "replace_all": {
                    "type": "boolean",
                    "description": "Replace every occurrence of this hunk. Defaults to false."
                  }
                },
                "required": ["old_string", "new_string"],
                "additionalProperties": false
              }
            }
          },
          "required": ["path"],
          "additionalProperties": false
        }""";
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) throws IOException {
    JsonNode args = ToolSupport.args(argumentsJson);
    Path file = ctx.resolve(ToolSupport.requireText(args, "path"));
    List<Hunk> hunks;
    try {
      hunks = hunks(args);
    } catch (IllegalArgumentException badRequest) {
      return ToolResult.error(badRequest.getMessage());
    }
    String label = ToolSupport.display(ctx, file);

    if (!Files.exists(file)) {
      return ToolResult.error("file not found: " + label);
    }
    if (Files.isDirectory(file)) {
      return ToolResult.error(label + " is a directory");
    }
    for (Hunk hunk : hunks) {
      if (hunk.oldString().isEmpty()) {
        return ToolResult.error(
            "old_string must not be empty; pass the exact text to replace, or use write to create"
                + " content from scratch");
      }
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

    List<int[]> ranges = new ArrayList<>();
    List<String> replacements = new ArrayList<>();
    List<Integer> owners = new ArrayList<>();
    for (int index = 0; index < hunks.size(); index++) {
      Hunk hunk = hunks.get(index);
      List<int[]> found = ToolSupport.findAll(text, hunk.oldString());
      if (found.isEmpty()) {
        return ToolResult.error(
            "nothing was written: "
                + failure(text, hunks, index, "no exact match")
                + "\n"
                + neighbourhood(text, hunk));
      }
      if (found.size() > 1 && !hunk.replaceAll()) {
        return ToolResult.error(
            "nothing was written: "
                + failure(text, hunks, index, "matches " + found.size() + " times")
                + "; add more surrounding context to make it unique, or set replace_all=true for"
                + " that hunk");
      }
      for (int[] range : found) {
        ranges.add(range);
        replacements.add(hunk.newString());
        owners.add(index);
      }
    }
    String overlap = overlap(ranges, owners, hunks);
    if (overlap != null) {
      return ToolResult.error("nothing was written: " + overlap);
    }

    String updated = applyAll(text, ranges, replacements);
    StringBuilder detail =
        new StringBuilder(label)
            .append(" (")
            .append(hunks.size())
            .append(hunks.size() == 1 ? " hunk, " : " hunks, ")
            .append(ranges.size())
            .append(ranges.size() == 1 ? " replacement" : " replacements");
    if (!ctx.insideCwd(file)) {
      detail.append("; OUTSIDE session cwd ").append(ctx.cwd());
    }
    // One diff of the whole change rather than one per hunk: the person approving is deciding about
    // the file that comes out of this, and three separate diffs of one file invite three separate
    // answers to one question.
    detail
        .append(")\n")
        .append(
            hunks.size() == 1
                ? DiffPreview.replacements(
                    text, ranges, replacements.get(0), PREVIEW_CONTEXT, PREVIEW_MAX_LINES)
                : DiffPreview.unified(text, updated, PREVIEW_CONTEXT, PREVIEW_MAX_LINES));
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

    // Written to a sibling and moved into place, so a crash or a full disk cannot leave the file
    // half-written: the failure mode of a partial write to a source file is worse than not editing it.
    writeAtomically(file, updated);
    return ToolResult.ok(
        "replaced "
            + ranges.size()
            + (ranges.size() == 1 ? " occurrence" : " occurrences")
            + (hunks.size() == 1 ? "" : " across " + hunks.size() + " hunks")
            + " in "
            + label
            + "; file now has "
            + ToolSupport.lineCount(updated)
            + " lines"
            + check.afterEditing(file, ctx));
  }

  /** One change to make: what to match, what to put there, and whether every occurrence is meant. */
  private record Hunk(String oldString, String newString, boolean replaceAll) {}

  /**
   * The hunks this request asks for: the several-change form, or the single-change one.
   *
   * <p>Both shapes stay because they are different jobs. One change is the common case and the flat
   * arguments are the shortest way to ask for it; several changes to one file are what a model does
   * when it fixes five places, and doing that as five calls is five approvals, five writes, and four
   * opportunities for a file to change under an approval that was already shown.
   */
  private static List<Hunk> hunks(JsonNode args) {
    JsonNode edits = args.get("edits");
    boolean flat = args.hasNonNull("old_string") || args.hasNonNull("new_string");
    if (edits != null && !edits.isNull()) {
      if (flat) {
        throw new IllegalArgumentException(
            "pass either old_string/new_string for one change, or edits for several, not both");
      }
      if (!edits.isArray() || edits.isEmpty()) {
        throw new IllegalArgumentException("edits must be a non-empty array of changes");
      }
      List<Hunk> hunks = new ArrayList<>();
      for (JsonNode entry : edits) {
        if (!entry.isObject()) {
          throw new IllegalArgumentException("each entry of edits must be an object: " + entry);
        }
        hunks.add(
            new Hunk(
                ToolSupport.requireText(entry, "old_string"),
                ToolSupport.requireText(entry, "new_string"),
                ToolSupport.optionalBool(entry, "replace_all", false)));
      }
      return hunks;
    }
    return List.of(
        new Hunk(
            ToolSupport.requireText(args, "old_string"),
            ToolSupport.requireText(args, "new_string"),
            ToolSupport.optionalBool(args, "replace_all", false)));
  }

  /** Which hunk failed and why, in the terms a model needs to retry successfully. */
  private static String failure(String text, List<Hunk> hunks, int index, String why) {
    String where = hunks.size() == 1 ? "the edit" : "hunk " + (index + 1) + " of " + hunks.size();
    Hunk hunk = hunks.get(index);
    int soft = ToolSupport.countNormalisedMatches(text, hunk.oldString());
    String hint =
        soft == 0
            ? "no region matches even after collapsing whitespace"
            : soft + " region(s) match once whitespace is normalised, so indentation or line breaks "
                + "differ from the file";
    return where + " found " + why + ": " + hint;
  }

  /** The lines around where a failed hunk was expected, so the retry needs no separate read. */
  private static String neighbourhood(String text, Hunk hunk) {
    int line = ToolSupport.lineOfFirstLine(text, hunk.oldString());
    if (line < 0) {
      return "(its first line appears nowhere in the file)";
    }
    return "(around line " + line + ", where its first line appears)\n"
        + ToolSupport.excerpt(text, line, PREVIEW_CONTEXT);
  }

  /**
   * Refuses hunks that overlap, naming both.
   *
   * <p>Overlapping hunks have no defined result — whichever is applied second is applied to text the
   * first one replaced — and a patch that half-applies is the thing this tool must never produce.
   */
  private static String overlap(List<int[]> ranges, List<Integer> owners, List<Hunk> hunks) {
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < ranges.size(); i++) {
      order.add(i);
    }
    order.sort((left, right) -> Integer.compare(ranges.get(left)[0], ranges.get(right)[0]));
    for (int i = 1; i < order.size(); i++) {
      int previous = order.get(i - 1);
      int current = order.get(i);
      if (ranges.get(current)[0] < ranges.get(previous)[1]) {
        return "hunks "
            + (owners.get(previous) + 1)
            + " and "
            + (owners.get(current) + 1)
            + " overlap in "
            + "the text they match; merge them into one hunk, or reorder them so they do not"
            + " cover the same text";
      }
    }
    return null;
  }

  /** Every replacement, back to front, so the offsets of the ones not yet applied stay valid. */
  private static String applyAll(String text, List<int[]> ranges, List<String> replacements) {
    StringBuilder builder = new StringBuilder(text);
    for (int i = ranges.size() - 1; i >= 0; i--) {
      int[] range = ranges.get(i);
      builder.replace(range[0], range[1], replacements.get(i));
    }
    return builder.toString();
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
