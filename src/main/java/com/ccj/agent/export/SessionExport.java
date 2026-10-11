package com.ccj.agent.export;

import com.ccj.agent.core.Message;
import com.ccj.agent.core.UsageTotals;
import com.ccj.agent.session.AttachmentStore;
import com.ccj.agent.session.FileSession;
import com.ccj.agent.session.Thumbnails;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * 把一个会话写成一份**自包含**的 HTML：断网也能打开，也不改动原文件。
 *
 * <p>会话是一个 JSONL 文件，那是这个工具的记账方式，不是一个人能读的东西——贴进 issue、发给同事、三年后
 * 再打开，它都不合适。而「本地优先」这件事如果只体现为「文件在你机器上」，就还差一步：一个能拿在手上、
 * 能发给别人的东西。
 *
 * <p>三条规矩：
 *
 * <ul>
 *   <li><strong>自包含。</strong>样式内联、图片以 {@code data:} URL 内联（用缩略图，不是原图），所以整个
 *       文件就是一个文件——没有外链、没有脚本、没有一个会在断网时变成灰色方块的请求。
 *   <li><strong>不改动原文件。</strong>导出只读：会话文件与它的附件目录在导出前后逐字节不变，测试对它们的
 *       （路径、大小、修改时间）做过快照比对。
 *   <li><strong>可重复。</strong>导出时间由调用方传进来，其余一切来自会话本身，所以同一个会话导出两次、
 *       同一个时刻，得到的字节完全相同——一份不能被 diff 的导出，就没法用来回答「上次和这次差在哪」。
 * </ul>
 *
 * <p>页面里没有一行脚本，正文一律被转义后放进 {@code <pre>}：模型写的东西是**文本**，这一点在页面上和在
 * 转录里是同一条规矩。
 */
public final class SessionExport {

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

  /** 一张图片消息的开头：{@code [attachment name.png] 描述…}，与转录里那段文本、压缩、重放用的是同一个形状。 */
  private static final String PICTURE_PREFIX = "[attachment ";

  private SessionExport() {}

  /**
   * 一份自包含的 HTML。
   *
   * @param exportedAt 写在页眉与页脚的那个时刻；同一个会话配同一个时刻，导出两次字节相同
   */
  public static String toHtml(FileSession session, Instant exportedAt) {
    List<Message> messages = session.messages();
    UsageTotals totals = session.totals();
    Path attachments = AttachmentStore.forSession(session.file()).directory();

    StringBuilder out = new StringBuilder();
    out.append("<!doctype html>\n<html lang=\"zh\">\n<head>\n<meta charset=\"utf-8\">\n");
    out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
    out.append("<title>ccj 会话 ").append(esc(session.id())).append("</title>\n");
    out.append("<style>\n").append(stylesheet()).append("</style>\n</head>\n<body>\n");
    out.append("<main>\n");

    out.append("<header>\n<h1>ccj 会话 ").append(esc(session.id())).append("</h1>\n");
    out.append("<dl class=\"meta\">\n");
    out.append(row("消息", Integer.toString(messages.size())));
    out.append(row("导出时间", TIMESTAMP.format(exportedAt)));
    out.append(row("回合与用量", ledger(totals)));
    out.append("</dl>\n</header>\n");

    out.append("<section class=\"transcript\">\n");
    for (Message message : messages) {
      append(out, message, attachments);
    }
    out.append("</section>\n");

    out.append("<footer>\n<p>这份文件是从 <code>").append(esc(session.id()))
        .append(".jsonl</code> 导出的一个副本，原文件与它的图片没有被改动。图片是缩略图，"
            + "要原图请回到那个会话所在的机器上找 <code>").append(esc(session.id()))
        .append(AttachmentStore.DIRECTORY_SUFFIX).append("</code>。</p>\n</footer>\n");
    out.append("</main>\n</body>\n</html>\n");
    return out.toString();
  }

  /** 页眉里那一行账本，与用量面板说的是同一件事。 */
  private static String ledger(UsageTotals totals) {
    StringBuilder out = new StringBuilder();
    out.append(totals.inputTokens()).append(" 输入 / ").append(totals.outputTokens()).append(" 输出 token");
    Double rate = totals.cacheHitRate();
    if (rate == null) {
      // 「没有信息」与「零」是两件事，导出也不该把它们画成同一个数。
      out.append("（这个提供方不报缓存，命中率未上报）");
    } else {
      out.append("（缓存命中 ").append(Math.round(rate * 100)).append("%）");
    }
    out.append("，").append(totals.modelTurns()).append(" 个模型回合，")
        .append(totals.toolCalls()).append(" 次工具调用，")
        .append(totals.toolErrors()).append(" 次错误，")
        .append(Math.round(totals.elapsedMillis() / 1000.0)).append(" 秒");
    if (totals.compactions() > 0) {
      out.append("，压缩过 ").append(totals.compactions()).append(" 次");
    }
    return out.toString();
  }

  private static void append(StringBuilder out, Message message, Path attachments) {
    switch (message) {
      case Message.System system ->
          out.append("<section class=\"msg msg-system\"><h2>系统提示</h2><pre>")
              .append(esc(system.text()))
              .append("</pre></section>\n");
      case Message.User user -> appendUser(out, user, attachments);
      case Message.Assistant assistant -> {
        out.append("<section class=\"msg msg-assistant\">\n<h2>助手</h2>\n");
        if (!assistant.text().isBlank()) {
          out.append("<pre class=\"text\">").append(esc(assistant.text())).append("</pre>\n");
        }
        for (Message.Thinking block : assistant.thinking()) {
          out.append("<details class=\"thinking\"><summary>思考块</summary><pre>")
              .append(esc(block.text()))
              .append("</pre></details>\n");
        }
        for (Message.ToolCall call : assistant.toolCalls()) {
          out.append("<div class=\"call\"><span class=\"tool\">")
              .append(esc(call.name()))
              .append("</span><pre>")
              .append(esc(pretty(call.arguments())))
              .append("</pre></div>\n");
        }
        out.append("</section>\n");
      }
      case Message.ToolResult result -> {
        out.append("<section class=\"msg msg-tool\">\n<details><summary>结果 · ")
            .append(esc(result.toolName()))
            .append(result.error() ? "（错误）" : "")
            .append("</summary><pre>")
            .append(esc(result.content()))
            .append("</pre></details>\n</section>\n");
      }
      case Message.Summary summary -> {
        out.append("<section class=\"msg msg-summary\"><h2>摘要</h2><p class=\"note\">它替换掉了 ")
            .append(summary.covers())
            .append(" 条更早的消息，原文仍在 ")
            .append(esc(summary.source()))
            .append(" 里。</p><pre>")
            .append(esc(summary.text()))
            .append("</pre></section>\n");
      }
    }
  }

  /**
   * 一条用户消息；如果它是带着图片的那一条，图片以缩略图内联进来。
   *
   * <p>名字按附件目录里的一张**平面**文件来解析（{@code ../}、绝对路径、跑到目录之外的符号链接都当没有
   * 这个文件）——导出的输入是不可信的文本，而它唯一会碰到文件系统的地方就是这里。解析不出来时那段文字照常
   * 显示，什么都不猜。
   */
  private static void appendUser(StringBuilder out, Message.User user, Path attachments) {
    out.append("<section class=\"msg msg-user\">\n<h2>用户</h2>\n");
    String text = user.text() == null ? "" : user.text();
    String picture = attachmentName(text);
    if (picture != null && attachments != null) {
      String inlined = inlineThumbnail(attachments, picture);
      if (inlined != null) {
        out.append("<figure><img alt=\"").append(esc(picture)).append("\" src=\"")
            .append(esc(inlined))
            .append("\"><figcaption>").append(esc(picture)).append("</figcaption></figure>\n");
      }
    }
    out.append("<pre class=\"text\">").append(esc(text)).append("</pre>\n</section>\n");
  }

  /** 那段文本里点名的图片名；不是一条图片消息时为 null。 */
  static String attachmentName(String text) {
    if (!text.startsWith(PICTURE_PREFIX)) {
      return null;
    }
    int end = text.indexOf(']', PICTURE_PREFIX.length());
    if (end < 0) {
      return null;
    }
    String name = text.substring(PICTURE_PREFIX.length(), end).strip();
    return name.isEmpty() ? null : name;
  }

  /** 缩略图的 {@code data:} URL；读不出来或画不出来时为 null。 */
  private static String inlineThumbnail(Path attachments, String name) {
    AttachmentStore store = AttachmentStore.at(attachments);
    Path file = store.resolve(name);
    if (file == null) {
      return null;
    }
    try {
      byte[] original = Files.readAllBytes(file);
      byte[] thumb = Thumbnails.of(original);
      String mediaType = AttachmentStore.mediaTypeOf(thumb);
      return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(thumb);
    } catch (IOException | RuntimeException e) {
      // 画不出小图不是一次失败：那张图片本身是好的，而这一条导出不该因此整个失败。
      return null;
    }
  }

  /** JSON 参数摊开成多行，只为了在页面里读得下去。读不出来的原样显示。 */
  private static String pretty(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return "";
    }
    try {
      return com.ccj.agent.core.Json.writePretty(com.ccj.agent.core.Json.parse(argumentsJson));
    } catch (IllegalArgumentException e) {
      return argumentsJson;
    }
  }

  private static String row(String label, String value) {
    return "  <div><dt>" + esc(label) + "</dt><dd>" + esc(value) + "</dd></div>\n";
  }

  /** 页面里的每一个插值都过这里。 */
  static String esc(String text) {
    if (text == null) {
      return "";
    }
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  /**
   * 样式内联，而且**没有任何脚本**。
   *
   * <p>颜色只用系统色（{@code color-scheme} + {@code prefers-color-scheme}），因为这份文件会被拷到别的
   * 机器、别的主题下去看：写死一套配色，等于替读它的人决定他该用什么主题。
   */
  private static String stylesheet() {
    return """
        :root { color-scheme: light dark; }
        body { margin: 0; background: Canvas; color: CanvasText; line-height: 1.6;
               font: 15px/1.6 system-ui, -apple-system, "Segoe UI", sans-serif; }
        main { max-width: 900px; margin: 0 auto; padding: 32px 20px 80px; }
        h1 { font-size: 20px; font-weight: 600; margin: 0 0 12px; }
        h2 { font-size: 13px; font-weight: 600; margin: 0 0 6px; opacity: 0.65;
             text-transform: uppercase; letter-spacing: 0.04em; }
        .meta { display: grid; grid-template-columns: max-content 1fr; gap: 4px 16px;
                margin: 0 0 28px; padding: 12px 16px; border: 1px solid color-mix(in srgb, CanvasText 14%, transparent);
                border-radius: 10px; font-size: 13px; }
        .meta div { display: contents; }
        .meta dt { opacity: 0.6; }
        .meta dd { margin: 0; }
        .msg { margin: 0 0 20px; padding: 14px 16px;
               border: 1px solid color-mix(in srgb, CanvasText 12%, transparent); border-radius: 12px; }
        .msg-system, .msg-summary { background: color-mix(in srgb, CanvasText 5%, transparent); }
        .msg-user { border-left: 3px solid color-mix(in srgb, CanvasText 35%, transparent); }
        .msg-assistant { border-left: 3px solid color-mix(in srgb, CanvasText 60%, transparent); }
        .msg-tool { border-style: dashed; }
        pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere;
              font: 13px/1.55 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
        .text { font: inherit; }
        details { margin: 8px 0 0; }
        summary { cursor: pointer; font-size: 13px; opacity: 0.75; }
        .call { margin: 8px 0 0; }
        .call .tool { display: inline-block; margin-bottom: 4px; padding: 1px 8px; border-radius: 999px;
                      background: color-mix(in srgb, CanvasText 10%, transparent); font-size: 12px; }
        .note { margin: 0 0 8px; font-size: 13px; opacity: 0.7; }
        figure { margin: 0 0 10px; }
        img { max-width: 100%; border-radius: 8px; border: 1px solid color-mix(in srgb, CanvasText 14%, transparent); }
        figcaption { font-size: 12px; opacity: 0.6; margin-top: 4px; }
        footer { margin-top: 40px; padding-top: 16px; border-top: 1px solid color-mix(in srgb, CanvasText 12%, transparent);
                 font-size: 12px; opacity: 0.7; }
        """;
  }
}
