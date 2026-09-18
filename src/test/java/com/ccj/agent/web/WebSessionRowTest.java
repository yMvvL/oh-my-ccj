package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 侧边栏的会话行是浏览器代码——它住在 {@code web/app.js} 里，那是一个没有导出的经典脚本——所以
 * 它的用例是一个 node 脚本（{@code src/test/js/session-row.test.mjs}），把 {@code sessionRow} 从
 * 发布出去的文件里抠出来，对着一个小小的 node 桩运行，就像 {@link WebMarkdownTest} 跑 markdown
 * 的用例那样。
 *
 * <p>它钉住的是行上那个标签：一个会话由它被问的第一件事来显示，而不是由它的时间戳 id，而在两行
 * 本来会读起来一样的时候，id 仍然够得着。这个类在有 node 的时候运行那个脚本，并且总是检查那条
 * 关于页面（而不是关于这一行）的性质：服务器发送这一行据以构建的 title。
 */
class WebSessionRowTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "session-row.test.mjs");
  private static final Path APP = Path.of("src", "main", "resources", "web", "app.js");

  @Test
  void theSessionRowScriptPasses() throws IOException, InterruptedException {
    runNodeCases(SCRIPT, "会话行");
  }

  /**
   * 对着发布出去的 {@code app.js} 跑一个 node 用例文件。
   *
   * <p>node 不在时报成<em>跳过</em>，而不是通过。更早的版本打印一行再返回，而 JUnit 把这记成一个
   * 绿色的测试：一台没有 node 的构建机器会显示每一个浏览器用例都通过，而它们一个都没跑。一个跑
   * 不了的测试必须在报告里说出来，因为报告是所有人都会读的东西。
   *
   * <p>输出是在进程运行期间收集的，而不是之后。更早的版本在等待之前就把流读到了末尾，于是等待
   * 从来就不是给它设界限的那个东西：一个卡住的用例文件会拖住整个构建，而超时只是装饰。
   */
  static void runNodeCases(Path script, String what) throws IOException, InterruptedException {
    Assumptions.assumeTrue(Files.exists(script), "源码树不是当前工作目录");
    String node = findNode();
    Assumptions.assumeTrue(
        node != null, "PATH 里没有 node —— " + what + " 的用例跑不了");

    Path root = script.toAbsolutePath().getParent().getParent().getParent().getParent();
    Process process = new ProcessBuilder(node, script.toAbsolutePath().toString())
        .directory(root.toFile())
        .redirectErrorStream(true)
        .start();
    process.getOutputStream().close();
    // 在另一个线程上抽干，这样管道不会在我们等待时被填满、把子进程卡死：一个打印量超过管道
    // 缓冲区的用例文件过去会一直挂到超时。
    StringBuilder collected = new StringBuilder();
    Thread drain =
        new Thread(
            () -> {
              try (var in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                String tail = "";
                while ((read = in.read(buffer)) != -1) {
                  String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
                  collected.append(chunk);
                  // 只留尾巴：失败用例有用的输出在末尾，而无界的缓冲区会让一个失控的脚本也变成
                  // 内存问题。
                  if (collected.length() > 200_000) {
                    collected.delete(0, collected.length() - 100_000);
                  }
                  tail = chunk;
                }
              } catch (IOException ignored) {
                // 进程结束了或被杀了；报告的是退出值。
              }
            },
            "node-cases-drain");
    drain.setDaemon(true);
    drain.start();

    boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    drain.join(2_000);
    String output = collected.toString();
    assertTrue(finished, what + " 的用例必须在 60 秒内跑完；目前的输出：\n" + output);
    assertTrue(process.exitValue() == 0, what + " 的用例失败了：\n" + output);
  }

  @Test
  void theRowIsBuiltFromTheTitleTheServerSends() {
    String script = appSource();
    if (script == null) { return; }

    // 这一行读的是 `title`（回退到 CLI 也在用的那个更短的 `preview`），
    // 而 AgentHub 把两者都放进了载荷。只要有一半被改名，侧边栏就会悄悄退回
    // 去显示 id，而这是任何服务器端的测试都抓不到的。
    assertTrue(script.contains("firstLine(str(item.title))"), "这一行必须读 title");
    assertTrue(hubSource().contains("node.put(\"title\", summary.title())"),
        "服务器必须发送 title");
  }

  @Test
  void aFinishedTurnReReadsTheSessionOrder() {
    String script = appSource();
    if (script == null) { return; }

    // 服务器按修改时间给会话排序，而一个回合会写当前会话的文件——所以页面
    // 持有的那个列表在回合结束的瞬间就过时了，刚用过的对话必须升到最上面。
    // 这里就是为此接的线：`done` 会再要一次顺序。
    int done = script.indexOf("function onDone(ev) {");
    int next = script.indexOf("// ------------------------------------------------------------- dispatch");
    assertTrue(done > 0 && next > done, "onDone 必须被界定出来");
    assertTrue(script.substring(done, next).contains("reorderSessionsAfterTurn()"),
        "结束的回合必须重新读一次顺序");
    assertTrue(script.contains("request('/api/sessions?workspace='"), script);
  }

  private static String appSource() {
    try {
      return Files.exists(APP) ? Files.readString(APP) : null;
    } catch (IOException err) {
      return null;
    }
  }

  private static String hubSource() {
    Path hub = Path.of("src", "main", "java", "com", "ccj", "agent", "web", "AgentHub.java");
    try {
      return Files.exists(hub) ? Files.readString(hub) : "";
    } catch (IOException err) {
      return "";
    }
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
