package com.ccj.agent.cli;

import com.ccj.agent.core.AgentLoop;
import com.ccj.agent.core.AgentOptions;
import com.ccj.agent.core.AppPaths;
import com.ccj.agent.core.ApprovalAnswer;
import com.ccj.agent.core.ApprovalRules;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.RuleApprover;
import com.ccj.agent.core.SpendLimit;
import com.ccj.agent.core.Checks;
import com.ccj.agent.session.CheckpointStore;
import com.ccj.agent.mcp.McpTools;
import com.ccj.agent.core.Compaction;
import com.ccj.agent.core.Config;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Prompts;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.TokenEstimate;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolRegistry;
import com.ccj.agent.core.ToolSpec;
import com.ccj.agent.demo.DemoProvider;
import com.ccj.agent.provider.ConfigModelCatalog;
import com.ccj.agent.provider.ProviderStore;
import com.ccj.agent.provider.Providers;
import com.ccj.agent.session.FileSession;
import com.ccj.agent.session.ResumePoint;
import com.ccj.agent.session.SessionStore;
import com.ccj.agent.tool.RestartTool;
import com.ccj.agent.tool.Tools;
import com.ccj.agent.ui.Ansi;
import com.ccj.agent.ui.ConsoleRenderer;
import com.ccj.agent.web.AgentHub;
import com.ccj.agent.workspace.WorkspaceStore;
import com.ccj.agent.web.HttpApi;
import com.ccj.agent.web.Tailnet;
import com.ccj.agent.web.WebToken;
import com.ccj.agent.web.Wallpapers;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把各个部件接起来，然后运行单个回合或一个 REPL。
 *
 * <p>每个入口点都返回退出码，并把它的流作为参数接收：这里没有任何地方调用 {@link System#exit}，
 * 也没有任何地方直接读 {@link System#in}，因此整个 CLI 可以在测试里用捕获的输出驱动、用标准输入
 * 喂数据。
 *
 * <p>失败策略：配置与提供方的问题报告到 stderr 并以 1 结束进程；flag 写错是用法错误，以 2 结束；
 * REPL 里失败的回合会被打印出来，会话继续，因为用户想待的地方恰恰是这个对话。还有一种状态存在，
 * 它不是失败：{@code restart} 安装好新构建的 jar 之后，这次运行正常结束并报告
 * {@link RestartTool#RESTART_EXIT}，启动器就是靠它知道应该启动新的 jar。
 */
public final class Cli {

  public static final String VERSION = "0.1.0";
  public static final String PROMPT = "ccj> ";
  public static final int DEFAULT_WEB_PORT = 6767;

  /** {@code --web-token} 未传入时承载 web token 的环境变量。 */
  public static final String ENV_WEB_TOKEN = "CCJ_WEB_TOKEN";

  /**
   * 短于这么多字符的、自己设的 token 会被点名一次。
   *
   * <p>16 不是一个密码学边界——它远在猜测可行范围之外——而是一条「这看起来像人随手编的」的界线：生成
   * 出来的 token 是 64 个十六进制字符，所以任何短于四分之一的都值一行文字。
   */
  private static final int SHORT_TOKEN_CHARS = 16;

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

  public Cli() {}

  /** 使用进程自身的流运行。 */
  public int run(String[] args) {
    return run(args, System.in, System.out, System.err);
  }

  /** 使用注入的流运行，并返回退出码。 */
  public int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
    CliOptions options;
    try {
      options = CliOptions.parse(args);
    } catch (CliOptions.UsageException e) {
      err.println("error: " + e.getMessage());
      err.println();
      err.println(CliOptions.usage());
      err.flush();
      return 2;
    }
    return execute(
        options,
        System.getenv(),
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)),
        out,
        err);
  }

  private int execute(
      CliOptions options, Map<String, String> env, BufferedReader in, PrintStream out, PrintStream err) {
    if (options.help()) {
      out.println(CliOptions.usage());
      out.flush();
      return 0;
    }
    if (options.version()) {
      out.println("oh-my-ccj " + VERSION);
      out.flush();
      return 0;
    }

    // 重启请求属于发出它的那次运行。这个进程可能运行多次——测试套件和任何嵌入方都会不止一次调用
    // `Cli`——而继承上一次的答案，会让下一次运行报告一个它从未请求过的重启。
    RestartTool.clearRequest();

    AppPaths paths =
        options.home() == null ? AppPaths.fromEnv(env) : new AppPaths(Path.of(options.home()));
    Path startDir = startingDir(options);

    // 工作区决定会话存放的位置；--workspace 选定一个，否则用启动时所在的目录去匹配注册表，再
    // 否则保持默认工作区为活动状态。
    WorkspaceStore workspaces;
    try {
      workspaces = WorkspaceStore.open(paths.home(), startDir);
      if (options.workspace() != null) {
        workspaces.activate(options.workspace());
      } else {
        workspaces.list().stream()
            .filter(workspace -> workspace.path().equals(startDir))
            .findFirst()
            .ifPresent(workspace -> workspaces.activate(workspace.name()));
      }
    } catch (RuntimeException e) {
      return fail(err, e);
    }
    Path sessionsDir = workspaces.active().sessionsDir();
    // 活动工作区拥有工作目录。侧边栏、--workspace 和会话历史都指向它，因此工具也在那里运行：
    // 从另一个目录启动的运行，绝不能悄悄地在别处干活——那正是「工作区是 oh-my-ccj」和「工具
    // 在你的主目录里跑」能同时成立的原因。-C 是唯一的覆盖方式，而状态行会在它生效时说明。
    Path cwd = options.cwd() == null ? workspaces.active().path() : startDir;
    Path cwdOverride = cwd.equals(workspaces.active().path()) ? null : cwd;

    if (options.listSessions()) {
      printSessions(sessionsDir, out);
      return 0;
    }

    // 每次运行一个 store；它记录的是哪个会话和哪个项目，则按回合、在运行该回合的线程上决定。
    CheckpointStore checkpoints = CheckpointStore.recording();
    Config config;
    Path configFile;
    try {
      configFile = options.config() == null ? paths.configFile() : Path.of(options.config());
      config = Config.layered(configFile, env, options.overrides());
      if (options.demo()) {
        // UI 报告的是*配置的*提供方，所以演示运行必须说明自己用的是 demo 提供方——显示一个并非
        // 正在作答的提供方，就是状态行在说谎。
        config = config.merge(demoConfig());
      }
    } catch (RuntimeException e) {
      return fail(err, e);
    }

    ToolRegistry tools;
    try {
      // 配置文件在构建工具之前读取，因为编辑类工具会带上这个文件声明的编辑后检查。Checks.from()
      // 持有的是路径而不是解析后的列表：会话运行期间新增的检查，在下一次编辑时就会生效，而不必等
      // 到重启。
      // shell 也从配置来：它就是「这台机器上跑命令的那个程序」，与 bash 工具和编辑后检查共用同一个
      // 答案。它在启动时读一次——设置表单不管它，改它和改绑定地址一样，是下一次启动的事。
      tools = Tools.standard(Checks.from(configFile), checkpoints, config.shell());
      // 用户自己的服务器，如果有的话。起不来的服务器是警告而不是拒绝：一个坏掉的条目不能拦住
      // 代理，但也不能一声不吭——缺失的能力和没人提过的能力，从这里看是一模一样的。
      McpTools mcp = McpTools.discover(paths.home().resolve("mcp.json"));
      mcp.registerInto(tools);
      for (String complaint : mcp.complaints()) {
        err.println("warning: " + complaint);
      }
      err.flush();
    } catch (RuntimeException e) {
      return fail(err, e);
    }
    if (options.tools()) {
      printTools(tools, out);
      return 0;
    }

    // 没有 flag 也没有提示词时：web UI 是默认前端，--repl 则是终端前端。
    boolean webMode = !options.repl() && options.print() == null;

    ProviderStore providerStore = ProviderStore.open(paths.home());

    Provider provider;
    if (options.demo()) {
      provider = new DemoProvider();
    } else {
      try {
        provider = Providers.create(config, env, providerStore);
      } catch (RuntimeException e) {
        if (!webMode) {
          return fail(err, e);
        }
        // 设置表单存在的意义正是修好这个：照常提供服务，让用户在那里配置。
        provider = null;
        err.println("尚未配置模型——请在 web UI（设置）里添加一个，或传入 --model");
        err.flush();
      }
    }

    if (!Files.isDirectory(cwd)) {
      err.println("error: 不是目录：" + cwd);
      err.flush();
      return 1;
    }

    FileSession session = null;
    try {
      try {
        session = openSession(options, sessionsDir, paths.home());
      } catch (RuntimeException e) {
        return fail(err, e);
      }

      boolean autoApprove = options.yolo() || Boolean.TRUE.equals(config.autoApprove());
      ConsoleRenderer renderer = new ConsoleRenderer(out, err, Ansi.enabled());
      AgentOptions agentOptions =
          new AgentOptions(
              options.demo() ? "demo" : config.model(),
              // 工作目录一并传入，这样项目自己的 CCJ.md 就能从那里读到——规则应该待在它们所
              // 描述的代码旁边。
              Prompts.system(config.systemPrompt(), cwd),
              config.temperature(),
              config.maxTokens(),
              config.reasoning(),
              config.maxContextTokens());

      if (options.demo() && options.print() == null) {
        out.println(
            "demo 模型——不需要密钥。可以试试：read README.md | run git status --short"
                + " | list src/**/*.java | search TODO");
        out.flush();
      }

      if (webMode) {
        return serveWeb(
            options,
            provider,
            tools,
            agentOptions,
            cwd,
            workspaces,
            cwdOverride,
            config,
            configFile,
            session,
            env,
            providerStore,
            paths.home(),
            out,
            err);
      }

      if (options.print() != null) {
        return oneShot(
            provider,
            tools,
            session,
            agentOptions,
            cwd,
            paths.home(),
            config,
            autoApprove,
            renderer,
            options.print(),
            in,
            out,
            err);
      }

      Repl repl =
          new Repl(
              provider,
              tools,
              sessionsDir,
              cwd,
              paths.home(),
              checkpoints,
              config,
              env,
              session,
              agentOptions,
              autoApprove,
              renderer,
              in,
              out,
              err);
      try {
        repl.run();
        // 以重启收尾的 REPL 是一次已完成的运行：会话已在磁盘上，而启动器会去启动那次重启安装
        // 的 jar。
        if (RestartTool.restartRequested()) {
          // 这个进程即将被替换，而新进程除非被告知，否则会从全新会话开始——所以把本 REPL 所在
          // 的对话为它记下来。这里读的是 REPL 当前的 id 而不是它启动时的那个：/new 和 /resume
          // 会把它换掉。
          ResumePoint.write(paths.home(), repl.sessionId());
          err.println("代理已安装新的 jar，正在重新启动 — 恢复会话 " + repl.sessionId());
          err.flush();
          return RestartTool.RESTART_EXIT;
        }
        return 0;
      } finally {
        // REPL 可能已经换到另一个会话（/new、/resume），那个才是当前活着的会话。
        repl.closeSession();
      }
    } finally {
      if (session != null) {
        session.close();
      }
      if (provider != null) {
        provider.close();
      }
    }
  }

  private int oneShot(
      Provider provider,
      ToolRegistry tools,
      FileSession session,
      AgentOptions agentOptions,
      Path cwd,
      Path home,
      Config config,
      boolean autoApprove,
      ConsoleRenderer renderer,
      String prompt,
      BufferedReader in,
      PrintStream out,
      PrintStream err) {
    AgentLoop loop =
        newLoop(
            provider, tools, session, agentOptions, cwd, home, config, autoApprove, renderer, in, out,
            err);
    try {
      loop.run(prompt);
      return RestartTool.restartRequested() ? RestartTool.RESTART_EXIT : 0;
    } catch (RuntimeException e) {
      return fail(err, e);
    } finally {
      renderer.reset();
    }
  }

  private AgentLoop newLoop(
      Provider provider,
      ToolRegistry tools,
      FileSession session,
      AgentOptions agentOptions,
      Path cwd,
      Path home,
      Config config,
      boolean autoApprove,
      ConsoleRenderer renderer,
      BufferedReader in,
      PrintStream out,
      PrintStream err) {
    ToolContext context =
        new ToolContext(
            cwd, gatedApprover(autoApprove, in, out, err, renderer, cwd, home), config.outputLimitBytes());
    return new AgentLoop(provider, tools, session, agentOptions, context, renderer);
  }

  /**
   * 终端上的关口，前面还挡着用户自己的规则；每个自动给出的回答都会播报到 stdout，这样没人被问到
   * 的那个决定，仍然是有人能看见的决定。
   */
  private Approver gatedApprover(
      boolean autoApprove,
      BufferedReader in,
      PrintStream out,
      PrintStream err,
      ConsoleRenderer renderer,
      Path cwd,
      Path home) {
    Approver interactive = approver(autoApprove, in, out, err, renderer);
    if (autoApprove) {
      // --yolo 就是「什么都别问我」：规则只会是通往同一答案的更慢路径。
      return interactive;
    }
    return new RuleApprover(
        ApprovalRules.open(home.resolve("approvals.json"), cwd), interactive, out::println);
  }

  /**
   * 有终端在看时是交互式关口，否则就是带解释的拒绝。这里沉默会看起来像卡死，所以非交互路径会点名
   * 那个工具，并说明怎样才能放行。
   */
  private Approver approver(
      boolean autoApprove,
      BufferedReader in,
      PrintStream out,
      PrintStream err,
      ConsoleRenderer renderer) {
    if (autoApprove) {
      return Approver.ALWAYS;
    }
    return request -> {
      if (in == null || System.console() == null) {
        err.println("已拒绝 " + request.title() + "：" + request.detail());
        err.println(
            "stdin 不是终端，因此 ccj 无法请求确认；"
                + "请带 --yolo 重新运行，以自动批准工具调用");
        err.flush();
        return ApprovalAnswer.DENY;
      }
      renderer.pauseSpinner();
      try {
        out.print(
            "批准 "
                + request.title()
                + " — "
                + request.detail()
                + "？[y/N/s=本会话/a=始终/d=以后都拒绝] ");
        out.flush();
        String answer = in.readLine();
        if (answer == null) {
          return ApprovalAnswer.DENY;
        }
        String normalized = answer.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
          case "y", "yes" -> ApprovalAnswer.ALLOW_ONCE;
          case "s", "session" -> ApprovalAnswer.ALLOW_SESSION;
          case "a", "always" -> ApprovalAnswer.ALLOW_ALWAYS;
          // 「以后都拒绝」和「始终允许」是一对：后者写一条 allow 规则，前者写一条 deny 规则。
          case "d", "never" -> ApprovalAnswer.DENY_ALWAYS;
          default -> ApprovalAnswer.DENY;
        };
      } catch (IOException e) {
        return ApprovalAnswer.DENY;
      } finally {
        renderer.resumeSpinner();
      }
    };
  }

  private FileSession openSession(CliOptions options, Path sessionsDir, Path home) {
    // 重启留下的东西：上一个进程所在的对话。无论这次运行最终打开什么，它都会最先被读取——并被
    // 花掉。
    //
    // 它为紧跟重启的那个进程回答一次「我们刚才到哪了」。把它留在原地，会让很久之后某次毫不相干
    // 的启动，被拖回一个用户早已离开的对话。
    Optional<String> previous = ResumePoint.read(home);
    ResumePoint.clear(home);

    if (options.resume() != null) {
      return SessionStore.open(sessionsDir, options.resume());
    }
    if (options.continueSession()) {
      List<SessionStore.Summary> all = SessionStore.list(sessionsDir);
      if (!all.isEmpty()) {
        return SessionStore.open(sessionsDir, all.get(0).id());
      }
    }
    // 放在最后检查，因为显式的 --resume 或 --continue 是用户在告诉这次运行该去哪，而那条记录
    // 只记得上一次在哪里。
    if (previous.isPresent()) {
      try {
        return SessionStore.open(sessionsDir, previous.get());
      } catch (RuntimeException e) {
        // 已被删除，或者属于另一个工作区。「打开上一个对话」不值得为此拒绝启动：这次运行改为
        // 从全新会话开始。
        return SessionStore.create(sessionsDir);
      }
    }
    return SessionStore.create(sessionsDir);
  }

  /**
   * 从 {@code start} 起（含）第一个没有任何东西在监听的端口；搜索找不到有用的结果时返回 -1
   * ——只有真空闲的建议才值得打印。
   */
  static int freePortFrom(int start) {
    for (int candidate = Math.max(1, start); candidate < start + 50 && candidate < 65536; candidate++) {
      try (ServerSocket probe = new ServerSocket()) {
        probe.setReuseAddress(true);
        probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), candidate), 1);
        return candidate;
      } catch (IOException taken) {
        // 已被占用；试下一个。
      }
    }
    return -1;
  }

  private static Config demoConfig() {
    return new Config(
        "demo", "demo", null, null, null, null, null, null, null, null, null, null, null, null,
        null);
  }

  /** 本次运行启动时所在的目录：{@code -C} 优先，否则用进程目录。 */
  private static Path startingDir(CliOptions options) {
    Path dir =
        options.cwd() == null
            ? Path.of(System.getProperty("user.dir", "."))
            : Path.of(options.cwd());
    return dir.toAbsolutePath().normalize();
  }

  /**
   * 提供浏览器 UI，并阻塞直到进程被中断。
   *
   * <p>不带 token 的非 loopback 绑定是被拒绝而不是被警告：这个 UI 能运行 shell 命令，因此把它
   * 暴露到网络上就是一块远程代码执行的面。
   *
   * <p>{@code provider} 可以为 null——UI 正是配置模型的地方，所以一份不可用的配置是要被展示出来
   * 的状态，而不是一个该为之死掉的错误。
   */
  private int serveWeb(
      CliOptions options,
      Provider provider,
      ToolRegistry tools,
      AgentOptions agentOptions,
      Path cwd,
      WorkspaceStore workspaces,
      Path cwdOverride,
      Config config,
      Path configFile,
      FileSession session,
      Map<String, String> env,
      ProviderStore providerStore,
      Path home,
      PrintStream out,
      PrintStream err) {
    int port = options.port() == null ? DEFAULT_WEB_PORT : options.port();
    if (port < 1 || port > 65535) {
      err.println("error: --port 必须介于 1 和 65535 之间");
      err.flush();
      return 2;
    }
    String requested = options.host() == null ? "" : options.host().strip();
    String token = webToken(options, env);
    List<InetSocketAddress> binds = new ArrayList<>();
    try {
      if (requested.isEmpty()) {
        // 默认两条路都开：loopback 给用户正坐着的这台机器，本机的 tailnet 地址给他们的手机——
        // 如果本机有的话。不用通配地址，因为通配地址也等于打开了咖啡馆的 wifi；点明地址的意义
        // 就在于，能连到这个端口的设备集合是用户自己挑出来的集合。
        binds.add(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        Tailnet.address().ifPresent(address -> binds.add(new InetSocketAddress(address, port)));
      } else if (requested.equalsIgnoreCase(Tailnet.FLAG_VALUE)) {
        Optional<InetAddress> tailnet = Tailnet.address();
        if (tailnet.isEmpty()) {
          err.println("error: 要求了 --host tailscale，但本机没有 tailnet 地址");
          err.println("  Tailscale 装好了、起来了吗？`tailscale ip -4` 应该能打印出一个");
          err.println("  否则请传入 --host <addr>，或者干脆不要这个 flag");
          err.flush();
          return 2;
        }
        binds.add(new InetSocketAddress(tailnet.get(), port));
      } else {
        binds.add(new InetSocketAddress(requested, port));
      }
    } catch (IllegalArgumentException e) {
      err.println("error: 无法绑定 " + requested + " — " + message(e));
      err.flush();
      return 2;
    }
    boolean reachesTheNetwork = binds.stream().anyMatch(bind -> !isLoopbackAddress(bind));
    // 通配地址是一个决定，而它很少是用户以为自己在做的那个：「绑定所有接口」包括咖啡馆的 wifi、酒店
    // 和任何同网段的设备，而 token 是唯一挡在它们和这个 shell 之间的东西。默认值刻意避开通配，所以
    // 走到这里说明用户是显式要求的——那就把代价说清楚，并给出更窄的那条路。
    for (InetSocketAddress bind : binds) {
      if (bind.getAddress() != null && bind.getAddress().isAnyLocalAddress()) {
        err.println(
            "warning: 绑定 " + requested + " 意味着这台机器所在的每一个网络都能连上来——"
                + "包括你现在所在的这个 wifi 上的任何设备。");
        err.println(
            "  想只在某个网络里可达，就点名那个地址（--host <addr>），或者用 `--host tailscale` "
                + "只服务 tailnet。");
        err.flush();
        break;
      }
    }
    // 自己设的 token 照用不误——这是用户的机器、用户的门——但一句短得出奇的 token 值得说出来：它是唯一
    // 挡在别人和你这个 shell 之间的东西，而这一行的成本是一次字符串长度比较。
    if (reachesTheNetwork && token != null && token.length() < SHORT_TOKEN_CHARS) {
      err.println(
          "warning: --web-token 只有 "
              + token.length()
              + " 个字符。这个 token 是这台机器上唯一的门禁，而短到可以猜的 token 会让人以为门是锁着的。");
      err.println(
          "  让它自己生成（64 个十六进制字符，存在 <home>/web-token 里），或者用 "
              + "`openssl rand -hex 32` 造一个。");
      err.flush();
    }
    if (reachesTheNetwork && token == null) {
      // 在这里生成一个 token 并存进主目录，因为重点就是敲一个词：`ccj`，手机就能用。每次都得
      // 传一遍的秘密，最终会落进 shell 历史、落进 `ps`，或者落进一个 alias——那又多了一个可能
      // 泄露的文件；而每次运行都变化的秘密，会让手机每次重启都被登出。
      try {
        token = WebToken.from(home);
      } catch (IOException e) {
        err.println("error: 拒绝在没有 token 的情况下把 web UI 提供到 " + binds);
        err.println("  这个 UI 能运行 shell 命令，而 token 也存不下来：" + message(e));
        err.println("  请传入 --web-token <secret>，或者指定一个可写的主目录");
        err.flush();
        return 2;
      }
    }

    AgentHub.Settings settings =
        new AgentHub.Settings(
            VERSION,
            workspaces,
            cwdOverride,
            configFile,
            env,
            (candidate, environment) -> Providers.create(candidate, environment, providerStore),
            new ConfigModelCatalog(providerStore),
            providerStore,
            null,
            Boolean.TRUE.equals(config.autoApprove()) || options.yolo(),
            options.subAgents());
    AgentHub hub = new AgentHub(provider, config, tools, settings, session);
    // 规则文件位于应用主目录，按项目区分：仓库无法自带一个「什么可以不经询问就运行」的决定，
    // 因为仓库写不到这里。
    hub.setApprovalRules(ApprovalRules.open(home.resolve("approvals.json"), cwd));
    Wallpapers wallpapers = Wallpapers.from(env, options.wallpapers());
    try (HttpApi api = HttpApi.start(hub, binds, token, wallpapers)) {
      List<String> urls = api.urls();
      out.println("oh-my-ccj " + VERSION + " — web UI：" + urls.get(0));
      for (int i = 1; i < urls.size(); i++) {
        out.println("                          也可通过 " + urls.get(i) + " 访问");
      }
      if (reachesTheNetwork) {
        out.println(
            "  第一个地址是本机（在那里不需要 token）；其余地址需要在 URL 里带上 token"
                + "（点开一次，token 就进了 cookie）");
      }
      out.println(
          (provider == null
                  ? "尚未配置模型——请在 UI 的「设置」里配置"
                  // 用的是配置里的名字，而不是实现的名字：用户把某个提供方叫作 "CommandCode"，
                  // 却显示 "openai"，就掩盖了到底是谁在作答，一个会话于是可以报告一个提供方，
                  // 却按另一个计费。
                  : "模型 "
                      + agentOptions.model()
                      + "（"
                      + (config.provider() == null ? provider.name() : config.provider())
                      + "）")
              + " — 会话 "
              + session.id()
              + " — "
              + (cwdOverride == null
                  ? "工作区 " + workspaces.activeName() + "（" + cwd + "）"
                  : "工作区 "
                      + workspaces.activeName()
                      + "（"
                      + workspaces.active().path()
                      + "），但 -C 把工具放在 " + cwd));
      out.println("按 Ctrl+C 停止");
      out.flush();
      if (!options.noOpen()) {
        // 用 loopback 那个 URL：本机永远能访问它，而且它不需要 token。用 tailnet 地址打开浏览器
        // 也行，但那会把一个秘密放进每个窗口的地址栏。
        openBrowser(urls.get(0), err);
      }
      // 提供服务会一直运行到有东西把它停下。Ctrl+C 是一种；`restart` 是另一种，而它不能是 hub
      // 完成的闩锁，因为 hub 是在此处之后才接好的，做决定的是注册表里的那个工具。每 200 毫秒
      // 轮询一个布尔值不要钱，还能让决定留在它产生的地方。
      while (!RestartTool.restartRequested()) {
        Thread.sleep(200);
      }
      // 新进程完全不知道屏幕上原本是哪个对话；把它记下来，前端就会回到用户离开的地方，而不是
      // 一个空对话。
      ResumePoint.write(home, session.id());
      err.println();
      err.println("代理已安装新的 jar，正在重新启动 — 恢复会话 " + session.id());
      err.flush();
      return RestartTool.RESTART_EXIT;
    } catch (IOException e) {
      err.println("error: 无法在 " + binds + " 上提供服务 — " + message(e));
      int free = freePortFrom(port + 1);
      err.println(
          "  那个端口上已经有别的东西了；"
              + (free > 0 ? "试试 --port " + free : "传入 --port <n>")
              + "把这个 UI 换到别处提供");
      err.flush();
      return 1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 0;
    } finally {
      hub.close();
    }
  }

  /**
   * 每个 web 请求都必须携带的 token：{@code --web-token}、{@code CCJ_WEB_TOKEN}、或者都没有。
   *
   * <p>这个环境变量存在的意义，是让「手机连笔记本」的配置不必把秘密粘到命令行上——那会是
   * 一个被 `ps`、shell 历史和任何正在被录制的终端读回去的文件——也不必粘到 shell alias 里，
   * 那又多了一个可能泄露的地方。每个会话导出一次，就把 token 挡在这两处之外。flag 依然优先，
   * 因此一次性运行可以用别的 token 而无需先取消什么；而空值表示两个来源都没有：空变量是
   * 「未设置」，不是空密码。
   *
   * <p>它在这里读取而不是在 {@code Config} 里，因为它不是模型设置：它关乎这次运行如何提供
   * 服务，并且刻意不存进配置文件——存了它的文件，就是主目录的每一份备份都会存下的文件。
   */
  static String webToken(CliOptions options, Map<String, String> env) {
    String flag = options == null ? null : options.webToken();
    if (flag != null && !flag.isBlank()) {
      return flag.strip();
    }
    String fromEnv = env == null ? null : env.get(ENV_WEB_TOKEN);
    return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.strip();
  }

  /** 当这个地址是其他设备能到达本机的位置时为 true。 */
  private static boolean isLoopbackAddress(InetSocketAddress bind) {
    InetAddress address = bind.getAddress();
    return address != null && address.isLoopbackAddress();
  }

  private static boolean isLoopback(String host) {
    String normalized = host.strip().toLowerCase(Locale.ROOT);
    if (normalized.equals("localhost")) {
      return true;
    }
    try {
      return InetAddress.getByName(normalized).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }

  private static void openBrowser(String url, PrintStream err) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    List<String> command =
        os.contains("mac")
            ? List.of("open", url)
            : os.contains("win")
                ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                : List.of("xdg-open", url);
    try {
      new ProcessBuilder(command)
          .redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .start();
    } catch (IOException e) {
      err.println("无法打开浏览器（" + message(e) + "）；请打开 " + url);
      err.flush();
    }
  }

  private static int fail(PrintStream err, RuntimeException e) {
    err.println("error: " + message(e));
    err.flush();
    return 1;
  }

  private static String message(Throwable e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  private static void printSessions(Path sessionsDir, PrintStream out) {
    List<SessionStore.Summary> sessions = SessionStore.list(sessionsDir);
    if (sessions.isEmpty()) {
      out.println("没有会话：" + sessionsDir);
      out.flush();
      return;
    }
    out.println("ID                              消息数    更新时间              预览");
    for (SessionStore.Summary session : sessions) {
      out.printf(
          "%-30s  %8d  %-19s  %s%n",
          session.id(),
          session.messageCount(),
          TIMESTAMP.format(session.lastModified()),
          session.preview());
    }
    out.flush();
  }

  private static void printTools(ToolRegistry tools, PrintStream out) {
    for (ToolSpec spec : tools.specs()) {
      String first = spec.description().lines().findFirst().orElse("").strip();
      out.printf("%-8s  %s%n", spec.name(), first);
    }
    out.flush();
  }

  private static void printConfig(Config config, Map<String, String> env, PrintStream out) {
    config.describe(env).forEach((key, value) -> out.printf("%-18s %s%n", key, value));
    out.flush();
  }

  private static String modelLabel(String model) {
    return model == null || model.isBlank() ? "（提供方默认）" : model;
  }

  /** REPL 的状态；每当会话、模型或审批模式变化时就重建。 */
  private final class Repl {

    private final Provider provider;
    private final ToolRegistry tools;
    private final Path sessionsDir;
    private final Path cwd;
    private final Path home;
    private final CheckpointStore checkpoints;
    private final Config config;
    private final Map<String, String> env;
    private final ConsoleRenderer renderer;
    private final BufferedReader in;
    private final PrintStream out;
    private final PrintStream err;
    private final AtomicReference<String> sessionId = new AtomicReference<>();

    private FileSession session;
    private AgentOptions agentOptions;
    private boolean autoApprove;
    private AgentLoop loop;

    Repl(
        Provider provider,
        ToolRegistry tools,
        Path sessionsDir,
        Path cwd,
        Path home,
        CheckpointStore checkpoints,
        Config config,
        Map<String, String> env,
        FileSession session,
        AgentOptions agentOptions,
        boolean autoApprove,
        ConsoleRenderer renderer,
        BufferedReader in,
        PrintStream out,
        PrintStream err) {
      this.provider = provider;
      this.tools = tools;
      this.sessionsDir = sessionsDir;
      this.cwd = cwd;
      this.home = home;
      this.checkpoints = checkpoints;
      this.config = config;
      this.env = env;
      this.session = session;
      this.agentOptions = agentOptions;
      this.autoApprove = autoApprove;
      this.renderer = renderer;
      this.in = in;
      this.out = out;
      this.err = err;
      this.sessionId.set(session.id());
      rebuild();
    }

    private void rebuild() {
      ToolContext context =
          new ToolContext(
              cwd,
              gatedApprover(autoApprove, in, out, err, renderer, cwd, home),
              config.outputLimitBytes());
      this.loop = new AgentLoop(provider, tools, session, agentOptions, context, renderer);
    }

    void run() {
      AtomicBoolean exiting = new AtomicBoolean();
      Thread hook =
          new Thread(
              () -> {
                if (!exiting.get()) {
                  err.println();
                  err.println("已中断 — 用这条命令恢复本会话：ccj --resume " + sessionId.get());
                  err.flush();
                }
              },
              "ccj-shutdown");
      Runtime.getRuntime().addShutdownHook(hook);
      out.println(
          "oh-my-ccj "
              + VERSION
              + " — 会话 "
              + session.id()
              + " — 模型 "
              + modelLabel(agentOptions.model())
              + " — 输入 /help 查看命令");
      out.flush();
      try {
        while (true) {
          out.print(PROMPT);
          out.flush();
          String line;
          try {
            line = in.readLine();
          } catch (IOException e) {
            err.println("error: " + message(e));
            err.flush();
            break;
          }
          if (line == null) {
            out.println();
            out.flush();
            break;
          }
          String input = line.strip();
          if (input.isEmpty()) {
            continue;
          }
          if (input.startsWith("/")) {
            if (!command(input)) {
              break;
            }
            continue;
          }
          turn(input);
          if (RestartTool.restartRequested()) {
            // 这个进程正在运行的 jar 已经是新的了，因此它没什么可做的了：继续下去只会让用户
            // 对着磁盘上已不存在的代码打字，而启动器才是负责启动那个工具所装 jar 的人。会话
            // 已在磁盘上，调用方会打印出如何恢复它。
            break;
          }
        }
      } finally {
        exiting.set(true);
        try {
          Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
          // 已经在关闭了；正在打印恢复提示的正是那个钩子。
        }
        renderer.reset();
      }
    }

    /**
     * 用模型写的一段摘要替换对话中较早的部分。
     *
     * <p>这次总结请求直接走提供方，而不经过主循环：跑一次循环会追加一条用户消息、并计入一个
     * 回合，而「总结你自己」既不是用户说过的话，也不是工作的一个回合。返回的对话会写成会话的
     * 下一代，因此被摘要替换掉的那个文件仍在磁盘上，仍然可读。
     */
    private void compact() {
      try {
        List<Message> before = session.messages();
        if (!Compaction.possible(before)) {
          out.println(
              "没什么可压缩的：这个对话比一次压缩所保留的 "
                  + Compaction.KEEP_EXCHANGES
                  + " 轮往来还短");
          return;
        }
        int beforeTokens = TokenEstimate.of(before);
        out.println("正在压缩…");
        out.flush();
        Message.Assistant reply =
            provider.complete(
                new Provider.Request(
                    agentOptions.model(),
                    null,
                    List.of(
                        new Message.User(
                            Compaction.INSTRUCTIONS + "\n\n" + Compaction.transcript(before))),
                    List.of(),
                    null,
                    null,
                    agentOptions.reasoning()),
                // 与网页端同一条规矩：摘要是一次真的模型请求，它的 token 属于这条账本——否则
                // `--max-total-tokens` 在每一次压缩上都少算一笔，而那是用户已经付掉的。
                event -> {
                  if (event instanceof Provider.Event.Usage usage) {
                    session.totals(
                        session
                            .totals()
                            .plus(
                                usage.inputTokens(),
                                usage.outputTokens(),
                                usage.cachedInputTokens(),
                                0,
                                0,
                                0,
                                0,
                                0));
                  }
                });
        String summary = reply.text() == null ? "" : reply.text().strip();
        if (summary.isEmpty()) {
          err.println("error: 模型返回了空摘要；没有任何内容被改动");
          return;
        }
        Path source = session.file();
        Compaction.Result result;
        try {
          result = Compaction.apply(before, summary, source.toString(), cwd);
        } catch (Compaction.NotWorthIt notWorthIt) {
          out.println("没有收益：" + notWorthIt.getMessage());
          out.println("这个对话还没长到值得用摘要省下什么。");
          return;
        }
        session.compactInto(result.messages(), session.totals().plusCompaction());
        int afterTokens = TokenEstimate.of(result.messages());
        out.println(
            "已压缩："
                + result.summarised()
                + " 条消息被总结，"
                + result.kept()
                + " 条原样保留 — "
                + beforeTokens
                + " → "
                + afterTokens
                + " token（估算），被替换的部分省下了 "
                + result.savedPercent()
                + "%");
        out.println("此前完整的对话仍然在 " + source + " 里");
      } catch (RuntimeException e) {
        err.println("error: " + message(e));
      } catch (Exception e) {
        err.println("error: 无法总结这个对话：" + message(e));
      }
    }

    private void turn(String input) {
      // 同一条上限，同一个判定：终端与网页是同一台机器上的同一条会话，两边不该有不同的规矩。判定在
      // 回合开始之前，而且**不进** checkpoints.beginTurn——一次被拒绝的输入没有改动任何文件，所以它
      // 不该留下一个可以退回的回合。
      String overBudget =
          SpendLimit.refusal(
              session.totals().inputTokens() + session.totals().outputTokens(),
              config.maxTotalTokens());
      if (overBudget != null) {
        err.println(overBudget);
        err.flush();
        return;
      }
      checkpoints.beginTurn(session.file(), cwd);
      try {
        loop.run(input);
      } catch (RuntimeException e) {
        err.println("error: " + message(e));
        err.flush();
      } finally {
        checkpoints.endTurn();
        renderer.reset();
        autoCompact();
      }
    }

    /** 把上一个回合改动过的东西放回原样，正如 {@code POST /api/undo} 为页面所做的那样。 */
    private void undo() {
      java.util.List<String> restored = checkpoints.undoLastTurn(session.file(), cwd);
      if (restored.isEmpty()) {
        out.println("没有可退回的：还没有任何回合改动过文件");
      } else {
        out.println(
            "已退回："
                + restored.size()
                + " 个文件已恢复为上一个回合之前的样子 — "
                + String.join(", ", restored)
                + "（还可以再退回 "
                + checkpoints.undoableTurns(session.file())
                + " 个回合）");
      }
      out.flush();
    }

    /**
     * 对话长过预算时在这里压缩，正如 web UI 所做的那样。
     *
     * <p>同样的规则、同样的理由：超过 `maxContextTokens` 之后，投影会开始省略工具输出、丢弃
     * 整组往来，而一段说明丢了什么的摘要，胜过对此保持沉默。没有配置预算就没有自动压缩——没有
     * 什么可超过的，而一次没人要的模型调用，不是可以拿来赌的东西。
     */
    private void autoCompact() {
      Integer budget = config.maxContextTokens();
      if (budget == null || budget <= 0 || !Compaction.possible(session.messages())) {
        return;
      }
      int estimate = TokenEstimate.of(session.messages());
      if (estimate <= budget) {
        return;
      }
      out.println(
          "这个对话已经超出它 "
              + budget
              + " token 的预算（约 "
              + estimate
              + "）；正在压缩 — /compact 可以手动做这件事，而完整的对话仍留在磁盘上");
      out.flush();
      compact();
    }

    private boolean command(String input) {
      String[] parts = input.split("\\s+", 2);
      String name = parts[0];
      String argument = parts.length > 1 ? parts[1].strip() : "";
      switch (name) {
        case "/exit" -> {
          return false;
        }
        case "/help" -> printHelp();
        case "/clear" -> {
          session.clear();
          out.println("会话 " + session.id() + " 的历史已清空");
        }
        case "/new" -> {
          FileSession next = SessionStore.create(sessionsDir);
          useSession(next);
          out.println("新会话 " + next.id());
        }
        case "/resume" -> {
          if (argument.isEmpty()) {
            out.println("用法：/resume <id>");
          } else {
            try {
              FileSession next = SessionStore.open(sessionsDir, argument);
              useSession(next);
              out.println(
                  "已恢复会话 " + next.id() + "（" + next.messages().size() + " 条消息）");
            } catch (RuntimeException e) {
              err.println("error: " + message(e));
            }
          }
        }
        case "/sessions" -> printSessions(sessionsDir, out);
        case "/model" -> {
          if (argument.isEmpty()) {
            out.println("模型：" + modelLabel(agentOptions.model()));
          } else {
            agentOptions =
                new AgentOptions(
                    argument,
                    agentOptions.system(),
                    agentOptions.temperature(),
                    agentOptions.maxTokens(),
                    agentOptions.reasoning(),
                    agentOptions.maxContextTokens());
            rebuild();
            out.println("模型已切换为 " + argument);
          }
        }
        case "/compact" -> compact();
        case "/undo" -> undo();
        case "/tools" -> printTools(tools, out);
        case "/config" -> printConfig(config, env, out);
        case "/yolo" -> {
          autoApprove = !autoApprove;
          rebuild();
          out.println("自动批准：" + (autoApprove ? "开" : "关"));
        }
        default ->
            out.println("未知命令：" + name + "（试试 /help）");
      }
      out.flush();
      err.flush();
      return true;
    }

    private void useSession(FileSession next) {
      session.close();
      session = next;
      sessionId.set(next.id());
      rebuild();
    }

    /**
     * 关闭 REPL 收尾时所在的会话。{@link #useSession} 已经关掉了它替换掉的那个，所以这里防的
     * 是 {@code /new} 和 {@code /resume} 在进程余生里多留一个打开的文件句柄。
     */
    void closeSession() {
      session.close();
    }

    /** REPL 当前所在的会话，{@code /new} 和 {@code /resume} 都可以把它换掉。 */
    String sessionId() {
      return sessionId.get();
    }

    private void printHelp() {
      out.println(
          """
          命令:
            /help            显示这份帮助
            /exit            离开 REPL
            /clear           忘掉当前对话（保留会话 id）
            /new             开始一个全新会话
            /resume <id>     按 id 重新打开一个会话
            /sessions        列出会话
            /model <name>    为本会话切换模型
            /compact         用摘要替换更早的回合，释放上下文
            /undo            把上一个回合改动过的文件放回原样
            /tools           列出可用的工具
            /config          显示生效中的配置
            /yolo            切换工具调用的自动批准

          其他任何输入都会发送给模型。
          """);
    }
  }
}
