package com.ccj.agent.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The demo provider is user-facing (it is what {@code ccj --demo} runs), so its routing and its
 * termination rule are pinned here: every route must produce a valid call, and a tool result must
 * always end the turn in prose or the loop would never stop.
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

    assertTrue(reply.toolCalls().isEmpty(), "a bare verb must not become a tool call");
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

    assertTrue(reply.toolCalls().isEmpty(), "the loop would never terminate otherwise");
    assertEquals("tool said: exit code 0 … (3 lines)", reply.text());
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

    assertTrue(!first.equals(second), "ids must not collide: " + first);
  }

  @Test
  void theStreamedEventsMatchTheReturnedTurn() {
    List<String> deltas = new ArrayList<>();
    List<String> started = new ArrayList<>();
    Provider.Request request =
        new Provider.Request(
            "demo", null, List.of(new Message.User("read pom.xml")), List.of(), null, null);

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

    assertEquals(List.of(), deltas, "a tool call has no prose to stream");
    assertEquals(List.of("read"), started);
    assertEquals(reply.toolCalls().get(0).id(), reply.toolCalls().get(0).id());
  }

  private static List<String> call(Message.Assistant assistant) {
    Message.ToolCall call = assistant.toolCalls().get(0);
    return List.of(call.name(), call.arguments());
  }
}
