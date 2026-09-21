package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import java.util.Optional;
import com.ccj.agent.session.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 会话与工作区：列出、切换、新建、删除，以及目录怎样决定一个对话属于哪里。
 *
 * <p>单独成类，是因为这一组都在摆弄持久层的形状——会话文件与工作区注册表——而不是流或模型。
 */
class WebSessionWorkspaceTest extends WebHarness {

  @Test
  void sessionsCanBeListedAndSwitched() throws Exception {
    provider.reply(Message.Assistant.text("ok"));
    String first;
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"remember me\"}");
      sse.await("done", 5000);
      first = json("/api/status").path("sessionId").asText();
    }

    JsonNode list = json("/api/sessions").path("sessions");
    assertEquals(1, list.size(), list.toString());
    assertEquals("remember me", list.get(0).path("preview").asText());
    assertEquals("remember me", list.get(0).path("title").asText(),
        "侧边栏用被问的第一句话给会话打标签，而不是用它的时间戳 id");
    assertEquals(first, list.get(0).path("id").asText());

    JsonNode created = postJson("/api/session", "{\"action\":\"new\"}");
    assertNotEquals(first, created.path("sessionId").asText());

    JsonNode resumed = postJson("/api/session", "{\"action\":\"resume\",\"id\":\"" + first + "\"}");
    assertEquals(first, resumed.path("sessionId").asText());
    assertEquals(2, resumed.path("messageCount").asInt(), "历史必须从磁盘回放");
  }

  @Test
  void newSessionOnAnEmptySessionIsRefusedInsteadOfMintingAnotherId() throws Exception {
    String before = json("/api/status").path("sessionId").asText();

    try (Sse sse = watch()) {
      JsonNode response = postJson("/api/session", "{\"action\":\"new\"}");
      assertEquals(before, response.path("sessionId").asText(), "id 不得改变");
      assertTrue(sse.await("notice", 3000).path("text").asText().contains("这个会话已经是空的——先随便说点什么"));
    }
    assertTrue(SessionStore.list(sessions).isEmpty(), "空会话不得冒出任何会话文件");
  }

  @Test
  void newSessionAfterARealTurnDoesStartAFreshOne() throws Exception {
    provider.reply(Message.Assistant.text("hi"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      sse.await("done", 5000);
    }
    String before = json("/api/status").path("sessionId").asText();

    JsonNode created = postJson("/api/session", "{\"action\":\"new\"}");

    assertNotEquals(before, created.path("sessionId").asText());
    assertEquals(0, json("/api/history").path("events").size(), "新会话是空的");
    assertEquals(0, json("/api/status").path("usage").path("inputTokens").asInt(), "总计已重置");
  }

  @Test
  void onlyAReconnectingClientGetsTheReplayBuffer() throws Exception {
    provider.reply(Message.Assistant.text("live answer"));
    long lastIdOfTheTurn;
    try (Sse first = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      SseEvent done = first.awaitEvent("done", 5000);
      lastIdOfTheTurn = done.id();
    }

    // 全新的页面没有缺口要补：它从 /api/history 渲染对话，所以在这里回放缓冲区会把每个最近的
    // 事件都画第二遍。
    try (Sse fresh = watch()) {
      fresh.await("status", 3000);
      Thread.sleep(300);
      assertEquals(
          List.of("status"),
          fresh.types(),
          "全新的连接拿到的是它的状态，而不是它从未见过的那些回合的实时事件");
    }

    // 重连的页面确实有缺口，而且只有那个缺口。
    try (Sse resumed = watchWithLastEventId(lastIdOfTheTurn - 1)) {
      assertEquals("done", resumed.await("done", 3000).path("type").asText());
    }
  }

  @Test
  void listsTheStartingDirectoryAsTheActiveWorkspace() throws Exception {
    JsonNode payload = json("/api/workspaces");

    assertEquals("ws", payload.path("active").asText());
    assertEquals(1, payload.path("workspaces").size());
    JsonNode entry = payload.path("workspaces").get(0);
    assertEquals(cwd.toString(), entry.path("path").asText());
    assertTrue(entry.path("active").asBoolean());
    assertEquals(0, entry.path("sessions").asInt());

    JsonNode status = json("/api/status");
    assertEquals("ws", status.path("workspace").path("name").asText());
    assertEquals(cwd.toString(), status.path("cwd").asText());
  }

  @Test
  void addingAndSwitchingAWorkspaceMovesBothTheDirectoryAndTheHistory() throws Exception {
    Path other = tmp.resolve("other-project");
    Files.writeString(Files.createDirectories(other).resolve("note.txt"), "in the other project");

    JsonNode added = postJson("/api/workspaces", "{\"name\":\"other\",\"path\":\"" + other + "\"}");
    assertEquals(2, added.path("workspaces").size(), added.toString());

    JsonNode switched = postJson("/api/workspace", "{\"name\":\"other\"}");
    assertEquals("other", switched.path("workspace").path("name").asText());
    assertEquals(other.toString(), switched.path("cwd").asText(), "工具现在在那个目录里工作");

    // 切换之前写下的会话属于旧工作区，绝不能在这里出现。
    assertEquals(0, json("/api/sessions").path("sessions").size());
    assertEquals(0, json("/api/history").path("events").size());

    // 而且相对的工具路径真的在那里解析：这个文件只存在于新工作区里。
    provider.reply(call("read", "path", "note.txt"));
    provider.reply(Message.Assistant.text("read it"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"read note.txt\"}");
      sse.await("done", 5000);
      JsonNode ended = lastOf(sse, "tool");
      assertTrue(ended.path("ok").asBoolean(), ended.toString());
      assertTrue(ended.path("output").asText().contains("in the other project"), ended.toString());
    }
  }

  @Test
  void eachWorkspaceKeepsItsOwnSessions() throws Exception {
    provider.reply(Message.Assistant.text("from the first workspace"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"remember this\"}");
      sse.await("done", 5000);
    }
    assertEquals(1, json("/api/sessions").path("sessions").size());
    String firstSession = json("/api/status").path("sessionId").asText();

    postJson("/api/workspaces", "{\"name\":\"second\",\"path\":\"" + tmp.resolve("second") + "\"}");
    postJson("/api/workspace", "{\"name\":\"second\"}");
    assertEquals(
        0, json("/api/sessions").path("sessions").size(), "另一个工作区就是另一段历史");

    postJson("/api/workspace", "{\"name\":\"ws\"}");

    JsonNode back = json("/api/sessions").path("sessions");
    assertEquals(1, back.size());
    assertEquals(firstSession, back.get(0).path("id").asText());
  }

  @Test
  void forgettingAWorkspaceIsRefusedForTheActiveOne() throws Exception {
    postJson("/api/workspaces", "{\"name\":\"temp\",\"path\":\"" + tmp.resolve("temp") + "\"}");

    HttpResponse<String> refusal =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/workspace?name=ws"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, refusal.statusCode(), refusal.body());
    assertTrue(refusal.body().contains("无法移除活动工作区 'ws'；请先切换到另一个"), refusal.body());

    HttpResponse<String> removed =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/workspace?name=temp"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, removed.statusCode(), removed.body());
    assertEquals(1, json("/api/workspaces").path("workspaces").size());
  }

  @Test
  void aBadWorkspaceIsRejectedWithAReason() throws Exception {
    assertEquals(400, post("/api/workspaces", "{\"name\":\"a/b\",\"path\":\"/tmp\"}").statusCode());
    assertEquals(400, post("/api/workspaces", "{\"name\":\"ws\",\"path\":\"/tmp\"}").statusCode());
    assertEquals(400, post("/api/workspaces", "{\"name\":\"ok\"}").statusCode());
    assertEquals(400, post("/api/workspace", "{\"name\":\"nope\"}").statusCode());
  }

  @Test
  void aPathWithoutANameIsAddedUnderTheFoldersOwnName() throws Exception {
    Path picked = Files.createDirectories(tmp.resolve("picked-thing"));

    JsonNode added = postJson("/api/workspaces", "{\"path\":\"" + picked + "\"}");

    assertEquals(2, added.path("workspaces").size(), added.toString());
    JsonNode entry = added.path("workspaces").get(1);
    assertEquals("picked-thing", entry.path("name").asText());
    assertEquals(picked.toString(), entry.path("path").asText());
    assertEquals("ws", added.path("active").asText(), "挑一个文件夹不会切换");
  }

  @Test
  void pickingTheSameDirectoryTwiceIsRefusedWithTheWorkspaceThatOwnsIt() throws Exception {
    Path project = Files.createDirectories(tmp.resolve("twice"));

    assertEquals("twice", postJson("/api/workspaces", "{\"path\":\"" + project + "\"}").path("workspaces").get(1).path("name").asText());

    // 同一个目录，不同的写法：正是归一化让这里变成一次拒绝，而不是多出一条带着竞争对手会话
    // 历史的条目。
    HttpResponse<String> refusal =
        post("/api/workspaces", "{\"path\":\"" + project.resolve(".") + "\"}");

    assertEquals(400, refusal.statusCode(), refusal.body());
    assertTrue(refusal.body().contains("twice"), refusal.body());
    assertEquals(2, json("/api/workspaces").path("workspaces").size());
  }

  @Test
  void aPathIsRequiredAndAnUnusableFolderNameIsRefused() throws Exception {
    HttpResponse<String> noPath = post("/api/workspaces", "{\"path\":\"\"}");
    assertEquals(400, noPath.statusCode(), noPath.body());
    assertTrue(noPath.body().contains("工作区需要一个目录"), noPath.body());

    // 名为 "my project" 的文件夹会被添加，而不是被拒——但名字没法当单独一个路径段的（开头的
    // 短横会读成一个 flag）会带着原因退回来。
    Path spaced = Files.createDirectories(tmp.resolve("my project"));
    assertEquals(
        "my project",
        postJson("/api/workspaces", "{\"path\":\"" + spaced + "\"}")
            .path("workspaces").get(1).path("name").asText());

    Path odd = Files.createDirectories(tmp.resolve("-dashed"));
    HttpResponse<String> oddName = post("/api/workspaces", "{\"path\":\"" + odd + "\"}");
    assertEquals(400, oddName.statusCode(), oddName.body());
    assertTrue(oddName.body().contains("文件夹名 '-dashed' 不能作为工作区名：工作区名称必须为 1-40 个字符，不能包含路径分隔符，不能以连字符开头，也不能是 '.' 或 '..'"), oddName.body());
  }

  @Test
  void deletingASessionRemovesItAndLeavesTheRest() throws Exception {
    provider.reply(Message.Assistant.text("one"));
    provider.reply(Message.Assistant.text("two"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"first\"}");
      sse.await("done", 5000);
    }
    String older = json("/api/status").path("sessionId").asText();
    JsonNode created = postJson("/api/session", "{\"action\":\"new\"}");
    String newer = created.path("sessionId").asText();
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"second\"}");
      sse.await("done", 5000);
    }

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/session?id=" + older))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, Json.parse(response.body()).path("sessions").size());
    assertEquals(newer, json("/api/status").path("sessionId").asText(), "另一个保持活动状态");
  }

  @Test
  void deletingTheActiveSessionStartsAFreshOne() throws Exception {
    provider.reply(Message.Assistant.text("hi"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"hello\"}");
      sse.await("done", 5000);
    }
    String active = json("/api/status").path("sessionId").asText();

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/session?id=" + active))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode(), response.body());
    assertFalse(Files.exists(sessions.resolve(active + ".jsonl")), "文件没了");
    assertNotEquals(active, json("/api/status").path("sessionId").asText(), "总得有个地方接着待");
    assertEquals(0, json("/api/sessions").path("sessions").size());
  }

  @Test
  void deletingASessionThatDoesNotExistIsRejected() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/session?id=20200101-000000-abcd"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(400, response.statusCode(), response.body());
    assertTrue(response.body().contains("在 本工作区 里没有会话 '20200101-000000-abcd'"), response.body());
    assertEquals(400, client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/session"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void deletingEverythingClearsTheWorkspaceAndStartsOver() throws Exception {
    provider.reply(Message.Assistant.text("one"));
    provider.reply(Message.Assistant.text("two"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"first\"}");
      sse.await("done", 5000);
      postJson("/api/session", "{\"action\":\"new\"}");
      post("/api/message", "{\"text\":\"second\"}");
      sse.awaitAtLeast("done", 2, 5000);
    }
    assertEquals(2, json("/api/sessions").path("sessions").size());

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/sessions")).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(0, Json.parse(response.body()).path("sessions").size());
    assertEquals(0, json("/api/sessions").path("sessions").size());
  }

  @Test
  void theFolderChooserIsOpenedOnDemandAndItsAnswersArePassedThrough() throws Exception {
    Path project = Files.createDirectories(tmp.resolve("picked-project"));

    chooserBehaviour = title -> Optional.of(project);

    JsonNode chosen = postJson("/api/workspaces/browse", "{}");
    assertEquals(project.toString(), chosen.path("path").asText());

    chooserBehaviour = title -> Optional.empty();
    assertTrue(postJson("/api/workspaces/browse", "{}").path("cancelled").asBoolean());

    chooserBehaviour =
        title -> {
          throw new IOException("no desktop session available, so ccj cannot open a folder chooser");
        };
    HttpResponse<String> failure = post("/api/workspaces/browse", "{}");
    assertEquals(400, failure.statusCode(), failure.body());
    assertTrue(failure.body().contains("desktop"), failure.body());

    assertEquals(405, get("/api/workspaces/browse").statusCode());
  }

  @Test
  void sessionsOfAnyWorkspaceCanBeListedWithoutSwitching() throws Exception {
    provider.reply(Message.Assistant.text("from ws"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"in the first\"}");
      sse.await("done", 5000);
    }
    String firstId = json("/api/status").path("sessionId").asText();
    postJson("/api/workspaces", "{\"name\":\"other\",\"path\":\"" + tmp.resolve("other") + "\"}");
    postJson("/api/workspace", "{\"name\":\"other\"}");

    // 折叠一个文件夹绝不能挪动当前工作区，只是读它的内容。
    JsonNode others = json("/api/sessions?workspace=ws");
    assertEquals("ws", others.path("workspace").asText());
    assertEquals(1, others.path("sessions").size());
    assertEquals(firstId, others.path("sessions").get(0).path("id").asText());
    assertEquals(
        "other", json("/api/status").path("workspace").path("name").asText(), "仍然是当前活动：other");

    assertEquals(0, json("/api/sessions?workspace=other").path("sessions").size());
    assertEquals(0, json("/api/sessions").path("sessions").size(), "不带参数就意味着当前活动的那个");
    assertEquals(400, get("/api/sessions?workspace=nope").statusCode());
  }

  @Test
  void aSessionOfAnotherWorkspaceCanBeDeletedWithoutSwitching() throws Exception {
    provider.reply(Message.Assistant.text("from ws"));
    try (Sse sse = watch()) {
      post("/api/message", "{\"text\":\"keep me\"}");
      sse.await("done", 5000);
    }
    String wsSession = json("/api/status").path("sessionId").asText();
    postJson("/api/workspaces", "{\"name\":\"other\",\"path\":\"" + tmp.resolve("other") + "\"}");
    postJson("/api/workspace", "{\"name\":\"other\"}");
    String activeNow = json("/api/status").path("sessionId").asText();

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/session?workspace=ws&id=" + wsSession))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(0, Json.parse(response.body()).path("sessions").size());
    assertEquals(activeNow, json("/api/status").path("sessionId").asText(), "仍然在 'other' 里");
    assertFalse(Files.exists(sessions.resolve(wsSession + ".jsonl")));

    // 而且全部删除那条路由接受同一个参数
    assertEquals(
        200,
        client.send(
                HttpRequest.newBuilder(URI.create(origin + "/api/sessions?workspace=ws"))
                    .DELETE()
                    .build(),
            HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(400, get("/api/sessions?workspace=nope").statusCode());
  }
}
