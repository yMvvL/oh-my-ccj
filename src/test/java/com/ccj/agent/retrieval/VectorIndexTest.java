package com.ccj.agent.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 向量表：归一化、前 k 个、存回磁盘、以及在维度对不上时拒绝作答。
 *
 * <p>这些用例都用小到能一眼算出来的向量，因为在向量上「看起来对」和「真的对」之间的距离，正是这类代码出错
 * 的方式：一个把相似度和距离搞反的排序，返回的结果依然是「一串排好序的东西」。
 */
class VectorIndexTest {

  @TempDir Path tmp;

  private static float[] vector(float... values) {
    return values;
  }

  private static Chunker.Chunk chunk(String path, int first, int last, String text) {
    return new Chunker.Chunk(path, first, last, text, Chunker.fingerprint(path, first, last, text));
  }

  @Test
  void theClosestVectorComesFirst() {
    VectorIndex index = new VectorIndex();
    index.add(chunk("near.txt", 1, 1, "near"), vector(1, 0, 0));
    index.add(chunk("far.txt", 1, 1, "far"), vector(0, 1, 0));

    List<VectorIndex.Hit> hits = index.search(vector(1, 0, 0), 2);

    assertEquals(2, hits.size());
    assertEquals("near.txt", hits.get(0).entry().path());
    assertEquals(1.0, hits.get(0).score(), 1e-6, "同一个方向就是相似度 1");
    assertEquals(0.0, hits.get(1).score(), 1e-6, "正交就是 0");
  }

  @Test
  void similarityIgnoresLengthSoALongChunkIsNotAutomaticallyCloser() {
    // 不归一化的话，一个由很多重复词组成的块会与所有东西都「像」，因为它什么都不像——它只是很大。
    VectorIndex index = new VectorIndex();
    index.add(chunk("short.txt", 1, 1, "short"), vector(1, 0));
    index.add(chunk("long.txt", 1, 1, "long"), vector(50, 0));

    List<VectorIndex.Hit> hits = index.search(vector(3, 0), 2);

    assertEquals(1.0, hits.get(0).score(), 1e-6);
    assertEquals(1.0, hits.get(1).score(), 1e-6, "同一个方向，长度不该改变相似度");
  }

  @Test
  void onlyTheTopKComeBackAndTheyAreOrdered() {
    VectorIndex index = new VectorIndex();
    index.add(chunk("a.txt", 1, 1, "a"), vector(1, 0, 0));
    index.add(chunk("b.txt", 1, 1, "b"), vector(0.8f, 0.2f, 0));
    index.add(chunk("c.txt", 1, 1, "c"), vector(0, 0, 1));

    List<VectorIndex.Hit> hits = index.search(vector(1, 0, 0), 2);

    assertEquals(2, hits.size());
    assertEquals(List.of("a.txt", "b.txt"), hits.stream().map(hit -> hit.entry().path()).toList());
    assertTrue(hits.get(0).score() >= hits.get(1).score());
  }

  @Test
  void anEmptyQueryOrAnEmptyIndexAnswersWithNothingRatherThanEverything() {
    VectorIndex index = new VectorIndex();
    assertEquals(List.of(), index.search(vector(1, 0), 3), "空索引没有东西可给");

    index.add(chunk("a.txt", 1, 1, "a"), vector(1, 0));
    assertEquals(List.of(), index.search(new float[0], 3));
    assertEquals(List.of(), index.search(null, 3));
    assertEquals(List.of(), index.search(vector(1, 0), 0));
  }

  @Test
  void aVectorFromAnotherModelIsRefusedRatherThanMultiplied() {
    VectorIndex index = new VectorIndex();
    index.add(chunk("a.txt", 1, 1, "a"), vector(1, 0, 0));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> index.search(vector(1, 0), 1));
    assertTrue(failure.getMessage().contains("3 维"), failure.getMessage());
    assertTrue(failure.getMessage().contains("重建"), failure.getMessage());
  }

  @Test
  void theIndexSurvivesBeingWrittenAndReadBack() throws Exception {
    VectorIndex index = new VectorIndex();
    index.add(chunk("a.txt", 1, 4, "first chunk"), vector(1, 0, 0));
    index.add(chunk("b.txt", 7, 9, "second chunk"), vector(0, 1, 0));
    index.save(tmp);

    assertTrue(VectorIndex.exists(tmp));
    VectorIndex reloaded = VectorIndex.load(tmp);

    assertEquals(2, reloaded.size());
    assertEquals("first chunk", reloaded.entries().get(0).text(), "正文也存下来了，结果才自己站得住");
    assertEquals(1.0, reloaded.search(vector(1, 0, 0), 1).get(0).score(), 1e-6);
    assertEquals(index.fingerprints(), reloaded.fingerprints());
  }

  @Test
  void anAlreadyKnownFingerprintIsNeverAddedTwice() {
    VectorIndex index = new VectorIndex();
    Chunker.Chunk once = chunk("a.txt", 1, 4, "same");
    index.add(once, vector(1, 0));
    index.add(once, vector(0, 1));

    assertEquals(1, index.size());
    assertEquals(1.0, index.search(vector(1, 0), 1).get(0).score(), 1e-6,
        "第二次给的那个向量不该覆盖第一次：那是同一段文字");
  }

  @Test
  void anEmptyVectorIsRefused() {
    VectorIndex index = new VectorIndex();
    assertThrows(IllegalArgumentException.class, () -> index.add(chunk("a.txt", 1, 1, "a"), new float[0]));
    assertThrows(IllegalArgumentException.class, () -> index.add(chunk("a.txt", 1, 1, "a"), null));
  }

  @Test
  void aDamagedIndexReadsAsAnEmptyOneRatherThanFailing() throws Exception {
    // 一份坏掉的缓存是可以重建的东西，而一个每次启动都炸的缓存比没有缓存更糟。
    Files.writeString(tmp.resolve(VectorIndex.CHUNKS_FILE), "{\"path\":\"a.txt\",\"dimensions\":3}\n");
    Files.write(tmp.resolve(VectorIndex.VECTORS_FILE), new byte[] {1, 2});

    assertEquals(0, VectorIndex.load(tmp).size());
  }

  @Test
  void aMissingIndexIsSimplyEmpty() {
    assertFalse(VectorIndex.exists(tmp));
    assertEquals(0, VectorIndex.load(tmp).size());
  }

  @Test
  void theStoredVectorIsNormalisedSoScoresAreComparableAcrossSearches() throws Exception {
    VectorIndex index = new VectorIndex();
    index.add(chunk("a.txt", 1, 1, "a"), vector(3, 4));
    index.save(tmp);

    float[] stored = VectorIndex.load(tmp).entries().get(0).vector();
    assertEquals(0.6, stored[0], 1e-6);
    assertEquals(0.8, stored[1], 1e-6);
    assertNotNull(index.search(vector(6, 8), 1));
  }
}
