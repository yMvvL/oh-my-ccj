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
 * The MCP client, against a real server process spawned the way the product spawns one.
 *
 * <p>The interesting failures here are all about the other end being a separate program: one that
 * speaks an older revision, one that logs until its pipe fills, one that dies while a call is in
 * flight, one that never answers. Each is a test rather than a paragraph because each is a way for
 * "bring your own tools" to hang the agent.
 */
class McpClientTest {

  @TempDir Path dir;

  /** The fixture, run out of the test classpath exactly as a real server is run. */
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
          "the tool says where it came from: " + echo.description());
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
    // The one retry the protocol allows, and the reason it is worth having: a server one revision
    // behind is a server that works, and refusing to speak to it would be a client bug dressed as
    // strictness.
    try (McpClient client = McpClient.start(fixture("old", "old-protocol"))) {
      assertEquals("echo: hi", client.callTool("echo", "{\"text\":\"hi\"}"));
    }
  }

  @Test
  void aServerThatLogsUntilItsPipeFillsDoesNotWedgeTheClient() {
    // stderr is drained on its own thread. Without that the server blocks on a full pipe and never
    // answers — and the failure looks like a hang with nothing to read.
    try (McpClient client = McpClient.start(fixture("noisy", "noisy"))) {
      assertEquals("echo: still here", client.callTool("echo", "{\"text\":\"still here\"}"));
    }
  }

  @Test
  void aServerThatNeverAnswersIsReportedRatherThanWaitedFor() {
    try (McpClient client = McpClient.start(fixture("slow"), 500)) {
      McpClient.McpToolFailure failure =
          assertThrows(McpClient.McpToolFailure.class, () -> client.callTool("slow", "{}"));

      assertTrue(failure.getMessage().contains("did not answer"), failure.getMessage());
      assertTrue(failure.getMessage().contains("'slow'"), "it names the server: " + failure.getMessage());
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

    assertTrue(failure.getMessage().contains("already closed")
        || failure.getMessage().contains("not running"), failure.getMessage());
  }

  @Test
  void aServerThatExitsDuringTheHandshakeNamesItselfAndWhy() {
    // A command that is not a server at all — the entry most likely to be wrong in a config file.
    McpConfig.Server broken =
        new McpConfig.Server("broken", "/bin/sh", List.of("-c", "echo 'not an MCP server' >&2; exit 3"), Map.of());

    RuntimeException failure = assertThrows(RuntimeException.class, () -> McpClient.start(broken));

    assertTrue(failure.getMessage().contains("broken"), failure.getMessage());
  }

  @Test
  void everyCallGoesThroughTheApproverAndTheToolNameIsTheSubject() {
    McpTools tools = McpTools.discover(null);
    assertTrue(tools.isEmpty(), "no file, no servers, nothing to start");

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
    assertEquals("mcp__fixture__echo", asked.get(0).tool(), "a rule matches on this name");
    assertTrue(denied.content().contains("rejected"), denied.content());
  }

  @Test
  void aConfiguredServerIsWrittenDownRatherThanAssumed() throws IOException {
    // The file is read per run, and what it says is what is started. Measured: a name with '__' in it
    // would make the tool names ambiguous, so it is refused rather than producing names nobody can
    // rule on.
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
    assertTrue(refused.getMessage().contains("cannot be a server name"), refused.getMessage());

    Files.writeString(file, "{\"servers\": [{\"name\": \"fs\"}]}");
    IllegalArgumentException noCommand =
        assertThrows(IllegalArgumentException.class, () -> McpConfig.from(file));
    assertTrue(noCommand.getMessage().contains("command"), noCommand.getMessage());

    assertFalse(McpConfig.from(dir.resolve("missing.json")).servers().size() > 0);
  }
}
