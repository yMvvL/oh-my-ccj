package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.ApprovalAnswer;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolRegistry;
import com.ccj.agent.core.ToolResult;
import com.ccj.agent.retrieval.EmbeddingConfig;
import com.ccj.agent.retrieval.RetrievalIndex;
import com.ccj.agent.retrieval.StubEmbeddings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 检索工具：它问什么、什么都不问的时候是什么时候、以及它在没配的时候说什么。
 *
 * <p>这个工具是这个仓库里唯一一个<em>把工作目录里的文字送出去</em>的东西，所以它的用例里最要紧的一条不是
 * 「它找到了什么」，而是 {@link #nothingLeavesTheMachineWhenTheApprovalIsRefused}：被拒绝时，替身端点收到
 * 的请求数必须是零。一个「先发了再问」的实现，会让审批从一道门变成一条通知。
 */
class RecallToolTest {

  @TempDir Path tmp;

  private StubEmbeddings endpoint;
  private Path workspace;

  @BeforeEach
  void setUp() throws Exception {
    endpoint = StubEmbeddings.start();
    workspace = Files.createDirectories(tmp.resolve("ws"));
    Files.writeString(
        workspace.resolve("sessions.txt"),
        "the session file is appended after every message, so a killed process keeps its history\n");
    Files.writeString(
        workspace.resolve("themes.txt"),
        "the colour scheme is remembered per browser and applied before the first paint\n");
  }

  @AfterEach
  void tearDown() {
    if (endpoint != null) {
      endpoint.close();
    }
  }

  private RecallTool tool() {
    EmbeddingConfig config = endpoint.config(10, 2);
    RetrievalIndex index =
        RetrievalIndex.open(workspace, tmp.resolve("home"), config, Map.of());
    return new RecallTool(index, tmp.resolve("home").resolve("embeddings.json"));
  }

  private ToolContext context(Approver approver) {
    return new ToolContext(workspace, approver, 32 * 1024);
  }

  /**
   * 经由注册表调用，也就是循环调用它的方式。
   *
   * <p>直接调 {@code execute} 会把坏参数当成异常抛出来，而工具这一层的约定是：可预期的失败是<em>结果</em>，
   * 由 {@link ToolRegistry} 把异常变成模型读得到的一句话。走这条路测的才是模型真正看到的东西。
   */
  private static ToolResult run(RecallTool tool, String argumentsJson, ToolContext ctx) {
    return ToolRegistry.of(tool)
        .execute(new Message.ToolCall("call_recall", tool.name(), argumentsJson), ctx);
  }

  @Test
  void withoutAnEndpointItSaysExactlyWhatToWriteAndWhere() throws Exception {
    RecallTool unconfigured = new RecallTool(null, Path.of("/home/someone/.oh-my-ccj/embeddings.json"));

    ToolResult result = run(unconfigured, "{\"query\":\"anything\"}", context(Approver.ALWAYS));

    assertTrue(result.error());
    assertTrue(result.content().contains("embeddings.json"), result.content());
    assertTrue(result.content().contains("grep"), "被拒绝时要说替代方案：" + result.content());
  }

  @Test
  void nothingLeavesTheMachineWhenTheApprovalIsRefused() throws Exception {
    AtomicInteger asked = new AtomicInteger();
    RecallTool tool = tool();

    ToolResult result =
        run(
            tool,
            "{\"query\":\"where is the session written\"}",
            context(
                request -> {
                  asked.incrementAndGet();
                  assertTrue(request.detail().contains("块的文字"), "审批要说清有多少文字要离开：" + request.detail());
                  assertTrue(request.detail().contains("stub-embed"), request.detail());
                  return ApprovalAnswer.DENY;
                }));

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("拒绝"), result.content());
    assertEquals(1, asked.get(), "问一次");
    assertEquals(0, endpoint.requests(), "被拒绝之后一个字节都不该发出去");
    assertEquals(0, endpoint.texts());
  }

  @Test
  void aBuildWithNothingNewToEmbedAsksNobody() throws Exception {
    // 缓存命中的那次检索不发任何文字出去，因此也不该弹一个提示：审批是关于出口的，不是关于这个工具的。
    RecallTool tool = tool();
    assertFalse(
        run(tool, "{\"query\":\"where is the session written\"}", context(Approver.ALWAYS)).error());

    int textsBefore = endpoint.texts();
    AtomicInteger asked = new AtomicInteger();
    ToolResult again =
        run(
            tool,
            "{\"query\":\"where is the session written\"}",
            context(
                request -> {
                  asked.incrementAndGet();
                  return ApprovalAnswer.ALLOW_ONCE;
                }));

    assertEquals(0, asked.get(), "一个字都没改，没有东西要送出去，就不该问人");
    assertFalse(again.error(), again.content());
    assertEquals(
        1, endpoint.texts() - textsBefore, "这一次只出去那一句查询；语料一个块都没有再发一遍");
  }

  @Test
  void resultsNameTheFileAndTheLineRange() throws Exception {
    ToolResult result =
        run(
            tool(),
            "{\"query\":\"where is the session file written after every message\"}",
            context(Approver.ALWAYS));

    assertFalse(result.error(), result.content());
    assertTrue(result.content().contains("sessions.txt:1-"), result.content());
    assertTrue(result.content().contains("索引："), "先说它索引了什么：" + result.content());
    assertTrue(result.content().contains("appended after every message"), result.content());
  }

  @Test
  void kIsRespectedAndCapped() throws Exception {
    RecallTool tool = tool();
    ToolResult one =
        run(tool, "{\"query\":\"colour scheme browser paint\",\"k\":1}", context(Approver.ALWAYS));

    int occurrences = one.content().split("themes.txt", -1).length - 1;
    assertEquals(1, occurrences, "只要一条就只给一条：" + one.content());

    ToolResult tooMany =
        run(tool, "{\"query\":\"colour scheme\",\"k\":500}", context(Approver.ALWAYS));
    assertTrue(tooMany.error(), "超出上限的参数是坏参数，而不是一个被悄悄夹住的值");
    assertTrue(tooMany.content().contains("参数无效"), tooMany.content());
  }

  @Test
  void aMissingQueryIsRefusedBeforeAnythingHappens() throws Exception {
    ToolResult result = run(tool(), "{}", context(Approver.ALWAYS));

    assertTrue(result.error());
    assertTrue(result.content().contains("query"), result.content());
    assertEquals(0, endpoint.requests(), "参数都不全，什么也不该发生");
  }

  @Test
  void itIsNotAReadOnlyToolBecauseItWritesAnIndexAndTalksToTheNetwork() {
    assertFalse(tool().readOnly());
    assertEquals("recall", RecallTool.NAME);
  }
}
