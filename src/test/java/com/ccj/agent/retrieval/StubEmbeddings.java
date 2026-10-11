package com.ccj.agent.retrieval;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个讲 {@code /embeddings} 的替身端点，不联网、不要密钥、每次都给同样的答案。
 *
 * <p>它的向量是「词袋散列」：每个词散进 64 个格子之一，出现就加一，最后归一化。它当然不是一个真的嵌入模型
 * ——它不懂同义、不懂语序——但它<em>是确定性的</em>，而这一点正是这些用例需要的：一个检索质量的数字，如果
 * 每次跑出来都不一样，就不是一个数字。真正的模型会换掉它算出来的向量，不会换掉围绕它的那套东西（切块、
 * 指纹、缓存、审批、排序），而那些才是这里要测的。
 *
 * <p>它同时数着两件事：收到过几次请求、收到过多少段文字。于是「审批被拒绝时什么都没有离开这台机器」是一条
 * 可以被证伪的主张，而不是一句注释。
 */
public final class StubEmbeddings implements AutoCloseable {

  /**
   * 向量的维度。
   *
   * <p>256 而不是 64：散列的格子越少，两个无关的词撞进同一格的机会越大，而那种碰撞是**假相似**——它会让
   * 一份语料里的每一块都像每一块。64 格配上几十个块时，这一点实测到了一个难看的召回率（recall@1=0.10），
   * 而那说明的是替身太弱，不是流水线有问题。见 {@code recallAtKIsMeasuredAgainstPlantedFacts} 打印的数字。
   */
  public static final int DIMENSIONS = 256;

  private final HttpServer server;
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicInteger texts = new AtomicInteger();

  private StubEmbeddings(HttpServer server) {
    this.server = server;
  }

  public static StubEmbeddings start() throws IOException {
    StubEmbeddings[] holder = new StubEmbeddings[1];
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    StubEmbeddings stub = new StubEmbeddings(server);
    holder[0] = stub;
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          stub.requests.incrementAndGet();
          JsonNode posted = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          ArrayNode input = (ArrayNode) posted.path("input");
          ObjectNode body = Json.object();
          ArrayNode data = body.putArray("data");
          for (int i = 0; i < input.size(); i++) {
            stub.texts.incrementAndGet();
            ObjectNode entry = data.addObject();
            entry.put("index", i);
            ArrayNode vector = entry.putArray("embedding");
            for (float value : vectorOf(input.get(i).asText())) {
              vector.add(value);
            }
          }
          byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
    server.start();
    return holder[0];
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  public EmbeddingConfig config(int chunkLines, int overlapLines) {
    return new EmbeddingConfig(baseUrl(), "stub-embed", null, null, chunkLines, overlapLines);
  }

  /** 收到过几次嵌入请求。 */
  public int requests() {
    return requests.get();
  }

  /** 一共算过多少段文字的向量。 */
  public int texts() {
    return texts.get();
  }

  /**
   * 一段文字的向量：散列词袋，归一化。
   *
   * <p>同一个词永远落进同一个格子，所以共享词的两段文字真的更近——这不是一个假的相似度，它只是不理解同义。
   */
  public static float[] vectorOf(String text) {
    float[] vector = new float[DIMENSIONS];
    String[] words = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+");
    List<String> kept = new ArrayList<>();
    for (String word : words) {
      if (word.isBlank() || STOP_WORDS.contains(word)) {
        continue;
      }
      add(vector, word, 1);
      kept.add(word);
    }
    // 双词也各算一格，权重略低：一个真模型理解的是词的搭配，而单看单词的散列分不出
    // 「rate limited」与「limited rate」的区别——更分不出它和一句碰巧用了这两个词的话。
    for (int i = 0; i + 1 < kept.size(); i++) {
      add(vector, kept.get(i) + " " + kept.get(i + 1), 0.7);
    }
    double sum = 0;
    for (float value : vector) {
      sum += (double) value * value;
    }
    if (sum > 0) {
      float length = (float) Math.sqrt(sum);
      for (int i = 0; i < vector.length; i++) {
        vector[i] /= length;
      }
    }
    return vector;
  }

  private static void add(float[] vector, String token, double weight) {
    vector[Math.floorMod(token.hashCode(), DIMENSIONS)] += weight;
  }

  /**
   * 一句话里那些不承载意思的词。
   *
   * <p>不去掉它们，每一段英文里最多的词都会变成最重的那几维，于是所有块彼此都很像——这正是第一版替身量出
   * 来的那个难看的召回率（recall@1=0.10）的来源，而它量的是替身有多粗糙，不是流水线有多好。
   */
  private static final java.util.Set<String> STOP_WORDS =
      java.util.Set.of("a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "do", "does",
          "for", "from", "has", "have", "how", "in", "is", "it", "its", "of", "on", "or", "so",
          "that", "the", "their", "them", "then", "there", "these", "they", "this", "to", "was",
          "what", "when", "where", "which", "who", "why", "will", "with", "without", "one", "two",
          "every", "into", "not", "no", "only", "out", "up", "we", "you", "your");

  @Override
  public void close() {
    server.stop(0);
  }
}
