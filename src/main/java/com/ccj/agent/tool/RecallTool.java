package com.ccj.agent.tool;

import com.ccj.agent.core.ApprovalRequest;
import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.ccj.agent.retrieval.RetrievalIndex;
import com.ccj.agent.retrieval.Source;
import com.ccj.agent.retrieval.VectorIndex;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 按意思找代码：一个问题进去，最像它的几段代码出来。
 *
 * <p>它补的是 {@code grep} 补不上的那个缺口。{@code grep} 找的是字，所以它只在<em>你知道你想找的那个词
 * 叫什么</em>的时候有用——而在一个陌生的仓库里，那正是你不知道的事。这个工具找的是意思：问「在哪里把会话写
 * 到磁盘上」，它回答 {@code session/FileSession.java}，哪怕那个文件里没有出现过「磁盘」这个词。
 *
 * <p>三条与这个仓库其余部分一致的规矩：
 *
 * <ul>
 *   <li><strong>它把文字送出本机，所以它要审批。</strong>审批问的是一个具体的数字——「把 N 个块发给
 *       <端点>」——而不是一句「要不要用检索」。缓存命中的时候 N 是 0，于是没有东西离开这台机器，于是也就不
 *       必问。
 *   <li><strong>它写缓存，所以它不是只读的。</strong>{@code readOnly} 控制的是循环能不能把它和别的调用
 *       并发跑；一个会写索引、会出网的调用不属于那一类，和 {@code bash} 一样。
 *   <li><strong>它必须能被解释。</strong>结果不是一串 id，而是 {@code 路径:行号-行号} 加一段摘录，因为一段
 *       被检索出来的代码，它的价值一半在于它是<em>哪里的</em>那一段。
 * </ul>
 */
public final class RecallTool implements Tool {

  public static final String NAME = "recall";

  /** 一次索引构建最多读多少字节的源码：一个上界，而不是一个目标。 */
  private static final long INDEX_BYTE_BUDGET = 16L * 1024 * 1024;

  /** 单个文件超过这么大就不进索引：它要么是数据，要么是压缩过的产物。 */
  private static final long MAX_FILE_BYTES = 512L * 1024;

  /** 一个块在结果里最多显示多少字符。 */
  private static final int EXCERPT_CHARS = 1200;

  private final RetrievalIndex index;
  private final Path embeddingsFile;

  /**
   * @param index 已经配好的索引；null 表示这台机器上还没有配置嵌入端点
   * @param embeddingsFile 配置该端点的那份文件，只用于在错误信息里点名它
   */
  public RecallTool(RetrievalIndex index, Path embeddingsFile) {
    this.index = index;
    this.embeddingsFile = embeddingsFile;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "Find code by meaning rather than by spelling: give it a question or a description, and it"
        + " returns the most similar passages with their file and line range. Use it when you do not"
        + " know what the thing you are looking for is called; use grep when you do. Requires an"
        + " embeddings endpoint in the application home, and asks before sending anything to it.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "What you are looking for, in words — a question, or a description of the behaviour. Not a regex."
            },
            "k": {
              "type": "integer",
              "description": "How many passages to return. Defaults to 8, maximum 50."
            }
          },
          "required": ["query"],
          "additionalProperties": false
        }""";
  }

  @Override
  public boolean readOnly() {
    return false;
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) throws Exception {
    JsonNode args = ToolSupport.args(argumentsJson);
    String query = ToolSupport.requireNonBlank(args, "query");
    int k = ToolSupport.optionalInt(args, "k", 8, 1, 50);
    if (index == null) {
      return ToolResult.error(
          "这台机器上没有配置语义检索：把 {\"baseUrl\": …, \"model\": …} 写进 "
              + embeddingsFile
              + " 即可（见 docs/RETRIEVAL.md）。在那之前请用 grep——它找字，不找意思");
    }

    List<Source> sources = readWorkspace(ctx.cwd());
    RetrievalIndex.Plan plan = index.plan(sources);
    if (plan.sendsAnything()) {
      // 审批带着那个数字：要离开这台机器的是 text，而「多少」正是决定要不要放行的那个东西。
      String refusal =
          ctx.refusal(
              ApprovalRequest.tool(
                  NAME,
                  "把工作目录里 "
                      + plan.pending().size()
                      + " 个块的文字发给 "
                      + index.config().describe()
                      + " 算向量（索引在 "
                      + index.directory()
                      + "），然后按意思检索"));
      if (refusal != null) {
        return ToolResult.error(refusal);
      }
    }

    RetrievalIndex.Built built = index.build(plan);
    List<VectorIndex.Hit> hits = index.search(query, k);
    return ToolResult.ok(render(built, hits, ctx.outputLimitBytes()));
  }

  /**
   * 这个工作目录里能进索引的文字。
   *
   * <p>走的是 {@code glob} 与 {@code grep} 用的那个遍历：同样的跳过目录、同样的二进制判断、同样的解码。一
   * 个把 {@code target/} 里的东西也索引进去的检索工具，会用编译产物回答关于源码的问题——而那正是「它给的
   * 答案看起来对、实际不对」的经典形状。
   */
  private List<Source> readWorkspace(Path cwd) throws IOException {
    List<Source> sources = new ArrayList<>();
    long[] budget = {INDEX_BYTE_BUDGET};
    ToolSupport.walkFiles(
        cwd,
        (file, relative) -> {
          if (budget[0] <= 0) {
            return;
          }
          long size = Files.size(file);
          if (size == 0 || size > MAX_FILE_BYTES) {
            return;
          }
          if (ToolSupport.isBinaryFile(file)) {
            return;
          }
          String text = ToolSupport.decodeText(Files.readAllBytes(file));
          if (text.isBlank()) {
            return;
          }
          budget[0] -= size;
          sources.add(new Source(ToolSupport.slashed(relative), text));
        });
    return sources;
  }

  /** 一次检索的答案：先说它索引了什么，再说它找到了什么。 */
  private static String render(
      RetrievalIndex.Built built, List<VectorIndex.Hit> hits, int outputLimitBytes) {
    StringBuilder out = new StringBuilder();
    out.append("索引：")
        .append(built.chunks())
        .append(" 个块，本次新算了 ")
        .append(built.embedded())
        .append(" 个（命中缓存 ")
        .append(built.reused())
        .append(" 个）\n");
    if (hits.isEmpty()) {
      out.append("按意思找不到任何东西。这个索引可能是空的——先随便搜一次让它建起来。\n");
      return out.toString();
    }
    int remaining = hits.size();
    for (VectorIndex.Hit hit : hits) {
      out.append('\n')
          .append(String.format(java.util.Locale.ROOT, "%.3f  ", hit.score()))
          .append(hit.entry().label())
          .append('\n');
      out.append(excerpt(hit));
      remaining--;
      // 输出的上限是**字节**，而摘录是字符；这里用同一把尺子（工具层的 utf8Length）量已经写出去的这一份，
      // 超了就停下并说明还剩几条——一个被砍掉的结果要说出自己被砍掉了。
      if (outputLimitBytes > 0 && ToolSupport.utf8Length(out.toString()) > outputLimitBytes) {
        out.append("\n… （结果超过了这次调用的输出上限，后面还有 ").append(remaining).append(" 条）\n");
        break;
      }
    }
    return out.toString();
  }

  private static String excerpt(VectorIndex.Hit hit) {
    String text = hit.entry().text() == null ? "" : hit.entry().text();
    return text.length() <= EXCERPT_CHARS ? text + "\n" : text.substring(0, EXCERPT_CHARS) + "\n…\n";
  }
}
