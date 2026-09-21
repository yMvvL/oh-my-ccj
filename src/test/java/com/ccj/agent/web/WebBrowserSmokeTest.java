package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.ccj.agent.core.ApprovalRules;
import com.ccj.agent.core.Config;
import com.ccj.agent.core.Provider;
import com.ccj.agent.demo.DemoProvider;
import com.ccj.agent.provider.ConfigModelCatalog;
import com.ccj.agent.provider.ProviderStore;
import com.ccj.agent.session.SessionStore;
import com.ccj.agent.tool.Tools;
import com.ccj.agent.workspace.WorkspaceStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

/**
 * 一个真的浏览器，驱动一个真的回合。
 *
 * <p>它比 {@link WebMarkdownTest} 那一类用例强在哪：那些用例把 {@code web/app.js} 里的一小块
 * 抠出来跑在 DOM 桩上，覆盖的是解析和渲染；布局、焦点、真的 {@code EventSource}，以及「按下
 * 发送之后服务器真的开了一个回合、回答真的回到了转录里」，长在它们够不着的地方。这里跑的是
 * 整条路——真的 HTTP、真的 SSE、真的工具执行、一个真的浏览器给的 DOM——所以页面那一层坏掉
 * 的时候，它是唯一会红的用例。
 *
 * <p>它会跳过什么场合：找不到能用的浏览器（{@code CCJ_CHROME} 优先，否则按一张 Linux 和 macOS
 * 的候选表去找），或者运行它的 node 没有全局 {@code WebSocket}（需要 22.4 或更新）。这两件事
 * 由脚本用退出码 3 说出来，这里把它记成 JUnit 的<em>跳过</em>，所以一台没有浏览器的构建机器
 * 照样是绿的，而报告里写明了为什么。跑不了的测试绝不能看起来像通过——这是这个包里早就立下的
 * 规矩（见 {@link WebSessionRowTest}）。
 */
class WebBrowserSmokeTest {

  private static final Path SCRIPT = Path.of("src", "test", "js", "browser-smoke.mjs");

  /** 页面让 demo 模型去读的那个文件：回答里必须出现它的内容，所以「回答了」等于「工具真的跑了」。 */
  private static final String FIXTURE = "smoke.txt";

  /**
   * 脚本自己的预算：起浏览器、跑一个回合、等回答。
   *
   * <p>这台机器上它跑 1.1 秒；而在维护者机器上全量套件跑到它时，同一个脚本曾经耗尽过 60 秒（转录空着、
   * 状态行空闲，说明连一个事件都没到）。那条用例证的是**行为**而不是速度，所以预算按「最慢的 CI 机器上
   * 起 Chrome」来定，而不是按这台机器的秒数。
   */
  private static final int BUDGET_SECONDS = 120;

  /** 进程的截止时间比脚本的预算长，这样先超时的是脚本，而它报出来的是一份带页面证据的说明。 */
  private static final int PROCESS_TIMEOUT_SECONDS = BUDGET_SECONDS + 60;

  /** 脚本用它说明「这台机器跑不了」，调用方把它记成跳过。 */
  private static final int SKIPPED = 3;

  @TempDir Path tmp;

  private AgentHub hub;
  private HttpApi api;

  @AfterEach
  void tearDown() {
    if (api != null) {
      api.close();
    }
    if (hub != null) {
      hub.close();
    }
  }

  @Test
  void thePageDrivesOneTurnInARealBrowser() throws Exception {
    Assumptions.assumeTrue(Files.exists(SCRIPT), "源码树不是当前工作目录");
    String node = findNode();
    Assumptions.assumeTrue(node != null, "PATH 里没有 node —— 浏览器冒烟跑不了");

    Path cwd = Files.createDirectories(tmp.resolve("ws"));
    // 哨兵必须有第一行：demo 提供方拿工具输出的第一行作答，所以回答里带着它就证明页面驱动的这
    // 个回合真的读到了工作目录里的这个文件。第二行让「共 2 行」那句话是真的。
    String sentinel = "SMOKE-" + UUID.randomUUID().toString().substring(0, 8);
    Files.writeString(cwd.resolve(FIXTURE), sentinel + "\n第二行\n");

    String url = serve(cwd);

    Run run =
        runScript(
            node,
            List.of(
                SCRIPT.toAbsolutePath().toString(),
                "--url",
                url,
                "--prompt",
                "read " + FIXTURE,
                "--expect",
                sentinel,
                "--timeout",
                Integer.toString(BUDGET_SECONDS)));

    assertTrue(
        run.finished(),
        "浏览器冒烟必须在 " + PROCESS_TIMEOUT_SECONDS + " 秒内收尾；目前的输出：\n" + run.output());
    if (run.code() == SKIPPED) {
      throw new TestAbortedException("这台机器跑不了浏览器冒烟：\n" + run.output());
    }
    if (run.code() != 0) {
      fail("浏览器冒烟失败了（退出码 " + run.code() + "）：\n" + run.output());
    }
    // 通过的那次也把脚本的话放出来：它说的是这个用例到底断言了什么，而这是失败时唯一有用的
    // 东西——构建日志里的「绿」应该能回答「测了什么」，而不是只回答「没红」。
    System.out.println(run.output());
  }

  /**
   * 起一个真的服务器，装配照抄 {@link WebApiTest}：临时主目录、demo 提供方、端口 0。
   *
   * <p>demo 提供方是这里唯一不需要密钥也不需要网络的模型，而它让「读一个文件」这种提示变成一次
   * 真的工具调用——只读工具不需要审批，所以这个用例不必自己去回答审批。
   */
  private String serve(Path cwd) throws IOException {
    ProviderStore providerStore = ProviderStore.open(tmp);
    // 端点留空：假模型没有端点，而填一个真的（resolved() 会填 OpenAI 的）就是让状态行指着一个
    // 没人会去的地方。输出上限用 Config 的默认值，工具和 CLI 都按它算。
    Config config =
        new Config(
            "demo",
            "demo",
            null,
            null,
            null,
            null,
            null,
            false,
            Config.DEFAULT_OUTPUT_LIMIT_BYTES,
            null,
            null);
    Provider provider = new DemoProvider();
    AgentHub.Settings settings =
        new AgentHub.Settings(
            "test",
            WorkspaceStore.open(tmp, cwd),
            null,
            tmp.resolve("config.json"),
            Map.of(),
            (candidate, environment) -> new DemoProvider(),
            new ConfigModelCatalog(providerStore),
            providerStore,
            null,
            false);
    hub = new AgentHub(provider, config, Tools.standard(), settings, SessionStore.create(tmp.resolve("sessions")));
    // 严格按 CLI 的接法接线：本项目的规则，放在应用主目录里。
    hub.setApprovalRules(ApprovalRules.open(tmp.resolve("approvals.json"), cwd));
    api = HttpApi.start(hub, new InetSocketAddress("127.0.0.1", 0), null, new Wallpapers(null));
    return "http://127.0.0.1:" + api.port();
  }

  /** 子进程的结果：它有没有收尾、退出码，以及它打印的一切。 */
  private record Run(boolean finished, int code, String output) {}

  /**
   * 跑那个 node 脚本，在它运行期间把输出收下来。
   *
   * <p>这个形状抄的是 {@link WebSessionRowTest#runNodeCases}，而那边不能复用：它既不能把 URL 传
   * 给脚本，也没法把「跑不了」（退出码 3）和「失败」分开——对它来说非 0 就是红。
   */
  private static Run runScript(String node, List<String> argv) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(node);
    command.addAll(argv);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    process.getOutputStream().close();

    // 在另一个线程上抽干：浏览器的一堆输出可以填满管道，而在我们等待的时候被填满的管道会把子
    // 进程卡到超时。只留尾巴——失败时有用的是末尾，而无界的缓冲区会让一个失控的脚本也变成内存
    // 问题。
    StringBuilder collected = new StringBuilder();
    Thread drain =
        new Thread(
            () -> {
              try (var in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                  collected.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                  if (collected.length() > 200_000) {
                    collected.delete(0, collected.length() - 100_000);
                  }
                }
              } catch (IOException ignored) {
                // 进程结束了或被杀了；报告的是退出值。
              }
            },
            "browser-smoke-drain");
    drain.setDaemon(true);
    drain.start();

    boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    drain.join(2_000);
    return new Run(finished, finished ? process.exitValue() : -1, collected.toString());
  }

  /** 能用的 node，找不到就是 null（调用方把它记成跳过，而不是失败）。 */
  private static String findNode() {
    for (String candidate : List.of("node", "nodejs")) {
      try {
        Process process =
            new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
        if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
          return candidate;
        }
      } catch (IOException err) {
        // 这个候选没用；看下一个。
      } catch (InterruptedException err) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }
}
