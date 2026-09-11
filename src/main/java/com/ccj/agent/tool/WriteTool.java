package com.ccj.agent.tool;

import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whole-file writer, gated on approval.
 *
 * <p>Replacing a file silently is the most destructive thing a coding agent does, so the approval
 * detail always states the size of the incoming content, whether anything is being overwritten, and
 * - when it is - a bounded diff against the current contents.
 */
public final class WriteTool implements Tool {

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
        + "prefer edit for changing part of an existing file.";
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
    if (!ctx.approve("write", detail.toString())) {
      return ToolResult.error("rejected by user");
    }

    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(file, bytes);
    return ToolResult.ok(
        "wrote "
            + bytes.length
            + " bytes to "
            + label
            + (replacing ? " (replaced existing file)" : " (created new file)"));
  }

  /** Current contents for the overwrite preview, or {@code null} when too large or not text. */
  private static String readForPreview(Path file) throws IOException {
    if (Files.size(file) > PREVIEW_MAX_BYTES) {
      return null;
    }
    return ToolSupport.decodeText(Files.readAllBytes(file));
  }
}
