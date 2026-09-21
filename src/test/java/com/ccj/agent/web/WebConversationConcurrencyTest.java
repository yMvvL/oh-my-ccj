package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.ProjectPrompt;
import com.ccj.agent.core.Provider;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * 一个对话正在跑时，别的东西还能做什么：排队、上限、中断，以及同一个服务器上的第二个会话。
 *
 * <p>单独成类，是因为这一组的每个断言都关于「忙碌」这个状态本身，而不是某个端点。
 */
class WebConversationConcurrencyTest extends WebHarness {

  @Test
  void aMessageSentWhileBusyWaitsAndThenRuns() throws Exception {
    // 它以前会被 409 拒绝，这让一次思考中的停顿变成了彻底卡死：输入框一直禁用，直到回合结束，
    // 而你在等待时想到的东西全都丢了。现在它会被排队，并且作为自己的一个回合运行——有自己的
    // user 事件和自己的 done。
    provider.reply(Message.Assistant.text("slow answer"));
    provider.reply(Message.Assistant.text("the queued answer"));
    provider.gate(new CountDownLatch(1));
    try (Sse sse = watch()) {
      assertEquals(202, post("/api/message", "{\"text\":\"first\"}").statusCode());

      HttpResponse<String> second = post("/api/message", "{\"text\":\"second\"}");
      assertEquals(202, second.statusCode(), second.body());
      assertTrue(Json.parse(second.body()).path("queued").asBoolean(), second.body());
      assertEquals(
          List.of("second"),
          queuedTexts(),
          "状态说明了什么在排队，这样输入框才能把它显示出来");

      provider.release();
      sse.await("done", 5000);
      // 排队的消息会自己开始，它开始后队列就空了。
      assertEquals("second", sse.await("user", 5000).path("text").asText());
      assertEquals("the queued answer", sse.awaitAtLeast("done", 2, 5000).path("finalText").asText());
      assertEquals(List.of(), queuedTexts());
    }
  }

  @Test
  void abortDropsWhatWasQueuedBehindTheTurn() throws Exception {
    // 中止是出了岔子时按下的按钮。让四条消息等着在被中止的回合松手的那一刻开始，是「停下」的
    // 反面；而那个计数会被公布出来，这样被丢弃的消息是看得见的，而不是悄无声息的。
    provider.reply(Message.Assistant.text("slow answer"));
    provider.gate(new CountDownLatch(1));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"first\"}");
      post("/api/message", "{\"text\":\"second\"}");
      post("/api/message", "{\"text\":\"third\"}");
      assertEquals(List.of("second", "third"), queuedTexts());

      post("/api/abort", "{}");

      provider.release();
      JsonNode notice = sse.await("notice", 5000);
      assertTrue(notice.path("text").asText().contains("已中止；丢掉了 2 条排队的消息"), notice.toString());
      assertEquals(List.of(), queuedTexts());
      assertFalse(
          sse.ofType("user").stream().anyMatch(event -> event.path("text").asText().equals("second")),
          "被丢弃的消息不得之后再启动");
    }
  }

  @Test
  void aQueueHasABoundAndSaysSo() throws Exception {
    // 没有上限的队列，是一种对已经在跑东西的会话失去控制的方式。
    provider.reply(Message.Assistant.text("slow answer"));
    provider.gate(new CountDownLatch(1));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"first\"}");
      HttpResponse<String> last = null;
      for (int i = 0; i < 16; i++) {
        last = post("/api/message", "{\"text\":\"waiting " + i + "\"}");
        assertEquals(202, last.statusCode(), last.body());
      }
      HttpResponse<String> over = post("/api/message", "{\"text\":\"one too many\"}");

      assertEquals(409, over.statusCode(), over.body());
      assertTrue(over.body().contains("这个对话已经有 16 条消息在等了；请等回合结束，或者中止它"), over.body());
      assertEquals(16, queuedTexts().size());
      provider.release();
    }
  }

  private List<String> queuedTexts() throws Exception {
    List<String> texts = new java.util.ArrayList<>();
    json("/api/status").path("queued").forEach(node -> texts.add(node.asText()));
    return texts;
  }

  @Test
  void anotherSessionRunsWhileOneIsStillBusy() throws Exception {
    // 这次改动的全部意义：b 会话里的一个回合不得锁住服务器，否则「在另一个对话里开个任务」就是
    // 一句空话。*同一个*会话里的*第二个*回合仍然被拒——每个对话一个写入者，才让转录不会变成两
    // 份。b 被扣在一次审批上，这是回合长时间占住服务器的最真实方式。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_b", "bash", "{\"command\":\"echo b\"}"))));
    provider.reply(Message.Assistant.text("a finished"));
    provider.reply(Message.Assistant.text("b finished"));
    provider.reply(Message.Assistant.text("b's queued answer"));
    try (Sse sse = watch()) {
      assertEquals(202, post("/api/message", "{\"text\":\"b: first\"}").statusCode());
      String b = json("/api/status").path("sessionId").asText();
      assertFalse(b.isEmpty(), "正在运行的会话必须可辨认");
      JsonNode approval = sse.awaitInSession(b, "approval", 1, 5000);

      assertTrue(json("/api/status").path("busy").asBoolean(), "b 正在运行");
      // 每个对话同一时刻只有一个回合，这一点没变——变的是第二条消息会等它，而不是被拒。
      HttpResponse<String> queuedBehindB = post("/api/message", "{\"text\":\"b: second\"}");
      assertEquals(202, queuedBehindB.statusCode(), queuedBehindB.body());
      assertTrue(Json.parse(queuedBehindB.body()).path("queued").asBoolean(), queuedBehindB.body());

      // 另开一个对话并在里面派一个任务，而 b 还在等人作答。
      String a = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(b, a);
      assertFalse(
          json("/api/status").path("busy").asBoolean(),
          "正在看的会话是空闲的，尽管 b 在运行");
      assertTrue(
          json("/api/status").path("running").toString().contains(b),
          "而且状态里点名了正在运行的那个会话");

      assertEquals(
          202,
          post("/api/message", "{\"text\":\"a: hello\"}").statusCode(),
          "另一个会话里的回合必须被接受");
      assertEquals("a finished", sse.awaitInSession(a, "done", 1, 5000).path("finalText").asText());

      // b 从未被打扰：它的审批仍然挂着，作答就结束了 b。
      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      // b 的两个回合按顺序：先是那个等人作答的，然后是排在它后面的消息。按列表来读，而不是
      // 调两次 `awaitInSession`——后者返回最新匹配的事件，会和排队回合自己的 `done` 抢。
      sse.awaitInSession(b, "done", 2, 5000);
      assertEquals(
          List.of("b finished", "b's queued answer"),
          sse.forSession(b).stream()
              .filter(event -> "done".equals(event.path("type").asText()))
              .map(event -> event.path("finalText").asText())
              .toList());
    }
  }

  @Test
  void whatTouchesOneConversationIsRefusedOnlyWhileThatOneRuns() throws Exception {
    // 哪些操作在意并发是一个决定，而不是习惯。显示一个对话什么都不改变，永远允许——包括正在
    // 工作的那个，让回合继续跑的全部意义就在这。会把回合脚下的地抽走的操作——删它的文件、改
    // 模型——要等它。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"b: long job\"}");
      String b = json("/api/status").path("sessionId").asText();
      JsonNode approval = sse.awaitInSession(b, "approval", 1, 5000);

      // 看着那个忙碌的对话没问题，反复地看也没问题。
      assertEquals(
          200,
          post("/api/session", "{\"action\":\"resume\",\"id\":\"" + b + "\"}").statusCode(),
          "正在运行的对话可以被显示");
      // 但它的文件不能被删：那会把转录从回合脚底下抽走。
      assertEquals(
          409,
          client
              .send(
                  HttpRequest.newBuilder(URI.create(origin + "/api/session?id=" + b))
                      .DELETE()
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode(),
          "也不能在它正被写入时删除");

      // 设置是每个对话脚下的地，所以它们继续被拒。
      assertEquals(
          409,
          post("/api/config", "{\"model\":\"other-model\"}").statusCode(),
          "在回合运行时改模型，正是必须等待的那种操作");

      // *切走*是用户继续干活的方式，所以那是允许的。
      String a = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(b, a, "可以在正在运行的对话旁边新开一个对话");

      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      sse.awaitInSession(b, "done", 1, 5000);
    }
  }

  @Test
  void anAbortStopsTheSessionOnScreenAndLeavesTheOtherAlone() throws Exception {
    // 中止是按对话来的：停下你正在看的那个任务，绝不能把另一个也停下。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"b: long job\"}");
      String b = json("/api/status").path("sessionId").asText();
      sse.awaitInSession(b, "approval", 1, 5000);

      String a = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(b, a);

      // 屏幕上这个会话是 a，而 a 没有在运行：这里没有什么可中止的。
      assertFalse(
          postJson("/api/abort", "{}").path("aborted").asBoolean(),
          "中止作用于屏幕上那个对话，而它是空闲的");

      JsonNode aborted = postJson("/api/abort?id=" + b, "{}");
      assertTrue(aborted.path("aborted").asBoolean(), "正在运行的会话仍然可以被停下");
      JsonNode stopped = sse.awaitInSession(b, "done", 1, 5000);
      assertTrue(stopped.path("aborted").asBoolean(), "而且它以被中止结束：" + stopped);
    }
  }

  @Test
  void theSidebarCanTellWhichSessionsAreRunning() throws Exception {
    // 没有这个，页面就无法标记用户启动后又切走的那一行。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"b: long job\"}");
      String b = json("/api/status").path("sessionId").asText();
      sse.awaitInSession(b, "approval", 1, 5000);

      JsonNode list = json("/api/sessions").path("sessions");
      assertEquals(1, list.size(), list.toString());
      assertEquals(b, list.get(0).path("id").asText());
      assertTrue(list.get(0).path("running").asBoolean(), list.toString());

      String a = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(b, a);
      assertTrue(
          json("/api/sessions").path("sessions").get(0).path("running").asBoolean(),
          "切走并不会把它停下");

      postJson("/api/abort?id=" + b, "{}");
      sse.awaitInSession(b, "done", 1, 5000);
      assertFalse(
          json("/api/sessions").path("sessions").get(0).path("running").asBoolean(),
          "回合结束后就不再是运行中");
    }
  }

  @Test
  void aRunningConversationCanStillBeOpened() throws Exception {
    // 有人报的 bug：a 里有回合在跑时，在侧边栏点 a 会被弹回来。切换*显示*哪个对话，对正在运行
    // 的回合什么都没改变——让任务继续跑的意义就在于你还能看着它。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"a: long job\"}");
      String a = json("/api/status").path("sessionId").asText();
      sse.awaitInSession(a, "approval", 1, 5000);

      // 把目光移开……
      String b = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(a, b);
      // ……再回到正在工作的那个。
      JsonNode back = postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + a + "\"}");
      assertEquals(a, back.path("sessionId").asText(), "正在运行的会话就是该看的那个");
      assertTrue(back.path("busy").asBoolean(), "而且它仍然显示为运行中");

      postJson("/api/abort?id=" + a, "{}");
      sse.awaitInSession(a, "done", 1, 5000);
      // 回合的结果落进了 a 自己的文件，且只落了一次：再次打开 a 绝不能在那上面又起一个写入者。
      List<String> lines = Files.readAllLines(sessions.resolve(a + ".jsonl"));
      assertTrue(lines.stream().anyMatch(line -> line.contains("tool_result")), lines.toString());
    }
  }

  @Test
  void openingARunningSessionAgainDoesNotOpenASecondWriter() throws Exception {
    // 旧检查存在的理由。它必须靠复用回合正在写的那个文件来回答，而不是靠拒绝显示对话：两个
    // FileSession 往同一个 JSONL 里追加，正是「每会话一条」规则要防的那种损坏。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("a finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"a: long job\"}");
      String a = json("/api/status").path("sessionId").asText();
      sse.awaitInSession(a, "approval", 1, 5000);

      // 磁盘上存在的第二个对话，用来切过去再切回来。
      String b = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(a, b);
      assertEquals(202, post("/api/message", "{\"text\":\"b: hello\"}").statusCode());
      sse.awaitInSession(b, "done", 1, 5000);

      // 来回切换是用户在等待时会做的事，而每次回到 a 都必须回到它那个回合正在用的同一个文件，
      // 而不是在它上面多一个写入者。
      for (int i = 0; i < 3; i++) {
        assertEquals(
            a,
            postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + a + "\"}")
                .path("sessionId")
                .asText());
        assertEquals(
            b,
            postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + b + "\"}")
                .path("sessionId")
                .asText());
      }

      postJson("/api/abort?id=" + a, "{}");
      sse.awaitInSession(a, "done", 1, 5000);
      // 每一行仍然是一个完整的 JSON 对象：多一个写入者会把字节交错进去。
      for (String line : Files.readAllLines(sessions.resolve(a + ".jsonl"))) {
        assertFalse(line.isBlank(), "没有撕裂的行");
        Json.parse(line);
      }
    }
  }

  @Test
  void anotherWorkspacesConversationCanBeOpenedWhileATurnRuns() throws Exception {
    // 有人报的 bug：一个工作区里的回合挡住了打开另一个工作区的对话。回合从启动那一刻起就拥有
    // 自己的工作目录和自己的会话文件，所以在它运行时看别的工作区不可能打扰到它。
    Path other = Files.createDirectories(tmp.resolve("other-ws"));

    provider.reply(
        new Message.Assistant(
            "",
            List.of(
                new Message.ToolCall(
                    "call_1", "bash", "{\"command\":\"touch made-by-a.txt\"}"))));
    provider.reply(Message.Assistant.text("c finished"));
    provider.reply(Message.Assistant.text("a finished"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"a: long job\"}");
      String a = json("/api/status").path("sessionId").asText();
      JsonNode approval = sse.awaitInSession(a, "approval", 1, 5000);

      // 注册工作区只是加一条注册表条目，而且必须在 a 运行时也能做——否则第二个工作区恰好在用户
      // 想去的时候够不着。
      postJson("/api/workspaces", "{\"name\":\"other\",\"path\":\"" + other + "\"}");

      // 切过去：换的是命名空间，不是正在运行的东西。
      JsonNode switched = postJson("/api/workspace", "{\"name\":\"other\"}");
      assertEquals("other", switched.path("workspace").path("name").asText());
      String c = switched.path("sessionId").asText();
      assertNotEquals(a, c, "那边是个新会话");

      // ……而且在第一个工作区的回合继续跑时它就能用。
      assertEquals(202, post("/api/message", "{\"text\":\"c: in the other workspace\"}").statusCode());
      assertEquals("c finished", sse.awaitInSession(c, "done", 1, 5000).path("finalText").asText());
      assertTrue(
          json("/api/status").path("running").toString().contains(a),
          "第一个工作区里的回合未受影响：" + json("/api/status"));

      // a 里的回合仍然在它启动时所在的目录里运行，而不是现在屏幕上那个：命令会写一个文件，
      // 而这个文件必须落在第一个工作区。
      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      assertEquals("a finished", sse.awaitInSession(a, "done", 1, 5000).path("finalText").asText());
      assertTrue(
          Files.exists(cwd.resolve("made-by-a.txt")),
          "a 的工具在 a 自己的工作区里运行，也就是它启动时所在的地方：" + cwd);
      assertFalse(
          Files.exists(other.resolve("made-by-a.txt")),
          "而不是当前只是显示在屏幕上的那个工作区");
    }
  }

  @Test
  void eachConversationsOwnRulesFollowItsWorkingDirectory() throws Exception {
    // 规则来自对话的工具所运行的目录，在 web UI 里这是*会话*的属性，不是服务器的属性：一个
    // 页面可以让一个对话在一个项目里、另一个在它的兄弟目录里，而每个请求都必须带上自己项目的
    // 规则。
    Files.writeString(
        cwd.resolve(ProjectPrompt.FILE_NAME), "Rule for the first workspace: run `make check`.\n");
    Path other = Files.createDirectories(tmp.resolve("second-ws"));
    Files.writeString(
        other.resolve(ProjectPrompt.FILE_NAME), "Rule for the second workspace: use tabs.\n");

    provider.reply(Message.Assistant.text("first answer"));
    provider.reply(Message.Assistant.text("second answer"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello in the first\"}");
      sse.await("done", 5000);
      String firstPrompt = lastSystemPrompt(0);
      assertTrue(firstPrompt.contains("run `make check`"), firstPrompt);
      assertFalse(firstPrompt.contains("use tabs"), firstPrompt);

      postJson("/api/workspaces", "{\"name\":\"second\",\"path\":\"" + other + "\"}");
      JsonNode switched = postJson("/api/workspace", "{\"name\":\"second\"}");
      post("/api/message", "{\"text\":\"hello in the second\"}");
      sse.awaitInSession(switched.path("sessionId").asText(), "done", 1, 5000);

      String secondPrompt = lastSystemPrompt(1);
      assertTrue(secondPrompt.contains("use tabs"), secondPrompt);
      assertFalse(
          secondPrompt.contains("run `make check`"),
          "而不是另一个工作区的规则：" + secondPrompt);
    }
  }

  /** 模拟提供方收到的第 n 个请求的系统提示词。 */
  private String lastSystemPrompt(int index) {
    List<Provider.Request> seen = provider.requests();
    assertTrue(seen.size() > index, "only " + seen.size() + " requests so far");
    String system = seen.get(index).system();
    assertTrue(system != null, "每个请求都必须带上系统提示词");
    return system;
  }

  @Test
  void aReplayedConversationKeepsItsReasoning() throws Exception {
    // 有人报的 bug：在一个对话思考时切走，再切回来，推理内容就没了——因为 historyJson 只回放
    // 散文和工具调用。推理内容就在会话文件里，所以丢掉它的回放是在回放另一个对话。
    provider.reply(Message.Assistant.text("answered"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"think about it\"}");
      sse.await("done", 5000);
      String session = json("/api/status").path("sessionId").asText();

      // 推理内容从流上到达，并随助手回合一起被持久化。
      provider.emitReasoning("considering the problem");
      post("/api/message", "{\"text\":\"and again\"}");
      sse.awaitAtLeast("done", 2, 5000);
      String file = Files.readString(sessions.resolve(session + ".jsonl"));
      assertTrue(file.contains("considering the problem"), "推理内容就在磁盘上：\n" + file);

      // 所以回放那个对话时必须把它再显示出来。
      JsonNode history = json("/api/history");
      assertEquals(session, history.path("sessionId").asText());
      List<String> reasoning = new ArrayList<>();
      history.path("events").forEach(event -> {
        if ("reasoning".equals(event.path("type").asText())) {
          reasoning.add(event.path("delta").asText());
        }
      });
      assertEquals(List.of("considering the problem"), reasoning,
          "回放必须带上推理内容，否则切走再切回来就丢了："
              + history.path("events"));
    }
  }

  @Test
  void aRedactedReasoningBlockIsNotReplayedAsText() throws Exception {
    // 被遮蔽块的载荷是不透明的，必须原封不动地送回*模型*；把它当散文显示会在转录里堆一堵
    // base64 的墙，那比什么都不显示更糟。
    provider.reply(Message.Assistant.text("done"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"go\"}");
      sse.await("done", 5000);
      String session = json("/api/status").path("sessionId").asText();
      // 手写一个：只有 Anthropic 会产生这种东西，而这个测试针对的是那层投影。
      Files.writeString(
          sessions.resolve(session + ".jsonl"),
          Files.readString(sessions.resolve(session + ".jsonl"))
              + com.ccj.agent.session.MessageCodec.toJson(
                  new Message.Assistant(
                      "",
                      List.of(),
                      List.of(Message.Thinking.redacted("b3BhcXVlLXBheWxvYWQ="))))
              + "\n");

      JsonNode history = json("/api/history");
      history.path("events").forEach(event ->
          assertFalse(
              event.path("delta").asText().contains("b3BhcXVl"),
              "不透明的载荷绝不能被渲染成散文：" + event));
    }
  }
}
