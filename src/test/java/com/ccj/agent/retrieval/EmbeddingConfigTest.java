package com.ccj.agent.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 嵌入端点的配置：缺席是一种状态，写坏了是一种错误，而两者不能混为一谈。
 *
 * <p>「没配」与「配错了」的区别在这里是承重的：前者意味着这个工具根本不在工具表里（模型看不到一个它用不了
 * 的东西），后者意味着一句看得懂的报错。把后者当成前者，会让语义检索在一次手误之后安静地消失。
 */
class EmbeddingConfigTest {

  @TempDir Path tmp;

  @Test
  void noFileMeansNoSemanticRetrievalRatherThanABrokenOne() {
    assertEquals(Optional.empty(), EmbeddingConfig.open(tmp.resolve("embeddings.json")));
  }

  @Test
  void aFileWithAnEndpointAndAModelIsRead() throws IOException {
    Path file = tmp.resolve("embeddings.json");
    Files.writeString(
        file,
        """
        {"baseUrl": "http://127.0.0.1:1234/v1/", "model": "nomic-embed-text",
         "apiKeyEnv": "LOCAL_KEY", "chunkLines": 60, "overlapLines": 10}
        """);

    EmbeddingConfig config = EmbeddingConfig.open(file).orElseThrow();

    assertEquals("nomic-embed-text", config.model());
    assertEquals("http://127.0.0.1:1234/v1/embeddings", config.endpoint(), "末尾的斜杠不重复");
    assertEquals(60, config.chunkLines());
    assertEquals(10, config.overlapLines());
    assertEquals("secret", config.key(Map.of("LOCAL_KEY", "secret")));
    assertTrue(config.describe().contains("nomic-embed-text"), config.describe());
  }

  @Test
  void aKeyIsNeverReadFromNowhere() throws IOException {
    Path file = tmp.resolve("embeddings.json");
    Files.writeString(file, "{\"baseUrl\": \"http://x/v1\", \"model\": \"m\"}");
    EmbeddingConfig config = EmbeddingConfig.open(file).orElseThrow();

    assertNull(config.key(Map.of()));
    assertNull(config.key(null));
    assertEquals("literal", new EmbeddingConfig("http://x/v1", "m", "literal", "OTHER", 10, 2)
        .key(Map.of("OTHER", "from-env")), "字面值优先于环境变量，与提供方的设置同一条规矩");
  }

  @Test
  void aMissingEndpointOrModelIsAnErrorThatNamesTheFile() throws IOException {
    Path file = tmp.resolve("embeddings.json");
    Files.writeString(file, "{\"model\": \"m\"}");
    IllegalArgumentException noEndpoint =
        assertThrows(IllegalArgumentException.class, () -> EmbeddingConfig.open(file));
    assertTrue(noEndpoint.getMessage().contains("baseUrl"), noEndpoint.getMessage());
    assertTrue(noEndpoint.getMessage().contains("embeddings.json"), noEndpoint.getMessage());

    Files.writeString(file, "{\"baseUrl\": \"http://x/v1\"}");
    IllegalArgumentException noModel =
        assertThrows(IllegalArgumentException.class, () -> EmbeddingConfig.open(file));
    assertTrue(noModel.getMessage().contains("model"), noModel.getMessage());

    Files.writeString(file, "not json");
    assertThrows(IllegalArgumentException.class, () -> EmbeddingConfig.open(file));
  }

  @Test
  void anOverlapAtLeastAsBigAsTheChunkIsCutDownToAQuarter() {
    // 重叠等于或超过块本身，会让每一块都包含上一块的全部：索引于是变成同一段文字的一堆拷贝。
    EmbeddingConfig clipped = new EmbeddingConfig("http://x/v1", "m", null, null, 40, 40);
    assertEquals(10, clipped.overlapLines());

    EmbeddingConfig negative = new EmbeddingConfig("http://x/v1", "m", null, null, 40, -5);
    assertEquals(EmbeddingConfig.DEFAULT_OVERLAP_LINES, negative.overlapLines());

    EmbeddingConfig defaults = new EmbeddingConfig("http://x/v1", "m", null, null, 0, 0);
    assertEquals(EmbeddingConfig.DEFAULT_CHUNK_LINES, defaults.chunkLines());
  }
}
