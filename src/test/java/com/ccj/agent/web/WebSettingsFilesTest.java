package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.ApprovalAnswer;
import com.ccj.agent.core.ApprovalRequest;
import com.ccj.agent.core.ApprovalRules;
import com.ccj.agent.core.Checks;
import com.ccj.agent.core.Config;
import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import com.ccj.agent.provider.ConfigModelCatalog;
import com.ccj.agent.provider.ProviderStore;
import com.ccj.agent.session.FileSession;
import com.ccj.agent.session.SessionStore;
import com.ccj.agent.tool.Tools;
import com.ccj.agent.workspace.WorkspaceStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 设置面板里的两件事：审批规则，和编辑后要跑的检查。
 *
 * <p>它们在此之前只能手编文件——{@code approvals.json}、配置文件里的 {@code checks}——而那正是手机上做
 * 不到的事。这里钉住的是：两个端点读得出来、写得回去，写出来的是和手编一样的东西，文件里的其他内容一个都
 * 没动，无效的输入带着 core 自己那句话被拒，而删掉一条规则之后，同一条命令真的又走回了审批。最后这一条是
 * 整个功能的理由，所以它不靠读文本来断言，而是跑一个真实的回合去看那条命令到底跑没跑。
 */
class WebSettingsFilesTest {

  @TempDir Path tmp;

  private final HttpClient client = HttpClient.newHttpClient();

  private Path cwd;
  private Path configFile;
  private ScriptedProvider provider;
  private ProviderStore providerStore;
  private AgentHub hub;
  private HttpApi api;
  private String origin;

  @BeforeEach
  void setUp() throws IOException {
    cwd = Files.createDirectories(tmp.resolve("ws"));
    configFile = tmp.resolve("config.json");
    providerStore = ProviderStore.open(tmp);
    provider = new ScriptedProvider();
    hub = hub(provider);
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null);
    origin = "http://127.0.0.1:" + api.port();
  }

  @AfterEach
  void tearDown() {
    if (api != null) {
      api.close();
    }
    if (hub != null) {
      hub.close();
    }
  }

  /** 规则文件在应用主目录里，按项目分键——接法与 CLI 完全一样。 */
  private Path approvalsFile() {
    return tmp.resolve("approvals.json");
  }

  private AgentHub hub(Provider initial) {
    AgentHub built =
        new AgentHub(
            initial,
            new Config(
                    "openai", "mock-model", "http://mock.invalid/v1", "sk-test", null, null, null,
                    null, 0, null, null)
                .resolved(),
            Tools.standard(),
            new AgentHub.Settings(
                "test",
                WorkspaceStore.open(tmp, cwd),
                null,
                configFile,
                Map.of(),
                (candidate, env) -> new ScriptedProvider(),
                new ConfigModelCatalog(providerStore),
                providerStore,
                null,
                false),
            SessionStore.create(tmp.resolve("sessions")));
    built.setApprovalRules(ApprovalRules.open(approvalsFile(), cwd));
    return built;
  }

  // ------------------------------------------------------------------ 审批规则

  @Test
  void aRuleCanBeAddedListedAndRemoved() throws Exception {
    assertFalse(Files.exists(approvalsFile()), "一开始没有规则文件");

    JsonNode added =
        postJson(
            "/api/rules",
            "{\"action\":\"add\",\"effect\":\"allow\",\"tool\":\"bash\",\"command\":\"git status\"}");

    assertEquals(approvalsFile().toString(), added.path("file").asText(), added.toString());
    assertEquals(cwd.toString(), added.path("project").asText());
    assertEquals(1, added.path("rules").size(), added.toString());
    JsonNode rule = added.path("rules").get(0);
    assertEquals("allow", rule.path("effect").asText());
    assertEquals("bash", rule.path("tool").asText());
    assertEquals("git status", rule.path("command").asText());
    assertTrue(rule.path("path").isNull(), "主语只有一个");
    // 读法由 ApprovalRules 自己给出，面板直接显示它，所以这里钉住它与规则同序。
    assertEquals(List.of("允许  bash git status"), strings(added.path("describe")));

    // 一次真实的审批判定：文件里那条规则确实放行这条命令，别的都不放行。
    ApprovalRules onDisk = ApprovalRules.open(approvalsFile(), cwd);
    assertEquals(Optional.of(true), onDisk.verdict(command("git status")));
    assertEquals(Optional.empty(), onDisk.verdict(command("git push")));

    JsonNode removed =
        postJson(
            "/api/rules",
            "{\"action\":\"remove\",\"effect\":\"allow\",\"tool\":\"bash\",\"command\":\"git status\"}");

    assertEquals(0, removed.path("rules").size(), removed.toString());
    assertEquals(
        Optional.empty(),
        ApprovalRules.open(approvalsFile(), cwd).verdict(command("git status")),
        "删掉之后，同一条命令不再被放行");
  }

  @Test
  void aDenyRuleIsWrittenToTheDenyListAndIsWhatStopsTheCall() throws Exception {
    JsonNode added =
        postJson(
            "/api/rules",
            "{\"action\":\"add\",\"effect\":\"deny\",\"tool\":\"bash\",\"command\":\"rm -rf /\"}");

    assertEquals("deny", added.path("rules").get(0).path("effect").asText());
    assertEquals(List.of("拒绝   bash rm -rf /"), strings(added.path("describe")));
    // 两个列表都只在需要时出现：一条拒绝规则不该顺手建出一个空的 allow。
    assertFalse(Files.readString(approvalsFile()).contains("\"allow\""), Files.readString(approvalsFile()));
    assertEquals(Optional.of(false), ApprovalRules.open(approvalsFile(), cwd).verdict(command("rm -rf /")));
  }

  @Test
  void anInvalidRuleIsRefusedWithTheCoreSentenceAndNeverLands() throws Exception {
    // 只写工具名等于放行它的每一次调用——core 早就这么说了，面板只是把同一句话搬到屏幕上。
    HttpResponse<String> tooBroad =
        post("/api/rules", "{\"action\":\"add\",\"effect\":\"allow\",\"tool\":\"bash\"}");

    assertEquals(400, tooBroad.statusCode(), tooBroad.body());
    assertTrue(
        tooBroad.body().contains("针对 'bash' 的规则必须指明 'command' 或 'path'"), tooBroad.body());
    assertFalse(Files.exists(approvalsFile()), "被拒的规则绝不能落盘");

    HttpResponse<String> wildcard =
        post(
            "/api/rules",
            "{\"action\":\"add\",\"effect\":\"allow\",\"tool\":\"bash\",\"command\":\"git * status\"}");

    assertEquals(400, wildcard.statusCode(), wildcard.body());
    assertTrue(wildcard.body().contains("唯一允许使用的通配符"), wildcard.body());

    assertEquals(
        400,
        post("/api/rules", "{\"action\":\"add\",\"effect\":\"maybe\",\"tool\":\"bash\",\"command\":\"ls\"}")
            .statusCode(),
        "effect 只能是 allow 或 deny");
    assertEquals(
        400,
        post("/api/rules", "{\"action\":\"nope\",\"effect\":\"allow\",\"tool\":\"bash\",\"command\":\"ls\"}")
            .statusCode(),
        "action 只能是 add 或 remove");
    assertEquals(400, post("/api/rules", "{\"action\":\"add\",\"effect\":\"allow\"}").statusCode());

    // 删一条不在文件里的规则不是一次编辑，而是一次需要解释的请求。
    postJson(
        "/api/rules",
        "{\"action\":\"add\",\"effect\":\"allow\",\"tool\":\"bash\",\"command\":\"ls\"}");
    HttpResponse<String> missing =
        post(
            "/api/rules",
            "{\"action\":\"remove\",\"effect\":\"allow\",\"tool\":\"bash\",\"command\":\"cat\"}");
    assertEquals(400, missing.statusCode(), missing.body());
  }

  /**
   * 这个功能的理由：规则改了之后，下一次工具调用就该按新的来——加一条允许，那条命令不问就跑；删掉它，
   * 同一条命令又走回审批。所以这里不读文本，而是跑两个真实的回合。
   */
  @Test
  void addingAndThenDeletingARuleChangesTheNextTurnsDecision() throws Exception {
    CopyOnWriteArrayList<AgentHub.Event> events = new CopyOnWriteArrayList<>();
    hub.subscribe(events::add);
    String command = "printf hi > proof.txt";

    postJson(
        "/api/rules",
        "{\"action\":\"add\",\"effect\":\"allow\",\"tool\":\"bash\",\"command\":\"" + command + "\"}");
    provider.reply(bashCall(command));
    provider.reply(Message.Assistant.text("ran without asking"));

    assertEquals(AgentHub.Submit.STARTED, hub.submit("first"));
    awaitEvent(events, "done", 1);

    assertTrue(Files.exists(cwd.resolve("proof.txt")), "允许规则让这条命令不经询问就跑了");
    assertEquals(0, count(events, "approval"), "规则作答了，所以没有问任何人");

    Files.delete(cwd.resolve("proof.txt"));

    postJson(
        "/api/rules",
        "{\"action\":\"remove\",\"effect\":\"allow\",\"tool\":\"bash\",\"command\":\"" + command + "\"}");
    provider.reply(bashCall(command));
    provider.reply(Message.Assistant.text("asked again"));

    assertEquals(AgentHub.Submit.STARTED, hub.submit("second"));
    AgentHub.Event approval = awaitEvent(events, "approval", 1);
    assertTrue(hub.resolveApproval(approval.payload().path("id").asText(), ApprovalAnswer.DENY));
    awaitEvent(events, "done", 2);

    assertFalse(
        Files.exists(cwd.resolve("proof.txt")),
        "规则没了，所以它又要问人——而这次被拒绝，于是它没有跑");
  }

  // ------------------------------------------------------------------ 编辑后检查

  @Test
  void checksCanBeReadReplacedAndRejected() throws Exception {
    Files.writeString(
        configFile,
        """
        {
          "provider": "openai",
          "maxTokens": 4096,
          "checks": [{"glob": "**/*.java", "command": "mvn -q -o -DskipTests compile"}]
        }
        """);

    JsonNode listed = json("/api/checks");
    assertEquals(configFile.toString(), listed.path("file").asText());
    assertEquals(1, listed.path("checks").size(), listed.toString());
    assertEquals("**/*.java", listed.path("checks").get(0).path("glob").asText());
    // 超时一并回传，因为 POST 是整份替换：看不见的字段会在重写时悄悄变成默认值。
    assertEquals(120, listed.path("checks").get(0).path("timeoutSeconds").asInt());

    JsonNode replaced =
        postJson(
            "/api/checks",
            "{\"checks\":[{\"glob\":\"**/*.py\",\"command\":\"pytest -q\",\"timeoutSeconds\":30}]}");

    assertEquals(1, replaced.path("checks").size(), replaced.toString());
    assertEquals("pytest -q", replaced.path("checks").get(0).path("command").asText());

    // 文件里其他一切原样留着，只有 checks 键变了——手写的系统提示词、预算都得活下来。
    JsonNode stored = Json.parse(Files.readString(configFile));
    assertEquals("openai", stored.path("provider").asText(), stored.toString());
    assertEquals(4096, stored.path("maxTokens").asInt());
    assertEquals("**/*.py", stored.path("checks").get(0).path("glob").asText());
    assertEquals(30, stored.path("checks").get(0).path("timeoutSeconds").asInt());

    // 被拒的保存把文件原样放回去，用的是 Checks 自己那句话。
    String before = Files.readString(configFile);
    HttpResponse<String> refused = post("/api/checks", "{\"checks\":[{\"glob\":\"**/*.py\"}]}");
    assertEquals(400, refused.statusCode(), refused.body());
    assertTrue(refused.body().contains("没有 'command'"), refused.body());
    assertEquals(before, Files.readString(configFile), "被拒的保存一个字节都不该改");

    HttpResponse<String> notAnArray = post("/api/checks", "{\"checks\":\"nope\"}");
    assertEquals(400, notAnArray.statusCode(), notAnArray.body());
    assertTrue(notAnArray.body().contains("必须是一组"), notAnArray.body());
    assertEquals(before, Files.readString(configFile));
  }

  @Test
  void anEmptyListRemovesTheChecksKeyAndKeepsEverythingElse() throws Exception {
    Files.writeString(configFile, "{\"model\":\"m\",\"checks\":[{\"glob\":\"**\",\"command\":\"make\"}]}");

    JsonNode cleared = postJson("/api/checks", "{\"checks\":[]}");

    assertEquals(0, cleared.path("checks").size(), cleared.toString());
    JsonNode stored = Json.parse(Files.readString(configFile));
    assertFalse(stored.has("checks"), "空列表就是没有检查，而不是一个空数组：" + stored);
    assertEquals("m", stored.path("model").asText(), "其他键照旧");
  }

  /**
   * 面板上写的「保存后对下一次编辑生效，不必重启」得有依据：{@link Checks} 持有的是路径，所以它每次查找
   * 都重新读文件。这条边界是面板对用户的承诺，所以它被钉在这里，而不是靠读注释相信它。
   */
  @Test
  void aSavedCheckIsPickedUpWithoutARestart() throws Exception {
    Files.writeString(configFile, "{\"checks\":[]}");
    Checks checks = Checks.from(configFile);
    assertTrue(checks.forPath(cwd.resolve("Main.java"), cwd).isEmpty());

    postJson(
        "/api/checks",
        "{\"checks\":[{\"glob\":\"**/*.java\",\"command\":\"javac Main.java\"}]}");

    assertEquals(
        "javac Main.java",
        checks.forPath(cwd.resolve("Main.java"), cwd).map(Checks.Check::command).orElse(""),
        "同一个进程里、同一次编辑上，新检查立刻可见");
  }

  // ------------------------------------------------------------------ 页面

  @Test
  void theSettingsDialogHasBothSectionsWiredToTheirEndpoints() throws IOException {
    String page = Files.readString(Path.of("src", "main", "resources", "web", "index.html"));
    String script = Files.readString(Path.of("src", "main", "resources", "web", "app.js"));

    for (String id : List.of("cfg-rules-section", "cfg-rules-list", "cfg-rule-form", "cfg-checks-section",
        "cfg-checks-list", "cfg-check-form")) {
      assertTrue(page.contains("id=\"" + id + "\""), "设置对话框缺少 " + id + "：" + page);
    }
    assertTrue(page.contains("始终允许"), "空列表要说清规则从哪来");
    assertTrue(script.contains("'/api/rules'"), "页面必须读/写规则");
    assertTrue(script.contains("'/api/checks'"), "页面必须读/写检查");
    assertTrue(script.contains("function renderRules(") && script.contains("function renderChecks("));
    // 删除是破坏性的，所以它走和别处一样的两段式手势，而不是一次点击就动手。
    assertTrue(script.contains("deleteControl(actions, '删除'"), "规则与检查的删除要用同一个手势");
  }

  @Test
  void theProtocolPickerOffersEveryProtocolAndSuggestsItsKeyVariable() throws IOException {
    String page = Files.readString(Path.of("src", "main", "resources", "web", "index.html"));
    String script = Files.readString(Path.of("src", "main", "resources", "web", "app.js"));

    // 协议下拉是手写的 HTML，而「有哪些协议」是 ProviderDefinition.KINDS 说的事实：多一种协议要同时改两处，
    // 所以这里把两边钉在一起——漏掉一处就是用户在设置面板里根本看不见那个协议。
    for (String kind : com.ccj.agent.core.ProviderDefinition.KINDS) {
      assertTrue(
          page.contains("value=\"" + kind + "\""), "协议下拉缺少 " + kind + "：" + page);
    }
    // 每个协议默认读哪个环境变量：填错的那一格会把用户引到一个永远不会被发送的密钥上。
    assertTrue(script.contains("function defaultKeyEnvFor("), "密钥变量的默认值要有名字");
    assertTrue(script.contains("return 'GEMINI_API_KEY'"), script);
    assertTrue(script.contains("dom.cfgNewKind.addEventListener('change'"), "换协议要跟着换那一格");
  }

  // --------------------------------------------------------------- HTTP 与脚本

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(origin + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String json) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(origin + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode json(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertEquals(200, response.statusCode(), response.body());
    return Json.parse(response.body());
  }

  private JsonNode postJson(String path, String json) throws Exception {
    HttpResponse<String> response = post(path, json);
    assertEquals(200, response.statusCode(), response.body());
    return Json.parse(response.body());
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(node -> out.add(node.asText()));
    return out;
  }

  private static ApprovalRequest command(String command) {
    return ApprovalRequest.command(command, "detail");
  }

  private static Message.Assistant bashCall(String command) {
    return new Message.Assistant(
        "",
        List.of(
            new Message.ToolCall(
                "call_bash", "bash", Json.write(Json.object().put("command", command)))));
  }

  private static int count(List<AgentHub.Event> events, String type) {
    int n = 0;
    for (AgentHub.Event event : events) {
      if (event.type().equals(type)) {
        n++;
      }
    }
    return n;
  }

  /** 等到第 {@code count} 个某类型的事件；超时是测试失败，而不是一个悄悄通过的断言。 */
  private static AgentHub.Event awaitEvent(List<AgentHub.Event> events, String type, int count)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      List<AgentHub.Event> matching = new ArrayList<>();
      for (AgentHub.Event event : events) {
        if (event.type().equals(type)) {
          matching.add(event);
        }
      }
      if (matching.size() >= count) {
        return matching.get(count - 1);
      }
      Thread.sleep(20);
    }
    List<String> seen = new ArrayList<>();
    events.forEach(event -> seen.add(event.type()));
    throw new AssertionError("没有等到第 " + count + " 个 '" + type + "' 事件；看到的是 " + seen);
  }

  /** 脚本化的提供方：每个请求一个排好队的助手回合。 */
  private static final class ScriptedProvider implements Provider {

    private final Deque<Message.Assistant> script = new ArrayDeque<>();

    ScriptedProvider reply(Message.Assistant assistant) {
      script.add(assistant);
      return this;
    }

    @Override
    public String name() {
      return "mock";
    }

    @Override
    public Message.Assistant complete(Request request, Consumer<Event> listener) {
      return script.isEmpty() ? Message.Assistant.text("(no scripted reply left)") : script.poll();
    }
  }
}
