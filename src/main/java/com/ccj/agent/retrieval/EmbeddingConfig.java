package com.ccj.agent.retrieval;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * 一个嵌入端点：把一段文字变成一串数字。
 *
 * <p>它住在应用主目录下的 {@code embeddings.json} 里，而不是 {@code config.json} 里——理由与
 * {@code mcp.json} 完全相同：设置表单管的是「哪个模型在回答」，而这一份说的是「有没有一个把文字变成向量的
 * 端点」，它可以在没有的时候整个缺席。缺席就是没有语义检索，而不是一个需要填报的空栏。
 *
 * <pre>
 * {"baseUrl": "http://127.0.0.1:1234/v1", "model": "nomic-embed-text",
 *  "apiKeyEnv": "OPENAI_API_KEY", "chunkLines": 40, "overlapLines": 8}
 * </pre>
 *
 * <p>{@code chunkLines} 与 {@code overlapLines} 是检索质量的两个旋钮，而它们也在这里，因为它们是<em>这个
 * 语料</em>的性质而不是这个程序的：四十行的窗口对一份代码库是个合理的块，对一个满是长文档的仓库就太小了。
 */
public record EmbeddingConfig(
    String baseUrl, String model, String apiKey, String apiKeyEnv, int chunkLines, int overlapLines) {

  /** 一个块有多少行；也是这个文件缺席时唯一的区别所在。 */
  public static final int DEFAULT_CHUNK_LINES = 40;

  /** 相邻两块重叠多少行：一句话跨在边界上时，它至少还完整地出现在其中一个块里。 */
  public static final int DEFAULT_OVERLAP_LINES = 8;

  /** 一次请求最多送多少个块：端点对批量大小的容忍度各家不同，而这一档是所有端点都吃得下的。 */
  public static final int BATCH = 32;

  public EmbeddingConfig {
    baseUrl = strip(baseUrl);
    model = strip(model);
    apiKey = strip(apiKey);
    apiKeyEnv = strip(apiKeyEnv);
    chunkLines = chunkLines <= 0 ? DEFAULT_CHUNK_LINES : chunkLines;
    overlapLines = overlapLines < 0 ? DEFAULT_OVERLAP_LINES : overlapLines;
    if (overlapLines >= chunkLines) {
      // 重叠不能等于或超过块本身：那样每一块都包含上一块的全部，而索引会变成同一段文字的许多份拷贝。
      overlapLines = Math.max(0, chunkLines / 4);
    }
  }

  /**
   * 这份文件里写着的配置；文件不在就是「没有配置语义检索」。
   *
   * <p>一个读不出来的文件按错误报，而不是按缺席报：把一份写坏的配置当成「没配」，会让检索在没有任何解释的
   * 情况下从工具表里消失——而那正是这个仓库最讨厌的那种静默失败。
   */
  public static Optional<EmbeddingConfig> open(Path file) {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    JsonNode node;
    try {
      node = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("无法读取嵌入配置 " + file, e);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(file + " 不是一份有效的嵌入配置：" + e.getMessage(), e);
    }
    EmbeddingConfig config =
        new EmbeddingConfig(
            node.path("baseUrl").asText(null),
            node.path("model").asText(null),
            node.path("apiKey").asText(null),
            node.path("apiKeyEnv").asText(null),
            node.path("chunkLines").asInt(DEFAULT_CHUNK_LINES),
            node.path("overlapLines").asInt(DEFAULT_OVERLAP_LINES));
    if (config.baseUrl() == null) {
      throw new IllegalArgumentException(file + " 里缺少 baseUrl：它必须指向一个讲 /embeddings 的端点");
    }
    if (config.model() == null) {
      throw new IllegalArgumentException(file + " 里缺少 model：端点上的哪个模型用来算向量");
    }
    return Optional.of(config);
  }

  /** 到端点的完整地址，末端的斜杠不算数。 */
  public String endpoint() {
    String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    return base + "/embeddings";
  }

  /**
   * 密钥：先看字面值，再看环境变量。
   *
   * <p>与环境变量版本的提供方设置同一条规矩——只回传「有没有」，而调用方绝不把它打印出来。
   */
  public String key(Map<String, String> env) {
    if (apiKey != null && !apiKey.isBlank()) {
      return apiKey.strip();
    }
    if (apiKeyEnv != null && !apiKeyEnv.isBlank() && env != null) {
      String fromEnv = env.get(apiKeyEnv);
      return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.strip();
    }
    return null;
  }

  /** 一行给人看的说明，不含密钥。 */
  public String describe() {
    return model + "（" + endpoint() + "，每块 " + chunkLines + " 行，重叠 " + overlapLines + "）";
  }

  private static String strip(String value) {
    if (value == null) {
      return null;
    }
    String stripped = value.strip();
    return stripped.isEmpty() ? null : stripped;
  }
}
