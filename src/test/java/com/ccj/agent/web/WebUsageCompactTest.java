package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Compaction;
import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.UsageTotals;
import com.ccj.agent.session.FileSession;
import com.ccj.agent.session.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 账本与压缩：token 的累计、上下文预算的估算、撤销上一回合，以及历史怎样被重放和压掉。
 *
 * <p>单独成类，是因为这一组读的是会话文件里记下来的账与转录，判据是数字而不是事件。
 */
class WebUsageCompactTest extends WebHarness {

  @Test
  void usageTotalsAndCacheHitRateAccumulateAcrossATurn() throws Exception {
    // 替身每个回合都报告同一笔账，所以两个回合就把两边都翻倍。
    provider.usage(100, 5, 40).reply(Message.Assistant.text("one"));
    provider.reply(Message.Assistant.text("two"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"first\"}");
      sse.await("done", 5000);
      post("/api/message", "{\"text\":\"second\"}");
      sse.awaitAtLeast("done", 2, 5000);
    }

    JsonNode usage = json("/api/status").path("usage");
    assertEquals(2, usage.path("turns").asInt());
    assertEquals(2, usage.path("steps").asInt());
    assertEquals(200, usage.path("inputTokens").asInt());
    assertEquals(10, usage.path("outputTokens").asInt());
    assertEquals(80, usage.path("cachedInputTokens").asInt());
    assertEquals(0.4, usage.path("cacheHitRate").asDouble(), 0.001);
    assertTrue(usage.path("elapsedMs").asLong() >= 0);
  }

  @Test
  void aProviderThatReportsNoCacheLeavesTheRateUnknown() throws Exception {
    provider.reply(Message.Assistant.text("hi"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      sse.await("done", 5000);
    }

    JsonNode usage = json("/api/status").path("usage");
    assertTrue(usage.path("cachedInputTokens").isNull(), usage.toString());
    assertTrue(usage.path("cacheHitRate").isNull(), "未知绝不能渲染成 0%");
    assertEquals(1, usage.path("turns").asInt());
  }

  @Test
  void theLedgerEqualsWhatTheProvidersReportedAcrossADelegationAndACompaction() throws Exception {
    // 路线图 1.3 的那条等号：一个用过子代理、并被压缩过一次的会话，面板的总数等于提供方逐回合数字之和。
    // 每一次请求各报一笔不同的账，所以「子代理那一次没进账本」「压缩那次摘要请求没记账」「压缩把账本归零」
    // 这三种错法各自落在不同的差值上，而不是凑成一个看起来对的数字。
    //
    // 「逐回合」在这里包括**压缩那一次摘要请求**：它是一次真的模型调用，用户为它付钱，所以它必须进账本
    // ——否则花费上限会在每一次压缩上少算一笔，而那正是这条等号要防的那种错。
    hub.setSubAgents(true);

    long reportedInput = 0;
    long reportedOutput = 0;
    long reportedCached = 0;

    // 八组往来：压缩留下最近 5 组，所以只有比这更长的对话才真的压得动。
    for (int i = 0; i < 8; i++) {
      reportedInput += 100 + i;
      reportedOutput += 10 + i;
      reportedCached += 20 + i;
      provider
          .usageFor(100 + i, 10 + i, 20 + i)
          .reply(Message.Assistant.text("answer " + i + " " + "detail ".repeat(50)));
      try (Sse sse = watch()) {
        post("/api/message", "{\"text\":\"question " + i + " " + "context ".repeat(50) + "\"}");
        sse.await("done", 5000);
      }
    }

    // 一次委派：主代理要 task，子代理自己跑一轮，主代理收尾——三次请求，三笔账。
    Message.Assistant delegate =
        new Message.Assistant(
            "",
            List.of(
                new Message.ToolCall(
                    "call_task",
                    "task",
                    Json.write(
                        Json.object()
                            .put("role", "explore")
                            .put("task", "find the one thing")))));
    provider.usageFor(1000, 100, 400).reply(delegate);
    provider.usageFor(2000, 200, 600).reply(Message.Assistant.text("it is in note.txt"));
    provider.usageFor(3000, 300, 800).reply(Message.Assistant.text("delegated"));
    reportedInput += 1000 + 2000 + 3000;
    reportedOutput += 100 + 200 + 300;
    reportedCached += 400 + 600 + 800;
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"find it\"}");
      sse.await("done", 5000);
    }

    // 压缩：这一次请求动过模型、账也报了，所以它的 5000/500/1000 与其它每一次一样进 token 总额，另加
    // compactions 那一格。它不进的是 steps 与 turns——压缩不是对话里的一步。
    provider
        .usageFor(5000, 500, 1000)
        .reply(Message.Assistant.text("Goal: answer questions. Files: none. Open: nothing."));
    reportedInput += 5000;
    reportedOutput += 500;
    reportedCached += 1000;
    JsonNode compacted = postJson("/api/compact", "{}");

    assertTrue(compacted.path("compacted").asBoolean(), "压缩真的发生了：" + compacted);
    assertEquals(
        12,
        provider.requests().size(),
        "八组往来 + 委派的三次 + 压缩的一次，全都在这个提供方上跑过");

    JsonNode usage = json("/api/status").path("usage");
    assertEquals(
        reportedInput,
        usage.path("inputTokens").asLong(),
        "输入：提供方逐回合报的是 " + reportedInput + "，面板报的是 " + usage);
    assertEquals(
        reportedOutput,
        usage.path("outputTokens").asLong(),
        "输出：提供方逐回合报的是 " + reportedOutput + "，面板报的是 " + usage);
    assertEquals(
        reportedCached,
        usage.path("cachedInputTokens").asLong(),
        "缓存：提供方逐回合报的是 " + reportedCached + "，面板报的是 " + usage);
    // 每一次模型请求都是一个模型回合：八组往来八次，委派那一回合里主代理两次、子代理自己一次。
    assertEquals(11, usage.path("steps").asInt(), "模型回合：" + usage);
    // 子代理的任务文本算一个用户回合（它做的是被当成工作加进父账本的那件事），所以是 8 + 1 + 1。
    assertEquals(10, usage.path("turns").asInt(), "用户回合：" + usage);
    // 而被压缩过一次的会话仍然只有一本连着的账：那些被摘要掉的回合曾在账本里，现在依然在，只算一遍。
    assertEquals(1, usage.path("compactions").asInt(), "压缩只记一次：" + usage);
  }

  @Test
  void historyReplaysTheConversationAsRenderEvents() throws Exception {
    Files.writeString(cwd.resolve("note.txt"), "on disk\n");
    provider.reply(call("read", "path", "note.txt"));
    provider.reply(Message.Assistant.text("done"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"make the file\"}");
      sse.await("done", 5000);
    }

    JsonNode history = json("/api/history");
    assertEquals(json("/api/status").path("sessionId").asText(), history.path("sessionId").asText());

    List<String> types = new ArrayList<>();
    history.path("events").forEach(event -> types.add(event.path("type").asText()));
    assertEquals(List.of("user", "tool", "tool", "text"), types, history.toString());

    JsonNode start = history.path("events").get(1);
    JsonNode end = history.path("events").get(2);
    assertEquals("start", start.path("state").asText());
    assertEquals("read", start.path("name").asText());
    assertEquals("note.txt", start.path("summary").asText());
    assertEquals(start.path("id").asText(), end.path("id").asText(), "卡片按调用 id 配对");
    assertTrue(end.path("ok").asBoolean());
    assertTrue(end.path("output").asText().contains("on disk"), end.toString());
    assertTrue(end.path("elapsedMs").isNull(), "历史不存计时，所以也不编造计时");
    history
        .path("events")
        .forEach(event -> assertTrue(event.path("replay").asBoolean(), event.toString()));
    assertEquals(1, history.path("usage").path("turns").asInt(), "一个用户回合");
    assertEquals(2, history.path("usage").path("steps").asInt(), "先工具调用，再答案");
  }

  @Test
  void anEmptyConversationReplaysAsNothing() throws Exception {
    JsonNode history = json("/api/history");

    assertEquals(0, history.path("events").size());
    assertEquals(json("/api/status").path("sessionId").asText(), history.path("sessionId").asText());
  }

  @Test
  void aResumedSessionContinuesItsTotalsInsteadOfStartingAtZero() throws Exception {
    provider.usage(100, 5, 40).reply(Message.Assistant.text("first"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"one\"}");
      sse.await("done", 5000);
    }
    String sessionId = json("/api/status").path("sessionId").asText();

    api.close();
    hub.close();
    hub = hub(new MockProvider("mock"), testConfig());
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();
    postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + sessionId + "\"}");

    JsonNode usage = json("/api/status").path("usage");
    assertEquals(1, usage.path("turns").asInt(), "更早的那个回合属于本会话");
    assertEquals(100, usage.path("inputTokens").asInt());
    assertEquals(0.4, usage.path("cacheHitRate").asDouble(), 0.001);
    assertEquals(2, json("/api/history").path("events").size(), "它的对话也一样");
  }

  @Test
  void aServerStartedOnAnExistingSessionContinuesItsBooks() throws Exception {
    FileSession owned = SessionStore.create(sessions);
    owned.append(new Message.User("an earlier conversation"));
    owned.totals(new UsageTotals(200, 30, 150, 1, 2, 1, 0, 900, true));

    api.close();
    hub.close();
    hub = hub(provider, testConfig(), owned);
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();

    JsonNode usage = json("/api/status").path("usage");
    assertEquals(1, usage.path("turns").asInt(), "重启绝不能把回合数忘掉");
    assertEquals(200, usage.path("inputTokens").asInt());
    assertEquals(150, usage.path("cachedInputTokens").asInt());
    assertEquals(0.75, usage.path("cacheHitRate").asDouble(), 0.001);
    assertEquals(1, json("/api/history").path("events").size());
  }

  @Test
  void compactingReplacesTheOlderTurnsWithASummaryAndKeepsTheFile() throws Exception {
    // 一个长到值得压缩的对话：8 组往来，所以最新的 5 组会被留下。
    for (int i = 0; i < 8; i++) {
      provider.reply(Message.Assistant.text("answer " + i + " " + "detail ".repeat(50)));
      try (Sse sse = watch()) {
        post("/api/message", "{\"text\":\"question " + i + " " + "context ".repeat(50) + "\"}");
        sse.await("done", 5000);
      }
    }
    String id = json("/api/status").path("sessionId").asText();
    Path original = sessions.resolve(id + ".jsonl");
    String originalBytes = Files.readString(original);
    JsonNode usageBefore = json("/api/status").path("usage");
    int stepsBefore = usageBefore.path("steps").asInt();

    // 向模型索要摘要的东西，是一个关于转录的提问，而不是一个回合。
    provider.reply(Message.Assistant.text("Goal: answer questions. Files: none. Open: nothing."));
    JsonNode result = postJson("/api/compact", "{}");

    assertTrue(result.path("compacted").asBoolean(), result.toString());
    assertEquals(6, result.path("summarised").asInt(), "三组各两条消息的往来被替换掉了");
    assertEquals(10, result.path("kept").asInt());
    assertTrue(result.path("afterTokens").asInt() < result.path("beforeTokens").asInt(), result.toString());
    assertEquals(1, result.path("generation").asInt());

    // 新代文件装着摘要加上留下的尾巴；原文件逐字节完好，这正是压缩可以放心尝试的全部理由。
    Path generation = sessions.resolve(id + ".g1.jsonl");
    assertTrue(Files.isRegularFile(generation), "新的一代已经在磁盘上");
    assertEquals(originalBytes, Files.readString(original), "它替换掉的那一代未被触碰");
    List<Message> compacted = FileSession.readAll(generation);
    assertTrue(compacted.get(0) instanceof Message.Summary, compacted.get(0).toString());

    // 这次摘要请求不是一个回合：没有追加用户消息，也没有计入步骤。
    var requests = provider.requests();
    var summariseRequest = requests.get(requests.size() - 1);
    assertNull(summariseRequest.system(), "摘要请求不带系统提示词");
    assertTrue(summariseRequest.tools().isEmpty(), "也没有工具可以去干活");
    assertEquals(1, requests.get(requests.size() - 1).messages().size(), "只有一条消息：转录本身");
    assertTrue(
        ((Message.User) summariseRequest.messages().get(0)).text().contains(Compaction.INSTRUCTIONS),
        "正是那段指令把一份转录变成摘要");
    assertEquals(stepsBefore, json("/api/status").path("usage").path("steps").asInt(), "不算一步");

    // 但它会被计入，而且是单独计：它耗了 token，把它折进步骤里会让那个数字同时意味着两件事。
    assertEquals(1, json("/api/status").path("usage").path("compactions").asInt());

    // 而且会话继续下去：下一个回合会被作答，基于压缩后的对话。
    provider.reply(Message.Assistant.text("continuing"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"what next?\"}");
      sse.await("done", 5000);
    }
    var nextTurn = provider.requests().get(provider.requests().size() - 1);
    assertTrue(
        nextTurn.messages().stream().anyMatch(m -> m instanceof Message.Summary),
        "下一个请求带着的就是摘要，而不是那些旧的回合：" + nextTurn.messages());
  }

  @Test
  void aCompactionIsRefusedWhileATurnIsRunning() throws Exception {
    // 一份转录上两个写入者，正是「每对话一个回合」标志存在的意义所在要防的例外，而压缩会
    // 重写这个对话是什么。
    CountDownLatch gate = new CountDownLatch(1);
    provider.reply(Message.Assistant.text("slow"));
    provider.gate(gate);
    post("/api/message", "{\"text\":\"start a turn\"}");

    HttpResponse<String> refusal = post("/api/compact", "{}");
    assertEquals(409, refusal.statusCode(), refusal.body());
    assertTrue(refusal.body().contains("还有回合在跑；请先中止它"), refusal.body());

    provider.release();
    gate.countDown();
  }

  @Test
  void aConversationTooShortToCompactIsRefusedWithoutCallingTheModel() throws Exception {
    provider.reply(Message.Assistant.text("only one exchange"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      sse.await("done", 5000);
    }
    int callsBefore = provider.requests().size();

    HttpResponse<String> refusal = post("/api/compact", "{}");

    assertEquals(400, refusal.statusCode(), refusal.body());
    assertTrue(refusal.body().contains("这个对话还没有可压缩的东西——它比压缩会保留的 " + Compaction.KEEP_EXCHANGES + " 组往复还短"), refusal.body());
    assertEquals(callsBefore, provider.requests().size(), "不会让模型去总结「什么都没有」");
    assertEquals(405, get("/api/compact").statusCode());
  }

  @Test
  void anEmptySummaryChangesNothing() throws Exception {
    for (int i = 0; i < 8; i++) {
      provider.reply(Message.Assistant.text("answer " + i));
      try (Sse sse = watch()) {
        post("/api/message", "{\"text\":\"question " + i + "\"}");
        sse.await("done", 5000);
      }
    }
    String id = json("/api/status").path("sessionId").asText();

    provider.reply(Message.Assistant.text("   "));
    HttpResponse<String> refusal = post("/api/compact", "{}");

    assertEquals(409, refusal.statusCode(), refusal.body());
    assertTrue(refusal.body().contains("模型返回了空摘要；什么都没有改动"), refusal.body());
    // 什么都没写，所以会话仍然是它的第 0 代，也仍然完整。
    assertEquals(16, json("/api/status").path("messageCount").asInt());
    assertFalse(Files.exists(sessions.resolve(id + ".g1.jsonl")));
    assertEquals(0, json("/api/status").path("usage").path("compactions").asInt());
  }

  @Test
  void usageReportsAContextEstimate() throws Exception {
    JsonNode usage = json("/api/status").path("usage");
    assertTrue(usage.has("contextTokens"), usage.toString());
    assertTrue(usage.path("contextLimit").asInt() >= 0, usage.toString());
    // 会话花掉的是 token 数，不是价钱：ccj 不携带费率表，所以一个金额字段会是一个用户无从核对
    // 的数字。
    assertFalse(usage.has("costUsd"), usage.toString());
    assertFalse(usage.has("priceAsOf"), usage.toString());
  }

  @Test
  void undoTakesBackWhatTheLastTurnChanged() throws Exception {
    // 人们在让代理靠近自己的文件之前会问的问题是「这能撤回吗」，而它的答案是一次回合的写入可以
    // 恢复——不是审批提示给的答案，后者从来只回答「这能跑吗」。
    hub.setAutoApprove(true);
    Path file = cwd.resolve("Notes.java");
    Files.writeString(file, "class Notes {\n  int a = 1;\n}\n");
    provider.reply(
        new Message.Assistant(
            "",
            List.of(
                new Message.ToolCall(
                    "call_1",
                    "edit",
                    "{\"path\":\"Notes.java\",\"edits\":["
                        + "{\"old_string\":\"int a = 1;\",\"new_string\":\"int a = 2;\"},"
                        + "{\"old_string\":\"class Notes {\",\"new_string\":\"class Notes implements Cloneable {\"}]}"))));
    provider.reply(Message.Assistant.text("changed two things"));

    JsonNode undone;
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"edit the notes\"}");
      sse.await("done", 5000);
      assertTrue(Files.readString(file).contains("int a = 2;"), "编辑确实发生了");
      assertEquals(
          "class Notes implements Cloneable {\n  int a = 2;\n}\n",
          Files.readString(file),
          "两个块都改了，各自落在该在的位置");

      undone = postJson("/api/undo", "{}");

      // 用通知而不是历史：undo 管的是文件，不是对话，而转录会说出放回了什么——读者本来就在看
      // 那块地方。
      JsonNode notice = sse.await("notice", 5000);
      assertTrue(notice.path("text").asText().startsWith("已退回：1 个文件恢复到上一回合之前的样子 ——"), notice.toString());
      assertTrue(notice.path("text").asText().contains("Notes.java"), notice.toString());
    }

    assertEquals(1, undone.path("restored").asInt(), undone.toString());
    assertEquals(
        "class Notes {\n  int a = 1;\n}\n",
        Files.readString(file),
        "文件变回了那个回合起初看到的样子");
    assertEquals(0, undone.path("remaining").asInt(), "那个回合已经用完了");
  }

  @Test
  void aFileTheTurnCreatedIsRemovedByUndo() throws Exception {
    hub.setAutoApprove(true);
    provider.reply(
        new Message.Assistant(
            "",
            List.of(
                new Message.ToolCall(
                    "call_1",
                    "write",
                    "{\"path\":\"Brand.java\",\"content\":\"class Brand {}\\n\"}"))));
    provider.reply(Message.Assistant.text("created"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"create it\"}");
      sse.await("done", 5000);
    }
    assertTrue(Files.exists(cwd.resolve("Brand.java")));

    postJson("/api/undo", "{}");

    assertFalse(Files.exists(cwd.resolve("Brand.java")), "新建的文件是被删掉，而不是被清空");
  }

  @Test
  void undoIsRefusedWhileATurnIsRunning() throws Exception {
    // 在一个正在运行的回合底下撤回，会把模型正推理到一半的文件恢复回去，那比两种状态里的任何
    // 一种都糟。
    provider.reply(Message.Assistant.text("slow"));
    provider.gate(new CountDownLatch(1));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"think\"}");

      HttpResponse<String> refused = post("/api/undo", "{}");

      assertEquals(409, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("还有回合在跑；退回上一个回合之前请先中止它"), refused.body());
      provider.release();
      sse.await("done", 5000);
    }
  }

  @Test
  void aConversationOverItsBudgetIsCompactedBetweenTurns() throws Exception {
    // 越过 `maxContextTokens` 之后，投影会开始省略工具输出、丢掉整组往来，而它的通知会说明这
    // 一点（"context: 213 → 26 tokens, 5 earlier exchange(s) dropped"），却从不说什么被丢了。
    // 一份点名自己替换了什么的摘要是更好的损失，而且它发生在回合之间——绝不在回合之内，那会让
    // 模型正想到一半的历史在它脚下变样。
    startWithBudget(50);
    // 十二组往来：一次压缩留下最新的五组、总结其余，所以它替换掉的那部分必须比摘要提示本身更大
    // ——实测下来，一组被总结的往来（52 token）对上 74 的摘要，会以 "nothing to gain" 被拒。
    for (int i = 0; i < 12; i++) {
      provider.reply(Message.Assistant.text("answer " + i));
    }
    // 第七次调用就是那次摘要调用，它不要工具、只要一条消息。
    provider.reply(Message.Assistant.text("Summary."));

    List<String> notices = new java.util.ArrayList<>();
    String question =
        "a question with a good many words in it, so that six of them are worth summarising: "
            + "the point of a compaction is that what it replaces is larger than what it writes";
    try (Sse sse = watch()) {
      for (int i = 0; i < 12; i++) {
        post("/api/message", "{\"text\":\"" + question + " " + i + "\"}");
        sse.awaitAtLeast("done", i + 1, 5000);
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline && !conversationWasCompacted()) {
        Thread.sleep(50);
      }
      sse.ofType("notice").forEach(notice -> notices.add(notice.path("text").asText()));
    }

    // 那次摘要调用在记录下来的请求里认得出来：没有工具，只有一条消息——压缩提示自己就带着
    // 转录。
    assertTrue(
        provider.requests().stream()
            .anyMatch(request -> request.tools().isEmpty() && request.messages().size() == 1),
        "本该发出过一次摘要调用：" + notices);
    assertTrue(
        conversationWasCompacted(),
        "会话文件本该有第二代；通知：" + notices);
    assertTrue(
        notices.stream().anyMatch(text -> text.startsWith("已自动压缩：")),
        "而且转录里该说这件事发生了：" + notices);
  }

  private boolean conversationWasCompacted() throws Exception {
    String id = json("/api/status").path("sessionId").asText();
    try (var files = java.nio.file.Files.list(sessions)) {
      return files.anyMatch(path -> path.getFileName().toString().startsWith(id + ".g"));
    }
  }

  /** 再次建起 hub，带一个足够小的提示预算，好让几个回合就能越过它。 */
  private void startWithBudget(int maxContextTokens) throws IOException {
    api.close();
    hub.close();
    // 十二个参数的那个构造以 maxContextTokens 收尾，而它是这个测试唯一设置的东西。
    Config budgeted =
        testConfig()
            .merge(
                new Config(
                    null, null, null, null, null, null, null, null, null, null, null,
                    maxContextTokens));
    hub = hub(provider, budgeted);
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();
  }
}
