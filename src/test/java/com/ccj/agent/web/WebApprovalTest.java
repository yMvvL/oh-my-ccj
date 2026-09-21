package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 未决的审批是浏览器代码——它住在 {@code web/app.js} 里，那是一个没有导出的经典脚本——所以它的
 * 用例是一个 node 脚本（{@code src/test/js/approval.test.mjs}），把拥有它的那两个函数从发布出去
 * 的文件里抠出来，对着一个小小的 node 桩运行，就像 {@link WebSessionRowTest} 跑会话行的用例那样。
 *
 * <p>它钉住的是这个类存在的理由：一个等待审批的回合，在用户一看别的对话时就把提示丢了，只剩
 * 中止这一条出路。这个请求是阻塞在服务器内存里的，而不是写进对话的，所以回放没法把它恢复——
 * 由状态携带它，页面再把它画出来。这个类在有 node 的时候运行那个脚本，并且总是在发布出去的源码
 * 上检查这份契约的两端：服务器报告未决请求时带上提示所需的一切，而页面读取那个字段。
 */
class WebApprovalTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "approval.test.mjs");
  private static final Path APP = Path.of("src", "main", "resources", "web", "app.js");

  @Test
  void theApprovalScriptPasses() throws IOException, InterruptedException {
    WebSessionRowTest.runNodeCases(SCRIPT, "尚未作答的审批");
  }

  @Test
  void theStatusCarriesEverythingAPromptNeeds() throws IOException {
    String hub = hubSource();

    // Pending 跟着审批台搬到了 ApprovalDesk.java：那张桌子就在那里，而记录着「谁在问、问什么」的也是
    // 它，所以这条主张的家跟着它走。它是包内可见的，因为状态快照在隔壁——列出屏幕上这个对话正在等什么，
    // 正是隔壁那件事。
    String desk = deskSource();
    assertTrue(
        desk.contains("record Pending("),
        "审批必须记住自己在问什么，而不只是它等待的那个 future：\n" + desk);
    assertTrue(
        hub.contains("pending.title()") && hub.contains("pending.detail()"),
        "而且状态会报告它，否则一个看向别处的页面就没法把提示再画出来：\n" + hub);
    assertTrue(
        hub.contains("node.putArray(\"approvals\")"),
        "放在它自己的字段底下，这样一个对话的请求不会被提供给另一个");
    // 只有正在等待的那个对话才看得到自己的请求。
    assertTrue(
        hub.contains("if (!pending.sessionId().equals(shownId))"),
        "按会话过滤：作答屏幕上的提示绝不能解决掉另一个会话的");
  }

  @Test
  void thePageRebuildsAPromptFromTheStatus() throws IOException {
    String script = WebSessionRowTest.appSourceOrSkip();

    assertTrue(script.contains("function renderApproval("),
        "提示由一个函数画出，这样实时事件和状态会用同样的方式重建它");
    assertTrue(script.contains("function syncApprovals("),
        "而一个状态会把仍然未决的东西带回来");
    assertTrue(script.contains("syncApprovals(status.approvals)"),
        "这正是页面拿着服务器报告的东西去调用的那个");
    // 把一个提示画进一份即将被重建的转录，正是这里要修的那种丢失，所以重建要等回放跑完。
    assertTrue(script.contains("state.pendingApprovalsSync"),
        "回放中途报告出来的请求会被扣住，直到转录不再被重建");
    assertTrue(script.contains("已不再等待"),
        "而服务器已经不再知道的请求会被关掉，而不是留在那儿无从作答");
  }


  private static String hubSource() {
    return source(Path.of("src", "main", "java", "com", "ccj", "agent", "web", "AgentHub.java"));
  }

  /** 审批台现在住在这里；{@code Pending} 那条主张读的就是它。 */
  private static String deskSource() {
    return source(Path.of("src", "main", "java", "com", "ccj", "agent", "web", "ApprovalDesk.java"));
  }

  private static String source(Path file) {
    try {
      return Files.exists(file) ? Files.readString(file) : "";
    } catch (IOException err) {
      return "";
    }
  }
}
