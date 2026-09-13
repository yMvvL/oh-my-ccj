package com.ccj.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageCodecTest {

  @Test
  void systemMessageSurvivesNewlinesQuotesEmojiAndCjk() {
    Message.System original =
        new Message.System("日本語のテキスト\nwith \"quotes\" and 'apostrophes'\nemoji 🚀👩💻\ttab");

    Message decoded = MessageCodec.fromJson(MessageCodec.toJson(original));

    assertEquals(original, decoded);
  }

  @Test
  void userMessageRoundTrips() {
    Message.User original = new Message.User("line one\n\"line two\" — 中文 🎯\n");

    assertEquals(original, MessageCodec.fromJson(MessageCodec.toJson(original)));
  }

  @Test
  void assistantWithToolCallsKeepsArgumentsVerbatim() {
    String arguments = "{\"path\":\"src/日本語.txt\",\"content\":\"a\\nb\\n🐙\"}";
    Message.Assistant original =
        new Message.Assistant(
            "Reading files\n第二行",
            List.of(
                new Message.ToolCall("call-1", "read", arguments),
                new Message.ToolCall("call-2", "bash", "{\"command\":\"ls -la\"}")));

    Message decoded = MessageCodec.fromJson(MessageCodec.toJson(original));

    assertEquals(original, decoded);
    assertEquals(arguments, ((Message.Assistant) decoded).toolCalls().get(0).arguments());
  }

  @Test
  void assistantWithoutToolCallsDecodesToEmptyList() {
    Message decoded = MessageCodec.fromJson("{\"type\":\"assistant\",\"text\":\"hello\"}");

    assertEquals(new Message.Assistant("hello", List.of()), decoded);
  }

  @Test
  void toolResultRoundTripsErrorFlag() {
    Message.ToolResult original = new Message.ToolResult("call-9", "edit", "not \"found\"\n東京", true);

    assertEquals(original, MessageCodec.fromJson(MessageCodec.toJson(original)));
  }

  @Test
  void unknownTypeIsRejected() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> MessageCodec.fromJson("{\"type\":\"thinking\",\"text\":\"hi\"}"));

    assertTrue(error.getMessage().contains("thinking"), error.getMessage());
  }

  @Test
  void missingTypeIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> MessageCodec.fromJson("{\"text\":\"hi\"}"));
  }

  @Test
  void nonObjectIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> MessageCodec.fromJson("[1,2,3]"));
    assertThrows(IllegalArgumentException.class, () -> MessageCodec.fromJson("\"plain\""));
  }

  @Test
  void thinkingBlocksSurviveTheRoundTripAndCostNothingWhenAbsent() {
    // Extended thinking has to be handed back to the API verbatim, which means the session file is
    // where a signature has to survive: a lost signature is a turn the API refuses.
    Message.Assistant thinking =
        new Message.Assistant(
            "answer",
            List.of(new Message.ToolCall("call_1", "read", "{}")),
            List.of(
                Message.Thinking.of("weighing the options", "sig-abc"),
                Message.Thinking.redacted("opaque")));

    Message decoded = MessageCodec.fromJson(MessageCodec.toJson(thinking));

    assertEquals(thinking, decoded);
    assertEquals(2, ((Message.Assistant) decoded).thinking().size());
    assertTrue(((Message.Assistant) decoded).thinking().get(1).redacted());

    // A turn without thinking is written exactly as it was before thinking existed.
    Message.Assistant plain = new Message.Assistant("answer", List.of());
    assertEquals(
        "{\"type\":\"assistant\",\"text\":\"answer\",\"tool_calls\":[]}",
        MessageCodec.toJson(plain));

    // And an empty block — a stream cut before it carried anything — is dropped rather than kept as
    // something the API would reject.
    Message.Assistant empty =
        (Message.Assistant)
            MessageCodec.fromJson(
                "{\"type\":\"assistant\",\"text\":\"x\",\"tool_calls\":[],\"thinking\":[{\"text\":\"\"}]}");
    assertTrue(empty.thinking().isEmpty());
  }

  @Test
  void malformedFieldsAreRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> MessageCodec.fromJson("{\"type\":\"user\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> MessageCodec.fromJson("{\"type\":\"assistant\",\"text\":123}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> MessageCodec.fromJson("{\"type\":\"assistant\",\"text\":\"x\",\"tool_calls\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MessageCodec.fromJson(
                "{\"type\":\"assistant\",\"text\":\"x\",\"tool_calls\":[{\"id\":\"1\"}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MessageCodec.fromJson(
                "{\"type\":\"tool_result\",\"tool_call_id\":\"1\",\"tool_name\":\"read\","
                    + "\"content\":\"x\",\"error\":\"yes\"}"));
  }

  @Test
  void invalidJsonIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> MessageCodec.fromJson("not json at all"));
  }
}
