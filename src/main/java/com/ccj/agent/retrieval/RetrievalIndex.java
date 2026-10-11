package com.ccj.agent.retrieval;

import com.ccj.agent.core.Json;
import com.ccj.agent.provider.EmbeddingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 一个工作目录的语义索引：把文件切成块、算出向量、存下来，然后按意思找回来。
 *
 * <p>它分成三件事，而不是一件，因为中间那件会<em>花钱、并且把文字送出本机</em>：
 *
 * <ol>
 *   <li>{@link #plan} 只读磁盘与已有的索引：它数得出「这次要送出去多少个块」；
 *   <li>审批发生在这两步之间——调用方拿这个数字去问人，而那个人看到的正是「有多少文字要离开这台机器」；
 *   <li>{@link #build} 真的调用嵌入端点，并把新增的向量补进索引。
 * </ol>
 *
 * <p>所以一次「什么都没有改」的检索不会有第三步：缓存命中的那些块不必再算一遍，于是也没有任何东西离开这台
 * 机器，于是也就不必问任何人。审批是关于出口的，而不是关于这个工具的。
 *
 * <p>索引放在应用主目录下的 {@code index/<工作目录的散列>/}，与仓库分开：它是缓存，而缓存不该出现在
 * {@code git status} 里。索引带着它是由哪个端点、哪个模型建的——换一个模型，旧的向量与新的不在同一个空间
 * 里，而两个空间的点积不会报错，只会给出没有意义的相似度。所以换模型就是整份重建。
 */
public final class RetrievalIndex {

  /** 一次构建最多索引多少个块：不是技术上限，是一张账单的上限。 */
  public static final int MAX_CHUNKS = 20_000;

  /** 元数据文件：这份索引是谁建的。 */
  private static final String META_FILE = "meta.json";

  /** 一次索引构建要花多少。 */
  public record Plan(List<Chunker.Chunk> pending, int reused, int chunks) {

    /** 这次构建会把文字送出去吗——即「要不要问人」的那个问题。 */
    public boolean sendsAnything() {
      return !pending.isEmpty();
    }
  }

  /** 一次构建实际做了什么。 */
  public record Built(int chunks, int embedded, int reused) {}

  private final Path directory;
  private final EmbeddingConfig config;
  private final EmbeddingClient client;
  private final VectorIndex index;
  private List<Chunker.Chunk> pending = List.of();

  private RetrievalIndex(
      Path directory, EmbeddingConfig config, EmbeddingClient client, VectorIndex index) {
    this.directory = directory;
    this.config = config;
    this.client = client;
    this.index = index;
  }

  /**
   * 打开（或准备好）{@code workspace} 的索引。
   *
   * <p>已有的索引只有在它是由同一个端点、同一个模型、同一种切块方式建的时候才被采用；否则按一份空索引开始，
   * 并在下一次构建时被覆盖。
   */
  public static RetrievalIndex open(
      Path workspace, Path home, EmbeddingConfig config, Map<String, String> env) {
    Path directory = home.resolve("index").resolve(key(workspace));
    VectorIndex index = VectorIndex.load(directory);
    if (index.size() > 0 && !sameBuilder(directory, config)) {
      index = new VectorIndex();
    }
    return new RetrievalIndex(directory, config, EmbeddingClient.forConfig(config, env), index);
  }

  public Path directory() {
    return directory;
  }

  public EmbeddingConfig config() {
    return config;
  }

  public VectorIndex index() {
    return index;
  }

  /** 这次要送出去多少、已经有多少——只读磁盘，不联网。 */
  public Plan plan(List<Source> sources) {
    List<Chunker.Chunk> chunks =
        Chunker.chunks(sources, config.chunkLines(), config.overlapLines(), MAX_CHUNKS);
    List<Chunker.Chunk> missing = new ArrayList<>();
    for (Chunker.Chunk chunk : chunks) {
      if (index.vectorFor(chunk.fingerprint()) == null) {
        missing.add(chunk);
      }
    }
    this.pending = List.copyOf(missing);
    return new Plan(this.pending, chunks.size() - missing.size(), chunks.size());
  }

  /**
   * 算那些还没有向量的块，并把索引写回磁盘。
   *
   * <p>每一批之后都写一次：一次跑了几分钟、花了几毛钱的构建，不该因为最后一句话失败而把前面的全部丢掉。元
   * 数据在任何一批之前先写，于是「这份索引是哪个模型建的」这个问题在构建一半被 Ctrl-C 之后也有答案。
   */
  public Built build(Plan plan) throws Exception {
    writeMeta(directory, config);
    int embedded = 0;
    List<Chunker.Chunk> queue = pending;
    for (int start = 0; start < queue.size(); start += EmbeddingConfig.BATCH) {
      List<Chunker.Chunk> batch =
          queue.subList(start, Math.min(queue.size(), start + EmbeddingConfig.BATCH));
      List<String> texts = new ArrayList<>(batch.size());
      for (Chunker.Chunk chunk : batch) {
        texts.add(chunk.embedText());
      }
      List<float[]> vectors = client.embed(texts);
      for (int i = 0; i < batch.size(); i++) {
        index.add(batch.get(i), vectors.get(i));
      }
      embedded += batch.size();
      index.save(directory);
    }
    if (queue.isEmpty()) {
      index.save(directory);
    }
    pending = List.of();
    return new Built(plan.chunks(), embedded, plan.reused());
  }

  /** 按意思找前 {@code k} 个块。它需要一次嵌入调用——一次，因为它只有一句查询。 */
  public List<VectorIndex.Hit> search(String query, int k) throws Exception {
    List<float[]> vectors = client.embed(List.of(query));
    return index.search(vectors.get(0), k);
  }

  /** 索引目录：{@code <home>/index/<工作目录的散列>}。 */
  static String key(Path workspace) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash =
          digest.digest(
              workspace.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("这个 JRE 没有 SHA-256", e);
    }
  }

  /** 磁盘上那份索引是不是这个端点、这个模型、这种切块方式建的。 */
  private static boolean sameBuilder(Path directory, EmbeddingConfig config) {
    Path meta = directory.resolve(META_FILE);
    if (!Files.isRegularFile(meta)) {
      return false;
    }
    try {
      JsonNode node = Json.parse(Files.readString(meta, StandardCharsets.UTF_8));
      return node.path("endpoint").asText("").equals(config.endpoint())
          && node.path("model").asText("").equals(config.model())
          && node.path("chunkLines").asInt(0) == config.chunkLines()
          && node.path("overlapLines").asInt(0) == config.overlapLines();
    } catch (IOException | IllegalArgumentException e) {
      return false;
    }
  }

  private static void writeMeta(Path directory, EmbeddingConfig config) {
    ObjectNode node = Json.object();
    node.put("endpoint", config.endpoint());
    node.put("model", config.model());
    node.put("chunkLines", config.chunkLines());
    node.put("overlapLines", config.overlapLines());
    try {
      Files.createDirectories(directory);
      Files.writeString(directory.resolve(META_FILE), Json.writePretty(node) + "\n", StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("无法写入索引元数据 " + directory, e);
    }
  }

  /** 关掉它底下的 HTTP 客户端。 */
  public void close() {
    client.close();
  }
}
