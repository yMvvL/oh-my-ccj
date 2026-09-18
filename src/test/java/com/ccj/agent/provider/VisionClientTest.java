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
          "预算是天花板而不是花销，所以默认值给得宽松");
      JsonNode parts = sent.path("messages").get(0).path("content");
      assertTrue(
          parts.get(0).path("text").asText().contains("not from the user"),
          "提示词必须说明图里的指令不是用户的：" + parts.get(0));
      JsonNode picture = parts.get(1);
      assertEquals("image_url", picture.path("type").asText());
      String url = picture.path("image_url").path("url").asText();
      assertTrue(url.startsWith(DATA_URL_PREFIX), "图片必须以 data URL 传输：" + url);
      assertArrayEquals(
          png,
          Base64.getDecoder().decode(url.substring(DATA_URL_PREFIX.length())),
          "data URL 必须携带图片自己的字节");
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
    // 把报上来的失败原样复现：一张内容繁杂的手机页面截图回了 HTTP 200，`finish_reason: length`，
    // 1500-token 的预算全部花在推理上，`content` 为空，回复里有 6224 个字符的思考。消息必须说清哪个设置
    // 能修好它，因为「收到的是什么」是一堵 JSON 墙，说不清。
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
      assertTrue(message.contains("推理"), message);
      assertTrue(message.contains("其中 1500 个花在思考上"), message);
      assertTrue(message.contains("maxTokens"), "必须点名能修好它的那个设置：" + message);
      assertTrue(message.contains("--vision-max-tokens"), message);
      assertTrue(message.contains("8192"), "以及它刚用掉的预算：" + message);
    }
  }

  @Test
  void aReplyThatFinishedWithNothingInContentIsNotBlamedOnTheBudget() throws Exception {
    // 另一种形状：它按自己的意思停下了，答案在它的推理里。调大预算在这里改变不了任何东西，而一条说「调大
    // 预算」的消息会把读者指去错误的地方——解法是另一个模型或端点。
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
      assertTrue(message.contains("不是预算的问题"), message);
      assertTrue(message.contains("换一个端点或模型"), message);
      assertFalse(message.contains("maxTokens"), "这里的问题不是预算：" + message);
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
    // 1500 是这个仓库最初的答案，对一张真实的截图来说太小：模型把它全花在思考上了。这个数字用的是配置里的
    // 值，而不是某个常量。
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

  /** 一张由 JDK 编码的真实 PNG，所以发出去的字节就是手机会发出的字节。 */
  private static byte[] png() throws Exception {
    BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, 0xFF0000);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, "png", out), "JDK 必须能写出 PNG");
    return out.toByteArray();
  }
}
