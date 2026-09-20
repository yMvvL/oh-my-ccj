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
 * 让真实的 CLI 对着一个按脚本应答的模型后端运行。
 *
 * <p>CLI 之下没有任何桩：请求真的经 HTTP 发出，SSE 流真的被解析，工具真的运行，产出的文件真的落到
 * 磁盘上。这正是这些测试的意义——单元测试证明各个部件，这些测试证明整台机器。
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

  /** 同一次调用外加若干参数，选项就是这样被端到端跑到的。 */
  private static String[] append(String[] base, String... extra) {
    String[] all = new String[base.length + extra.length];
    System.arraycopy(base, 0, all, 0, base.length);
    System.arraycopy(extra, 0, all, base.length, extra.length);
    return all;
  }

  /**
   * 一条会话里的消息行，去掉账本行。
   *
   * <p>账本行（{@code "type":"usage"}）记的是提供方报了多少 token，而这几条断言问的是「对话里发生了什么」。
   * 把两者混进同一个行数里，会让任何一次记账改动看起来都像对话本身出了问题——而记账确实会变。
   */
  private static List<String> messageLines(List<String> lines) {
    return lines.stream().filter(line -> !line.contains("\"type\":\"usage\"")).toList();
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
   * 一个活动工作区为 {@link #workspace} 的注册表，就像侧边栏会留下的那样。
   *
   * <p>没有它，首次运行会以起始目录播种工作区，而那恰好是测试 JVM 所在之处——于是「工具在哪里运行」
   * 的答案就会来自测试框架的工作目录，而不是测试自己设置的任何东西。
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
    // 这个功能的要点：工具运行目录中的 CCJ.md 会出现在每次请求的提示词里，而用户无需配置任何东西。
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
        "工作目录的规则必须在请求里： " + sent.substring(0, 600));
    assertFalse(
        sent.contains("Rules from"),
        "没有点名文件的标题：一个已知位置上的唯一文件没有什么需要消歧的，而标题会在每次请求上"
            + " 白花提示词： "
            + sent.substring(0, 600));
  }

  @Test
  void aTerminalTurnRecordsWhatTheProviderReportedAndTheCapReadsIt() throws IOException {
    // 终端此前**一个 token 都不记**：账本只在网页那条路上被写，所以 `--max-total-tokens` 在 REPL 与 -p
    // 里永远不触发，而一条终端会话的文件里除了压缩次数什么都没有。提供方报的数字现在由循环本身记账——
    // 它是唯一知道会话的那个东西，也是两个前端都有的那个东西。
    //
    // 这一条同时钉住两端：文件里的数字来自提供方（11/7），而紧接着的那个回合因为 18 > 5 被拒绝、且模型
    // 没有被问第二次。
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiText("first"));
    server.enqueue(MockModelServer.openAiText("second"));

    Run run =
        runCliWithStdin(
            "hello\nagain\n",
            "--repl",
            "--max-total-tokens",
            "5",
            "--home",
            home.toString(),
            "--config",
            home.resolve("config.json").toString(),
            "-C",
            workspace.toString());

    assertEquals(0, run.exitCode(), run.err());
    Path file = sessionFiles().get(0);
    String text = Files.readString(file);
    assertTrue(text.contains("\"input_tokens\":11"), "提供方报的输入 token 要进账本：" + text);
    assertTrue(text.contains("\"output_tokens\":7"), "输出同理：" + text);
    assertTrue(
        run.err().contains("本会话已用 18 token，超过花费上限 5"),
        "上限读的是同一条账本：" + run.err());
    assertEquals(1, server.recorded().size(), "被拒绝的回合没有问模型");
  }

  @Test
  void rulesInAParentDirectoryAreNotUsed() throws IOException {
    // 读取刻意只深入一层目录。向上遍历会让主目录里的一个文件在没有任何项目要求的情况下作用于其下每
    // 一个项目，用户还得到处向上翻找才知道自己的提示词为什么变了。一次运行用了什么，必须只看着它
    // 启动时所在的目录就能回答。
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
    assertTrue(sent.contains("this one uses tabs"), "工作目录的规则被使用了： " + sent);
    assertFalse(
        sent.contains("never force-push"),
        "而父目录的规则没有，尽管它就在上一层： " + sent);
    // 内置规则仍在请求里：项目文件只是引领提示词，并不会取代这个 agent 本身。
    assertTrue(sent.contains("You are ccj"), sent.substring(0, 500));
    assertTrue(sent.contains("Inspect before you change"), sent.substring(0, 900));
  }

  @Test
  void aWorkingDirectoryWithoutRulesSendsTheSamePromptAsBefore() throws IOException {
    // 必须是增量式的，否则每个既有配置都会悄悄改变。请求里的系统提示词必须与规则文件存在之前完全
    // 一致。
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiText("ok"));

    Run run = runCli(args("hello"));

    assertEquals(0, run.exitCode(), run.err());
    String sent = server.lastRequest().body();
    assertTrue(sent.contains("You are ccj"), sent.substring(0, 400));
    assertFalse(
        sent.contains(ProjectPrompt.FILE_NAME),
        "这里不存在规则文件，所以不会被提及： " + sent.substring(0, 600));
  }

  @Test
  void theProjectsRulesLeadThePromptInARealRequest() throws IOException {
    // 顺序要看到达线上时的样子，而不只是构建器返回时的样子：项目自己的声明在前，内置规则随后。
    Files.writeString(
        workspace.resolve(ProjectPrompt.FILE_NAME), "PROJECT RULE: this tree uses tabs.");
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiText("ok"));

    Run run = runCli(args("hello"));

    assertEquals(0, run.exitCode(), run.err());
    String sent = server.lastRequest().body();
    int project = sent.indexOf("PROJECT RULE");
    int base = sent.indexOf("You are ccj");
    assertTrue(project >= 0 && base >= 0, sent.substring(0, 800));
    assertTrue(project < base, "项目的规则排在内置规则之前：\n" + sent.substring(0, 800));
  }

  @Test
  void anOversizedRulesFileIsCutRatherThanSentWhole() throws IOException {
    // 系统提示词出现在每一次请求里且无人裁剪，因此失控的规则文件必须在读取处就被限制。标记会说明
    // 这一点，因为一个以为自己读完了全部规则的模型，比一个被告知列表不完整的模型更糟。
    Files.writeString(
        workspace.resolve(ProjectPrompt.FILE_NAME), "rule ".repeat(ProjectPrompt.LIMIT_CHARS));
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiText("ok"));

    Run run = runCli(args("hello"));

    assertEquals(0, run.exitCode(), run.err());
    String sent = server.lastRequest().body();
    assertTrue(sent.contains("cut short"), "裁剪被宣告了： " + sent.length());
    assertTrue(
        sent.length() < ProjectPrompt.LIMIT_CHARS * 2,
        "且请求仍是有界的： " + sent.length());
  }

  @Test
  void toolsRunInTheActiveWorkspaceEvenWhenTheRunStartsElsewhere() throws IOException {
    // 起始目录过去会变成无声的 cwd 覆盖，于是宣布「workspace project (…/project)」的一次运行实际上
    // 却在它被启动的目录里读写文件。工作区是侧边栏所选定的东西，因此工具必须在那里运行。
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
        "工具必须读取工作区的文件，而不是起始目录的： "
            + server.lastRequest().body());
  }

  @Test
  void aCheckDeclaredInTheConfigFileRunsAfterAnEditAndItsVerdictReachesTheModel() throws IOException {
    // 整个功能，端到端且离线：CLI 读取配置文件，用其声明的检查构建工具，模型的编辑触发其中一个，
    // 判决出现在下一次请求里——与改动同一步，这正是拥有它的全部意义。
    Files.writeString(workspace.resolve("Foo.java"), "class Foo {}\n");
    writeConfig("openai", server.openAiBaseUrl(), true);
    Files.writeString(
        home.resolve("config.json"),
        Files.readString(home.resolve("config.json"))
            .replace(
                "\"autoApprove\": true",
                "\"autoApprove\": true,\n  \"checks\": [{\"glob\": \"**/*.java\","
                    + " \"command\": \"echo 'Foo.java:1: error: cannot find symbol'; exit 1\"}]"));
    server.enqueue(
        MockModelServer.openAiToolCall(
            "call_1",
            "edit",
            "{\"path\":\"Foo.java\",\"old_string\":\"class Foo {}\","
                + "\"new_string\":\"class Foo { int x = missing; }\"}"));
    server.enqueue(MockModelServer.openAiText("fixed it"));

    Run run = runCli(args("break Foo.java"));

    assertEquals(0, run.exitCode(), run.err());
    String toModel = server.lastRequest().body();
    assertTrue(
        toModel.contains("cannot find symbol"),
        "检查的判决必须出现在编辑之后的那次请求里： " + toModel);
    assertTrue(toModel.contains("[check] echo "), toModel);
    assertTrue(
        Files.readString(workspace.resolve("Foo.java")).contains("missing"),
        "编辑本身仍然发生了");
  }

  @Test
  void aFileNoCheckIsAboutIsEditedWithoutRunningAnything() throws IOException {
    // 默认保持零开销：没有声明任何检查的项目，或者没有任何检查匹配的文件，得到的结果与模型早已知道
    // 如何读取的完全一样。
    Files.writeString(workspace.resolve("notes.md"), "old text\n");
    writeConfig("openai", server.openAiBaseUrl(), true);
    Files.writeString(
        home.resolve("config.json"),
        Files.readString(home.resolve("config.json"))
            .replace(
                "\"autoApprove\": true",
                "\"autoApprove\": true,\n  \"checks\": [{\"glob\": \"**/*.java\","
                    + " \"command\": \"echo should-not-run\"}]"));
    server.enqueue(
        MockModelServer.openAiToolCall(
            "call_1",
            "edit",
            "{\"path\":\"notes.md\",\"old_string\":\"old text\",\"new_string\":\"new text\"}"));
    server.enqueue(MockModelServer.openAiText("done"));

    Run run = runCli(args("edit the notes"));

    assertEquals(0, run.exitCode(), run.err());
    assertFalse(server.lastRequest().body().contains("should-not-run"), server.lastRequest().body());
    assertFalse(server.lastRequest().body().contains("[check]"), server.lastRequest().body());
  }

  @Test
  void theCwdFlagIsStillHonouredAndSaysSo() throws IOException {
    // -C 仍是单次运行的覆盖，但它不再无声：工具运行在活动工作区之外某处的会话，必须说明那是哪个
    // 目录。
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
        "参数指定的目录必须是实际使用的那个： " + server.lastRequest().body());
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
    assertEquals(2, server.requestCount(), "一次回合用于工具调用，一次用于回答");

    String secondRequest = server.lastRequest().body();
    assertTrue(
        secondRequest.contains("\"model\":\"test-model\""),
        "配置的模型必须到达线上： " + secondRequest);
    assertTrue(
        secondRequest.contains("hello from disk"),
        "工具结果必须回喂给模型： " + secondRequest);
    assertTrue(
        server.lastRequest().authorization().contains("sk-test-key-1234"),
        "配置的密钥必须作为 bearer token 发送");

    List<Path> sessions = sessionFiles();
    assertEquals(1, sessions.size(), "会话必须被持久化： " + sessions);
    List<String> lines = Files.readAllLines(sessions.get(0));
    assertEquals(4, messageLines(lines).size(), "user、assistant(工具调用)、tool result、assistant\n" + lines);
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
    // 该档位可以配置，却一直只被网页 UI 应用：CLI 运行会忽略它，这正是「在浏览器里能用」的功能所
    // 掩盖的那类缺口。
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiText("ok"));

    Run flagged = runCli(append(args("think hard"), "--reasoning", "high"));

    assertEquals(0, flagged.exitCode(), flagged.err());
    assertTrue(
        server.lastRequest().body().contains("\"reasoning_effort\":\"high\""),
        "该参数必须到达请求： " + server.lastRequest().body());
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
        "config.json 与其他任何设置来源一样： " + server.lastRequest().body());
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
        wire.contains("已截短") || wire.contains("已略去"),
        "第二次请求必须携带被裁剪过的历史： " + wire.substring(0, Math.min(400, wire.length())));
    assertTrue(run.out().contains("上下文："), "并在转录里说明这一点： " + run.out());
    List<String> lines = Files.readAllLines(sessionFiles().get(0));
    assertEquals(4, messageLines(lines).size(), "会话文件保留完整的对话：\n" + lines);
  }

  @Test
  void aSessionInterruptedMidCallCanBeResumed() throws IOException {
    // 精确复现那份报告：一个回合在其工具调用已写入、结果尚未写入时被中止，此后每条消息都以
    // "an assistant message with 'tool_calls' must be followed by tool messages" 返回。
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
        "请求必须回答那个被悬置的调用： " + sent);
    assertTrue(sent.contains("未运行"), "并说明它遭遇了什么： " + sent);
    assertTrue(
        run.out().contains("历史已修复"),
        "用户被告知对话为什么又能用了： " + run.out());
    // 文件保留它的字节：修复属于请求，不属于记录。这次运行只追加它自己的两条消息，别无其他——不会
    // 有合成的结果被写进历史。
    List<String> lines = Files.readAllLines(sessions.resolve(id + ".jsonl"));
    assertEquals(4, messageLines(lines).size(), "只追加了新回合：\n" + lines);
    assertTrue(
        lines.get(1).contains("\"tool_calls\""),
        "被中断的回合仍与记录时一模一样：\n" + lines);
    assertTrue(
        lines.stream().noneMatch(line -> line.contains("未运行")),
        "修复没有被写回：\n" + lines);
  }

  @Test
  void aResultThatSomethingDisplacedDuringTheTurnIsCarriedBackForTheRequest() throws IOException {
    // 报告出来的形态：助手请求了一次调用，结果尚未写入之前有别的东西被写进了会话，结果于是落在它
    // 之后。照记录原样发送时，那条 tool 消息回答不了 API 能看到的助手消息，之后每个回合都以
    // "Messages with role 'tool' must be a response to a preceding message with 'tool_calls'"。
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
        "真实结果只发送一次，旁边没有编造任何东西： " + sent);
    assertFalse(sent.contains("未运行"), "该调用得到了回答： " + sent);
    int answer = sent.indexOf("\"role\":\"tool\"");
    int probe = sent.indexOf("a probe written mid-turn");
    assertTrue(answer > 0 && probe > answer,
        "回答回到它所属的回合，排在挤走它的东西之前： " + sent);
    assertTrue(
        run.out().contains("历史已修复") && run.out().contains("带回了提出它们的那个回合"),
        "转录说明了请求被做了什么处理： " + run.out());
    // 记录原封不动：修复只是关于「发送什么」的陈述。
    List<String> lines = Files.readAllLines(sessions.resolve(id + ".jsonl"));
    assertEquals(6, messageLines(lines).size(), "只追加了新回合：\n" + lines);
    assertEquals(
        1, occurrences(String.join("\n", lines), "\"type\":\"user\",\"text\":\"a probe written mid-turn\""),
        "被挤走的消息仍留在它被写入的位置：\n" + lines);
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
    assertEquals("sk-test-key-1234", server.lastRequest().apiKey(), "预期使用 x-api-key 头");
    assertTrue(
        server.lastRequest().body().contains("anthropic round trip"),
        "工具结果必须映射进 tool_result 块： " + server.lastRequest().body());
  }

  @Test
  void sideEffectingToolsAreRefusedWhenNothingCanApproveThem() throws IOException {
    writeConfig("openai", server.openAiBaseUrl(), false);
    server.enqueue(
        MockModelServer.openAiToolCall("call_1", "bash", "{\"command\":\"printf no > nope.txt\"}"));
    server.enqueue(MockModelServer.openAiText("I could not create it"));

    Run run = runCli(args("create nope.txt"));

    assertEquals(0, run.exitCode(), run.err());
    assertFalse(Files.exists(workspace.resolve("nope.txt")), "被拒绝的命令绝不能运行");
    String feedback = server.lastRequest().body();
    assertTrue(
        feedback.contains("拒绝"),
        "模型必须得知该调用已被拒绝： " + feedback);
  }

  @Test
  void readOnlyPromptsAreAnsweredWithoutUsingTools() throws IOException {
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiText("just talking"));

    Run run = runCli(args("say hi"));

    assertEquals(0, run.exitCode(), run.err());
    assertTrue(run.out().contains("just talking"), run.out());
    assertEquals(1, server.requestCount(), "普通回答不得触发另一个回合");
  }

  @Test
  void providerErrorsFailLoudlyInsteadOfHanging() throws IOException {
    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueueError(401, "{\"error\":{\"message\":\"bad key\"}}");

    Run run = runCli(args("hello"));

    assertEquals(1, run.exitCode(), "提供方失败不算成功");
    assertTrue(
        (run.err() + run.out()).contains("401"),
        "状态码必须被报告：out=" + run.out() + " err=" + run.err());
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
    // 这个标志是一个进程问自己的一个问题，答案属于提出它的那次运行。会多次运行 CLI 的进程——本测试
    // 套件如此，嵌入方也可能如此——绝不能让下一次运行报告一个它从未请求过的重启：工具设置标志，运行
    // 开始时清除它，只有*本次*运行请求的重启才会结束它。
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
    assertTrue(RestartTool.restartRequested(), "该工具确实请求过用新 jar 重启");

    writeConfig("openai", server.openAiBaseUrl(), true);
    server.enqueue(MockModelServer.openAiText("no restart here"));

    Run run = runCli(args("just answer"));

    assertEquals(0, run.exitCode(), "这次运行什么都没请求： " + run.err());
    assertTrue(run.out().contains("no restart here"), run.out());
  }

  @Test
  void theReplEndsItselfWhenTheAgentInstallsANewJar() throws IOException {
    // REPL 过去会在重启后继续读取 stdin，于是进程继续运行早已不在磁盘上的字节，启动器也永远等不到
    // 交接。一个回合，然后退出码 75——stdin 上的第二行永远不会被读取，这正是请求计数所证明的。
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
    assertEquals(1, server.requestCount(), "重启之后的回合绝不能运行");
    assertEquals("next", Files.readString(workspace.resolve("target/ccj.jar")));
    // 下一个进程必须回到这个会话，而那则记录就是它回归的方式：启动器重新运行同一条命令，而那条命令
    // 本身不携带任何会话。
    assertTrue(run.err().contains("恢复会话"), run.err());
    assertEquals(
        Optional.of(sessionIdIn(run.err())),
        ResumePoint.read(home),
        "REPL 所在的那个会话被写下来，留给后面的进程： " + run.err());
  }

  /** 「恢复会话 <id>」一行所点名的会话 id。 */
  private static String sessionIdIn(String text) {
    int at = text.indexOf("恢复会话 ");
    assertTrue(at >= 0, text);
    String rest = text.substring(at + "恢复会话 ".length());
    int end = rest.indexOf('\n');
    return (end < 0 ? rest : rest.substring(0, end)).strip();
  }

  @Test
  void theProcessThatFollowsARestartResumesTheConversation() throws IOException {
    // 用户用一句话提出的要求：重启应该把人放回原来的地方。启动器重跑同一条命令且不带 --resume，
    // 所以这个事实必须在磁盘上活下来。
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
        "对话在磁盘上，所以恢复它才成为可能");

    // 下一个进程完全按启动器启动它的方式启动：同一条命令，不加任何东西。它必须打开重启留下的那个
    // 会话。
    Run again =
        runCliWithStdin(
            "what did we just do?\n",
            "--repl",
            "--yolo",
            "--home", home.toString(),
            "--config", home.resolve("config.json").toString(),
            "-C", workspace.toString());

    assertEquals(0, again.exitCode(), again.err());
    // 证明它打开了那个会话：唯一带有消息的会话就是重启时所在的那个，因此如果这次运行用了新会话，
    // 它的回合就会出现在第二个文件里。
    List<Path> files;
    try (Stream<Path> listed = Files.list(home.resolve("sessions"))) {
      files = listed.toList();
    }
    assertEquals(1, files.size(), "只有一个对话，而且它就是被恢复的那个： " + files);
    List<String> written = Files.readAllLines(files.get(0));
    assertTrue(
        written.stream().anyMatch(line -> line.contains("what did we just do?")),
        "问题进入了被恢复的对话：\n" + written);
    assertTrue(
        written.stream().anyMatch(line -> line.contains("install the new jar")),
        "它仍然保有重启中断的那个回合：\n" + written);
    // 而那则记录已被用掉：一次重启，一次恢复。
    assertTrue(ResumePoint.read(home).isEmpty(), "那则记录只回答一次");
  }

  @Test
  void anExplicitResumeBeatsWhatTheRestartRemembered() throws IOException {
    // --resume 是用户在说这次运行去哪里；那则记录只记得上一次在哪。把这两者弄反，会让该参数在重启
    // 之后立刻变得没法用。
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
        2, rememberedLines.size(), "被记住的会话不得得到本次运行的回合：\n"
            + rememberedLines);
    assertTrue(
        Files.readAllLines(sessions.resolve(chosen + ".jsonl")).stream()
            .anyMatch(line -> line.contains("in the chosen session")),
        "回合进入了参数点名的会话");
    assertTrue(ResumePoint.read(home).isEmpty(), "而那则记录已被用掉，不会留到以后触发");
  }

  @Test
  void aRememberedConversationThatIsGoneStartsFreshInsteadOfFailing() throws IOException {
    writeConfig("openai", server.openAiBaseUrl(), true);
    ResumePoint.write(home, "20260913-010000-abcd");   // 磁盘上从未存在过
    server.enqueue(MockModelServer.openAiText("fresh start"));

    Run run = runCli("-p", "hello", "--home", home.toString(),
        "--config", home.resolve("config.json").toString(), "-C", workspace.toString());

    assertEquals(0, run.exitCode(), run.err());
    assertTrue(run.out().contains("fresh start"), run.out());
    assertTrue(ResumePoint.read(home).isEmpty(), "而失效的记录不会滞留");
  }

  @Test
  void unknownFlagsAreRejectedBeforeAnyRequest() {
    Run run = runCli("--not-a-flag");

    assertEquals(2, run.exitCode(), "用法错误以退出码 2 结束");
    assertEquals(0, server.requestCount());
  }
}
