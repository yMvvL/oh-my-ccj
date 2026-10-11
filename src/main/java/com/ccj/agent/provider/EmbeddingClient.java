package com.ccj.agent.provider;

import com.ccj.agent.core.Json;
import com.ccj.agent.retrieval.EmbeddingConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

/**
 * 一个嵌入端点：一次请求拿回一批向量。
 *
 * <p>它只讲一种协议——OpenAI 形状的 {@code POST /embeddings}，请求体是
 * {@code {"model": …, "input": […]}}，响应体是 {@code {"data": [{"index": …, "embedding": […]}]}}——
 * 因为这是这一行里事实上通用的那一种：OpenAI、Azure、OpenRouter、Ollama 的 {@code /v1}、vLLM、
 * llama.cpp、LM Studio 都讲它，所以「用本机的一个小模型算向量」不需要任何新的适配器。
 *
 * <p>它用的是与 {@link Transport} 同一条管道，所以重试、`Retry-After`、以及「HTTP 200 但不是我们要的东西」
 * 那种诊断，与对话模型那边一字不差。这不是巧合：一个建立索引的循环没有人在按钮后面盯着，它比一次视觉调用
 * 更需要那条重试曲线。
 */
public final class EmbeddingClient implements AutoCloseable {

  /** 一次请求最多送多少个块：由调用方分批，这里的上限是「别让一个请求变成一次内存事件」。 */
  private static final int MAX_BATCH = 256;

  /** 响应体读到这里就够了；一个向量列表不会更大。 */
  private static final int BODY_LIMIT_BYTES = 64 * 1024 * 1024;

  private final EmbeddingConfig config;
  private final String key;
  private final HttpClient http;
  private final ExecutorService executor;

  private EmbeddingClient(EmbeddingConfig config, String key) {
    this.config = config;
    this.key = key;
    this.executor = Transport.newExecutor("ccj-embeddings");
    this.http = Transport.newClient(executor);
  }

  public static EmbeddingClient forConfig(EmbeddingConfig config, Map<String, String> env) {
    return new EmbeddingClient(config, config.key(env));
  }

  /**
   * 一批文字的向量，按输入的顺序。
   *
   * <p>按 {@code index} 归位，而不是按数组里出现的顺序：规范说它是下标，而一个不按顺序作答的端点如果被
   * 当成按顺序作答，会安静地把每一段文字配上另一段的向量——那种错误在检索结果里看起来只是「不太准」，没有
   * 任何东西会失败。
   */
  public List<float[]> embed(List<String> texts) throws Exception {
    if (texts.isEmpty()) {
      return List.of();
    }
    if (texts.size() > MAX_BATCH) {
      throw new IllegalArgumentException(
          "一次最多 " + MAX_BATCH + " 段文字，实际给了 " + texts.size() + "：请分批");
    }
    ObjectNode body = Json.object();
    body.put("model", config.model());
    ArrayNode input = body.putArray("input");
    texts.forEach(input::add);

    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(config.endpoint()))
            .timeout(Transport.REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
    if (key != null) {
      request.header("Authorization", "Bearer " + key);
    }

    // 事件流那一侧的回调在这里没有对应物（这不是一个流），所以重试通知去一个不做任何事的地方：没有人在看
    // 一次索引构建的进度条，而把「正在重试」写进会话，只会在转录里留下一行没人要的噪音。
    HttpResponse<Stream<String>> response =
        Transport.send(http, request.build(), event -> {});
    String text = read(response.body());
    return parse(text, texts.size());
  }

  private List<float[]> parse(String body, int expected) {
    JsonNode root;
    try {
      root = Json.parse(body);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "嵌入端点 " + config.endpoint() + " 返回的不是 JSON：" + excerpt(body), e);
    }
    JsonNode data = root.path("data");
    if (!data.isArray()) {
      throw new IllegalArgumentException(
          "嵌入端点的应答里没有 data 数组（模型 '" + config.model() + "' 存在吗？）：" + excerpt(body));
    }
    float[][] vectors = new float[expected][];
    int seen = 0;
    for (JsonNode entry : data) {
      int index = entry.path("index").asInt(seen);
      if (index < 0 || index >= expected) {
        throw new IllegalArgumentException(
            "嵌入端点给了一个越界的 index " + index + "（这一批有 " + expected + " 段文字）");
      }
      JsonNode embedding = entry.path("embedding");
      if (!embedding.isArray() || embedding.isEmpty()) {
        throw new IllegalArgumentException("嵌入端点的第 " + index + " 条没有 embedding 数组");
      }
      float[] vector = new float[embedding.size()];
      for (int i = 0; i < vector.length; i++) {
        vector[i] = (float) embedding.get(i).asDouble();
      }
      vectors[index] = vector;
      seen++;
    }
    if (seen != expected) {
      // 少给的那几段不能当成零向量：一个零向量与任何东西的相似度都是 0，于是它会安静地排到最后，而调用方
      // 会以为「这几段不相关」——那是一次测量与一次缺失分不开的例子。
      throw new IllegalArgumentException(
          "嵌入端点返回了 " + seen + " 条向量，但这一批有 " + expected + " 段文字");
    }
    return List.of(vectors);
  }

  /** 读整个响应体，但有一个上限，并且无论如何都关掉流。 */
  private static String read(Stream<String> lines) throws IOException {
    StringBuilder kept = new StringBuilder();
    try (lines) {
      for (String line : (Iterable<String>) lines::iterator) {
        if (kept.length() + line.length() > BODY_LIMIT_BYTES) {
          throw new IOException("嵌入端点的应答超过 " + BODY_LIMIT_BYTES + " 字节：这不像是一批向量");
        }
        kept.append(line);
      }
    }
    return kept.toString();
  }

  private static String excerpt(String body) {
    String flat = body == null ? "" : body.strip().replace('\n', ' ');
    return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
