package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Approver;
import com.ccj.agent.core.Checks;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolRegistry;
import com.ccj.agent.core.ToolResult;
import com.ccj.agent.core.ToolSpec;
import com.ccj.agent.session.CheckpointStore;
import com.ccj.agent.ui.ToolSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolsTest {

  @TempDir Path dir;

  @Test
  void registersTheStandardToolsInAdvertisedOrder() {
    assertEquals(
        List.of("read", "write", "edit", "bash", "glob", "grep", "fetch", "restart"),
        Tools.standard().names());
  }

  @Test
  void everyToolAdvertisesANamedObjectSchema() {
    for (ToolSpec spec : Tools.standard().specs()) {
      assertFalse(spec.description().isBlank(), spec.name());
      assertEquals("object", Json.parse(spec.parametersJson()).path("type").asText(), spec.name());
      assertTrue(Json.parse(spec.parametersJson()).has("properties"), spec.name());
    }
  }

  @Test
  void registryTurnsBadArgumentsIntoErrorResults() {
    ToolRegistry registry = Tools.standard();

    ToolResult missing = registry.execute(new Message.ToolCall("1", "read", "{}"), ToolContext.of(dir));
    ToolResult malformed =
        registry.execute(new Message.ToolCall("2", "glob", "{\"pattern\":"), ToolContext.of(dir));
    ToolResult unknown =
        registry.execute(new Message.ToolCall("3", "nope", "{}"), ToolContext.of(dir));

    assertTrue(missing.error(), missing.content());
    assertTrue(missing.content().contains("缺少必需参数 'path'"), missing.content());
    assertTrue(malformed.error(), malformed.content());
    assertTrue(malformed.content().contains("JSON 无效"), malformed.content());
    assertTrue(unknown.error(), unknown.content());
    assertTrue(unknown.content().contains("未知工具 'nope'"), unknown.content());
  }

  @Test
  void theConfiguredShellReachesBothPlacesThatRunAnything() throws Exception {
    // 三参形式存在的唯一理由，就是一次装配把同一个程序交给会跑命令的两处。两边各点一次名，因为接到一半
    // 的线看起来完全正常——直到有人在一台没装 bash 的机器上打开它。
    Path shell = dir.resolve("fake-shell");
    Files.writeString(shell, "#!/bin/sh\nprintf '%s|' \"$@\"\nexit 7\n");
    Files.setPosixFilePermissions(shell, PosixFilePermissions.fromString("rwxr-xr-x"));
    Path config = dir.resolve("config.json");
    Files.writeString(config, "{\"checks\": [{\"glob\": \"**/*.java\", \"command\": \"true\"}]}");
    Files.writeString(dir.resolve("Foo.java"), "class Foo {}\n");

    ToolRegistry registry =
        Tools.standard(Checks.from(config), CheckpointStore.none(), shell.toString());
    ToolContext ctx = new ToolContext(dir, Approver.ALWAYS, 32 * 1024);

    ToolResult bash =
        registry.execute(new Message.ToolCall("1", "bash", "{\"command\":\"echo hi\"}"), ctx);
    ToolResult edit =
        registry.execute(
            new Message.ToolCall(
                "2",
                "edit",
                "{\"path\":\"Foo.java\",\"old_string\":\"class Foo {}\","
                    + "\"new_string\":\"class Foo { }\"}"),
            ctx);

    assertTrue(bash.content().contains("-lc|echo hi|"), bash.content());
    // 检查命令是 `true`：在默认的 bash 下它退出 0，所以这里出现的 7 只可能来自配置里的那个程序。
    assertTrue(edit.content().contains("-lc|true|"), edit.content());
    assertTrue(edit.content().contains("exit code 7"), edit.content());
  }

  @Test
  void aDelegationCardShowsTheRoleAndTheFirstLineOfTheTask() {
    // 那串参数 JSON 看不出被派出去的是谁，读起来却是任务本身的一整段。终端和网页共用这一行摘要，所以修在
    // 这里，两边的卡片一起变好。
    Message.ToolCall call =
        new Message.ToolCall(
            "1",
            "task",
            "{\"role\":\"explore\",\"task\":\"Find the parser.\\nThen check the lexer.\"}");

    String summary = ToolSummary.summarise(call);

    assertEquals("explore: Find the parser.", summary, "角色加上任务的首行");
    assertFalse(summary.contains("\"role\""), "不再是原始参数 JSON：" + summary);

    // 卡片仍然是一行：任务再长也是裁掉，而不是绕成好几行。
    Message.ToolCall longTask =
        new Message.ToolCall(
            "2", "task", "{\"role\":\"build\",\"task\":\"" + "x".repeat(200) + "\"}");
    String clipped = ToolSummary.summarise(longTask);
    assertTrue(clipped.startsWith("build: x"), clipped);
    assertTrue(clipped.length() <= ToolSummary.WIDTH, "被裁到一行的宽度：" + clipped.length());
  }
}
