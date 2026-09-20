package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 「添加工作区」是浏览器代码——它住在 {@code web/app.js} 里，那是一个没有导出的经典脚本——所以
 * 它的用例是一个 node 脚本（{@code src/test/js/workspace-add.test.mjs}），把这一段从发布出去的
 * 文件里抠出来，对着一个小小的 node 桩运行，就像 {@link WebSessionRowTest} 跑那一行的用例那样。
 *
 * <p>它钉住的是那个手势：点一下就打开桌面的选择器，选中的文件夹以自己的名字被加进来。这个类还
 * 检查只有凑在一起才成立的两半——页面发出的请求和应答它的路由——因为一个发送
 * {@code {"path": ...}} 的页面，需要一个接受不带名字的路径的服务器。
 */
class WebWorkspaceAddTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "workspace-add.test.mjs");

  @Test
  void theAddWorkspaceScriptPasses() throws IOException, InterruptedException {
    WebSessionRowTest.runNodeCases(SCRIPT, "添加工作区");
  }

  @Test
  void theClickIsWiredToTheChooserAndTheRequestCarriesNoName() {
    String script = WebSessionRowTest.appSourceOrSkip();

    // 跟随 markdown 那批用例的做法：发布出去的脚本必须在那个触发器上跟选择器对话、并且提交一个
    // 路径，因为服务器正是据此推出名字的。
    assertTrue(script.contains("pickWorkspaceFolder"), "页面必须使用桌面的选择器");
    assertTrue(script.contains("addWorkspaceByPicking"), "触发器必须真的添加，而不只是往字段里填个值");

    int trigger = script.indexOf("dom.workspaceAdd.addEventListener");
    int submit = script.indexOf("dom.workspaceAddForm.addEventListener");
    assertTrue(trigger > 0 && submit > trigger, "触发器和表单都必须接好线");
    assertTrue(
        script.substring(trigger, submit).contains("addWorkspaceByPicking()"),
        "在触发器上点一下就调用选择器");

    // 而表单已经没有名字可输了：由文件夹来给工作区命名。
    String page = pageSource();
    if (page != null) {
      assertTrue(page.contains("id=\"ws-new-path\""), "路径字段作为兜底保留着");
      assertTrue(page.contains("id=\"workspace-pick\""), "表单仍然能调用选择器");
      assertTrue(!page.contains("ws-new-name"), "名字字段已经没了");
    }
  }


  private static String pageSource() {
    return read(Path.of("src", "main", "resources", "web", "index.html"));
  }

  private static String read(Path path) {
    try {
      return Files.exists(path) ? Files.readString(path) : null;
    } catch (IOException err) {
      return null;
    }
  }
}
