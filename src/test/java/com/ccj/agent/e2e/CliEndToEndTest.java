package com.ccj.agent.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.cli.Cli;
import com.ccj.agent.core.ProjectPrompt;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.ccj.agent.session.ResumePoint;
import com.ccj.agent.tool.RestartTool;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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

  /** The same invocation with extra flags, which is how the options are exercised end to end. */
  private static String[] append(String[] base, String... extra) {
    String[] all = new String[base.length + extra.length];
    System.arraycopy(base, 0, all, 0, base.length);
    System.arraycopy(extra, 0, all, base.length, extra.length);
    return all;
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

  /**
   * A registry whose active workspace is {@link #workspace}, as the sidebar would leave it.
   *
   * <p>Without one the first run seeds the workspace from the starting directory, which is where the
   * test JVM happens to be — and then the answer to "where do the tools run" would come from the
   * test framework's working directory rather than from anything the test set up.
   */
  private void writeRegistry() throws IOException {
    Files.writeString(
        home.resolve("workspaces.json"),
        """
        {
          "active": "project",
          "workspaces": [ { "name": "project", "path": "%s" } ]
        }
        """
            .formatted(workspace));
  }

  @Test
  void aProjectsOwnRulesReachTheRequest() throws IOException {
    // The point of the feature: a CCJ.md in the directory the tools run in is in the prompt of every
    // request, without the user configuring anything.
    Files.writeString(
        workspace.resolve(ProjectPrompt.FILE_NAME),
        "This project is special: always run `make check` before answering.\n");
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiText("understood"));

    Run run = runCli(args("what are the rules here?"));

    assertEquals(0, run.exitCode(), run.err());
    String sent = server.lastRequest().body();
    assertTrue(
        sent.contains("always run `make check`"),
        "the working directory's rules must be in the request: " + sent.substring(0, 600));
    assertTrue(sent.contains(ProjectPrompt.FILE_NAME), "and under a heading naming the file: " + sent);
  }

  @Test
  void rulesInAParentDirectoryApplyToo() throws IOException {
    // A tree of projects with one rules file at the top and the agent started inside a subdirectory:
    // reading only the working directory would drop the rules in exactly the case they are for.
    Path nested = Files.createDirectories(workspace.resolve("src/module"));
    Files.writeString(
        workspace.resolve(ProjectPrompt.FILE_NAME), "Top rule: never force-push.");
    Files.writeString(
        nested.resolve(ProjectPrompt.FILE_NAME), "Module rule: this one uses tabs.");
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiText("ok"));

    Run run =
        runCli(
            "-p", "hello",
            "--home", home.toString(),
            "--config", home.resolve("config.json").toString(),
            "-C", nested.toString());

    assertEquals(0, run.exitCode(), run.err());
    String sent = server.lastRequest().body();
    assertTrue(sent.contains("never force-push"), "the parent's rule is included: " + sent);
    assertTrue(sent.contains("this one uses tabs"), "and so is the module's: " + sent);
    assertTrue(
        sent.indexOf("never force-push") < sent.indexOf("this one uses tabs"),
        "the closer rule comes last, so it can narrow the one above it: " + sent);
  }

  @Test
  void aWorkingDirectoryWithoutRulesSendsTheSamePromptAsBefore() throws IOException {
    // Additive, or every existing setup changes silently. The request's system prompt must be exactly
    // what it was before rules files existed.
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiText("ok"));

    Run run = runCli(args("hello"));

    assertEquals(0, run.exitCode(), run.err());
    String sent = server.lastRequest().body();
    assertTrue(sent.contains("You are ccj"), sent.substring(0, 400));
    assertFalse(
        sent.contains(ProjectPrompt.FILE_NAME),
        "no rules file exists here, so none is mentioned: " + sent.substring(0, 600));
  }

  @Test
  void anOversizedRulesFileIsCutRatherThanSentWhole() throws IOException {
    // The system prompt is on every request and nothing trims it, so a runaway rules file has to be
    // bounded where it is read. The marker says so, because a model that believes it read every rule
    // is worse than one told the list is incomplete.
    Files.writeString(
        workspace.resolve(ProjectPrompt.FILE_NAME), "rule ".repeat(ProjectPrompt.LIMIT_CHARS));
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiText("ok"));

    Run run = runCli(args("hello"));

    assertEquals(0, run.exitCode(), run.err());
    String sent = server.lastRequest().body();
    assertTrue(sent.contains("cut short"), "the cut is announced: " + sent.length());
    assertTrue(
        sent.length() < ProjectPrompt.LIMIT_CHARS * 2,
        "and the request stays bounded: " + sent.length());
  }

  @Test
  void toolsRunInTheActiveWorkspaceEvenWhenTheRunStartsElsewhere() throws IOException {
    // The starting directory used to become a silent cwd override, so a run that announced
    // "workspace project (…/project)" was really reading and writing files in the directory it was
    // launched from. The workspace is what the sidebar selects, so it is where the tools must run.
    Files.writeString(workspace.resolve("note.txt"), "hello from the workspace\n");
    writeRegistry();
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiToolCall("call_1", "read", "{\"path\":\"note.txt\"}"));
    server.enqueue(MockModelServer.openAiText("the workspace file says hello"));

    Run run =
        runCli(
            "-p",
            "read note.txt",
            "--home",
            home.toString(),
            "--config",
            home.resolve("config.json").toString());

    assertEquals(0, run.exitCode(), run.err());
    assertTrue(
        server.lastRequest().body().contains("hello from the workspace"),
        "the tool must read the workspace's file, not the starting directory's: "
            + server.lastRequest().body());
  }

  @Test
  void theCwdFlagIsStillHonouredAndSaysSo() throws IOException {
    // -C stays a per-run override, but it is no longer silent: a session whose tools run somewhere
    // other than the active workspace has to say which directory that is.
    Files.writeString(tmp.resolve("elsewhere.txt"), "from the flag\n");
    writeRegistry();
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiToolCall("call_1", "read", "{\"path\":\"elsewhere.txt\"}"));
    server.enqueue(MockModelServer.openAiText("read it"));

    Run run =
        runCli(
            "-p",
            "read elsewhere.txt",
            "--home",
            home.toString(),
            "--config",
            home.resolve("config.json").toString(),
            "-C",
            tmp.toString());

    assertEquals(0, run.exitCode(), run.err());
    assertTrue(
        server.lastRequest().body().contains("from the flag"),
        "the flag's directory must be the one used: " + server.lastRequest().body());
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
  void theConfiguredReasoningTierReachesTheWireFromTheCommandLine() throws IOException {
    // The tier was configurable and only ever applied by the web UI: a CLI run ignored it, which is
    // the kind of gap a "works in the browser" feature hides.
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiText("ok"));

    Run flagged = runCli(append(args("think hard"), "--reasoning", "high"));

    assertEquals(0, flagged.exitCode(), flagged.err());
    assertTrue(
        server.lastRequest().body().contains("\"reasoning_effort\":\"high\""),
        "the flag must reach the request: " + server.lastRequest().body());
  }

  @Test
  void theConfiguredReasoningTierInTheConfigFileIsHonouredToo() throws IOException {
    Files.writeString(
        home.resolve("config.json"),
        """
        {
          "provider": "openai",
          "model": "test-model",
          "baseUrl": "%s",
          "apiKey": "sk-test-key-1234",
          "autoApprove": true,
          "reasoning": "low"
        }
        """
            .formatted(server.openAiBaseUrl()));
    server.enqueue(MockModelServer.openAiText("ok"));

    Run run = runCli(args("think a little"));

    assertEquals(0, run.exitCode(), run.err());
    assertTrue(
        server.lastRequest().body().contains("\"reasoning_effort\":\"low\""),
        "config.json is a source of settings like any other: " + server.lastRequest().body());
  }

  @Test
  void aContextBudgetTrimsWhatGoesOnTheWireWithoutTouchingTheSession() throws IOException {
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(
        MockModelServer.openAiToolCall("call_1", "bash", "{\"command\":\"seq 1 20000\"}"));
    server.enqueue(MockModelServer.openAiText("counted them"));

    Run run = runCli(append(args("count a lot"), "--max-context-tokens", "3000"));

    assertEquals(0, run.exitCode(), run.err());
    String wire = server.lastRequest().body();
    assertTrue(
        wire.contains("cut short") || wire.contains("elided"),
        "the second request must carry a trimmed history: " + wire.substring(0, Math.min(400, wire.length())));
    assertTrue(run.out().contains("context:"), "and say so in the transcript: " + run.out());
    List<String> lines = Files.readAllLines(sessionFiles().get(0));
    assertEquals(4, lines.size(), "the session file keeps the whole conversation:\n" + lines);
  }

  @Test
  void aSessionInterruptedMidCallCanBeResumed() throws IOException {
    // Reproduces the report exactly: a turn was aborted after its tool call was written and before
    // its result was, and from then on every message came back as
    // "an assistant message with 'tool_calls' must be followed by tool messages".
    writeConfig("openai", server.openAiBaseUrl(), true);
    Path sessions = Files.createDirectories(home.resolve("sessions"));
    String id = "20260912-010101-abcd";
    Files.writeString(
        sessions.resolve(id + ".jsonl"),
        """
        {"type":"user","text":"read the file"}
        {"type":"assistant","text":"","tool_calls":[{"id":"call_1","name":"read","arguments":"{\\"path\\":\\"note.txt\\"}"}]}
        """);
    server.enqueue(MockModelServer.openAiText("carried on"));

    Run run =
        runCli(
            "-p", "carry on",
            "--home", home.toString(),
            "--config", home.resolve("config.json").toString(),
            "--resume", id,
            "-C", workspace.toString());

    assertEquals(0, run.exitCode(), run.err());
    String sent = server.lastRequest().body();
    assertTrue(
        sent.contains("\"tool_call_id\":\"call_1\""),
        "the request must answer the call that was left hanging: " + sent);
    assertTrue(sent.contains("not run"), "and say what happened to it: " + sent);
    assertTrue(
        run.out().contains("history repaired"),
        "the user is told why the conversation works again: " + run.out());
    // The file keeps its bytes: the repair belongs to the request, not to the record. The run appends
    // its own two messages, and nothing else — no synthetic result is written into the history.
    List<String> lines = Files.readAllLines(sessions.resolve(id + ".jsonl"));
    assertEquals(4, lines.size(), "only the new turn is appended:\n" + lines);
    assertTrue(
        lines.get(1).contains("\"tool_calls\""),
        "the interrupted turn is still exactly as it was recorded:\n" + lines);
    assertTrue(
        lines.stream().noneMatch(line -> line.contains("not run")),
        "the repair is not written back:\n" + lines);
  }

  @Test
  void aResultThatSomethingDisplacedDuringTheTurnIsCarriedBackForTheRequest() throws IOException {
    // Reported shape: the assistant asked for a call, something was written into the session before
    // the result was, and the result then sat past it. Sent as recorded, the tool message answers no
    // assistant message the API can see, and every later turn came back as
    // "Messages with role 'tool' must be a response to a preceding message with 'tool_calls'".
    writeConfig("openai", server.openAiBaseUrl(), true);
    Path sessions = Files.createDirectories(home.resolve("sessions"));
    String id = "20260912-010102-abcd";
    Files.writeString(
        sessions.resolve(id + ".jsonl"),
        """
        {"type":"user","text":"read the file"}
        {"type":"assistant","text":"","tool_calls":[{"id":"call_1","name":"read","arguments":"{\\"path\\":\\"note.txt\\"}"}]}
        {"type":"user","text":"a probe written mid-turn"}
        {"type":"tool_result","tool_call_id":"call_1","tool_name":"read","content":"file contents","error":false}
        """);
    server.enqueue(MockModelServer.openAiText("carried on"));

    Run run =
        runCli(
            "-p", "carry on",
            "--home", home.toString(),
            "--config", home.resolve("config.json").toString(),
            "--resume", id,
            "-C", workspace.toString());

    assertEquals(0, run.exitCode(), run.err());
    String sent = server.lastRequest().body();
    assertEquals(1, occurrences(sent, "\"tool_call_id\":\"call_1\""),
        "the real result is sent once, and nothing is invented beside it: " + sent);
    assertFalse(sent.contains("not run"), "the call was answered: " + sent);
    int answer = sent.indexOf("\"role\":\"tool\"");
    int probe = sent.indexOf("a probe written mid-turn");
    assertTrue(answer > 0 && probe > answer,
        "the answer goes back into its turn, before what displaced it: " + sent);
    assertTrue(
        run.out().contains("history repaired") && run.out().contains("moved back"),
        "the transcript says what was done to the request: " + run.out());
    // The record is untouched: the repair is a statement about what to send.
    List<String> lines = Files.readAllLines(sessions.resolve(id + ".jsonl"));
    assertEquals(6, lines.size(), "only the new turn is appended:\n" + lines);
    assertEquals(
        1, occurrences(String.join("\n", lines), "\"type\":\"user\",\"text\":\"a probe written mid-turn\""),
        "the displaced message stays where it was written:\n" + lines);
  }

  private static int occurrences(String haystack, String needle) {
    int count = 0;
    for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
      count++;
    }
    return count;
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
  void aRestartAskedForByAnEarlierRunDoesNotEndThisOne() throws Exception {
    // The flag is one process asking itself one question, and the answer belongs to the run that
    // made it. A process that runs the CLI more than once — this suite does, an embedder may — must
    // not have the next run report a restart it never asked for: the tool sets the flag, the run
    // starts by clearing it, and only a restart *this* run asked for ends it.
    Path project = Files.createDirectories(tmp.resolve("self-build"));
    Files.createDirectories(project.resolve("target"));
    Files.writeString(project.resolve("target/ccj.jar"), "installed");
    Files.writeString(project.resolve("target/ccj-next.jar"), "next");

    ToolResult installed =
        new RestartTool()
            .execute(
                "{\"built\":\"target/ccj-next.jar\"}",
                new ToolContext(project, Approver.ALWAYS, 0));

    assertFalse(installed.error(), installed.content());
    assertTrue(RestartTool.restartRequested(), "the tool did ask to be restarted on the new jar");

    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiText("no restart here"));

    Run run = runCli(args("just answer"));

    assertEquals(0, run.exitCode(), "this run asked for nothing: " + run.err());
    assertTrue(run.out().contains("no restart here"), run.out());
  }

  @Test
  void theReplEndsItselfWhenTheAgentInstallsANewJar() throws IOException {
    // The REPL used to keep reading stdin after a restart, so the process went on running bytes that
    // were no longer on disk and the launcher never got its handover. One turn, then exit 75 — a
    // second line on stdin is never read, which is what the request count proves.
    Files.createDirectories(workspace.resolve("target"));
    Files.writeString(workspace.resolve("target/ccj.jar"), "installed");
    Files.writeString(workspace.resolve("target/ccj-next.jar"), "next");
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(
        MockModelServer.openAiToolCall(
            "call_1", "restart", "{\"built\":\"target/ccj-next.jar\"}"));

    Run run =
        runCliWithStdin(
            "install the new jar\nand then something else\n",
            "--repl",
            "--yolo",
            "--home",
            home.toString(),
            "--config",
            home.resolve("config.json").toString(),
            "-C",
            workspace.toString());

    assertEquals(RestartTool.RESTART_EXIT, run.exitCode(), run.err());
    assertEquals(1, server.requestCount(), "the turn after the restart must not run");
    assertEquals("next", Files.readString(workspace.resolve("target/ccj.jar")));
    // The next process has to come back to this conversation, and the note is how it will: the
    // launcher re-runs the same command, which carries no session of its own.
    assertTrue(run.err().contains("resuming session"), run.err());
    assertEquals(
        Optional.of(sessionIdIn(run.err())),
        ResumePoint.read(home),
        "the session the REPL was on is written down for the process that follows: " + run.err());
  }

  /** The session id named by a "resuming session <id>" line. */
  private static String sessionIdIn(String text) {
    int at = text.indexOf("resuming session ");
    assertTrue(at >= 0, text);
    String rest = text.substring(at + "resuming session ".length());
    int end = rest.indexOf('\n');
    return (end < 0 ? rest : rest.substring(0, end)).strip();
  }

  @Test
  void theProcessThatFollowsARestartResumesTheConversation() throws IOException {
    // What the user asked for in one sentence: a restart should put them back where they were. The
    // launcher re-runs the same command with no --resume, so the fact has to survive on disk.
    Files.createDirectories(workspace.resolve("target"));
    Files.writeString(workspace.resolve("target/ccj.jar"), "installed");
    Files.writeString(workspace.resolve("target/ccj-next.jar"), "next");
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(
        MockModelServer.openAiToolCall(
            "call_1", "restart", "{\"built\":\"target/ccj-next.jar\"}"));
    server.enqueue(MockModelServer.openAiText("carried on after the restart"));

    Run restarted =
        runCliWithStdin(
            "install the new jar\n",
            "--repl",
            "--yolo",
            "--home", home.toString(),
            "--config", home.resolve("config.json").toString(),
            "-C", workspace.toString());
    assertEquals(RestartTool.RESTART_EXIT, restarted.exitCode(), restarted.err());
    String session = sessionIdIn(restarted.err());
    assertTrue(
        Files.exists(home.resolve("sessions").resolve(session + ".jsonl")),
        "the conversation is on disk, so resuming it is possible at all");

    // The next process is started exactly the way the launcher starts it: the same command, with
    // nothing added. It must open the conversation the restart left behind.
    Run again =
        runCliWithStdin(
            "what did we just do?\n",
            "--repl",
            "--yolo",
            "--home", home.toString(),
            "--config", home.resolve("config.json").toString(),
            "-C", workspace.toString());

    assertEquals(0, again.exitCode(), again.err());
    // Proof it opened that conversation: the only session with messages is the one the restart was
    // on, so if this run used a new session its turn would be in a second file.
    List<Path> files;
    try (Stream<Path> listed = Files.list(home.resolve("sessions"))) {
      files = listed.toList();
    }
    assertEquals(1, files.size(), "one conversation only, and it is the resumed one: " + files);
    List<String> written = Files.readAllLines(files.get(0));
    assertTrue(
        written.stream().anyMatch(line -> line.contains("what did we just do?")),
        "the question went to the resumed conversation:\n" + written);
    assertTrue(
        written.stream().anyMatch(line -> line.contains("install the new jar")),
        "which still has the turn the restart interrupted:\n" + written);
    // And the note is spent: one restart, one resume.
    assertTrue(ResumePoint.read(home).isEmpty(), "the note answers once");
  }

  @Test
  void anExplicitResumeBeatsWhatTheRestartRemembered() throws IOException {
    // --resume is the user saying where this run goes; the note only remembers where the last one
    // was. Getting this backwards would make the flag unusable right after a restart.
    writeConfig("openai", server.openAiBaseUrl(), true);
    Path sessions = Files.createDirectories(home.resolve("sessions"));
    String remembered = "20260913-010000-abcd";
    String chosen = "20260913-020000-beef";
    Files.writeString(
        sessions.resolve(remembered + ".jsonl"),
        """
        {"type":"user","text":"the remembered conversation"}
        {"type":"assistant","text":"hello","tool_calls":[]}
        """);
    Files.writeString(
        sessions.resolve(chosen + ".jsonl"),
        """
        {"type":"user","text":"the one I asked for"}
        {"type":"assistant","text":"hello","tool_calls":[]}
        """);
    ResumePoint.write(home, remembered);
    server.enqueue(MockModelServer.openAiText("in the chosen session"));

    Run run =
        runCli(
            "-p", "hello",
            "--resume", chosen,
            "--home", home.toString(),
            "--config", home.resolve("config.json").toString(),
            "-C", workspace.toString());

    assertEquals(0, run.exitCode(), run.err());
    List<String> rememberedLines = Files.readAllLines(sessions.resolve(remembered + ".jsonl"));
    assertEquals(
        2, rememberedLines.size(), "the remembered session must not gain this run's turn:\n"
            + rememberedLines);
    assertTrue(
        Files.readAllLines(sessions.resolve(chosen + ".jsonl")).stream()
            .anyMatch(line -> line.contains("in the chosen session")),
        "the turn went to the session the flag named");
    assertTrue(ResumePoint.read(home).isEmpty(), "and the note is spent, not left to fire later");
  }

  @Test
  void aRememberedConversationThatIsGoneStartsFreshInsteadOfFailing() throws IOException {
    writeConfig("openai", server.openAiBaseUrl(), true);
    ResumePoint.write(home, "20260913-010000-abcd");   // never existed on disk
    server.enqueue(MockModelServer.openAiText("fresh start"));

    Run run = runCli("-p", "hello", "--home", home.toString(),
        "--config", home.resolve("config.json").toString(), "-C", workspace.toString());

    assertEquals(0, run.exitCode(), run.err());
    assertTrue(run.out().contains("fresh start"), run.out());
    assertTrue(ResumePoint.read(home).isEmpty(), "and a dead note does not linger");
  }

  @Test
  void unknownFlagsAreRejectedBeforeAnyRequest() {
    Run run = runCli("--not-a-flag");

    assertEquals(2, run.exitCode(), "usage errors exit 2");
    assertEquals(0, server.requestCount());
  }
}
