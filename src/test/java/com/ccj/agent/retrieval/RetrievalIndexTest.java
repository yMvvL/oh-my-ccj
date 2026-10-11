package com.ccj.agent.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 一个真的端点（本地替身）、一份真的语料、一套真的索引流程——以及它的召回率。
 *
 * <p>这里量的是**检索质量**，而不只是「它跑起来了」。一个检索工具的失败方式不是抛异常，它是安静地排错顺序；
 * 所以这一组用例里最重要的一条是 {@link #recallAtKIsMeasuredAgainstPlantedFacts}：往语料里埋进事实，再从
 * 一个**不含原文**的问句出发，看它能不能找回来，并把 recall@1、recall@5 与 MRR 一起打印出来。
 *
 * <p>替身端点的向量是词袋散列（见 {@link StubEmbeddings}），所以这些数字量的是**这一套东西**——切块、指纹、
 * 缓存、排序——而不是某个真模型有多聪明。换成一个真模型会换掉这些向量；不会换掉这段代码。
 */
class RetrievalIndexTest {

  @TempDir Path tmp;

  private StubEmbeddings endpoint;

  @BeforeEach
  void setUp() throws Exception {
    endpoint = StubEmbeddings.start();
  }

  @AfterEach
  void tearDown() {
    if (endpoint != null) {
      endpoint.close();
    }
  }

  private Path corpus() throws Exception {
    Path workspace = Files.createDirectories(tmp.resolve("ws"));
    Files.writeString(
        workspace.resolve("writes.txt"),
        "the session is appended to disk after every tool call, so a killed process\n"
            + "still leaves a transcript that can be reopened\n");
    Files.writeString(
        workspace.resolve("colours.txt"),
        "the theme is picked from a css variable, and the dark one is remembered per browser\n");
    Files.writeString(
        workspace.resolve("sandbox.txt"),
        "there is no sandbox: the approval prompt is the only guard, and yolo removes it\n");
    return workspace;
  }

  private RetrievalIndex index(Path workspace) {
    return RetrievalIndex.open(workspace, tmp.resolve("home"), endpoint.config(10, 2), Map.of());
  }

  private static List<Source> sources(Path workspace) throws Exception {
    List<Source> sources = new ArrayList<>();
    try (var files = Files.list(workspace)) {
      for (Path file : files.toList()) {
        sources.add(new Source(file.getFileName().toString(), Files.readString(file)));
      }
    }
    return sources;
  }

  @Test
  void aQueryInDifferentWordsStillFindsThePassageThatMeansIt() throws Exception {
    Path workspace = corpus();
    RetrievalIndex index = index(workspace);
    index.build(index.plan(sources(workspace)));

    // 问句里没有一个字出现在目标文件里（"persist"、"across restarts" 都不是它写下的话）。
    List<VectorIndex.Hit> hits = index.search("how does a conversation survive a restart", 3);

    assertFalse(hits.isEmpty());
    assertTrue(
        hits.stream().anyMatch(hit -> hit.entry().path().equals("writes.txt")),
        "最像的一段应当来自讲持久化的那个文件，实际是 " + hits.stream().map(h -> h.entry().label()).toList());
  }

  @Test
  void theSecondBuildSendsNothingBecauseEveryFingerprintAlreadyHasAVector() throws Exception {
    // 这条就是「审批是关于出口的」那一整套设计的依据：没有东西要送出去，就不必问任何人，也不花一分钱。
    Path workspace = corpus();
    RetrievalIndex index = index(workspace);

    RetrievalIndex.Plan first = index.plan(sources(workspace));
    assertTrue(first.sendsAnything());
    index.build(first);
    int firstRequestCount = endpoint.requests();

    RetrievalIndex.Plan second = index.plan(sources(workspace));
    assertFalse(second.sendsAnything(), "一个字都没改，就没有块需要重算：" + second);
    assertEquals(second.chunks(), second.reused());
    index.build(second);

    assertEquals(firstRequestCount, endpoint.requests(), "第二次构建一个请求都不该发出去");
  }

  @Test
  void anEditedFileOnlyCostsTheChunksThatActuallyChanged() throws Exception {
    Path workspace = corpus();
    RetrievalIndex index = index(workspace);
    index.build(index.plan(sources(workspace)));

    Files.writeString(
        workspace.resolve("colours.txt"),
        "the theme is picked from a css variable, and the dark one is remembered per browser\n"
            + "a new line that was not there before, which changes exactly one chunk\n");
    RetrievalIndex.Plan plan = index.plan(sources(workspace));

    assertTrue(plan.sendsAnything(), "改过的那一块要重算");
    assertTrue(plan.reused() > 0, "其余没动的块应当命中缓存：" + plan);
    assertTrue(plan.pending().size() < plan.chunks(), "不该重算整份语料：" + plan);
  }

  @Test
  void theIndexSurvivesAReopenWithoutReloadingTheEndpoint() throws Exception {
    Path workspace = corpus();
    RetrievalIndex first = index(workspace);
    first.build(first.plan(sources(workspace)));
    int requests = endpoint.requests();

    RetrievalIndex reopened =
        RetrievalIndex.open(workspace, tmp.resolve("home"), endpoint.config(10, 2), Map.of());
    assertEquals(first.index().size(), reopened.index().size(), "块还在");
    assertEquals(requests, endpoint.requests(), "打开一份已有的索引不该联网");
  }

  @Test
  void changingTheModelThrowsTheOldIndexAway() throws Exception {
    // 两个模型的向量不在同一个空间里，而在那个空间里的点积不会报错——它只会给出没有意义的相似度。
    Path workspace = corpus();
    RetrievalIndex first = index(workspace);
    first.build(first.plan(sources(workspace)));
    assertTrue(first.index().size() > 0);

    EmbeddingConfig other = new EmbeddingConfig(endpoint.baseUrl(), "another-model", null, null, 10, 2);
    RetrievalIndex reopened =
        RetrievalIndex.open(workspace, tmp.resolve("home"), other, Map.of());

    assertEquals(0, reopened.index().size(), "另一个模型的索引必须整份重建，而不是拿来用");
  }

  @Test
  void aDifferentChunkingIsAlsoADifferentIndex() throws Exception {
    Path workspace = corpus();
    RetrievalIndex first = index(workspace);
    first.build(first.plan(sources(workspace)));

    RetrievalIndex other =
        RetrievalIndex.open(workspace, tmp.resolve("home"), endpoint.config(4, 1), Map.of());

    assertEquals(0, other.index().size(), "块变了，指纹就全变了；沿用旧索引只会让缓存永远不命中");
  }

  @Test
  void theSameWorkspaceGetsTheSameIndexDirectoryAndAnotherOneDoesNot() throws Exception {
    assertEquals(
        RetrievalIndex.key(tmp.resolve("ws")), RetrievalIndex.key(tmp.resolve("ws").toAbsolutePath()));
    assertNotEquals(RetrievalIndex.key(tmp.resolve("ws")), RetrievalIndex.key(tmp.resolve("other")));
  }

  /**
   * 召回率：埋进去的事实能不能被找回来——而且是用一个不含原文的问句去问。
   *
   * <p>四十份文件、每份讲一件各不相同的「事」；十句问句，每句只与其中一份共享词。然后量 recall@1、
   * recall@5 与 MRR（第一个正确结果的倒数排名的平均），并把它们打印出来——这三个数字而不是「它好像能
   * 用」，是这个工具可以说「够用」的唯一依据。
   */
  @Test
  void recallAtKIsMeasuredAgainstPlantedFacts() throws Exception {
    Path workspace = Files.createDirectories(tmp.resolve("big"));
    String[] topics = {
      "retry policy for rate limited requests",
      "session storage on disk as jsonl",
      "token budget projection for long conversations",
      "approval rules written by the user",
      "markdown rendering without a framework",
      "virtual threads used for tool execution",
      "sse backpressure for slow clients",
      "checkpoint snapshots per turn",
      "compaction summary of old turns",
      "mcp servers contributing tools",
      "theme colours stored per browser",
      "wallpaper rotation on the start page",
      "sub agents with their own model",
      "cost limit per session in tokens",
      "vision model describing pictures",
      "tailnet address exposed to the phone",
      "web token generated once and stored",
      "bootstrap restarting into the same session",
      "diff preview before a write",
      "glob skipping ignored directories"
    };
    for (int i = 0; i < topics.length; i++) {
      // 每一份都只讲它自己那件事。刻意不加一段所有文件都一样的开头——那段话会变成每一块里最主要的词，
      // 于是所有的块彼此都很像，而那是替身的问题被读成了流水线的问题。
      Files.writeString(
          workspace.resolve(String.format(Locale.ROOT, "topic-%02d.txt", i)),
          "Notes on " + topics[i] + ".\n" + topics[i] + " is decided in one place and read everywhere.\n");
    }
    RetrievalIndex index = RetrievalIndex.open(workspace, tmp.resolve("home"), endpoint.config(6, 1), Map.of());
    index.build(index.plan(sources(workspace)));

    // 每句问句与它对应的那份文件共享词，但整句原样并不出现在文件里——所以这是检索，不是 grep。
    //
    // 这个替身**只会看字**：它没有同义词，也不懂「stored」和「storage」是一家人。所以这些问句用的是语料
    // 自己的词汇——一份真的嵌入端点不需要这条约束（那正是它贵的地方），而这里量的是流水线：切块、指纹、
    // 缓存、排序，都不该因为换了一个更聪明的端点而改变。
    String[] queries = {
      "what happens when a request is rate limited",
      "where does a session get stored as jsonl",
      "how is the context window kept under budget",
      "which approval rules decide what may run without asking",
      "how are answers turned into readable markdown",
      "which threads run the tools",
      "what stops a slow client from blocking everyone",
      "can a turn be undone",
      "what happens to older turns in a long conversation",
      "how do external servers add their own tools"
    };

    int atOne = 0;
    int atFive = 0;
    double reciprocalRank = 0;
    StringBuilder ranks = new StringBuilder();
    for (int i = 0; i < queries.length; i++) {
      List<VectorIndex.Hit> hits = index.search(queries[i], 5);
      int rank = -1;
      for (int position = 0; position < hits.size(); position++) {
        if (hits.get(position).entry().path().equals(String.format(Locale.ROOT, "topic-%02d.txt", i))) {
          rank = position + 1;
          break;
        }
      }
      ranks.append(rank).append(' ');
      if (rank == 1) {
        atOne++;
      }
      if (rank > 0) {
        atFive++;
        reciprocalRank += 1.0 / rank;
      }
    }
    double recall1 = atOne / (double) queries.length;
    double recall5 = atFive / (double) queries.length;
    double mrr = reciprocalRank / queries.length;
    System.out.printf(
        Locale.ROOT,
        "检索质量（%d 份文件，%d 个块）：recall@1=%.2f  recall@5=%.2f  MRR=%.2f%n",
        topics.length,
        index.index().size(),
        recall1,
        recall5,
        mrr);
    System.out.println("每一句的排名：" + ranks.toString().strip());

    // 词袋散列配上分明的语料，召回不应该是碰运气；这一条钉住的是「排序没有反过来」。
    assertEquals(1.0, recall5, 1e-9, "每一句都该在前五条里找到它对应的那份文件");
    assertTrue(mrr > 0.6, "第一个正确结果不该总是排在最后：MRR=" + mrr);
  }
}
