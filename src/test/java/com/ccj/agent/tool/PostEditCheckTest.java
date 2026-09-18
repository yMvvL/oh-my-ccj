package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.ApprovalAnswer;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.Checks;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 编辑之后自行运行的检查，通过真正的 `edit` 与 `write` 工具驱动。
 *
 * <p>被测的是编译器得以开口的那个时刻：与编辑同一步，或者根本不开口。这里其余的一切——用哪条命令、
 * 多少输出——存在都是为了让那个时刻便宜到可以拥有。
 */
class PostEditCheckTest {

  @TempDir Path tmp;

  private Path config;
  private Path cwd;

  private void givenConfig(String json) throws IOException {
    config = tmp.resolve("config.json");
    Files.writeString(config, json);
    cwd = Files.createDirectories(tmp.resolve("project"));
  }

  private ToolContext context() {
    return new ToolContext(cwd, request -> ApprovalAnswer.ALLOW_ONCE, 32 * 1024);
  }

  private static String editArgs(String path, String from, String to) {
    ObjectNode args = Json.object();
    args.put("path", path);
    args.put("old_string", from);
    args.put("new_string", to);
    return Json.write(args);
  }

  private String edit(String path, String from, String to) throws Exception {
    return new EditTool(Checks.from(config)).execute(editArgs(path, from, to), context()).content();
  }

  @Test
  void aBrokenEditComesBackWithWhatTheCheckSaid() throws Exception {
    // 全部要点就在这里：模型在同一步里被告知它弄坏了什么，于是修复就是它接下来做的事，而不是它得自己
    // 决定去找的东西。
    givenConfig(
        """
        {"checks": [{"glob": "**/*.java", "command": "echo 'Foo.java:3: error: cannot find symbol'; exit 1"}]}
        """);
    Path file = cwd.resolve("Foo.java");
    Files.writeString(file, "class Foo {}\n");

    String result = edit("Foo.java", "class Foo {}", "class Foo { int x = missing; }");

    assertTrue(result.startsWith("在 Foo.java 中替换了 1 处"), result);
    assertTrue(result.contains("[check] echo 'Foo.java:3: error: cannot find symbol'; exit 1"), result);
    assertTrue(result.contains("cannot find symbol"), result);
    assertTrue(result.contains("exit code 1"), result);
    assertTrue(result.contains("继续之前先修好它"), result);
  }

  @Test
  void aCheckThatPassesSaysSoInOneLine() throws Exception {
    givenConfig("{\"checks\": [{\"glob\": \"**/*.java\", \"command\": \"true\"}]}");
    Files.writeString(cwd.resolve("Foo.java"), "class Foo {}\n");

    String result = edit("Foo.java", "class Foo {}", "class Foo { int x; }");

    assertTrue(result.contains("[check] true — exit 0 ("), result);
    assertTrue(result.lines().count() <= 3, "通过的检查是一行，不是一份日志: " + result);
  }

  @Test
  void nothingRunsForAFileNoCheckIsAbout() throws Exception {
    givenConfig("{\"checks\": [{\"glob\": \"**/*.java\", \"command\": \"echo ran\"}]}");
    Files.writeString(cwd.resolve("notes.md"), "old\n");

    String result = edit("notes.md", "old", "new");

    assertFalse(result.contains("[check]"), result);
    assertFalse(result.contains("ran"), result);
  }

  @Test
  void aFailedEditStartsNothing() throws Exception {
    // 命令在确实发生过的写入之后运行。什么都没匹配上的编辑什么都没改，而为一件从未发生的改动去构建
    // 整个项目，是没人需要读的活儿。
    givenConfig("{\"checks\": [{\"glob\": \"**\", \"command\": \"echo should-not-run > marker.txt\"}]}");
    Files.writeString(cwd.resolve("Foo.java"), "class Foo {}\n");

    String result = edit("Foo.java", "not in the file", "whatever");

    assertTrue(result.startsWith("未写入任何内容：该编辑未找到精确匹配"), result);
    assertFalse(Files.exists(cwd.resolve("marker.txt")), "检查在一次失败的编辑上运行了");
  }

  @Test
  void writeRunsTheCheckToo() throws Exception {
    givenConfig("{\"checks\": [{\"glob\": \"**/*.txt\", \"command\": \"echo checked $PWD\"}]}");

    ObjectNode args = Json.object();
    args.put("path", "notes.txt");
    args.put("content", "hello\n");
    ToolResult result =
        new WriteTool(Checks.from(config)).execute(Json.write(args), context());

    assertTrue(result.content().contains("[check] echo checked $PWD — exit 0 ("), result.content());
  }

  @Test
  void theCheckRunsWhereTheSessionRunsAndNowhereElse() throws Exception {
    // 命令是用户的，但路径是模型选的。检查绝不能变成一种让构建在别人目录里运行的手段：工作目录是
    // 会话的。
    givenConfig("{\"checks\": [{\"glob\": \"**\", \"command\": \"pwd\"}]}");
    Path outside = Files.createDirectories(tmp.resolve("elsewhere"));
    Files.writeString(outside.resolve("Foo.java"), "class Foo {}\n");

    String result =
        new EditTool(Checks.from(config))
            .execute(editArgs(outside.resolve("Foo.java").toString(), "class Foo {}", "class Foo { }"), context())
            .content();

    assertFalse(result.contains("[check]"), "会话工作区之外的文件什么都不会运行: " + result);
  }

  @Test
  void aCheckThatHangsIsKilledAtItsTimeout() throws Exception {
    givenConfig("{\"checks\": [{\"glob\": \"**\", \"command\": \"sleep 30\", \"timeoutSeconds\": 1}]}");
    Files.writeString(cwd.resolve("Foo.java"), "class Foo {}\n");

    long started = System.nanoTime();
    String result = edit("Foo.java", "class Foo {}", "class Foo { int x; }");
    long seconds = (System.nanoTime() - started) / 1_000_000_000L;

    assertTrue(result.contains("运行 1s 后超时"), result);
    assertTrue(seconds < 10, "截止时间必须兜住这个回合，而它花了 " + seconds + "s");
  }

  @Test
  void aMalformedChecksBlockIsSaidOutLoud() throws Exception {
    givenConfig("{\"checks\": \"mvn compile\"}");
    Files.writeString(cwd.resolve("Foo.java"), "class Foo {}\n");

    String result = edit("Foo.java", "class Foo {}", "class Foo { int x; }");

    assertTrue(result.contains("[check] 配置的检查读不出来"), result);
    assertTrue(result.contains("checks"), result);
  }

  @Test
  void aChattyCheckIsReportedBounded() throws Exception {
    // 结果是一段用户此后每个回合都要付费的提示，所以构建日志是被封顶的，而不是整段贴出来：头、尾，
    // 以及被略去部分的精确计数。
    givenConfig(
        "{\"checks\": [{\"glob\": \"**\", \"command\": \"yes 'noise from the compiler' | head -2000; exit 1\"}]}");
    Files.writeString(cwd.resolve("Foo.java"), "class Foo {}\n");

    String result = edit("Foo.java", "class Foo {}", "class Foo { int x; }");

    assertTrue(result.contains("省略了"), result);
    assertTrue(result.length() < 20_000, "报告必须保持很小，实际是 " + result.length());
  }

  @Test
  void withNothingConfiguredTheToolsBehaveExactlyAsBefore() throws Exception {
    givenConfig("{\"provider\": \"openai\"}");
    Files.writeString(cwd.resolve("Foo.java"), "class Foo {}\n");

    String result = edit("Foo.java", "class Foo {}", "class Foo { int x; }");

    assertEquals(
        "在 Foo.java 中替换了 1 处；文件现在有 1 行",
        result,
        "没有配置文件、没有检查，结果与模型早已知道如何读取的完全一样");
  }

  @Test
  void theEditToolStillWorksWithNoChecksAtAll() throws Exception {
    // 每个既有的调用方都用无参方式构造这个工具，默认值必须保持原有的行为，而不是变成一个错误。
    Files.writeString(tmp.resolve("Loose.java"), "class Loose {}\n");
    ToolContext direct = new ToolContext(tmp, Approver.ALWAYS, 4096);

    String result =
        new EditTool().execute(editArgs("Loose.java", "class Loose {}", "class Loose { }"), direct).content();

    assertTrue(result.startsWith("在 Loose.java 中替换了 1 处"), result);
  }
}
