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
 * 带行号的文件读取器。
 *
 * <p>即使只返回一个窗口，整个文件也会被流式读取并校验：任意位置的 NUL 字节或残缺的 UTF-8 序列都会被
 * 报成「binary」，而不是把替换字符漏进对话。内存只受所请求窗口的限制，不受文件大小限制。
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
      return ToolResult.error("找不到文件: " + label);
    }
    if (Files.isDirectory(file)) {
      return ToolResult.error(label + " 是目录；用 glob 列出它里面的文件");
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
      return ToolResult.ok("(" + label + " 为空)");
    }
    if (offset > total) {
      return ToolResult.error(
          "offset " + offset + " 越过了 " + label + " 的末尾（共 " + total + " 行）");
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
          // 有一行比整个预算还大。跳过它会让同一行每次都回答「resume with offset=N」，那是一页读者
          // 永远翻不过去的页，所以改为返回它的开头——有界，且下一个 offset 会越过它。
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
      body.append("(输出在 ")
          .append(ctx.outputLimitBytes())
          .append(" 字节处被截断；");
      if (lineTruncated) {
        body.append("第 ").append(last).append(" 行比这更长，已被截短；");
      }
      body.append(label)
          .append(" 共 ")
          .append(total)
          .append(" 行；用 offset=")
          .append(last + 1)
          .append(" 继续)");
    } else if (last < total) {
      body.append('(')
          .append(label)
          .append(" 共 ")
          .append(total)
          .append(" 行；显示第 ")
          .append(offset)
          .append('-')
          .append(last)
          .append(" 行；用 offset=")
          .append(last + 1)
          .append(" 继续)");
    }
    return ToolResult.ok(body.toString());
  }

  private static String binaryMessage(String label) {
    return "无法读取 " + label + "：二进制内容（NUL 字节或非法 UTF-8），不是文本文件";
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
