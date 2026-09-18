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

/**
 * Whole-file writer, gated on approval.
 *
 * <p>Replacing a file silently is the most destructive thing a coding agent does, so the approval
 * detail always states the size of the incoming content, whether anything is being overwritten, and
 * - when it is - a bounded diff against the current contents.
 */
public final class WriteTool implements Tool {

  /** The check that runs after a successful "write": nothing unless the user declared one. */
  private final PostEditCheck check;

  public WriteTool() {
    this(com.ccj.agent.core.Checks.none());
  }

  public WriteTool(com.ccj.agent.core.Checks checks) {
    this.check = new PostEditCheck(checks);
  }

  private static final int PREVIEW_CONTEXT = 3;
  private static final int PREVIEW_MAX_LINES = 24;
  private static final long PREVIEW_MAX_BYTES = 1024L * 1024;

  @Override
  public String name() {
    return "write";
  }

  @Override
  public String description() {
    return "Write a UTF-8 file, creating parent directories as needed. Overwrites the whole file; "
        + "prefer edit for changing part of an existing file. If the project configures a check for "
        + "this file type, it runs after the write and its verdict is appended to this result.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "File to write, relative to the session working directory or absolute."
            },
            "content": {
              "type": "string",
              "description": "Complete new contents of the file."
            }
          },
          "required": ["path", "content"],
          "additionalProperties": false
        }""";
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) throws IOException {
    JsonNode args = ToolSupport.args(argumentsJson);
    Path file = ctx.resolve(ToolSupport.requireText(args, "path"));
    String content = ToolSupport.requireText(args, "content");
    String label = ToolSupport.display(ctx, file);
    if (Files.isDirectory(file)) {
      return ToolResult.error(label + " is a directory");
    }

    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    boolean replacing = Files.exists(file);
    StringBuilder detail =
        new StringBuilder(label)
            .append(" (")
            .append(ToolSupport.lineCount(content))
            .append(" lines, ")
            .append(bytes.length)
            .append(" bytes, ")
            .append(replacing ? "replaces existing content" : "creates a new file");
    if (!ctx.insideCwd(file)) {
      detail.append("; OUTSIDE session cwd ").append(ctx.cwd());
    }
    detail.append(')');
    if (replacing) {
      String existing = readForPreview(file);
      if (existing != null && !existing.equals(content)) {
        detail
            .append('\n')
            .append(DiffPreview.unified(existing, content, PREVIEW_CONTEXT, PREVIEW_MAX_LINES));
      }
    }
    String refusal = ctx.refusal(ApprovalRequest.file("write", file, detail.toString()));
    if (refusal != null) {
      return ToolResult.error(refusal);
    }

    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    // A file that appeared while the approval was waiting is a change the prompt did not mention: the
    // approver agreed to "creates a new file" and would now be overwriting somebody's work. The
    // reverse is fine — a file that was there and was deleted means the write creates what it claimed
    // to create — so only the appearance is refused, and the message says what to do about it.
    if (!replacing && Files.exists(file)) {
      return ToolResult.error(
          "refused: "
              + label
              + " did not exist when this write was approved, and does now. Writing would discard"
              + " whatever appeared in between. Read it first, then decide.");
    }
    // Staged and renamed rather than written in place, so a crash cannot leave a truncated file: a
    // half-written source file is worse than none, because nothing about it says it is incomplete.
    writeAtomically(file, bytes);
    return ToolResult.ok(
        "wrote "
            + bytes.length
            + " bytes to "
            + label
            + (replacing ? " (replaced existing file)" : " (created new file)")
            + check.afterEditing(file, ctx));
  }

  /**
   * Writes through a temporary file in the same directory and renames it into place.
   *
   * <p>The rename is atomic within one filesystem, so a reader sees the old file or the new one and
   * never a truncated mixture. Same directory, because a rename across filesystems degrades to a
   * copy, which is the non-atomic thing this exists to avoid.
   */
  private static void writeAtomically(Path file, byte[] bytes) throws IOException {
    Path parent = file.getParent();
    Path staged = Files.createTempFile(parent == null ? Path.of(".") : parent, ".ccj-write", ".tmp");
    try {
      Files.write(staged, bytes);
      if (Files.exists(file)) {
        // The original's permissions, so replacing a file does not quietly change who can read it.
        try {
          Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(file));
        } catch (UnsupportedOperationException | IOException ignored) {
          // Not a POSIX filesystem, or unreadable: the content is what matters.
        }
      }
      Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      Files.deleteIfExists(staged);
      throw e;
    }
  }

  /** Current contents for the overwrite preview, or {@code null} when too large or not text. */
  private static String readForPreview(Path file) throws IOException {
    if (Files.size(file) > PREVIEW_MAX_BYTES) {
      return null;
    }
    return ToolSupport.decodeText(Files.readAllBytes(file));
  }
}
