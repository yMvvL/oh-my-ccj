package com.ccj.agent.tool;

import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Line-numbered file reader.
 *
 * <p>The whole file is streamed and validated even when only a window is returned: a NUL byte or a
 * malformed UTF-8 sequence anywhere is reported as "binary" instead of leaking replacement
 * characters into the conversation. Memory stays bounded by the requested window, not the file.
 */
public final class ReadTool implements Tool {

  private static final int DEFAULT_LIMIT = 2000;
  private static final int MAX_LIMIT = 100_000;

  @Override
  public String name() {
    return "read";
  }

  @Override
  public boolean readOnly() {
    return true;
  }

  @Override
  public String description() {
    return "Read a UTF-8 text file and return numbered lines. Page through large files with "
        + "offset/limit; the result says where to continue.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "File to read, relative to the session working directory or absolute."
            },
            "offset": {
              "type": "integer",
              "description": "1-based line to start at. Defaults to 1."
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of lines to return. Defaults to 2000."
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
    int offset = ToolSupport.optionalInt(args, "offset", 1, 1, Integer.MAX_VALUE);
    int limit = ToolSupport.optionalInt(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
    String label = ToolSupport.display(ctx, file);

    if (!Files.exists(file)) {
      return ToolResult.error("file not found: " + label);
    }
    if (Files.isDirectory(file)) {
      return ToolResult.error(label + " is a directory; use glob to list the files inside it");
    }

    List<String> window = new ArrayList<>();
    int total = 0;
    var decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try (var reader =
        new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.indexOf('\0') >= 0) {
          return ToolResult.error(binaryMessage(label));
        }
        total++;
        if (total >= offset && window.size() < limit) {
          window.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
      }
    } catch (CharacterCodingException e) {
      return ToolResult.error(binaryMessage(label));
    }

    if (total == 0) {
      return ToolResult.ok("(" + label + " is empty)");
    }
    if (offset > total) {
      return ToolResult.error(
          "offset " + offset + " is past the end of " + label + " (" + total + " lines)");
    }

    StringBuilder body = new StringBuilder();
    int bytes = 0;
    int emitted = 0;
    boolean byteCapped = false;
    boolean lineTruncated = false;
    for (int i = 0; i < window.size(); i++) {
      String rendered = renderLine(offset + i, window.get(i));
      int length = ToolSupport.utf8Length(rendered);
      if (bytes + length > ctx.outputLimitBytes()) {
        byteCapped = true;
        if (emitted == 0) {
          // One line bigger than the whole budget. Skipping it would answer "resume with
          // offset=N" for the same line every time, a page the reader can never turn, so the head
          // of it is returned instead — bounded, and the next offset is past it.
          String clipped = ToolSupport.truncateUtf8(rendered, ctx.outputLimitBytes());
          body.append(clipped);
          bytes += ToolSupport.utf8Length(clipped);
          emitted = 1;
          lineTruncated = true;
        }
        break;
      }
      body.append(rendered);
      bytes += length;
      emitted++;
    }

    int last = offset + emitted - 1;
    if (byteCapped) {
      body.append("(output truncated at ")
          .append(ctx.outputLimitBytes())
          .append(" bytes; ");
      if (lineTruncated) {
        body.append("line ").append(last).append(" is longer than that and was cut short; ");
      }
      body.append(label)
          .append(" has ")
          .append(total)
          .append(" lines; resume with offset=")
          .append(last + 1)
          .append(')');
    } else if (last < total) {
      body.append('(')
          .append(label)
          .append(" has ")
          .append(total)
          .append(" lines; showing lines ")
          .append(offset)
          .append('-')
          .append(last)
          .append("; resume with offset=")
          .append(last + 1)
          .append(')');
    }
    return ToolResult.ok(body.toString());
  }

  private static String binaryMessage(String label) {
    return "cannot read " + label + ": binary content (NUL byte or invalid UTF-8), not a text file";
  }

  private static String renderLine(int number, String text) {
    String digits = Integer.toString(number);
    StringBuilder line = new StringBuilder(digits.length() + text.length() + 7);
    for (int i = digits.length(); i < 5; i++) {
      line.append(' ');
    }
    return line.append(digits).append('\t').append(text).append('\n').toString();
  }
}
