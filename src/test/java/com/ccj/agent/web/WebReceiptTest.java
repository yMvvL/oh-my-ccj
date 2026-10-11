package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 收据跨过真的 HTTP，而它的每一条事实都能与别处对上。
 *
 * <p>路线图给这一项的验收是三条对照：文件清单与 {@code GET /api/undo} 的预览一致、命令与退出码与转录一致、
 * token 与账本一致。前两条在这里逐字比对——它们能做到一致不是因为有人记得同步，而是因为两边读的是同一份
 * 数据和同一段代码。
 */
class WebReceiptTest extends WebHarness {

  private static Message.Assistant write(String path, String content) {
    return new Message.Assistant(
        "",
        List.of(
            new Message.ToolCall(
                "call_write",
                "write",
                Json.write(Json.object().put("path", path).put("content", content)))),
        List.of());
  }

  /** 等第 {@code index} 个（从 0 数）审批请求到达，然后作答。 */
  private void answer(Sse sse, int index, String answer) throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    JsonNode approval = null;
    while (System.nanoTime() < deadline) {
      List<JsonNode> seen = sse.ofType("approval");
      if (seen.size() > index) {
        approval = seen.get(index);
        break;
      }
      Thread.sleep(10);
    }
    assertNotNull(approval, "第 " + (index + 1) + " 个审批请求应当到达：" + sse.snapshot());
    post(
        "/api/approval",
        "{\"id\":\"" + approval.path("id").asText() + "\",\"answer\":\"" + answer + "\"}");
  }

  @Test
  void theReceiptNamesTheFilesTheCommandsAndWhatWasNotVerified() throws Exception {
    // 改一个已经存在的文件（于是它是一条「改动」，不是「新建」），再跑一条命令。
    Files.writeString(cwd.resolve("notes.txt"), "old\n");
    provider.reply(write("notes.txt", "new\n"));
    provider.reply(bashCall("printf hi > made.txt"));
    provider.reply(Message.Assistant.text("做完了。"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"改个文件再跑条命令\"}");
      answer(sse, 0, "always");
      answer(sse, 1, "always");
      sse.await("done", 5000);
    }

    JsonNode receipt = json("/api/receipt").path("receipt");
    assertFalse(receipt.isNull(), "跑过回合之后就该有收据");
    assertEquals(3, receipt.path("steps").asInt(), receipt.toString());

    // 改动：与 /api/undo 的预览逐字一致。
    JsonNode preview = json("/api/undo").path("files");
    assertEquals(preview.size(), receipt.path("changed").size(), receipt.toString());
    assertEquals(
        preview.get(0).path("path").asText(), receipt.path("changed").get(0).path("path").asText());
    assertEquals(
        preview.get(0).path("action").asText(),
        receipt.path("changed").get(0).path("action").asText());
    assertEquals("restore", receipt.path("changed").get(0).path("action").asText(), "文件原来就在");
    assertEquals("new\n", Files.readString(cwd.resolve("notes.txt")), "收据不改动任何东西");

    // 命令：与转录里的那一条一致，含退出码。
    assertEquals(1, receipt.path("commands").size(), receipt.toString());
    assertEquals("printf hi > made.txt", receipt.path("commands").get(0).path("line").asText());
    assertEquals(0, receipt.path("commands").get(0).path("exitCode").asInt());

    // 工具：两次调用，都成功。
    assertEquals(2, receipt.path("calls").size(), receipt.toString());
    assertTrue(receipt.path("calls").get(0).path("ok").asBoolean());
    assertFalse(receipt.path("calls").get(0).path("refused").asBoolean());

    // token：收据里的「会话累计」必须与用量面板上是同一个数——它们读的是同一本账，而这里是那条主张的
    // 证据。
    JsonNode usage = json("/api/status").path("usage");
    assertEquals(
        usage.path("inputTokens").asLong(),
        receipt.path("tokens").path("sessionInput").asLong(),
        receipt.toString());
    assertEquals(usage.path("outputTokens").asLong(), receipt.path("tokens").path("sessionOutput").asLong());

    // 没有验证：这个测试没有配置编辑后检查，所以那一条必须出现。
    String text = receipt.path("text").asText();
    assertTrue(text.contains("没有验证"), text);
    assertTrue(text.contains("notes.txt"), text);
    assertTrue(text.contains("没有配置任何适用于它们的编辑后检查"), text);
    assertTrue(text.startsWith("收据 · 会话 "), text);
  }

  @Test
  void aConversationOnlyTurnSaysItVerifiedNothing() throws Exception {
    provider.reply(Message.Assistant.text("只是聊了两句。"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"随便聊聊\"}");
      sse.await("done", 5000);
    }

    JsonNode receipt = json("/api/receipt").path("receipt");
    assertTrue(receipt.path("quiet").asBoolean(), receipt.toString());
    assertEquals(0, receipt.path("changed").size());
    assertEquals(0, receipt.path("commands").size());
    String text = receipt.path("text").asText();
    assertTrue(text.contains("没有改动任何文件"), text);
    assertTrue(text.contains("没有跑任何命令"), text);
    assertTrue(text.contains("这一轮只有对话：没有改动，没有命令，没有验证"), text);
  }

  @Test
  void beforeAnyTurnThereIsNoReceiptRatherThanAnError() throws Exception {
    JsonNode answer = json("/api/receipt");

    assertTrue(answer.path("receipt").isNull(), answer.toString());
    assertEquals(200, get("/api/receipt").statusCode());
    assertEquals(405, post("/api/receipt", "{}").statusCode());
  }

  @Test
  void thePageAddressesTheIdsTheReceiptPanelNeeds() throws IOException {
    // 一个拼错的 id 就是一个不会动的按钮，而它在浏览器里看起来和「还没跑过回合」一模一样。所以这里让
    // 页面与脚本互相核对一遍：脚本找的每一个 id，标记里都得真的有。
    String page = Files.readString(Path.of("src", "main", "resources", "web", "index.html"));
    String script = WebSessionRowTest.appSourceOrSkip();

    for (String id : List.of("btn-receipt", "receipt-overlay", "receipt-text", "receipt-hint", "receipt-close")) {
      assertTrue(page.contains("id=\"" + id + "\""), "index.html 里应当有 id=\"" + id + "\"");
      assertTrue(script.contains("$('" + id + "')"), "app.js 应当认领 " + id);
    }
    assertTrue(page.contains("id=\"btn-export\""), "导出是一个链接，所以它在标记里");
    assertTrue(script.contains("/api/export?id="), "而它的目标由脚本按当前会话填");
    assertTrue(script.contains("function openReceipt("), "收据由这个函数取回来并画出来");
    assertTrue(
        script.contains("dom.receiptText.textContent"),
        "正文是**文本**放进去的：收据里有模型写过的话，而它不是一个可以当 HTML 用的东西");
  }
}
