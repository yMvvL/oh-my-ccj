package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 审批握手：闸门挡住工具调用、答案如何回来、规则与「本次会话都允许」各自留下什么。
 *
 * <p>单独成类，是因为这些用例都在同一条链上做文章——暂停的回合、跨连接仍活着的询问、
 * 以及被拒绝时工作区必须一动不动。
 */
class WebApprovalFlowTest extends WebHarness {

  @Test
  void aToolCallBlocksOnApprovalAndThenReallyRuns() throws Exception {
    provider.reply(bashCall("printf hi > made.txt"));
    provider.reply(Message.Assistant.text("done"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"create the file\"}");

      JsonNode approval = sse.await("approval", 5000);
      assertEquals("bash", approval.path("title").asText());
      assertTrue(approval.path("detail").asText().contains("made.txt"), approval.toString());
      assertFalse(
          Files.exists(cwd.resolve("made.txt")), "审批还挂着的时候什么都不能发生");

      JsonNode start = sse.await("tool", 1000);
      assertEquals("start", start.path("state").asText());
      assertEquals("printf hi > made.txt", start.path("summary").asText());

      HttpResponse<String> resolved =
          post(
              "/api/approval",
              "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      assertEquals(200, resolved.statusCode(), resolved.body());
      JsonNode done = sse.await("done", 5000);
      assertEquals("done", done.path("finalText").asText());
      // 文件在**回合结束之后**才读：批复返回 200 只说明那个答案被记下了，工具可能还在跑。此前这里当场就读，
      // 于是在 macOS 的 CI 上偶发 NoSuchFileException——一条在别人机器上红、在本机永远绿的测试，比没有这条
      // 测试更糟，因为它把「真的在磁盘上发生了」这句话变得不可信。
      assertEquals("hi", Files.readString(cwd.resolve("made.txt")));
      JsonNode ended = lastOf(sse, "tool");
      assertTrue(ended.path("ok").asBoolean(), ended.toString());
      assertEquals("end", ended.path("state").asText());
    }
  }

  @Test
  void anUnansweredApprovalWaitsRatherThanExpiring() throws Exception {
    // 这里钉住的行为：向人提出的问题不会过期。旧的 120 秒上限会终结回合，并让页面显示一个
    // 早已被撤回的提示——活儿被丢下了，而用户说不出为什么。
    //
    // 故意等过了旧的超时时间。两分钟会让整个测试套件没法用，所以断言的是：在*页面*对一条沉默
    // 的流放弃（20 秒）很久之后，回合仍然在等；旧设计失败的窗口正是这一段：提示没能及时到达
    // 浏览器。这里仍在等，就说明请求撑过来了。
    provider.reply(bashCall("printf hi > waiting.txt"));
    provider.reply(Message.Assistant.text("done"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"do it\"}");
      JsonNode approval = sse.await("approval", 5000);
      assertFalse(approval.path("id").asText().isEmpty());

      Thread.sleep(21_000); // past the page's stale-stream window

      // 仍然挂着，并没有替用户作答。
      JsonNode status = json("/api/status");
      assertEquals(1, status.path("approvals").size(), status.toString());
      assertFalse(Files.exists(cwd.resolve("waiting.txt")), "没人作答时什么也没跑");

      // 两条出路仍然有效：作答。
      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      sse.await("done", 5000);
      assertEquals("hi", Files.readString(cwd.resolve("waiting.txt")));
    }
  }

  @Test
  void abortingAnswersAPendingApproval() throws Exception {
    // 一条不依赖计时器的出路：等一个活人的回合以前要靠超时来终结，没有超时之后，中止就得是
    // 那个作答的东西。
    provider.reply(bashCall("printf hi > never.txt"));
    provider.reply(Message.Assistant.text("stopped"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"do it\"}");
      sse.await("approval", 5000);

      assertEquals(200, post("/api/abort", "{}").statusCode());

      // 回合结束了，而不是永远等下去；而且什么也没跑。
      sse.await("done", 5000);
      assertFalse(Files.exists(cwd.resolve("never.txt")));
      assertEquals(0, json("/api/status").path("approvals").size(), "问题已被撤回");
    }
  }

  @Test
  void aDeniedApprovalLeavesTheWorkspaceUntouched() throws Exception {
    provider.reply(bashCall("printf hi > nope.txt"));
    provider.reply(Message.Assistant.text("stopped"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"do it\"}");
      JsonNode approval = sse.await("approval", 5000);
      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":false}");

      sse.await("done", 5000);
      assertFalse(Files.exists(cwd.resolve("nope.txt")), "被拒绝的调用绝不能碰磁盘");
      assertFalse(lastOf(sse, "tool").path("ok").asBoolean(), "工具必须报告失败");
      assertFalse(hub.autoApprove(), "拒绝不能把闸门关掉");
    }
  }

  @Test
  void allowingForTheSessionStopsAskingAboutThatCommandAndNothingElse() throws Exception {
    // 「记住」现在意味着什么，而区别正是重点。它过去会把整个会话切到自动批准——于是替一条
    // 命令作答就决定了此后每一个问题——所以干这事的那个开关成了谁也不敢碰的那一个。现在作答
    // 的范围和用户按下的按钮一样窄：这条命令，本会话，其他所有东西的闸门照旧开着。
    // 回复是按回合入队的，而不是一次全排好：这些回合是不同的命令，而一次排满的队列会把第三个
    // 回合的答案递给第二个回合。
    provider.reply(bashCall("printf a > one.txt"));
    provider.reply(Message.Assistant.text("first"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"one\"}");
      JsonNode approval = sse.await("approval", 5000);
      post(
          "/api/approval",
          "{\"id\":\"" + approval.path("id").asText() + "\",\"answer\":\"session\"}");
      sse.await("done", 5000);
      assertFalse(hub.autoApprove(), "这个问题问的是一条命令，不是每一条命令");

      // 同一条命令再来一次：由记住的东西作答，所以不会有第二次提示。
      provider.reply(bashCall("printf a > one.txt"));
      provider.reply(Message.Assistant.text("same command"));
      post("/api/message", "{\"text\":\"same again\"}");
      sse.awaitAtLeast("done", 2, 5000);
      assertEquals(1, sse.ofType("approval").size(), "同一条命令不得问两次");

      // 不同的命令就是不同的问题，它仍然会被问到。
      provider.reply(bashCall("printf c > three.txt"));
      provider.reply(Message.Assistant.text("different command"));
      post("/api/message", "{\"text\":\"something else\"}");
      JsonNode second = sse.awaitAtLeast("approval", 2, 5000);
      assertNotNull(second, "没人放行过的命令仍然必须问一次");
      assertEquals("bash", second.path("tool").asText());
      assertEquals("printf c > three.txt", second.path("command").asText(),
          "而且提示把命令作为一个字段带上，这样才能被规则匹配");
      post("/api/approval", "{\"id\":\"" + second.path("id").asText() + "\",\"answer\":\"deny\"}");
      sse.awaitAtLeast("done", 3, 5000);
      assertFalse(Files.exists(cwd.resolve("three.txt")), "被拒绝的命令绝不能跑过");
    }
  }

  @Test
  void alwaysAllowWritesARuleAndAProjectPicksItUp() throws Exception {
    // 「总是允许」这个答案是一次文件写入，所以正是要小心的那一个：写进去的东西不能比被问到的
    // 范围更宽，而且事后必须真的管用——包括对后来才启动、重新读这个文件的进程。
    provider.reply(bashCall("printf a > one.txt"));
    provider.reply(Message.Assistant.text("first"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"one\"}");
      JsonNode approval = sse.await("approval", 5000);
      post(
          "/api/approval",
          "{\"id\":\"" + approval.path("id").asText() + "\",\"answer\":\"always\"}");
      sse.await("done", 5000);
    }

    String written = Files.readString(approvalsFile());
    assertTrue(written.contains("printf a > one.txt"), written);
    assertTrue(written.contains(cwd.toString()), "归档在它被授予的那个项目下：" + written);
    assertTrue(
        written.contains("one.txt"),
        "规则就是被批准的那条命令，逐字照录：" + written);
    assertFalse(
        written.contains("\"command\" : \"*\"") || written.contains("\"tool\" : \"bash\"\n    }"),
        "并且没有任何会放行所有命令的东西：" + written);

    // 同一个文件上重建一个全新的 hub，如下一个进程会做的那样：这条命令不会再被问到。
    api.close();
    hub.close();
    hub = hub(provider, testConfig());
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();

    provider.reply(bashCall("printf a > one.txt"));
    provider.reply(Message.Assistant.text("again"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"again\"}");
      sse.await("done", 5000);
      assertEquals(0, sse.ofType("approval").size(), "文件里的规则现在就替它作答");
      assertTrue(Files.exists(cwd.resolve("one.txt")));
    }
  }

  @Test
  void aRuleThatForbidsIsRefusedWithoutAskingAnybody() throws Exception {
    // 拒绝优先，而且它绝不能变成一次提示：写规则的意义就在于它自己说了算。
    Files.createDirectories(approvalsFile().getParent());
    Files.writeString(
        approvalsFile(),
        "{\"projects\": {\""
            + cwd
            + "\": {\"deny\": [{\"tool\": \"bash\", \"command\": \"printf a > one.txt\"}]}}}");
    provider.reply(bashCall("printf a > one.txt"));
    provider.reply(Message.Assistant.text("tried"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"one\"}");
      JsonNode done = sse.await("done", 5000);
      assertEquals(0, sse.ofType("approval").size(), "规则作答了，所以不用问任何人");
      assertFalse(Files.exists(cwd.resolve("one.txt")), "而且这条命令没有跑");
      assertTrue(done.toString().contains("tried"), done.toString());
    }
    // 转录里说的是规则说了不行，而不是报告某个人的拒绝：在一个没人看着的会话里，这是两种
    // 不同的事件。
    assertTrue(
        sseText().contains("被审批文件中的某条规则拒绝（可运行 ccj --help、查看 SECURITY.md 了解如何改规则）"),
        "规则的拒绝读起来必须就是规则的拒绝：" + sseText());
  }

  private String sseText() throws Exception {
    // 本次会话的转录里有什么就返回什么，如同页面会渲染出的样子。
    return json("/api/history").toString();
  }

  @Test
  void anApprovalInAnotherSessionIsVisibleAndAnswerableFromHere() throws Exception {
    // 一个需要活人的后台回合，不能因为用户正在看另一个对话就永远等下去：请求会带着它的会话一起
    // 发布，而在哪儿都能作答。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("ran it"));
    try (Sse sse = watch()) {
      assertEquals(202, post("/api/message", "{\"text\":\"start the long job\"}").statusCode());
      String b = json("/api/status").path("sessionId").asText();

      String a = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(b, a);

      JsonNode approval = sse.awaitInSession(b, "approval", 1, 5000);
      assertEquals(b, approval.path("sessionId").asText(), "请求里点名了它属于哪个会话");
      assertEquals("bash", approval.path("title").asText());

      post(
          "/api/approval",
          "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      assertEquals("ran it", sse.awaitInSession(b, "done", 1, 5000).path("finalText").asText());
    }
  }

  @Test
  void aPendingApprovalSurvivesLeavingAndComingBack() throws Exception {
    // 有人报的 bug：一个等待审批的回合，在用户一看别的对话时就把提示丢了，只剩中止。审批是
    // 阻塞在内存里的请求，不是一条消息，所以回放历史并不能把它带回来——它必须在等待中的那个
    // 对话的状态里可见，而页面在进入时必须把它重新渲染出来。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("ran it"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"a: run it\"}");
      String a = json("/api/status").path("sessionId").asText();
      JsonNode approval = sse.awaitInSession(a, "approval", 1, 5000);
      String approvalId = approval.path("id").asText();

      // 看向别处再回来：请求仍然未决，所以必须把这件事告诉页面。
      String b = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertNotEquals(a, b);
      JsonNode back = postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + a + "\"}");
      assertEquals(a, back.path("sessionId").asText());

      // 等待中的那个对话的状态点名了未决的请求，并带上把提示重新画出来所需的一切。
      JsonNode pending = back.path("approvals");
      assertTrue(pending.isArray(), back.toString());
      assertEquals(1, pending.size(), "未决的请求被报告了出来：" + back);
      assertEquals(approvalId, pending.get(0).path("id").asText());
      assertEquals("bash", pending.get(0).path("title").asText());
      assertTrue(pending.get(0).path("detail").asText().contains("echo hi"), pending.toString());

      // 而且从另一个对话里作答仍然有效。
      post("/api/approval", "{\"id\":\"" + approvalId + "\",\"allow\":true}");
      assertEquals("ran it", sse.awaitInSession(a, "done", 1, 5000).path("finalText").asText());
      assertEquals(
          0,
          json("/api/status").path("approvals").size(),
          "已解决的请求不再挂着");
    }
  }

  @Test
  void anApprovalBelongsToOneConversationOnly() throws Exception {
    // 可以有两个回合同时等待。每个页面只能被提供它自己那个对话的请求，否则作答屏幕上那个就会
    // 解决掉另一个的。
    provider.reply(
        new Message.Assistant(
            "", List.of(new Message.ToolCall("call_1", "bash", "{\"command\":\"echo hi\"}"))));
    provider.reply(Message.Assistant.text("a ran it"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"a: run it\"}");
      String a = json("/api/status").path("sessionId").asText();
      JsonNode approval = sse.awaitInSession(a, "approval", 1, 5000);

      // 一个全新的对话没有属于它自己的未决请求。
      String b = postJson("/api/session", "{\"action\":\"new\"}").path("sessionId").asText();
      assertEquals(
          0,
          json("/api/status").path("approvals").size(),
          "b 没有任何未决请求；a 的请求不是 b 的：" + json("/api/status"));
      // a 仍在等待，而且仍然这么说着。
      postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + a + "\"}");
      assertEquals(1, json("/api/status").path("approvals").size());

      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"allow\":true}");
      sse.awaitInSession(a, "done", 1, 5000);
    }
  }
}
