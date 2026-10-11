package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * 指标与痕迹跨过真的 HTTP，而数字来自真的回合。
 *
 * <p>单元用例能证明格式与记账，证明不了接线：一个没有被注册到任何东西上的指标注册表看起来完全一样，
 * 而它唯一的症状是抓取端上什么都不出现。所以这里走的是真服务器、真请求、真 SSE，然后去读那个端点。
 */
class WebMetricsTest extends WebHarness {

  @Test
  void theMetricsEndpointReportsTheTurnThisServerRan() throws Exception {
    Files.writeString(cwd.resolve("notes.txt"), "the file\n");
    provider.reply(call("read", "path", "notes.txt"));
    provider.reply(Message.Assistant.text("read it"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"peek\"}");
      sse.await("done", 5000);
    }
    // 侧栏要走一遍才留下那个数字——而它正是那个会随会话变大而变慢的调用，所以这条主张是「它确实被量了」。
    body("/api/sessions");

    String text = body("/metrics");
    assertTrue(text.contains("# TYPE ccj_runs_total counter\n"), text);
    assertTrue(text.contains("ccj_runs_total{outcome=\"ok\"} 1\n"), text);
    assertTrue(text.contains("ccj_model_turns_total 2\n"), text);
    assertTrue(text.contains("ccj_tool_calls_total{tool=\"read\"} 1\n"), text);
    assertTrue(text.contains("ccj_tool_seconds_count{tool=\"read\"} 1\n"), text);
    assertTrue(text.contains("ccj_run_seconds_count 1\n"), text);
    // 前缀是每个回合固定发出去的那一份：它必须是个真数字，而不是「还没量」。
    assertTrue(text.contains("ccj_prompt_prefix_tokens "), text);
    assertFalse(text.contains("ccj_prompt_prefix_tokens 0\n"), text);
    assertTrue(text.contains("ccj_session_list_seconds_count 1\n"), text);
  }

  @Test
  void theTraceEndpointDescribesTheLastRunAsATree() throws Exception {
    Files.writeString(cwd.resolve("notes.txt"), "the file\n");
    provider.reply(call("read", "path", "notes.txt"));
    provider.reply(Message.Assistant.text("read it"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"peek\"}");
      sse.await("done", 5000);
    }

    JsonNode trace = json("/api/trace");
    JsonNode run = trace.path("run");
    assertEquals("run", run.path("name").asText(), trace.toString());
    assertEquals("ok", run.path("attributes").path("outcome").asText(), trace.toString());
    assertEquals(2, run.path("children").size(), trace.toString());

    JsonNode firstTurn = run.path("children").get(0);
    assertEquals("turn", firstTurn.path("name").asText());
    JsonNode tool = firstTurn.path("children").get(0);
    assertEquals("tool", tool.path("name").asText(), firstTurn.toString());
    assertEquals("read", tool.path("attributes").path("tool").asText(), tool.toString());
    assertTrue(tool.path("end").asLong() >= tool.path("start").asLong(), tool.toString());
  }

  @Test
  void theTraceEndpointSaysSoWhenNothingHasRunYet() throws Exception {
    // 「还没跑过」是一种状态，不是一个错误：一个刚起来的服务器没有痕迹可给，而 404 会让页面把它当成故障。
    JsonNode trace = json("/api/trace");
    assertTrue(trace.path("run").isNull(), trace.toString());
  }

  @Test
  void theMetricsEndpointTakesReadsOnly() throws Exception {
    assertEquals(405, post("/metrics", "{}").statusCode());
    assertEquals(405, post("/api/trace", "{}").statusCode());
  }

  @Test
  void anAnsweredApprovalIsCountedWithTheAnswerThatWasGiven() throws Exception {
    provider.reply(bashCall("printf hi > made.txt"));
    provider.reply(Message.Assistant.text("done"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"do it\"}");
      JsonNode approval = sse.await("approval", 5000);
      assertFalse(approval.path("id").asText().isEmpty(), approval.toString());
      post(
          "/api/approval",
          "{\"id\":\"" + approval.path("id").asText() + "\",\"answer\":\"always\"}");
      sse.await("done", 5000);
    }

    String text = body("/metrics");
    // 线上那个词是 `always`（见 ApprovalDesk.answerOf），而指标里的标签是那个枚举自己的名字——两个词都是
    // 各自的词汇表里对的那一个，混用它们才会让这张表读起来像在说两件事。
    // 审批不经过循环的回调——它发生在一次工具调用的里面——所以这条主张同时钉住了那层装饰被接上了。
    assertTrue(text.contains("ccj_approvals_total{answer=\"allow_always\"} 1\n"), text);
    assertTrue(text.contains("ccj_approval_seconds_count 1\n"), text);
    assertEquals("hi", Files.readString(cwd.resolve("made.txt")));
  }

  @Test
  void aDeniedApprovalIsCountedSeparatelyFromAnAllowedOne() throws Exception {
    provider.reply(bashCall("printf hi > never.txt"));
    provider.reply(Message.Assistant.text("stopped"));

    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"do it\"}");
      JsonNode approval = sse.await("approval", 5000);
      post("/api/approval", "{\"id\":\"" + approval.path("id").asText() + "\",\"answer\":\"deny\"}");
      sse.await("done", 5000);
    }

    String text = body("/metrics");
    assertTrue(text.contains("ccj_approvals_total{answer=\"deny\"} 1\n"), text);
    assertFalse(Files.exists(cwd.resolve("never.txt")), "被拒绝的命令什么都不该跑");
  }

  @Test
  void theSubscriberGaugeFollowsTheOpenStreams() throws Exception {
    assertEquals(0, gauge("/metrics", "ccj_sse_subscribers"));
    try (Sse ignored = watch()) {
      // 订阅是在打开流的那一次请求里完成的，所以这里要等到它真的被记下来。
      long deadline = System.nanoTime() + 5_000_000_000L;
      while (System.nanoTime() < deadline && gauge("/metrics", "ccj_sse_subscribers") == 0) {
        Thread.sleep(10);
      }
      assertEquals(1, gauge("/metrics", "ccj_sse_subscribers"), body("/metrics"));
    }
    try (Sse ignored = watch()) {
      // 第二个连接：这个仪表数的是连接，而不是「有没有人在听」。
      Thread.sleep(100);
      assertEquals(2, gauge("/metrics", "ccj_sse_subscribers"), body("/metrics"));
    }
  }

  /** 一个没有标签的仪表的当前值。 */
  private int gauge(String path, String name) throws Exception {
    for (String line : body(path).split("\n")) {
      if (line.startsWith(name + " ")) {
        return Integer.parseInt(line.substring(name.length() + 1).strip());
      }
    }
    throw new AssertionError("指标 " + name + " 不在 " + body(path));
  }
}
