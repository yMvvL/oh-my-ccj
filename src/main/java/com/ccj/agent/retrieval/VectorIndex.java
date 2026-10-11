package com.ccj.agent.retrieval;

import com.ccj.agent.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/**
 * 向量，以及它们各自属于哪一段文字；可以存到磁盘上，也可以按相似度取回前 k 个。
 *
 * <p>两个文件，放在一个目录里：{@code chunks.jsonl} 是一行一个块的元数据（路径、行号、指纹、维度），
 * {@code vectors.bin} 是按同一顺序紧排的 32 位浮点数。分开存而不是塞进一个 JSON 里，是因为向量占的地方比
 * 元数据大得多，而一个 base64 过的 JSON 数组比二进制大三分之一、解析起来慢一个数量级——这个文件会被<em>反复</em>
 * 全部读进来，每一次搜索都要用它。
 *
 * <p>向量在写进去之前就被归一化到单位长度了，所以相似度就是点积。这不只是省掉一次除法：它让每个数都落在
 * −1 到 1 之间（同一个模型产出的向量之间），于是「这个结果到底像不像」有一个绝对的说法，而不是只在一次
 * 排序里有意义。
 *
 * <p>搜索是<strong>暴力</strong>的：每个向量算一次点积，取前 k 个。这是一个决定，而它的依据是量出来的规模
 * ——几千个块、每次搜索几十万个浮点乘加，在本机上是毫秒级——见 docs/RETRIEVAL.md。近似索引（HNSW 那一类）
 * 要用一次「可能漏掉真正最近的那个」换一次快，而在几千个块的规模上，那次快换来的是七十微秒，漏掉的那个
 * 换来的是一句「检索不准」。等语料涨到几十万块时，这里会先变成一个能被测量的瓶颈，再被换掉。
 */
public final class VectorIndex {

  /**
   * 一个块，以及它的向量。
   *
   * <p>正文也被留下来，尽管它在磁盘上占的地方与向量相当：一次检索的答案必须能自己站住。只存路径与行号的
   * 索引，会让每一段结果都要回去把那个文件重读一遍——而那些行在两次检索之间可能已经变了，于是「你找到的
   * 那一段」与「它给你看的那一段」不再是同一段。
   */
  public record Entry(
      String path, int firstLine, int lastLine, String fingerprint, String text, float[] vector) {

    /** 出处的一行写法：{@code path:first-last}。检索结果的每一行都用它。 */
    public String label() {
      return path + ":" + firstLine + "-" + lastLine;
    }
  }

  /** 一次命中的结果。 */
  public record Hit(Entry entry, double score) {}

  /** 元数据文件与向量文件的名字，写在同一个目录下。 */
  public static final String CHUNKS_FILE = "chunks.jsonl";

  public static final String VECTORS_FILE = "vectors.bin";

  private final List<Entry> entries = new ArrayList<>();
  private final Map<String, Integer> byFingerprint = new LinkedHashMap<>();

  /** 磁盘上那份索引里的全部块，按它们被写下来的顺序。 */
  public List<Entry> entries() {
    return List.copyOf(entries);
  }

  public int size() {
    return entries.size();
  }

  /** 已经为哪些指纹算过向量——增量更新的全部依据。 */
  public Set<String> fingerprints() {
    return Set.copyOf(byFingerprint.keySet());
  }

  /** 这个指纹的向量，没有则为 null。 */
  public float[] vectorFor(String fingerprint) {
    Integer at = byFingerprint.get(fingerprint);
    return at == null ? null : entries.get(at).vector();
  }

  public void add(Chunker.Chunk chunk, float[] vector) {
    if (byFingerprint.containsKey(chunk.fingerprint())) {
      return;
    }
    if (vector == null || vector.length == 0) {
      throw new IllegalArgumentException("一个块不能配一个空的向量：" + chunk.label());
    }
    byFingerprint.put(chunk.fingerprint(), entries.size());
    entries.add(
        new Entry(
            chunk.path(),
            chunk.firstLine(),
            chunk.lastLine(),
            chunk.fingerprint(),
            chunk.text(),
            unit(vector)));
  }

  /** 前 {@code k} 个最像 {@code query} 的块，相似的在前。 */
  public List<Hit> search(float[] query, int k) {
    if (query == null || query.length == 0 || entries.isEmpty() || k <= 0) {
      return List.of();
    }
    float[] unit = unit(query);
    for (Entry entry : entries) {
      if (entry.vector().length != unit.length) {
        // 维度对不上，说明这个索引是另一个模型建的。宁可整体拒绝，也不要把两个空间的向量相乘：那个数不会
        // 报错，它只会是一个没有意义的相似度。
        throw new IllegalStateException(
            "索引里的向量有 " + entry.vector().length + " 维，而查询是 " + unit.length + " 维——这份索引是"
                + "另一个模型建的，删掉它重建即可");
      }
    }
    // 一个只有 k 个位置的小顶堆：取前 k 个不需要把全部结果排序，而这个文件可能有几万行。
    PriorityQueue<Hit> best = new PriorityQueue<>(Comparator.comparingDouble(Hit::score));
    for (Entry entry : entries) {
      double score = dot(unit, entry.vector());
      if (best.size() < k) {
        best.add(new Hit(entry, score));
      } else if (score > best.peek().score()) {
        best.poll();
        best.add(new Hit(entry, score));
      }
    }
    List<Hit> hits = new ArrayList<>(best);
    hits.sort(Comparator.comparingDouble(Hit::score).reversed());
    return List.copyOf(hits);
  }

  /** 把整份索引写到 {@code directory}；写一半的索引读起来会比自己短，所以经由临时文件改名到位。 */
  public void save(Path directory) {
    try {
      Files.createDirectories(directory);
      StringBuilder metadata = new StringBuilder();
      ByteBuffer buffer = ByteBuffer.allocate(totalBytes());
      buffer.order(ByteOrder.LITTLE_ENDIAN);
      for (Entry entry : entries) {
        ObjectNode node = Json.object();
        node.put("path", entry.path());
        node.put("firstLine", entry.firstLine());
        node.put("lastLine", entry.lastLine());
        node.put("fingerprint", entry.fingerprint());
        node.put("text", entry.text());
        node.put("dimensions", entry.vector().length);
        metadata.append(Json.write(node)).append('\n');
        for (float value : entry.vector()) {
          buffer.putFloat(value);
        }
      }
      writeAtomically(directory.resolve(CHUNKS_FILE), metadata.toString().getBytes(StandardCharsets.UTF_8));
      writeAtomically(directory.resolve(VECTORS_FILE), buffer.array());
    } catch (IOException e) {
      throw new UncheckedIOException("无法写入索引 " + directory, e);
    }
  }

  /**
   * 读回 {@code directory} 里的索引；没有或读不出来的，就是一份空索引。
   *
   * <p>一份坏掉的索引不该是一个错误：它是可以重建的缓存，而「删掉它再跑一次」正是用户会做的事。所以这里对
   * 不上就丢掉，而不是抛出去——一个每次启动都炸的缓存，比没有缓存更糟。
   */
  public static VectorIndex load(Path directory) {
    VectorIndex index = new VectorIndex();
    Path chunks = directory.resolve(CHUNKS_FILE);
    Path vectors = directory.resolve(VECTORS_FILE);
    if (!Files.isRegularFile(chunks) || !Files.isRegularFile(vectors)) {
      return index;
    }
    try {
      byte[] raw = Files.readAllBytes(vectors);
      ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      for (String line : Files.readAllLines(chunks, StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        JsonNode node = Json.parse(line);
        int dimensions = node.path("dimensions").asInt(0);
        if (dimensions <= 0 || buffer.remaining() < dimensions * Float.BYTES) {
          return new VectorIndex(); // 两半对不上：按没有索引处理
        }
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
          vector[i] = buffer.getFloat();
        }
        index.entries.add(
            new Entry(
                node.path("path").asText(""),
                node.path("firstLine").asInt(0),
                node.path("lastLine").asInt(0),
                node.path("fingerprint").asText(""),
                node.path("text").asText(""),
                vector));
        index.byFingerprint.put(node.path("fingerprint").asText(""), index.entries.size() - 1);
      }
      return index;
    } catch (IOException | IllegalArgumentException e) {
      return new VectorIndex();
    }
  }

  /** 这个目录里有没有一份索引。 */
  public static boolean exists(Path directory) {
    return Files.isRegularFile(directory.resolve(CHUNKS_FILE))
        && Files.isRegularFile(directory.resolve(VECTORS_FILE));
  }

  private int totalBytes() {
    int bytes = 0;
    for (Entry entry : entries) {
      bytes += entry.vector().length * Float.BYTES;
    }
    return bytes;
  }

  private static void writeAtomically(Path target, byte[] bytes) throws IOException {
    Path staged = target.resolveSibling(target.getFileName() + ".tmp");
    Files.write(
        staged,
        bytes,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
  }

  /** 一个向量除以它自己的长度；零向量原样返回。 */
  static float[] unit(float[] vector) {
    double sum = 0;
    for (float value : vector) {
      sum += (double) value * value;
    }
    float[] out = new float[vector.length];
    if (sum <= 0) {
      return out;
    }
    float length = (float) Math.sqrt(sum);
    for (int i = 0; i < vector.length; i++) {
      out[i] = vector[i] / length;
    }
    return out;
  }

  private static double dot(float[] a, float[] b) {
    double sum = 0;
    for (int i = 0; i < a.length; i++) {
      sum += (double) a[i] * b[i];
    }
    return sum;
  }

  /** 这个索引里出现过的所有路径，排序；供「哪些文件进了索引」这种问题用。 */
  public Set<String> paths() {
    return new TreeSet<>(entries.stream().map(Entry::path).toList());
  }
}
