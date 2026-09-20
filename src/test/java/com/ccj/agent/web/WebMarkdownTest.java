package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 助手的 markdown 渲染器是浏览器代码——它住在 {@code web/app.js} 里，那是一个没有导出的经典
 * 脚本——所以它的用例没法直接写成 JUnit 断言。它们是一个 node 脚本
 * （{@code src/test/js/markdown.test.mjs}），把渲染器自己的源码从发布出去的文件里抠出来，对着
 * 一个小小的 DOM 运行。
 *
 * <p>这个类在有 node 的时候运行那个脚本，并且总是检查两条关于页面（而不是关于解析器）的性质：
 * 答案真的被渲染成 markdown，以及渲染器构建的是节点而不是 HTML 字符串。没有 node 的机器会跳过
 * 这个脚本，而不是失败——前端没有构建步骤，也没有要装的 npm 依赖，而一个跑不了的测试绝不能看
 * 起来像一个通过了的测试。
 */
class WebMarkdownTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "markdown.test.mjs");

  @Test
  void theRendererScriptPasses() throws IOException, InterruptedException {
    // 用共享的运行器，而不是它的第二份副本：被替换掉的那份在等待*之前*就把子进程的输出读到
    // 末尾，于是超时只是装饰——一个卡住的用例文件会拖住整个构建——而且它用打印一行再返回的方式
    // 来报告 node 缺失，而 JUnit 把这记成通过。现在两者都在一处修好了。
    WebSessionRowTest.runNodeCases(SCRIPT, "markdown 渲染器");
  }

  @Test
  void aCaseFileThatCannotBeRunIsSkippedRatherThanPassed() {
    // 这里钉住的 bug：更早的版本打印一行再返回，而 JUnit 把这记成一个绿色的测试——一台没有
    // node 的构建机器会显示每一个浏览器用例都通过，而它们一个都没跑。所以缺失的脚本必须让用例
    // 中止，而不是让它完成。
    //
    // 通过真正的入口点、用一个不可能存在的路径来断言，所以这检查的是运行器，而不是碰巧在跑它
    // 的那台机器。
    Path missing = Path.of("src", "test", "js", "this-file-does-not-exist.test.mjs");

    assertThrows(
        org.opentest4j.TestAbortedException.class,
        () -> WebSessionRowTest.runNodeCases(missing, "一个不存在的用例文件"));
  }

  @Test
  void theAnswerIsRenderedAsMarkdown() {
    String script = WebSessionRowTest.appSourceOrSkip();

    // 流式答案走 markdown 那条路，而循环在什么都没流式传输时发的那份兜底也走同一条——否则一个
    // 没有任何增量的回合，就会成为屏幕上唯一一个以纯文本渲染的答案。
    assertTrue(script.contains("queueMarkdown(assistantBlock()"), script);
    assertTrue(script.contains("queueMarkdown(block, finalText)"), script);
    assertTrue(script.contains("renderMarkdown(md, node)"), script);
    assertFalse(script.contains("assistantNode(block, 'text').appendChild"), script);
  }

  @Test
  void theRendererBuildsNodesAndNeverHtmlSource() {
    String script = WebSessionRowTest.appSourceOrSkip();
    int from = script.indexOf("---- markdown: parse");
    int to = script.indexOf("---- transcript");
    assertTrue(from > 0 && to > from, "markdown 这一段必须由它的横幅界定出来");
    String renderer = script.substring(from, to);

    // 页面的契约，在最容易被破坏的地方再说一遍：模型输出变成节点，所以答案里的标签没有 HTML
    // 源码可供被当成标记解析，而链接只有在它的协议被过滤之后才会被赋予 href。
    assertFalse(renderer.contains("innerHTML"), "渲染器绝不能构建 HTML 字符串");
    assertFalse(renderer.contains("insertAdjacentHTML"), renderer);
    assertFalse(renderer.contains("createContextualFragment"), renderer);
    assertFalse(renderer.contains("document.write"), renderer);
    assertTrue(renderer.contains("mdSafeUrl"), "链接目标必须被过滤");
  }


  private static String findNode() {
    for (String candidate : List.of("node", "nodejs")) {
      try {
        Process process = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
        if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
          return candidate;
        }
      } catch (IOException | InterruptedException err) {
        // 试下一个名字
      }
    }
    return null;
  }
}
