package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 收据：它说了什么，以及它**拒绝**说什么。
 *
 * <p>这个类最要紧的一条主张不是它列出的那些数字，而是它对「没有验证」那一节的处理：一句编出来的「一切正常」
 * 比一句「我不知道」更糟，所以这里逐条钉住每一种「读不出来」的说法——退出码读不出来就是 null、贴着输出上限
 * 只说贴着上限、不报缓存的提供方写成「未上报」而不是 0%。
 */
class ReceiptTest {

  @TempDir Path tmp;

  private static Message.Assistant asked(String id, String tool, String arguments) {
    return new Message.Assistant(
        "", List.of(new Message.ToolCall(id, tool, arguments)), List.of());
  }

  private static Message.ToolResult result(String id, String tool, String content, boolean error) {
    return new Message.ToolResult(id, tool, content, error);
  }

  private static UsageTotals totals(long input, long output, long cached, boolean reported, int modelTurns, int compactions) {
    return new UsageTotals(input, output, cached, 0, modelTurns, 0, 0, 0, compactions, reported);
  }

  private Checks checks(String json) throws IOException {
    Path config = tmp.resolve("config.json");
    Files.writeString(config, json);
    return Checks.from(config);
  }

  @Test
  void everyCommandIsMatchedToItsCallAndAnUnreadableExitCodeStaysNull() throws IOException {
    List<Message> messages =
        List.of(
            new Message.User("跑两条命令"),
            asked("c1", "bash", "{\"command\":\"mvn -q -o compile\"}"),
            result("c1", "bash", "exit code 0\n", false),
            asked("c2", "bash", "{\"command\":\"cat /nonexistent\"}"),
            result("c2", "bash", "exit code 1\ncat: no such file\n", false),
            asked("c3", "bash", "{\"command\":\"sleep 900\"}"),
            result("c3", "bash", "exit code -1 (超时)\n", true));

    Receipt receipt =
        Receipt.of(
            "s", messages, List.of(), UsageTotals.empty(), totals(0, 0, 0, false, 1, 0), 32768,
            Checks.none(), tmp);

    assertEquals(3, receipt.commands().size());
    assertEquals("mvn -q -o compile", receipt.commands().get(0).line());
    assertEquals(0, receipt.commands().get(0).exitCode());
    assertEquals(1, receipt.commands().get(1).exitCode());
    assertNull(receipt.commands().get(2).exitCode(), "「超时」不是一个退出码，收据不会把它编成 0");
    assertTrue(receipt.render().contains("1 条非零退出"), receipt.render());
    assertTrue(receipt.render().contains("退出码读不出来"), receipt.render());
  }

  @Test
  void aRefusalIsRecognisedFromTheToolsOwnWordsAndOnlyFromAnError() throws IOException {
    List<Message> messages =
        List.of(
            new Message.User("做点危险的事"),
            asked("c1", "bash", "{\"command\":\"rm -rf /tmp/x\"}"),
            result("c1", "bash", ApprovalAnswer.DENY.refusal(), true),
            asked("c2", "read", "{\"path\":\"notes.txt\"}"),
            // 一个文件的内容正好以那句话开头：它不是一次拒绝，因为它不是一条错误结果。
            result("c2", "read", ApprovalAnswer.DENY.refusal() + "（这是文件里的字）", false));

    Receipt receipt =
        Receipt.of("s", messages, List.of(), UsageTotals.empty(), totals(0, 0, 0, false, 1, 0), 32768, Checks.none(), tmp);

    assertEquals(1, receipt.calls().stream().filter(Receipt.Call::refused).count());
    assertFalse(receipt.calls().get(1).refused(), "成功的结果从来不是一次拒绝");
    assertTrue(
        receipt.unverified().stream().anyMatch(gap -> gap.contains("被人或规则拒绝")),
        receipt.unverified().toString());
  }

  @Test
  void aCallThatNeverRanIsCountedWhereItBelongs() throws IOException {
    List<Message> messages =
        List.of(
            new Message.User("中止我"),
            asked("c1", "bash", "{\"command\":\"sleep 900\"}"),
            result("c1", "bash", SessionRepair.NOT_RUN, true));

    Receipt receipt =
        Receipt.of("s", messages, List.of(), UsageTotals.empty(), totals(0, 0, 0, false, 1, 0), 32768, Checks.none(), tmp);

    assertTrue(receipt.calls().get(0).neverRan());
    assertFalse(receipt.calls().get(0).refused(), "「未运行」与「被拒绝」是两件事");
    assertTrue(receipt.render().contains("1 次从未运行"), receipt.render());
    assertTrue(
        receipt.unverified().stream().anyMatch(gap -> gap.contains("在运行之前就停了")),
        receipt.unverified().toString());
  }

  @Test
  void anEditedFileWithNoApplicableCheckIsReportedAsUnverified() throws IOException {
    Checks checks = checks("{\"checks\": [{\"glob\": \"**/*.java\", \"command\": \"mvn -q -o compile\"}]}");
    List<Receipt.FileChange> changed =
        List.of(new Receipt.FileChange("src/A.java", "restore"), new Receipt.FileChange("notes.txt", "restore"));

    Receipt receipt =
        Receipt.of(
            "s",
            List.of(new Message.User("改两个文件")),
            changed,
            UsageTotals.empty(),
            totals(0, 0, 0, false, 1, 0),
            32768,
            checks,
            tmp);

    assertEquals(1, receipt.unverified().size(), receipt.unverified().toString());
    assertTrue(receipt.unverified().get(0).contains("notes.txt"), receipt.unverified().toString());
    assertFalse(receipt.unverified().get(0).contains("src/A.java"), "配置里那条检查管得着它");
  }

  @Test
  void aFileThatWasOnlyCreatedByTheTurnIsNotClaimedToHaveBeenChecked() throws IOException {
    // action 是 delete 的意思是：回合新建了它。新建的文件里没有「改动」可以被检查，所以它不该被算成
    // 「改过却没检查」——那样报出来的数字会一直比真实的大。
    Receipt receipt =
        Receipt.of(
            "s",
            List.of(new Message.User("建一个文件")),
            List.of(new Receipt.FileChange("new.txt", "delete")),
            UsageTotals.empty(),
            totals(0, 0, 0, false, 1, 0),
            32768,
            Checks.none(),
            tmp);

    assertFalse(
        receipt.unverified().stream().anyMatch(gap -> gap.contains("没有配置任何适用于它们的编辑后检查")),
        receipt.unverified().toString());
  }

  @Test
  void aResultThatSitsAgainstTheOutputLimitIsFlaggedWithoutGuessing() throws IOException {
    String fat = "x".repeat(30_000);
    List<Message> messages =
        List.of(
            new Message.User("读一个大东西"),
            asked("c1", "read", "{\"path\":\"big.txt\"}"),
            result("c1", "read", fat, false));

    Receipt receipt =
        Receipt.of("s", messages, List.of(), UsageTotals.empty(), totals(0, 0, 0, false, 1, 0), 32768, Checks.none(), tmp);

    String gap =
        receipt.unverified().stream()
            .filter(text -> text.contains("贴着输出上限"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(receipt.unverified().toString()));
    assertTrue(gap.contains("30000 字节 / 上限 32768"), gap);
    assertFalse(gap.contains("被截断了\""), "它不声称截断发生过——它只说这个结果贴着上限：" + gap);
  }

  @Test
  void aQuietTurnSaysExactlyThatAndNothingMore() {
    // 路线图里的验收之一：只有对话的回合，收据要明说「没有改动，没有命令，没有验证」。
    Receipt receipt =
        Receipt.of(
            "s",
            List.of(new Message.User("你好"), Message.Assistant.text("你好。")),
            List.of(),
            UsageTotals.empty(),
            totals(1200, 40, 900, true, 1, 0),
            32768,
            Checks.none(),
            tmp);

    assertTrue(receipt.quiet());
    String text = receipt.render();
    assertTrue(text.contains("没有改动任何文件"), text);
    assertTrue(text.contains("没有跑任何命令"), text);
    assertTrue(text.contains("这一轮只有对话：没有改动，没有命令，没有验证"), text);
    assertTrue(text.contains("0 次调用"), text);
    assertFalse(
        text.contains("没有发现没被验证的东西"),
        "什么都不发生的回合只该有一句话，而不是两句互相取消的话：\n" + text);
  }

  @Test
  void spendingIsTheDifferenceBetweenTheTwoSnapshots() {
    Receipt receipt =
        Receipt.of(
            "s",
            List.of(new Message.User("说点什么")),
            List.of(),
            totals(10_000, 500, 8_000, true, 4, 0),
            totals(12_400, 540, 9_600, true, 6, 1),
            32768,
            Checks.none(),
            tmp);

    assertEquals(2_400, receipt.spent().inputTokens());
    assertEquals(40, receipt.spent().outputTokens());
    assertEquals(2, receipt.steps());
    assertEquals(1, receipt.spent().compactions());
    assertTrue(
        receipt.unverified().stream().anyMatch(gap -> gap.contains("压缩")),
        receipt.unverified().toString());
  }

  @Test
  void aProviderThatReportsNoCacheIsNotDrawnAsAZeroHitRate() {
    Receipt quiet =
        Receipt.of(
            "s",
            List.of(new Message.User("你好")),
            List.of(),
            UsageTotals.empty(),
            totals(1000, 10, 0, false, 1, 0),
            32768,
            Checks.none(),
            tmp);
    assertTrue(quiet.render().contains("未上报"), quiet.render());

    Receipt caching =
        Receipt.of(
            "s",
            List.of(new Message.User("你好")),
            List.of(),
            UsageTotals.empty(),
            totals(1000, 10, 750, true, 1, 0),
            32768,
            Checks.none(),
            tmp);
    assertTrue(caching.render().contains("累计缓存命中 75%"), caching.render());
  }

  @Test
  void theJsonCarriesTheSameFactsAsTheText() {
    Receipt receipt =
        Receipt.of(
            "session-1",
            List.of(
                new Message.User("改一个文件"),
                asked("c1", "write", "{\"path\":\"notes.txt\",\"content\":\"hi\"}"),
                result("c1", "write", "wrote 2 bytes", false)),
            List.of(new Receipt.FileChange("notes.txt", "restore")),
            UsageTotals.empty(),
            totals(900, 30, 0, false, 1, 0),
            32768,
            Checks.none(),
            tmp);

    var json = receipt.toJson();

    assertEquals("session-1", json.path("sessionId").asText());
    assertEquals(1, json.path("changed").size());
    assertEquals("notes.txt", json.path("changed").get(0).path("path").asText());
    assertEquals("restore", json.path("changed").get(0).path("action").asText());
    assertEquals(1, json.path("calls").size());
    assertEquals("write", json.path("calls").get(0).path("tool").asText());
    assertEquals(900, json.path("tokens").path("input").asLong());
    assertFalse(json.path("tokens").path("cacheReported").asBoolean());
    assertFalse(json.path("unverified").isEmpty());
    assertTrue(json.path("text").asText().contains("收据"), json.path("text").asText());
    assertNotNull(json.path("text").asText());
  }
}
