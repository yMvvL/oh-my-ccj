package com.ccj.agent.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * demo 提供方是给人用的（{@code ccj --demo} 跑的就是它），所以它的路由和终止规则钉在这里：每条路由都
 * 必须产出一个合法的调用，而工具结果必须总是以散文结束这个回合，否则循环永远不会停。
 */
class DemoProviderTest {

  private final DemoProvider provider = new DemoProvider();

  private Message.Assistant decide(String userText) {
    return provider.decide(List.of(new Message.User(userText)));
  }

  @Test
  void routesTheFourSupportedCommands() {
    assertEquals(
        List.of("read", "{\"path\":\"README.md\"}"),
        call(decide("read README.md")));
    assertEquals(
        List.of("bash", "{\"command\":\"git status --short\"}"),
        call(decide("run git status --short")));
    assertEquals(
        List.of("bash", "{\"command\":\"ls -la\"}"),
        call(decide("bash ls -la")));
    assertEquals(
        List.of("glob", "{\"pattern\":\"src/main/**/*.java\"}"),
        call(decide("list src/main/**/*.java")));
    assertEquals(List.of("glob", "{\"pattern\":\"**/*\"}"), call(decide("list")));
    assertEquals(List.of("grep", "{\"pattern\":\"TODO\"}"), call(decide("search TODO")));
  }

  @Test
  void routingIsCaseInsensitiveAndTrimmed() {
    assertEquals(List.of("read", "{\"path\":\"a.txt\"}"), call(decide("  READ   a.txt  ")));
  }

  @Test
  void aBareVerbIsAnsweredInsteadOfMisreadAsAPath() {
    Message.Assistant reply = decide("read");

    assertTrue(reply.toolCalls().isEmpty(), "光秃秃的动词不能变成一个工具调用");
    assertTrue(reply.text().contains("read <path>"), reply.text());
  }

  @Test
  void anythingElseGetsTheVocabularyBack() {
    Message.Assistant reply = decide("who are you?");

    assertTrue(reply.toolCalls().isEmpty());
    assertTrue(reply.text().contains("who are you?"), reply.text());
    assertTrue(reply.text().contains("search <regex>"), reply.text());
  }

  @Test
  void aToolResultAlwaysEndsTheTurnInProse() {
    Message.Assistant reply =
        provider.decide(
            List.of(
                new Message.User("run ls"),
                new Message.ToolResult("demo-bash-1", "bash", "exit code 0\nfile-a\nfile-b", false)));

    assertTrue(reply.toolCalls().isEmpty(), "否则循环永远不会终止");
    assertEquals("工具说：exit code 0 …（共 3 行）", reply.text());
  }

  @Test
  void anEmptyConversationDoesNotCrash() {
    assertTrue(provider.decide(List.of()).toolCalls().isEmpty());
    assertTrue(provider.decide(null).toolCalls().isEmpty());
  }

  @Test
  void toolCallIdsAreUniqueAcrossTurns() {
    String first = decide("read a.txt").toolCalls().get(0).id();
    String second = decide("read a.txt").toolCalls().get(0).id();

    assertTrue(!first.equals(second), "id 不能相撞：" + first);
  }

  @Test
  void theStreamedEventsMatchTheReturnedTurn() {
    List<String> deltas = new ArrayList<>();
    List<String> started = new ArrayList<>();
    Provider.Request request =
        new Provider.Request(
            "demo", null, List.of(new Message.User("read pom.xml")), List.of(), null, null, null);

    Message.Assistant reply =
        provider.complete(
            request,
            event -> {
              switch (event) {
                case Provider.Event.TextDelta d -> deltas.add(d.text());
                case Provider.Event.ToolCallStart s -> started.add(s.name());
                default -> {}
              }
            });

    assertEquals(List.of(), deltas, "工具调用没有散文可以流式发送");
    assertEquals(List.of("read"), started);
    assertEquals(reply.toolCalls().get(0).id(), reply.toolCalls().get(0).id());
  }

  private static List<String> call(Message.Assistant assistant) {
    Message.ToolCall call = assistant.toolCalls().get(0);
    return List.of(call.name(), call.arguments());
  }
}
