package com.ccj.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.ApprovalAnswer;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MCP 客户端，针对一个按产品方式派生的真实服务器进程来测试。
 *
 * <p>这里有意思的失败，全都关于另一端是另一个程序：讲旧修订版的、一直打日志直到管道写满的、
 * 在一次调用途中死掉的、从不回答的。每一种都是一个测试而不是一段文字，因为每一种都是「带上你
 * 自己的工具」把代理卡住的方式。
 */
class McpClientTest {

  @TempDir Path dir;

  /** 那个 fixture，像真实服务器一样从测试 classpath 里跑起来。 */
  private static McpConfig.Server fixture(String name, String... args) {
    List<String> argv = new java.util.ArrayList<>(List.of(args));
    List<String> all = new java.util.ArrayList<>();
    all.add("com.ccj.agent.mcp.FixtureMcpServer");
    all.addAll(argv);
    return new McpConfig.Server(
        name, System.getProperty("java.home") + "/bin/java",
        concat(List.of("-cp", System.getProperty("java.class.path")), all), Map.of());
  }

  private static List<String> concat(List<String> first, List<String> second) {
    List<String> all = new java.util.ArrayList<>(first);
    all.addAll(second);
    return all;
  }

  @Test
  void aServerIsAskedWhatItOffersAndTheSchemasComeThrough() {
    try (McpClient client = McpClient.start(fixture("fixture"))) {
      List<McpTool> tools = client.tools();

      assertEquals(List.of("echo", "always_fails"), tools.stream().map(McpTool::name).map(
          name -> name.replace("mcp__fixture__", "")).toList());
      McpTool echo = tools.get(0);
      assertEquals("mcp__fixture__echo", echo.name());
      assertTrue(echo.description().contains("Echo back"), echo.description());
      assertTrue(echo.description().contains("MCP server 'fixture'"),
          "工具会说明自己来自哪里：" + echo.description());
      assertTrue(echo.parametersJson().contains("\"text\""), echo.parametersJson());
    }
  }

  @Test
  void aCallReturnsWhatTheServerSaid() {
    try (McpClient client = McpClient.start(fixture("fixture"))) {
      assertEquals("echo: hello", client.callTool("echo", "{\"text\":\"hello\"}"));
    }
  }

  @Test
  void aToolThatReportsFailureIsAFailureWithItsMessage() {
    try (McpClient client = McpClient.start(fixture("fixture"))) {
      McpClient.McpToolFailure failure =
          assertThrows(
              McpClient.McpToolFailure.class, () -> client.callTool("always_fails", "{}"));

      assertTrue(failure.getMessage().contains("the fixture was asked to fail"), failure.getMessage());
    }
  }

  @Test
  void aServerThatOnlySpeaksTheOlderRevisionIsAskedAgain() {
    // 协议只允许的那一次重试，以及它值得拥有的理由：落后一个修订版的服务器是一台能用的
    // 服务器，拒绝和它说话就是伪装成严格的客户端 bug。
    try (McpClient client = McpClient.start(fixture("old", "old-protocol"))) {
      assertEquals("echo: hi", client.callTool("echo", "{\"text\":\"hi\"}"));
    }
  }

  @Test
  void aServerThatLogsUntilItsPipeFillsDoesNotWedgeTheClient() {
    // stderr 在自己的线程上被抽干。没有它，服务器会阻塞在写满的管道上、永不回答——而那失败
    // 看起来就是一次没什么可读的卡死。
    try (McpClient client = McpClient.start(fixture("noisy", "noisy"))) {
      assertEquals("echo: still here", client.callTool("echo", "{\"text\":\"still here\"}"));
    }
  }

  @Test
  void aServerThatNeverAnswersIsReportedRatherThanWaitedFor() {
    try (McpClient client = McpClient.start(fixture("slow"), 500)) {
      McpClient.McpToolFailure failure =
          assertThrows(McpClient.McpToolFailure.class, () -> client.callTool("slow", "{}"));

      assertTrue(failure.getMessage().contains("没有回应"), failure.getMessage());
      assertTrue(failure.getMessage().contains("'slow'"), "它会点名服务器：" + failure.getMessage());
      assertTrue(failure.getMessage().contains("tools/call"), failure.getMessage());
      assertTrue(failure.getMessage().contains("500ms"), failure.getMessage());
    }
  }

  @Test
  void aServerThatIsGoneIsReportedWithTheServerName() throws IOException {
    McpClient client = McpClient.start(fixture("fixture"));
    client.close();

    McpClient.McpToolFailure failure =
        assertThrows(McpClient.McpToolFailure.class, () -> client.callTool("echo", "{\"text\":\"x\"}"));

    assertTrue(failure.getMessage().contains("已经关闭")
        || failure.getMessage().contains("不再运行"), failure.getMessage());
  }

  @Test
  void aServerThatExitsDuringTheHandshakeNamesItselfAndWhy() {
    // 一条根本不是服务器的命令——配置文件里最容易写错的那个条目。
    McpConfig.Server broken =
        new McpConfig.Server("broken", "/bin/sh", List.of("-c", "echo 'not an MCP server' >&2; exit 3"), Map.of());

    RuntimeException failure = assertThrows(RuntimeException.class, () -> McpClient.start(broken));

    assertTrue(failure.getMessage().contains("broken"), failure.getMessage());
  }

  @Test
  void everyCallGoesThroughTheApproverAndTheToolNameIsTheSubject() {
    McpTools tools = McpTools.discover(null);
    assertTrue(tools.isEmpty(), "没有文件、没有服务器，就没有可启动的东西");

    McpConfig.Server server = fixture("fixture");
    McpTool echo = new McpTool(server, "echo", "Echo back.", "{}");
    McpTool.Clients clients = s -> McpClient.start(s);
    McpTool bound = echo.boundTo(clients);

    java.util.List<com.ccj.agent.core.ApprovalRequest> asked = new java.util.ArrayList<>();
    ToolResult denied =
        bound.execute(
            "{\"text\":\"x\"}",
            new ToolContext(
                dir,
                request -> {
                  asked.add(request);
                  return ApprovalAnswer.DENY;
                },
                4096));

    assertTrue(denied.error(), denied.content());
    assertEquals("mcp__fixture__echo", asked.get(0).tool(), "规则就是靠这个名字匹配的");
    assertTrue(denied.content().contains("被用户拒绝"), denied.content());
  }

  @Test
  void aConfiguredServerIsWrittenDownRatherThanAssumed() throws IOException {
    // 文件每次运行都会被读取，它写什么就启动什么。实测过：名字里带 '__' 会让工具名产生歧义，
    // 所以它被拒绝，而不是产出没人能据以立规则的名字。
    Path file = dir.resolve("mcp.json");
    Files.writeString(
        file,
        """
        {"servers": [{"name": "fs", "command": "npx", "args": ["-y", "server-fs", "/tmp"],
                      "env": {"TOKEN": "abc"}}]}
        """);

    McpConfig config = McpConfig.from(file);

    assertEquals(1, config.servers().size());
    assertEquals("fs", config.servers().get(0).name());
    assertEquals(List.of("-y", "server-fs", "/tmp"), config.servers().get(0).args());
    assertEquals("abc", config.servers().get(0).env().get("TOKEN"));
    assertEquals("mcp__fs__", config.servers().get(0).toolPrefix());

    Files.writeString(file, "{\"servers\": [{\"name\": \"a__b\", \"command\": \"x\"}]}");
    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> McpConfig.from(file));
    assertTrue(refused.getMessage().contains("不能作为服务器名"), refused.getMessage());

    Files.writeString(file, "{\"servers\": [{\"name\": \"fs\"}]}");
    IllegalArgumentException noCommand =
        assertThrows(IllegalArgumentException.class, () -> McpConfig.from(file));
    assertTrue(noCommand.getMessage().contains("command"), noCommand.getMessage());

    assertFalse(McpConfig.from(dir.resolve("missing.json")).servers().size() > 0);
  }
}
