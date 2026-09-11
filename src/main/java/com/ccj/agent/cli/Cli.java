package com.ccj.agent.cli;

import com.ccj.agent.core.AgentLoop;
import com.ccj.agent.core.AgentOptions;
import com.ccj.agent.core.AppPaths;
import com.ccj.agent.core.Approver;
import com.ccj.agent.core.Config;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolRegistry;
import com.ccj.agent.core.ToolSpec;
import com.ccj.agent.demo.DemoProvider;
import com.ccj.agent.provider.ConfigModelCatalog;
import com.ccj.agent.provider.ProviderStore;
import com.ccj.agent.provider.Providers;
import com.ccj.agent.session.FileSession;
import com.ccj.agent.session.SessionStore;
import com.ccj.agent.tool.Tools;
import com.ccj.agent.ui.Ansi;
import com.ccj.agent.ui.ConsoleRenderer;
import com.ccj.agent.web.AgentHub;
import com.ccj.agent.workspace.WorkspaceStore;
import com.ccj.agent.web.HttpApi;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wires the pieces together and runs either one turn or a REPL.
 *
 * <p>Every entry point returns an exit code and takes its streams as arguments: nothing here calls
 * {@link System#exit} and nothing reads {@link System#in} directly, so the whole CLI can be driven
 * from a test with captured output and fed stdin.
 *
 * <p>Failure policy: configuration and provider problems are reported on stderr and end the process
 * with 1; a bad flag is a usage error with 2; a failed turn inside the REPL is printed and the
 * session continues, because the conversation is exactly where the user wants to stay.
 */
public final class Cli {

  public static final String VERSION = "0.1.0";
  public static final String PROMPT = "ccj> ";
  public static final int DEFAULT_WEB_PORT = 6767;

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

  public Cli() {}

  /** Runs with the process streams. */
  public int run(String[] args) {
    return run(args, System.in, System.out, System.err);
  }

  /** Runs with injected streams and returns the exit code. */
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

    AppPaths paths =
        options.home() == null ? AppPaths.fromEnv(env) : new AppPaths(Path.of(options.home()));
    Path startDir = startingDir(options);

    // The workspace decides where sessions live; --workspace picks one, otherwise the directory we
    // were started in is matched against the registry, otherwise the default stays active.
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
    Path cwd = options.workspace() == null ? startDir : workspaces.active().path();
    Path cwdOverride = cwd.equals(workspaces.active().path()) ? null : cwd;

    if (options.listSessions()) {
      printSessions(sessionsDir, out);
      return 0;
    }

    ToolRegistry tools;
    try {
      tools = Tools.standard();
    } catch (RuntimeException e) {
      return fail(err, e);
    }
    if (options.tools()) {
      printTools(tools, out);
      return 0;
    }

    Config config;
    Path configFile;
    try {
      configFile = options.config() == null ? paths.configFile() : Path.of(options.config());
      config = Config.layered(configFile, env, options.overrides());
      if (options.demo()) {
        // The UI reports the *configured* provider, so a demo run has to say it is the demo one —
        // showing a provider that is not the one answering would be a lie the status line tells.
        config = config.merge(demoConfig());
      }
    } catch (RuntimeException e) {
      return fail(err, e);
    }

    // No flag, no prompt: the web UI is the default front end, and --repl is the terminal one.
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
        // The settings form exists to fix exactly this: serve anyway and let it be configured there.
        provider = null;
        err.println("no model configured — add one in the web UI (Settings), or pass --model");
        err.flush();
      }
    }

    if (!Files.isDirectory(cwd)) {
      err.println("error: not a directory: " + cwd);
      err.flush();
      return 1;
    }

    FileSession session = null;
    try {
      try {
        session = openSession(options, sessionsDir);
      } catch (RuntimeException e) {
        return fail(err, e);
      }

      boolean autoApprove = options.yolo() || Boolean.TRUE.equals(config.autoApprove());
      ConsoleRenderer renderer = new ConsoleRenderer(out, err, Ansi.enabled());
      AgentOptions agentOptions =
          new AgentOptions(
              options.demo() ? "demo" : config.model(),
              config.systemPrompt(),
              config.temperature(),
              config.maxTokens(),
              config.maxSteps(), null);

      if (options.demo() && options.print() == null) {
        out.println(
            "demo model — no key needed. Try: read README.md | run git status --short"
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
            config,
            autoApprove,
            renderer,
            options.print(),
            in,
            out,
            err);
      }

      new Repl(
              provider,
              tools,
              sessionsDir,
              cwd,
              config,
              env,
              session,
              agentOptions,
              autoApprove,
              renderer,
              in,
              out,
              err)
          .run();
      return 0;
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
      Config config,
      boolean autoApprove,
      ConsoleRenderer renderer,
      String prompt,
      BufferedReader in,
      PrintStream out,
      PrintStream err) {
    AgentLoop loop =
        newLoop(provider, tools, session, agentOptions, cwd, config, autoApprove, renderer, in, out, err);
    try {
      loop.run(prompt);
      return 0;
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
      Config config,
      boolean autoApprove,
      ConsoleRenderer renderer,
      BufferedReader in,
      PrintStream out,
      PrintStream err) {
    ToolContext context =
        new ToolContext(
            cwd, approver(autoApprove, in, out, err, renderer), config.outputLimitBytes());
    return new AgentLoop(provider, tools, session, agentOptions, context, renderer);
  }

  /**
   * Interactive gate when a terminal is watching, deny-with-explanation otherwise. Silence here
   * would look like a hang, so the non-interactive path names the tool and how to opt in.
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
    return (title, detail) -> {
      if (in == null || System.console() == null) {
        err.println("denied " + title + ": " + detail);
        err.println(
            "stdin is not a terminal, so ccj cannot ask for confirmation;"
                + " re-run with --yolo to approve tool calls automatically");
        err.flush();
        return false;
      }
      renderer.pauseSpinner();
      try {
        out.print("approve " + title + " — " + detail + "? [y/N] ");
        out.flush();
        String answer = in.readLine();
        if (answer == null) {
          return false;
        }
        String normalized = answer.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("y") || normalized.equals("yes");
      } catch (IOException e) {
        return false;
      } finally {
        renderer.resumeSpinner();
      }
    };
  }

  private FileSession openSession(CliOptions options, Path sessionsDir) {
    if (options.resume() != null) {
      return SessionStore.open(sessionsDir, options.resume());
    }
    if (options.continueSession()) {
      List<SessionStore.Summary> all = SessionStore.list(sessionsDir);
      if (!all.isEmpty()) {
        return SessionStore.open(sessionsDir, all.get(0).id());
      }
    }
    return SessionStore.create(sessionsDir);
  }

  /**
   * The first port at or after {@code start} that nothing is listening on, or -1 when the search
   * finds nothing useful — a suggestion is only worth printing if it is actually free.
   */
  static int freePortFrom(int start) {
    for (int candidate = Math.max(1, start); candidate < start + 50 && candidate < 65536; candidate++) {
      try (ServerSocket probe = new ServerSocket()) {
        probe.setReuseAddress(true);
        probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), candidate), 1);
        return candidate;
      } catch (IOException taken) {
        // Occupied; try the next one.
      }
    }
    return -1;
  }

  private static Config demoConfig() {
    return new Config(
        "demo", "demo", null, null, null, null, null, null, null, null, null, null);
  }

  /** The directory this run starts in: {@code -C} wins, otherwise the process directory. */
  private static Path startingDir(CliOptions options) {
    Path dir =
        options.cwd() == null
            ? Path.of(System.getProperty("user.dir", "."))
            : Path.of(options.cwd());
    return dir.toAbsolutePath().normalize();
  }

  /**
   * Serves the browser UI and blocks until the process is interrupted.
   *
   * <p>A non-loopback bind without a token is refused rather than warned about: the UI can run shell
   * commands, so exposing it on a network is a remote code execution surface.
   *
   * <p>{@code provider} may be null — the UI is where a model gets configured, so an unusable
   * configuration is a state to be shown, not an error to die on.
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
      PrintStream out,
      PrintStream err) {
    String host =
        options.host() == null || options.host().isBlank() ? "127.0.0.1" : options.host().strip();
    int port = options.port() == null ? DEFAULT_WEB_PORT : options.port();
    if (port < 1 || port > 65535) {
      err.println("error: --port must be between 1 and 65535");
      err.flush();
      return 2;
    }
    if (!isLoopback(host) && (options.webToken() == null || options.webToken().isBlank())) {
      err.println("error: refusing to serve the web UI on " + host + " without a token");
      err.println("  the UI can run shell commands; pass --web-token <secret> or bind 127.0.0.1");
      err.flush();
      return 2;
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
            Boolean.TRUE.equals(config.autoApprove()) || options.yolo());
    AgentHub hub = new AgentHub(provider, config, tools, settings, session);
    try (HttpApi api = HttpApi.start(hub, new InetSocketAddress(host, port), options.webToken())) {
      out.println("oh-my-ccj " + VERSION + " — web UI: " + api.url());
      out.println(
          (provider == null
                  ? "no model configured — Settings in the UI"
                  : "model " + agentOptions.model() + " (" + provider.name() + ")")
              + " — session "
              + session.id()
              + " — workspace "
              + workspaces.activeName()
              + " ("
              + cwd
              + ")");
      out.println("Ctrl+C to stop");
      out.flush();
      if (!options.noOpen()) {
        openBrowser(api.url(), err);
      }
      new CountDownLatch(1).await();
      return 0;
    } catch (IOException e) {
      err.println("error: cannot serve " + host + ":" + port + " — " + message(e));
      int free = freePortFrom(port + 1);
      err.println(
          "  something else is already on that port;"
              + (free > 0 ? " try --port " + free : " pass --port <n>")
              + " to serve this UI somewhere else");
      err.flush();
      return 1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 0;
    } finally {
      hub.close();
    }
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
      err.println("could not open a browser (" + message(e) + "); open " + url);
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
      out.println("no sessions in " + sessionsDir);
      out.flush();
      return;
    }
    out.println("ID                              MESSAGES  UPDATED              PREVIEW");
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
    return model == null || model.isBlank() ? "(provider default)" : model;
  }

  /** REPL state; rebuilt whenever the session, model or approval mode changes. */
  private final class Repl {

    private final Provider provider;
    private final ToolRegistry tools;
    private final Path sessionsDir;
    private final Path cwd;
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
          new ToolContext(cwd, approver(autoApprove, in, out, err, renderer), config.outputLimitBytes());
      this.loop = new AgentLoop(provider, tools, session, agentOptions, context, renderer);
    }

    void run() {
      AtomicBoolean exiting = new AtomicBoolean();
      Thread hook =
          new Thread(
              () -> {
                if (!exiting.get()) {
                  err.println();
                  err.println(
                      "interrupted — resume this session with: ccj --resume " + sessionId.get());
                  err.flush();
                }
              },
              "ccj-shutdown");
      Runtime.getRuntime().addShutdownHook(hook);
      out.println(
          "oh-my-ccj "
              + VERSION
              + " — session "
              + session.id()
              + " — model "
              + modelLabel(agentOptions.model())
              + " — /help for commands");
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
        }
      } finally {
        exiting.set(true);
        try {
          Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
          // Already shutting down; the hook is what is printing the resume hint.
        }
        renderer.reset();
      }
    }

    private void turn(String input) {
      try {
        loop.run(input);
      } catch (RuntimeException e) {
        err.println("error: " + message(e));
        err.flush();
      } finally {
        renderer.reset();
      }
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
          out.println("history cleared for session " + session.id());
        }
        case "/new" -> {
          FileSession next = SessionStore.create(sessionsDir);
          useSession(next);
          out.println("new session " + next.id());
        }
        case "/resume" -> {
          if (argument.isEmpty()) {
            out.println("usage: /resume <id>");
          } else {
            try {
              FileSession next = SessionStore.open(sessionsDir, argument);
              useSession(next);
              out.println("resumed session " + next.id() + " (" + next.messages().size() + " messages)");
            } catch (RuntimeException e) {
              err.println("error: " + message(e));
            }
          }
        }
        case "/sessions" -> printSessions(sessionsDir, out);
        case "/model" -> {
          if (argument.isEmpty()) {
            out.println("model: " + modelLabel(agentOptions.model()));
          } else {
            agentOptions =
                new AgentOptions(
                    argument,
                    agentOptions.system(),
                    agentOptions.temperature(),
                    agentOptions.maxTokens(),
                    agentOptions.maxSteps(), null);
            rebuild();
            out.println("model set to " + argument);
          }
        }
        case "/tools" -> printTools(tools, out);
        case "/config" -> printConfig(config, env, out);
        case "/yolo" -> {
          autoApprove = !autoApprove;
          rebuild();
          out.println("auto-approve " + (autoApprove ? "on" : "off"));
        }
        default ->
            out.println("unknown command: " + name + " (try /help)");
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

    private void printHelp() {
      out.println(
          """
          Commands:
            /help            show this help
            /exit            leave the REPL
            /clear           forget the current conversation (keeps the session id)
            /new             start a fresh session
            /resume <id>     reopen a session by id
            /sessions        list sessions
            /model <name>    switch model for this session
            /tools           list available tools
            /config          show the effective configuration
            /yolo            toggle auto-approval of tool calls

          Anything else is sent to the model.
          """);
    }
  }
}
