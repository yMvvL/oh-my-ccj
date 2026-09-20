package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 回放一段长对话是浏览器代码——它住在 {@code web/app.js} 里，那是一个没有导出的经典脚本——所以
 * 那个把一个历史拆成「现在显示什么」和「稍后补上什么」的函数，是一个 node 脚本
 * （{@code src/test/js/replay.test.mjs}），把它从发布出去的文件里抠出来，对着一个小小的 node 桩
 * 运行，就像 {@link WebSessionRowTest} 跑会话行的用例那样。
 *
 * <p>它钉住的是这次拆分存在的理由：切回一段长对话过去会把每一张工具卡片一次性重建，把线程堵得
 * 足够久，久到切换背后正在跑的一个回合看起来像冻住了。这个类在有 node 的时候运行那个脚本，并且
 * 总是检查发布出去的源码里让那两趟都正确的性质：切口落在渲染器本来就当作一个边界的地方，更早的
 * 那部分在屏幕外画好再插到读者上方，而且插入时读者的滚动位置被按住了。
 */
class WebReplayTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "replay.test.mjs");
  private static final Path APP = Path.of("src", "main", "resources", "web", "app.js");

  @Test
  void theReplayScriptPasses() throws IOException, InterruptedException {
    WebSessionRowTest.runNodeCases(SCRIPT, "长对话回放");
  }

  @Test
  void theEarlierPartIsDrawnInIdleTimeAndInsertedAbove() throws IOException {
    String script = WebSessionRowTest.appSourceOrSkip();

    assertTrue(script.contains("function splitReplay("),
        "历史由一个函数拆分，所以它的边界规则可以被测试");
    assertTrue(script.contains("function loadOlderReplay("),
        "而且更早的那部分有自己的加载器");
    assertTrue(script.contains("requestIdleCallback"),
        "它在浏览器空闲时运行：切换背后正在流式输出的回合仍能继续画");
    assertTrue(script.contains("document.createDocumentFragment()"),
        "并且先把这组往来在屏幕外建好再挂上去，所以不会有画到一半的东西");
    // 读者的位置由加在他上方的那段高度按住；没有这个，每落下一块，正在读的文字就在屏幕上往下
    // 滑一段。
    assertTrue(script.contains("dom.transcript.scrollTop = before + grew"),
        "而且滚动位置会按加在它上方的高度被修正");
  }

  @Test
  void aSupersededReplayStops() throws IOException {
    String script = WebSessionRowTest.appSourceOrSkip();

    // 在更早的那部分还在加载时又切一次，绝不能把旧对话追加进新的转录里。
    assertTrue(script.contains("if (seq !== replaySeq) { return; }"),
        "被取代的回放会停下，而不是往一份已经没了的转录里写");
  }

  @Test
  void theBudgetIsBoundedAndModest() throws IOException {
    String script = WebSessionRowTest.appSourceOrSkip();

    int at = script.indexOf("REPLAY_TAIL_EVENTS = ");
    assertTrue(at >= 0, "第一趟有一个具名的预算");
    String rest = script.substring(at + "REPLAY_TAIL_EVENTS = ".length());
    int limit = Integer.parseInt(rest.substring(0, rest.indexOf(';')).strip());
    assertTrue(limit > 0 && limit <= 1000,
        "第一趟必须便宜：" + limit + " 个事件不是一次有界的绘制");
    // 比预算短的对话一趟就渲染完，这是常见情况，绝不能有任何改变。
    assertTrue(script.contains("if (clean.length <= REPLAY_TAIL_EVENTS)"),
        "而放得进预算的历史不会被动");
  }

}
