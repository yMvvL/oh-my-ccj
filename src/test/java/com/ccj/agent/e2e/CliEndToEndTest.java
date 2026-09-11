package com.ccj.agent.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.cli.Cli;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the real CLI against a scripted model backend.
 *
 * <p>Nothing is stubbed below the CLI: the request really goes out over HTTP, the SSE stream is
 * really parsed, the tool really runs, and the resulting file really lands on disk. That is the
 * whole point of these tests — the unit tests prove the pieces, these prove the machine.
 */
class CliEndToEndTest {

  @TempDir Path tmp;

  private MockModelServer server;
  private Path home;
  private Path workspace;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockModelServer();
    home = Files.createDirectories(tmp.resolve("ccj-home"));
    workspace = Files.createDirectories(tmp.resolve("workspace"));
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private record Run(int exitCode, String out, String err) {}

  private Run runCli(String... args) {
    return runCliWithStdin("", args);
  }

  private Run runCliWithStdin(String stdin, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    InputStream in = new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
    int exit;
    try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      exit = new Cli().run(args, in, outStream, errStream);
    }
    return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private void writeConfig(String provider, String baseUrl, boolean autoApprove) throws IOException {
    Files.writeString(
        home.resolve("config.json"),
        """
        {
          "provider": "%s",
          "model": "test-model",
          "baseUrl": "%s",
          "apiKey": "sk-test-key-1234",
          "maxSteps": 5,
          "autoApprove": %s
        }
        """
            .formatted(provider, baseUrl, autoApprove));
  }

  private String[] args(String prompt) {
    return new String[] {
      "-p", prompt, "--home", home.toString(), "--config", home.resolve("config.json").toString(),
      "-C", workspace.toString()
    };
  }

  private List<Path> sessionFiles() throws IOException {
    Path sessions = home.resolve("sessions");
    if (!Files.isDirectory(sessions)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(sessions)) {
      return files.filter(p -> p.toString().endsWith(".jsonl")).toList();
    }
  }

  @Test
  void openAiCompatibleToolCallRoundTripReachesTheModelTwice() throws IOException {
    Files.writeString(workspace.resolve("note.txt"), "hello from disk\n");
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiToolCall("call_1", "read", "{\"path\":\"note.txt\"}"));
    server.enqueue(MockModelServer.openAiText("the file says hello"));

    Run run = runCli(args("read note.txt and tell me what it says"));

    assertEquals(0, run.exitCode(), run.err());
    assertTrue(run.out().contains("the file says hello"), run.out());
    assertEquals(2, server.requestCount(), "one turn for the tool call, one for the answer");

    String secondRequest = server.lastRequest().body();
    assertTrue(
        secondRequest.contains("\"model\":\"test-model\""),
        "the configured model must reach the wire: " + secondRequest);
    assertTrue(
        secondRequest.contains("hello from disk"),
        "the tool result must be fed back to the model: " + secondRequest);
    assertTrue(
        server.lastRequest().authorization().contains("sk-test-key-1234"),
        "the configured key must be sent as a bearer token");

    List<Path> sessions = sessionFiles();
    assertEquals(1, sessions.size(), "the session must be persisted: " + sessions);
    List<String> lines = Files.readAllLines(sessions.get(0));
    assertEquals(4, lines.size(), "user, assistant(tool call), tool result, assistant\n" + lines);
  }

  @Test
  void bashToolReallyWritesAFileOnDisk() throws IOException {
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(
        MockModelServer.openAiToolCall("call_1", "bash", "{\"command\":\"printf hi > made.txt\"}"));
    server.enqueue(MockModelServer.openAiText("created made.txt"));

    Run run = runCli(args("create made.txt"));

    assertEquals(0, run.exitCode(), run.err());
    assertEquals("hi", Files.readString(workspace.resolve("made.txt")));
    assertTrue(run.out().contains("created made.txt"), run.out());
  }

  @Test
  void editToolAppliesTheChangeThroughTheWholeStack() throws IOException {
    Files.writeString(workspace.resolve("main.java"), "class A { int x = 1; }\n");
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(
        MockModelServer.openAiToolCall(
            "call_1",
            "edit",
            "{\"path\":\"main.java\",\"old_string\":\"int x = 1\",\"new_string\":\"int x = 2\"}"));
    server.enqueue(MockModelServer.openAiText("bumped x to 2"));

    Run run = runCli(args("bump x"));

    assertEquals(0, run.exitCode(), run.err());
    assertEquals("class A { int x = 2; }\n", Files.readString(workspace.resolve("main.java")));
  }

  @Test
  void anthropicProviderSpeaksItsOwnWireFormat() throws IOException {
    Files.writeString(workspace.resolve("data.txt"), "anthropic round trip\n");
    writeConfig("anthropic", server.anthropicBaseUrl(), true);
    server.enqueue(MockModelServer.anthropicToolCall("toolu_1", "read", "{\"path\":\"data.txt\"}"));
    server.enqueue(MockModelServer.anthropicText("read it via anthropic"));

    Run run = runCli(args("read data.txt"));

    assertEquals(0, run.exitCode(), run.err());
    assertTrue(run.out().contains("read it via anthropic"), run.out());
    assertEquals(2, server.requestCount());
    assertEquals("sk-test-key-1234", server.lastRequest().apiKey(), "x-api-key header expected");
    assertTrue(
        server.lastRequest().body().contains("anthropic round trip"),
        "tool result must be mapped into a tool_result block: " + server.lastRequest().body());
  }

  @Test
  void sideEffectingToolsAreRefusedWhenNothingCanApproveThem() throws IOException {
    writeConfig("openai", server.openAiBaseUrl(), false);
    server.enqueue(
        MockModelServer.openAiToolCall("call_1", "bash", "{\"command\":\"printf no > nope.txt\"}"));
    server.enqueue(MockModelServer.openAiText("I could not create it"));

    Run run = runCli(args("create nope.txt"));

    assertEquals(0, run.exitCode(), run.err());
    assertFalse(Files.exists(workspace.resolve("nope.txt")), "denied command must not run");
    String feedback = server.lastRequest().body();
    assertTrue(
        feedback.contains("rejected") || feedback.contains("denied"),
        "the model must learn the call was refused: " + feedback);
  }

  @Test
  void readOnlyPromptsAreAnsweredWithoutUsingTools() throws IOException {
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiText("just talking"));

    Run run = runCli(args("say hi"));

    assertEquals(0, run.exitCode(), run.err());
    assertTrue(run.out().contains("just talking"), run.out());
    assertEquals(1, server.requestCount(), "a plain answer must not trigger another turn");
  }

  @Test
  void providerErrorsFailLoudlyInsteadOfHanging() throws IOException {
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueueError(401, "{\"error\":{\"message\":\"bad key\"}}");

    Run run = runCli(args("hello"));

    assertEquals(1, run.exitCode(), "a provider failure is not a success");
    assertTrue(
        (run.err() + run.out()).contains("401"),
        "the status code must be reported: out=" + run.out() + " err=" + run.err());
  }

  @Test
  void helpAndVersionDoNotTouchTheNetwork() {
    Run help = runCli("--help");
    assertEquals(0, help.exitCode(), help.err());
    assertTrue(help.out().contains("--print"), help.out());

    Run version = runCli("--version");
    assertEquals(0, version.exitCode(), version.err());
    assertTrue(version.out().contains("oh-my-ccj"), version.out());

    assertEquals(0, server.requestCount());
  }

  @Test
  void unknownFlagsAreRejectedBeforeAnyRequest() {
    Run run = runCli("--not-a-flag");

    assertEquals(2, run.exitCode(), "usage errors exit 2");
    assertEquals(0, server.requestCount());
  }
}
