package com.ccj.agent.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The playground routes prompts to tool calls, which is all a human needs to drive the real CLI
 * offline. The behaviour worth pinning is that every route produces a valid call and that a tool
 * result always ends the turn in prose — otherwise the loop would never terminate.
 */
class PlaygroundModelTest {

  private static JsonNode userSays(String text) {
    return Json.parse(
        "{\"messages\":[{\"role\":\"user\",\"content\":"
            + Json.write(Json.mapper().getNodeFactory().textNode(text))
            + "}]}");
  }

  private static MockModelServer.PlaygroundAction action(JsonNode request) {
    return MockModelServer.playgroundAction(request);
  }

  @Test
  void routesPromptsToRealToolCalls() {
    assertEquals(
        new MockModelServer.PlaygroundAction.Call("play_read", "read", "{\"path\":\"README.md\"}"),
        action(userSays("read README.md")));

    assertEquals(
        new MockModelServer.PlaygroundAction.Call(
            "play_bash", "bash", "{\"command\":\"git status --short\"}"),
        action(userSays("run git status --short")));

    assertEquals(
        new MockModelServer.PlaygroundAction.Call(
            "play_glob", "glob", "{\"pattern\":\"src/**/*.java\"}"),
        action(userSays("list src/**/*.java")));

    assertEquals(
        new MockModelServer.PlaygroundAction.Call(
            "play_glob", "glob", "{\"pattern\":\"**/*\"}"),
        action(userSays("list")));

    assertEquals(
        new MockModelServer.PlaygroundAction.Call(
            "play_grep", "grep", "{\"pattern\":\"TODO\"}"),
        action(userSays("search TODO")));
  }

  @Test
  void routesAreCaseInsensitiveAndTrimmed() {
    assertEquals(
        new MockModelServer.PlaygroundAction.Call("play_read", "read", "{\"path\":\"a.txt\"}"),
        action(userSays("  READ   a.txt  ")));
  }

  @Test
  void aBareVerbIsNotMistakenForAPath() {
    var say =
        assertInstanceOf(
            MockModelServer.PlaygroundAction.Say.class, action(userSays("read")));
    assertTrue(say.text().contains("read <path>"), say.text());
  }

  @Test
  void unknownPromptsAreAnsweredInProseSoTheLoopEnds() {
    var say =
        assertInstanceOf(
            MockModelServer.PlaygroundAction.Say.class, action(userSays("who are you?")));
    assertTrue(say.text().contains("who are you?"), say.text());
  }

  @Test
  void aToolResultAlwaysEndsTheTurnWithProse() {
    JsonNode request =
        Json.parse(
            "{\"messages\":[{\"role\":\"user\",\"content\":\"run ls\"},"
                + "{\"role\":\"tool\",\"content\":\"exit code 0\\nfile-a\\nfile-b\"}]}");

    var say = assertInstanceOf(MockModelServer.PlaygroundAction.Say.class, action(request));
    assertEquals("tool said: exit code 0 … (3 lines)", say.text());
  }

  @Test
  void anEmptyConversationDoesNotCrash() {
    assertInstanceOf(
        MockModelServer.PlaygroundAction.Say.class, action(Json.parse("{\"messages\":[]}")));
    assertInstanceOf(MockModelServer.PlaygroundAction.Say.class, action(Json.parse("{}")));
  }
}
