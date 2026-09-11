package com.ccj.agent.cli;

import com.ccj.agent.core.Config;

/**
 * Command line flags, parsed by hand.
 *
 * <p>A parser small enough to read in one sitting is worth more here than a dependency: the flag
 * set changes with the agent and every flag maps onto one {@link Config} field, so the mapping in
 * {@link #overrides()} is the whole specification. Unset flags stay null so they cannot mask a
 * value from the config file or the environment.
 */
public record CliOptions(
    String print,
    String model,
    String provider,
    String baseUrl,
    String apiKey,
    String apiKeyEnv,
    Integer maxSteps,
    Double temperature,
    Integer maxTokens,
    boolean yolo,
    String resume,
    boolean continueSession,
    boolean listSessions,
    boolean tools,
    String config,
    String home,
    String system,
    String cwd,
    boolean demo,
    boolean web,
    boolean repl,
    boolean noOpen,
    Integer port,
    String host,
    String webToken,
    boolean help,
    boolean version) {

  /** Thrown for anything a user could fix by reading the usage text; the CLI exits 2. */
  public static final class UsageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UsageException(String message) {
      super(message);
    }
  }

  /** Only the fields the user actually set, ready to layer on top of file and environment. */
  public Config overrides() {
    return new Config(
        provider,
        model,
        baseUrl,
        apiKey,
        apiKeyEnv,
        temperature,
        maxTokens,
        maxSteps,
        yolo ? Boolean.TRUE : null,
        null,
        system);
  }

  public static CliOptions parse(String[] args) {
    String print = null;
    String model = null;
    String provider = null;
    String baseUrl = null;
    String apiKey = null;
    String apiKeyEnv = null;
    Integer maxSteps = null;
    Double temperature = null;
    Integer maxTokens = null;
    boolean yolo = false;
    String resume = null;
    boolean continueSession = false;
    boolean listSessions = false;
    boolean toolsFlag = false;
    String config = null;
    String home = null;
    String system = null;
    String cwd = null;
    boolean demo = false;
    boolean web = false;
    boolean repl = false;
    boolean noOpen = false;
    Integer port = null;
    String host = null;
    String webToken = null;
    boolean open = false;
    boolean help = false;
    boolean version = false;

    int i = 0;
    while (i < args.length) {
      String arg = args[i];
      String name = arg;
      String inline = null;
      if (arg.startsWith("--")) {
        int eq = arg.indexOf('=');
        if (eq >= 0) {
          name = arg.substring(0, eq);
          inline = arg.substring(eq + 1);
        }
      }
      switch (name) {
        case "-h", "--help" -> {
          help = true;
          i++;
        }
        case "-v", "--version" -> {
          version = true;
          i++;
        }
        case "--yolo", "--auto-approve" -> {
          yolo = true;
          i++;
        }
        case "--continue" -> {
          continueSession = true;
          i++;
        }
        case "--list-sessions" -> {
          listSessions = true;
          i++;
        }
        case "--tools" -> {
          toolsFlag = true;
          i++;
        }
        case "--web" -> {
          web = true;
          i++;
        }
        case "--demo" -> {
          demo = true;
          i++;
        }
        case "--repl" -> {
          repl = true;
          i++;
        }
        case "--no-open" -> {
          noOpen = true;
          i++;
        }
        case "--port" -> {
          port = integer(name, take(args, i, name, inline));
          i += inline == null ? 2 : 1;
        }
        case "--host" -> {
          host = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--web-token" -> {
          webToken = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "-p", "--print" -> {
          print = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--model" -> {
          model = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--provider" -> {
          provider = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--base-url" -> {
          baseUrl = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--api-key" -> {
          apiKey = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--api-key-env" -> {
          apiKeyEnv = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--max-steps" -> {
          maxSteps = integer(name, take(args, i, name, inline));
          i += inline == null ? 2 : 1;
        }
        case "--max-tokens" -> {
          maxTokens = integer(name, take(args, i, name, inline));
          i += inline == null ? 2 : 1;
        }
        case "--temperature" -> {
          temperature = decimal(name, take(args, i, name, inline));
          i += inline == null ? 2 : 1;
        }
        case "--resume" -> {
          resume = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--config" -> {
          config = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--home" -> {
          home = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--system" -> {
          system = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "-C", "--cwd" -> {
          cwd = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        default -> throw new UsageException(unexpected(arg));
      }
    }

    if (repl && web) {
      throw new UsageException("--repl and --web ask for different front ends; pick one");
    }

    return new CliOptions(
        print,
        model,
        provider,
        baseUrl,
        apiKey,
        apiKeyEnv,
        maxSteps,
        temperature,
        maxTokens,
        yolo,
        resume,
        continueSession,
        listSessions,
        toolsFlag,
        config,
        home,
        system,
        cwd,
        demo,
        web,
        repl,
        noOpen,
        port,
        host,
        webToken,
        help,
        version);
  }

  public static String usage() {
    return """
        Usage: ccj [options]

    Modes:
          (no flags)               serve the web UI, opening it in a browser
              --repl               interactive REPL in this terminal
              --web                the default, stated explicitly (for scripts)
              --no-open            serve the web UI without opening a browser
          -p, --print <prompt>     run a single turn, print the answer, and exit

    Demo:
          --demo                   no model and no key: read/run/list/search become real tool calls,
                                   so the loop, the tools and the approval prompts can be tried out

    Model:
              --provider <name>    provider to use (openai, anthropic, ...)
              --model <name>       model identifier
              --base-url <url>     override the provider base URL
              --api-key <key>      API key literal
              --api-key-env <var>  environment variable holding the API key
              --temperature <n>    sampling temperature
              --max-tokens <n>     response token cap
              --max-steps <n>      model turns per user input (default 25)
              --system <text>      system prompt for this run

        Sessions:
              --resume <id>        reopen a session by id
              --continue           reopen the most recent session
              --list-sessions      print sessions and exit

        Web:
              --port <n>           web UI port (default 8787)
              --host <addr>        bind address (default 127.0.0.1; anything else needs --web-token)
              --web-token <token>  require this token from every web request

        Runtime:
              --config <file>      config file (default: <home>/config.json)
              --home <dir>         application home (default: ~/.oh-my-ccj)
          -C, --cwd <dir>          working directory tools resolve relative paths against
              --tools              print available tools and exit
              --yolo               approve every tool call, alias --auto-approve
          -h, --help               print this help
          -v, --version            print the version

        The web UI is the default front end; --repl gives the terminal one. In the REPL, type
        /help for commands. Model settings live in the web UI and can be changed there at runtime.
        """;
  }

  private static String take(String[] args, int index, String name, String inline) {
    if (inline != null) {
      return inline;
    }
    if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
      throw new UsageException("option " + name + " requires a value");
    }
    return args[index + 1];
  }

  private static String unexpected(String arg) {
    if (arg.startsWith("-")) {
      return "unknown option: " + arg;
    }
    return "unexpected argument: " + arg + " (use -p to pass a prompt)";
  }

  private static Integer integer(String name, String raw) {
    try {
      return Integer.valueOf(raw.strip());
    } catch (NumberFormatException e) {
      throw new UsageException("option " + name + " expects an integer, got: " + raw);
    }
  }

  private static Double decimal(String name, String raw) {
    try {
      return Double.valueOf(raw.strip());
    } catch (NumberFormatException e) {
      throw new UsageException("option " + name + " expects a number, got: " + raw);
    }
  }
}
