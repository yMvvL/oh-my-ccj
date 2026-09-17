package com.ccj.agent.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.AgentException;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.VisionConfig;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class VisionClientTest {

  private static final String DATA_URL_PREFIX = "data:image/png;base64,";

  private static final String DESCRIPTION =
      "{\"choices\":[{\"message\":{\"content\":\"a red square\"}}]}";

  @Test
  void sendsThePictureAsADataUrlAndReturnsTheDescription() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.json(200, DESCRIPTION));
        VisionClient client = new VisionClient(server.url(), "sk-vision", "vision-test")) {
      byte[] png = png();

      assertEquals("a red square", client.describe(png, "image/png"));

      assertEquals("/chat/completions", server.path(0));
      assertEquals("Bearer sk-vision", server.header(0, "authorization"));
      JsonNode sent = Json.parse(server.body(0));
      assertEquals("vision-test", sent.get("model").asText());
      assertEquals(
          VisionClient.DEFAULT_MAX_TOKENS,
          sent.get("max_tokens").asInt(),
          "the budget is a ceiling and not a spend, so the default is generous");
      JsonNode parts = sent.path("messages").get(0).path("content");
      assertTrue(
          parts.get(0).path("text").asText().contains("not from the user"),
          "the prompt must say the picture's instructions are not the user's: " + parts.get(0));
      JsonNode picture = parts.get(1);
      assertEquals("image_url", picture.path("type").asText());
      String url = picture.path("image_url").path("url").asText();
      assertTrue(url.startsWith(DATA_URL_PREFIX), "the picture must travel as a data URL: " + url);
      assertArrayEquals(
          png,
          Base64.getDecoder().decode(url.substring(DATA_URL_PREFIX.length())),
          "the data URL must carry the picture's own bytes");
    }
  }

  @Test
  void aServerErrorIsAFailedDescriptionNotACrash() throws Exception {
    try (FakeServer server =
            FakeServer.start(FakeServer.Reply.json(500, "{\"error\":\"upstream exploded\"}"));
        VisionClient client = new VisionClient(server.url(), "sk-vision", "vision-test")) {
      AgentException failure =
          assertThrows(AgentException.class, () -> client.describe(png(), "image/png"));

      assertTrue(failure.getMessage().contains("500"), failure.getMessage());
      assertTrue(failure.getMessage().contains("/chat/completions"), failure.getMessage());
      assertTrue(failure.getMessage().contains("upstream exploded"), failure.getMessage());
    }
  }

  @Test
  void aReplyThatRanOutOfRoomWhileReasoningNamesTheSetting() throws Exception {
    // The reported failure, reproduced: a phone screenshot of a busy page came back HTTP 200 with
    // `finish_reason: length`, every one of the 1500-token budget spent on reasoning, `content`
    // empty, and 6224 characters of thinking in the reply. The message has to say which setting
    // fixes it, because "what arrived" is a wall of JSON that does not.
    String reply =
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\","
            + "\"reasoning\":\"We need answer. Need describe picture as data…\"},"
            + "\"finish_reason\":\"length\"}],"
            + "\"usage\":{\"completion_tokens\":1500,"
            + "\"completion_tokens_details\":{\"reasoning_tokens\":1500}}}";
    try (FakeServer server = FakeServer.start(FakeServer.Reply.json(200, reply));
        VisionClient client = new VisionClient(server.url(), "sk-vision", "vision-test")) {
      AgentException failure =
          assertThrows(AgentException.class, () -> client.describe(png(), "image/png"));

      String message = failure.getMessage();
      assertTrue(message.contains("HTTP 200"), message);
      assertTrue(message.contains("reasoning"), message);
      assertTrue(message.contains("1500 of them spent thinking"), message);
      assertTrue(message.contains("maxTokens"), "it must name the setting that fixes it: " + message);
      assertTrue(message.contains("--vision-max-tokens"), message);
      assertTrue(message.contains("8192"), "and the budget it just used: " + message);
    }
  }

  @Test
  void aReplyThatFinishedWithNothingInContentIsNotBlamedOnTheBudget() throws Exception {
    // The other shape: it stopped on its own terms with the answer inside its reasoning. Raising the
    // budget would change nothing here, and a message that said to raise it would send the reader to
    // the wrong place — the fix is another model or endpoint.
    String reply =
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\","
            + "\"reasoning\":\"the picture shows a red square\"},"
            + "\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"completion_tokens\":12}}";
    try (FakeServer server = FakeServer.start(FakeServer.Reply.json(200, reply));
        VisionClient client = new VisionClient(server.url(), "sk-vision", "vision-test")) {
      AgentException failure =
          assertThrows(AgentException.class, () -> client.describe(png(), "image/png"));

      String message = failure.getMessage();
      assertTrue(message.contains("not the budget"), message);
      assertTrue(message.contains("another endpoint or model"), message);
      assertFalse(message.contains("maxTokens"), "the budget is not the problem here: " + message);
    }
  }

  @Test
  void aBodyThatIsNotTheExpectedJsonIsReportedWithWhatArrived() throws Exception {
    try (FakeServer server =
            FakeServer.start(
                FakeServer.Reply.json(200, "<html><body>gateway splash</body></html>"));
        VisionClient client = new VisionClient(server.url(), "sk-vision", "vision-test")) {
      AgentException failure =
          assertThrows(AgentException.class, () -> client.describe(png(), "image/png"));

      assertTrue(failure.getMessage().contains("gateway splash"), failure.getMessage());
    }
  }

  @Test
  void aConfiguredBudgetIsWhatTheEndpointIsAsked() throws Exception {
    // 1500 was this repository's first answer and it is too small for a real screenshot: the model
    // spent all of it thinking. The number is the configured one, not a constant.
    try (FakeServer server = FakeServer.start(FakeServer.Reply.json(200, DESCRIPTION));
        VisionClient client = new VisionClient(server.url(), "sk-vision", "vision-test", 4096)) {
      assertEquals("a red square", client.describe(png(), "image/png"));
      assertEquals(4096, Json.parse(server.body(0)).get("max_tokens").asInt());
    }
  }

  @Test
  void theConfiguredBudgetTravelsFromTheVisionBlock() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.json(200, DESCRIPTION));
        VisionClient client =
            VisionClient.from(
                new VisionConfig(server.url(), "sk-vision", null, "vision-test", 16384), Map.of())) {
      client.describe(png(), "image/png");
      assertEquals(16384, Json.parse(server.body(0)).get("max_tokens").asInt());
    }
  }

  @Test
  void fromReadsTheKeyFromTheVariableTheBlockNames() throws Exception {
    try (FakeServer server = FakeServer.start(FakeServer.Reply.json(200, DESCRIPTION));
        VisionClient client =
            VisionClient.from(
                new VisionConfig(server.url(), null, "MY_VISION_KEY", "vision-test"),
                Map.of("MY_VISION_KEY", "sk-env"))) {
      assertEquals("a red square", client.describe(png(), "image/png"));
      assertEquals("Bearer sk-env", server.header(0, "authorization"));
    }
  }

  @Test
  void fromRefusesAnUnconfiguredBlockAndAMissingKey() {
    IllegalArgumentException unconfigured =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                VisionClient.from(
                    new VisionConfig(null, "sk-vision", null, "vision-test"), Map.of()));
    assertTrue(unconfigured.getMessage().contains("--vision-base-url"), unconfigured.getMessage());

    IllegalArgumentException noKey =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                VisionClient.from(
                    new VisionConfig("http://127.0.0.1:1", null, null, "vision-test"), Map.of()));
    assertTrue(noKey.getMessage().contains("CCJ_VISION_API_KEY"), noKey.getMessage());
    assertTrue(noKey.getMessage().contains("--vision-api-key"), noKey.getMessage());

    IllegalArgumentException named =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                VisionClient.from(
                    new VisionConfig("http://127.0.0.1:1", null, "MY_VISION_KEY", "vision-test"),
                    Map.of("MY_VISION_KEY", "  ")));
    assertTrue(named.getMessage().contains("MY_VISION_KEY"), named.getMessage());
  }

  /** A real PNG, encoded by the JDK, so the bytes that go out are the bytes a phone would send. */
  private static byte[] png() throws Exception {
    BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, 0xFF0000);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, "png", out), "the JDK must be able to write a PNG");
    return out.toByteArray();
  }
}
