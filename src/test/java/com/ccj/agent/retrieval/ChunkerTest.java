package com.ccj.agent.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 切块：窗口、重叠、以及一个块的指纹。
 *
 * <p>指纹那几条是这里最要紧的：增量更新（也就是「改一个文件不必重算整个仓库」）全靠它，而一个算错的方向
 * ——内容没变却换了指纹——不会报错，只会让每一次检索都重新花一遍钱。
 */
class ChunkerTest {

  private static List<String> lines(int count) {
    List<String> out = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      out.add("line " + i);
    }
    return out;
  }

  private static List<Chunker.Chunk> chunks(int lines, int window, int overlap) {
    return Chunker.chunks(List.of(new Source("a.txt", String.join("\n", lines(lines)))), window, overlap, 1000);
  }

  @Test
  void aFileBecomesWindowsThatOverlapByTheConfiguredNumberOfLines() {
    List<Chunker.Chunk> chunks = chunks(10, 4, 1);

    assertEquals(3, chunks.size(), chunks.toString());
    assertEquals(1, chunks.get(0).firstLine());
    assertEquals(4, chunks.get(0).lastLine());
    assertEquals(4, chunks.get(1).firstLine(), "重叠一行：上一块的末行也在下一块的开头");
    assertEquals(7, chunks.get(1).lastLine());
    assertEquals(7, chunks.get(2).firstLine());
    assertEquals(10, chunks.get(2).lastLine(), "最后一块在文件末尾收住，不会越过去");
  }

  @Test
  void aChunkCarriesItsPathAndLineRangeForBothTheModelAndTheReader() {
    Chunker.Chunk first = chunks(10, 4, 1).get(0);

    assertEquals("a.txt", first.path());
    assertEquals("a.txt:1-4", first.label());
    assertTrue(first.embedText().startsWith("# a.txt:1-4\n"), first.embedText());
    assertTrue(first.embedText().contains("line 1"), first.embedText());
  }

  @Test
  void anOverlapAtLeastAsBigAsTheWindowStillTerminatesAndNeverGrowsAChunk() {
    // 重叠等于窗口会让每一块都包含上一块的全部。这里不假装那种配置是有意义的——把它变成「每行一块」，
    // 至少是有界的、每一步都真的往前走的（重叠本身由 EmbeddingConfig 在入口处裁掉，那是配置的职责）。
    List<Chunker.Chunk> chunks = chunks(10, 4, 4);

    assertEquals(7, chunks.size(), "每次只往前一行，所以十行切出七块：" + chunks);
    for (Chunker.Chunk chunk : chunks) {
      assertTrue(
          chunk.text().lines().count() <= 4,
          "没有一块能长过窗口：" + chunk.label() + "\n" + chunk.text());
    }
    assertEquals(10, chunks.get(chunks.size() - 1).lastLine(), "最后一块仍然在文件末尾收住");
  }

  @Test
  void aWindowOfNothingButBlanksIsNotAChunk() {
    List<Chunker.Chunk> chunks =
        Chunker.chunks(List.of(new Source("blank.txt", "\n\n\n\n\ntext here\n\n\n")), 2, 0, 100);

    assertEquals(1, chunks.size(), chunks.toString());
    assertTrue(chunks.get(0).text().contains("text here"));
  }

  @Test
  void aChunkIsCappedSoOneLongLineCannotBecomeAWholeDocument() {
    List<Chunker.Chunk> chunks =
        Chunker.chunks(List.of(new Source("one.txt", "x".repeat(Chunker.MAX_CHUNK_CHARS * 2))), 1, 0, 100);

    assertEquals(Chunker.MAX_CHUNK_CHARS, chunks.get(0).text().length());
  }

  @Test
  void theChunkBudgetIsRespected() {
    assertEquals(5, Chunker.chunks(List.of(new Source("a.txt", String.join("\n", lines(500)))), 10, 2, 5).size());
  }

  @Test
  void aFingerprintFollowsTheContentAndThePlaceItCameFrom() {
    String at1 = Chunker.fingerprint("a.txt", 1, 4, "same text");
    assertEquals(at1, Chunker.fingerprint("a.txt", 1, 4, "same text"), "同样的输入，同样的指纹");
    assertNotEquals(at1, Chunker.fingerprint("a.txt", 1, 4, "other text"));
    assertNotEquals(at1, Chunker.fingerprint("b.txt", 1, 4, "same text"), "内容一样但出处不同");
    assertNotEquals(
        at1, Chunker.fingerprint("a.txt", 2, 5, "same text"), "行号也在指纹里：挪过位置就是另一个块");
  }

  @Test
  void reChunkingAnUnchangedFileProducesTheSameFingerprints() {
    // 这条主张是「重新索引一个没改过的仓库不花任何钱」的全部依据。
    assertEquals(
        chunks(20, 5, 1).stream().map(Chunker.Chunk::fingerprint).toList(),
        chunks(20, 5, 1).stream().map(Chunker.Chunk::fingerprint).toList());
  }

  @Test
  void anEditLeavesTheChunksBeforeItAlone() {
    List<Chunker.Chunk> before = chunks(20, 5, 0);
    List<Chunker.Chunk> after =
        Chunker.chunks(
            List.of(new Source("a.txt", String.join("\n", lines(20)).replace("line 19", "changed"))),
            5,
            0,
            1000);

    assertEquals(
        before.get(0).fingerprint(), after.get(0).fingerprint(), "前面没动过的块指纹不变");
    assertNotEquals(
        before.get(3).fingerprint(), after.get(3).fingerprint(), "改过的那一块指纹变了");
    assertFalse(after.isEmpty());
  }
}
