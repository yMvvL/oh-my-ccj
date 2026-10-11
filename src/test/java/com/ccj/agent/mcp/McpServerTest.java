package com.ccj.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Approver;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.ToolRegistry;
import com.ccj.agent.tool.RestartTool;
import com.ccj.agent.tool.Tools;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 这个协议的另一头：一个真的客户端开着 CCJ 自己的服务器，以及协议本身的边角。
 *
 * <p>客户端与服务器是同一个仓库里、同一份协议的两半，所以最值钱的一条用例就是让它们面对面跑一次：真的
 * 子进程、真的 stdin/stdout、真的握手。一个只对着手写字符串测过的协议实现，测的是它自己以为的形状——而这个
 * 形状是与别人共用的东西，它错了唯一能被发现的方式，是让另一半来说话。
 *
 * <p>边角（坏 JSON、未知方法、通知、没有对应请求的应答）在读代码时看不出对错，所以它们对着
 * {@link McpServer#handle} 逐条钉住，不必为每一条启动一个进程。
 */
class McpServerTest {

  @TempDir Path tmp;

  private Path home;
  private Path workspace;

  /** 一个工作目录，以及一个空的（可选的）应用主目录。 */
  private void prepare() throws Exception {
    home = Files.createDirectories(tmp.resolve("home"));
    workspace = Files.createDirectories(tmp.resolve("ws"));
  }

  // ------------------------------------------------------------------ 协议

  private McpServer server(Approver approver, Path cwd) {
    ToolRegistry exposed = new ToolRegistry();
    for (String name : Tools.standard().names()) {
      if (RestartTool.NAME.equals(name)) {
        continue;
      }
      Tools.standard().find(name).ifPresent(exposed::register);
    }
    return new McpServer(exposed, cwd, approver, 32 * 1024, "test");
  }

  @Test
  void theHandshakeSaysWhoThisIsAndWhatItSpeaks() {
    JsonNode answer =
        Json.parse(
            server(Approver.NEVER, tmp)
                .handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));

    assertEquals("2.0", answer.path("jsonrpc").asText());
    assertEquals(1, answer.path("id").asInt());
    assertEquals(McpServer.PROTOCOL_VERSION, answer.path("result").path("protocolVersion").asText());
    assertEquals("ccj", answer.path("result").path("serverInfo").path("name").asText());
    assertTrue(answer.path("result").path("capabilities").has("tools"), answer.toString());
  }

  @Test
  void aNotificationIsNeverAnswered() {
    McpServer server = server(Approver.NEVER, tmp);
    assertEquals(null, server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
    assertEquals(null, server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\"}"));
  }

  @Test
  void theToolListCarriesEachToolsOwnSchemaAndNeverOffersRestart() {
    JsonNode answer =
        Json.parse(server(Approver.NEVER, tmp).handle("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}"));

    List<String> names = new ArrayList<>();
    for (JsonNode tool : answer.path("result").path("tools")) {
      names.add(tool.path("name").asText());
      assertTrue(tool.path("description").asText().length() > 10, tool.toString());
      assertEquals(
          "object",
          tool.path("inputSchema").path("type").asText(),
          "每个工具的 schema 都是一张 JSON Schema 对象：" + tool);
    }
    assertTrue(names.contains("read"), names.toString());
    assertFalse(names.contains(RestartTool.NAME), "restart 会结束这个进程，它不能出现在这里：" + names);
  }

  @Test
  void anUnknownMethodIsAProtocolErrorAndAnUnknownToolIsAToolError() {
    McpServer server = server(Approver.NEVER, tmp);

    JsonNode unknownMethod =
        Json.parse(server.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\"}"));
    assertEquals(-32601, unknownMethod.path("error").path("code").asInt(), unknownMethod.toString());

    JsonNode unknownTool =
        Json.parse(
            server.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"summon-a-dragon\"}}"));
    assertTrue(unknownTool.path("result").path("isError").asBoolean(), unknownTool.toString());
    assertTrue(unknownTool.path("result").path("content").get(0).path("text").asText().contains("未知工具"));
  }

  @Test
  void aLineThatIsNotJsonComesBackAsAParseError() {
    JsonNode answer = Json.parse(server(Approver.NEVER, tmp).handle("not json at all"));
    assertEquals(-32700, answer.path("error").path("code").asInt(), answer.toString());
    assertTrue(answer.path("id").isNull(), "一行读不出来的东西没有 id 可指：" + answer);
  }

  @Test
  void aRefusedToolIsReportedAsAToolErrorRatherThanAProtocolError() {
    // 拒绝是调用方的模型要读到的内容（「被用户拒绝」），而不是一次协议失败——后者会让它以为客户端坏了。
    JsonNode answer =
        Json.parse(
            server(Approver.NEVER, tmp)
                .handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"bash\",\"arguments\":{\"command\":\"echo hi\"}}}"));
    assertTrue(answer.path("result").path("isError").asBoolean(), answer.toString());
    assertTrue(answer.path("result").path("content").get(0).path("text").asText().contains("拒绝"));
  }

  @Test
  void theServerReadsUntilTheInputEnds() throws Exception {
    prepare();
    Files.writeString(workspace.resolve("notes.txt"), "the file\n");
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    String lines =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n"
            + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"read\",\"arguments\":{\"path\":\"notes.txt\"}}}\n";

    int code =
        server(Approver.NEVER, workspace)
            .serve(
                new BufferedReader(new StringReader(lines)),
                stdout,
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

    assertEquals(
        0, code, "输入结束就是这个客户端走了，而不是一次失败：" + stderr.toString(StandardCharsets.UTF_8));
    List<String> answers = stdout.toString(StandardCharsets.UTF_8).lines().toList();
    assertEquals(2, answers.size(), "两条请求两条应答，通知不占行：" + answers);
    assertTrue(answers.get(1).contains("the file"), answers.get(1));
  }

  // ------------------------------------------------------------------ 真的客户端，真的子进程

  @Test
  void theRealClientDrivesTheRealServerOverStdio() throws Exception {
    prepare();
    Files.writeString(workspace.resolve("notes.txt"), "the file\n");

    try (McpClient client = McpClient.start(subprocess(home, workspace, "--yolo"))) {
      // 表里的名字是给**这个**代理看的（`mcp__<服务器>__<工具>`），而线路上那个名字就是服务器自己的名字：
      // 前缀是本程序为了方便写审批规则而加的，服务器不认识它。
      List<String> names = client.tools().stream().map(McpTool::name).sorted().toList();
      assertEquals(
          List.of(
              "mcp__ccj__bash",
              "mcp__ccj__edit",
              "mcp__ccj__fetch",
              "mcp__ccj__glob",
              "mcp__ccj__grep",
              "mcp__ccj__read",
              "mcp__ccj__write"),
          names);

      String text = client.callTool("read", "{\"path\":\"notes.txt\"}");
      assertTrue(text.contains("the file"), text);

      // 写下去的字节真的落到了磁盘上，而且落在 -C 点名的那个目录里——这条链的两头都是真的。
      client.callTool("write", "{\"path\":\"proof.txt\",\"content\":\"from the other agent\\n\"}");
      assertEquals("from the other agent\n", Files.readString(workspace.resolve("proof.txt")));

      // 参数坏掉是一条工具错误，调用方读得到它。
      McpClient.McpToolFailure refused =
          assertThrows(
              McpClient.McpToolFailure.class,
              () -> client.callTool("read", "{\"path\":\"nowhere.txt\"}"));
      assertTrue(refused.getMessage().contains("nowhere.txt"), refused.getMessage());
    }
  }

  @Test
  void withoutApprovalRulesAWriteIsRefusedAndNothingIsWritten() throws Exception {
    prepare();

    try (McpClient client = McpClient.start(subprocess(home, workspace))) {
      McpClient.McpToolFailure refused =
          assertThrows(
              McpClient.McpToolFailure.class,
              () -> client.callTool("write", "{\"path\":\"proof.txt\",\"content\":\"x\\n\"}"));
      assertTrue(refused.getMessage().contains("拒绝"), refused.getMessage());
      assertFalse(Files.exists(workspace.resolve("proof.txt")), "被拒绝的写入什么都不该留下");
    }
  }

  @Test
  void aRuleInTheApprovalFileIsWhatLetsItThrough() throws Exception {
    prepare();
    // 同一个文件、同一套规则、同一个主语——只不过这一次它是由用户写下来的，而不是由一次点击记下来的。
    Files.writeString(
        home.resolve("approvals.json"),
        "{\"projects\": {\""
            + workspace.toAbsolutePath().normalize()
            + "\": {\"allow\": [{\"tool\": \"write\", \"path\": \"**\"}]}}}");

    try (McpClient client = McpClient.start(subprocess(home, workspace))) {
      client.callTool("write", "{\"path\":\"allowed.txt\",\"content\":\"by the rule\\n\"}");
      assertEquals("by the rule\n", Files.readString(workspace.resolve("allowed.txt")));
    }
  }

  /**
   * 一个真的 CCJ，作为一个 MCP 服务器被启动。
   *
   * <p>用的是本进程自己的类路径，所以它跑的就是刚编译出来的那些类——与用户在命令行上敲的那个入口是同一个
   * {@code Main}。
   */
  private static McpConfig.Server subprocess(Path home, Path workspace, String... extra) {
    List<String> args = new ArrayList<>();
    args.add("-cp");
    args.add(System.getProperty("java.class.path"));
    args.add("com.ccj.agent.cli.Main");
    args.add("--mcp-serve");
    args.add("--home");
    args.add(home.toString());
    args.add("-C");
    args.add(workspace.toString());
    args.addAll(List.of(extra));
    return new McpConfig.Server(
        "ccj",
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        args,
        Map.of("CCJ_HOME", home.toString()));
  }
}
