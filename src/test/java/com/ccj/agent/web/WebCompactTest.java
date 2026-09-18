package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 一次压缩落地时页面做什么，是浏览器代码——它住在 {@code web/app.js} 里，那是一个没有导出的经典
 * 脚本——所以要紧的那两个函数是一个 node 脚本（{@code src/test/js/compaction.test.mjs}），把它们
 * 从发布出去的文件里抠出来，对着一个小小的桩运行，就像 {@link WebReplayTest} 跑回放的用例那样。
 *
 * <p>它钉住的两个 bug 都躲过了一整轮 {@code mvn test}，也躲过了一次真正经 HTTP 驱动的端到端
 * 会话，因为 curl 看不见转录：
 *
 * <ol>
 *   <li>{@code loadHistory} 是<em>追加</em>的，因为它本来是给「id 变了、面板已清空」的会话切换
 *       写的。压缩保持同一个 id，于是被留下的那几组往来又在屏幕上已有的那些底下画了一遍。
 *   <li>摘要则压根没被画出来——{@code historyJson} 没有为它准备分支，直接落到了「不属于一份被
 *       渲染的对话」的默认处理上。
 * </ol>
 *
 * <p>下面这些用例是 node 自己查不了的：服务器按名字产生那个事件、页面按名字消费它，两边都要有。
 */
class WebCompactTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "compaction.test.mjs");
  private static final Path APP = Path.of("src", "main", "resources", "web", "app.js");
  private static final Path HUB =
      Path.of("src", "main", "java", "com", "ccj", "agent", "web", "AgentHub.java");

  @Test
  void theCompactionScriptPasses() throws IOException, InterruptedException {
    WebSessionRowTest.runNodeCases(SCRIPT, "压缩");
  }

  @Test
  void theTranscriptIsClearedBeforeTheNewHistoryArrives() throws IOException {
    String script = source(APP);
    assertTrue(script != null, "app.js 必须可读");

    int at = script.indexOf("async function onCompacted(");
    assertTrue(at >= 0, "页面会处理 compacted 事件");
    // 清空必须发生在取数据*之前*：在它之后的话，追加已经发生，屏幕上的对话就翻倍了。
    int clear = script.indexOf("clearTranscript();", at);
    int load = script.indexOf("loadHistory(", at);
    assertTrue(clear > at && load > clear,
        "onCompacted 必须在重新读取历史之前清空转录");
  }

  @Test
  void theSummaryEventIsRendered() throws IOException {
    String script = source(APP);
    assertTrue(script != null, "app.js 必须可读");

    assertTrue(script.contains("case 'summary':"), "页面会派发 summary 事件");
    assertTrue(script.contains("function appendSummary("), "而且有一个渲染它的东西");
    // 那张卡片会说出完整对话仍然在哪里：正是这一点让一份漏掉细节的摘要是可恢复的，所以它不是
    // 可有可无的装饰。
    assertTrue(script.contains("读回"),
        "卡片会告诉读者，细节可以从文件里读回来");
  }

  @Test
  void theServerSendsTheSummaryRatherThanDroppingIt() throws IOException {
    String hub = source(HUB);
    assertTrue(hub != null, "AgentHub.java 必须可读");

    // bug 就在这儿：`Message.Summary` 落到了默认分支上，而那个分支的注释说系统消息不是被渲染
    // 的内容，于是一次压缩之后加载的页面只显示留下的那几组往来，没有任何迹象表明发生过压缩。
    int at = hub.indexOf("public ObjectNode historyJson()");
    assertTrue(at >= 0, "历史是由一个方法构建的");
    int end = hub.indexOf("private static ObjectNode replay(", at);
    String history = hub.substring(at, end > at ? end : hub.length());

    assertTrue(history.contains("case Message.Summary summary ->"),
        "historyJson 必须为摘要准备一个分支");
    assertTrue(history.contains("replay(\"summary\")"),
        "并作为 summary 事件发出去，而这正是页面派发的东西");
    assertTrue(history.contains(".put(\"covers\""),
        "带上那张卡片显示的计数");
  }

  @Test
  void theCompactedEventIsStillAnnouncedToThePage() throws IOException {
    String hub = source(HUB);
    assertTrue(hub != null, "AgentHub.java 必须可读");

    // 正是这个事件告诉页面该重建了；单有 summary 事件只在取历史时才到，而取历史正是这个事件
    // 触发的。
    assertTrue(hub.contains("\"compacted\""), "hub 会发布一个 compacted 事件");
    assertTrue(hub.contains(".put(\"savedPercent\""),
        "并且报告它省下了多少，也就是那条通知显示的数字");
  }

  private static String source(Path path) {
    try {
      return Files.exists(path) ? Files.readString(path) : null;
    } catch (IOException err) {
      return null;
    }
  }
}
