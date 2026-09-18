package com.ccj.agent.cli;

import com.ccj.agent.core.Config;
import com.ccj.agent.core.VisionConfig;
import java.util.Map;

/**
 * 命令行 flag，手工解析。
 *
 * <p>在这里，一个坐下来一口气就能读完的解析器，比一个依赖更有价值：flag 集合随代理一起变化，
 * 而每个 flag 都映射到 {@link Config} 的一个字段，因此 {@link #overrides()} 里的映射就是全部的
 * 规格说明。未设置的 flag 保持 null，这样它们就无法掩盖配置文件或环境变量里的值。
 */
public record CliOptions(
    String print,
    String model,
    String provider,
    String baseUrl,
    String apiKey,
    String apiKeyEnv,
    String visionBaseUrl,
    String visionApiKey,
    String visionApiKeyEnv,
    String visionModel,
    Integer visionMaxTokens,
    Double temperature,
    Integer maxTokens,
    Integer maxContextTokens,
    String reasoning,
    boolean yolo,
    String resume,
    boolean continueSession,
    boolean listSessions,
    boolean tools,
    String config,
    String home,
    String system,
    String language,
    String cwd,
    String workspace,
    boolean demo,
    boolean web,
    boolean repl,
    boolean noOpen,
    Integer port,
    String host,
    String webToken,
    boolean subAgents,
    String wallpapers,
    boolean help,
    boolean version) {

  /** 任何用户读一遍用法说明就能修好的问题都抛这个；CLI 随后以 2 退出。 */
  public static final class UsageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UsageException(String message) {
      super(message);
    }
  }

  /** 只包含用户真正设置过的字段，可以直接叠加到文件和环境变量之上。 */
  public Config overrides() {
    return new Config(
        provider,
        model,
        baseUrl,
        apiKey,
        apiKeyEnv,
        temperature,
        maxTokens,
        yolo ? Boolean.TRUE : null,
        null,
        system,
        language,
        reasoning,
        maxContextTokens,
        null,
        Map.of(),
        vision());
  }

  /**
   * 把 vision 系列的 flag 当成一整块，一个都没给时为 null。
   *
   * <p>用 null 而不是空块：空块在合并时等同于「这里什么都没说」，因而无法与文件里的块区分，而
   * 一个只点名某个字段的块，必须能取到它下面文件里的端点与密钥。四个里哪一个没设置，不是本类的
   * 事。
   */
  public VisionConfig vision() {
    VisionConfig vision =
        new VisionConfig(visionBaseUrl, visionApiKey, visionApiKeyEnv, visionModel, visionMaxTokens);
    return vision.isEmpty() ? null : vision;
  }

  public static CliOptions parse(String[] args) {
    String print = null;
    String model = null;
    String provider = null;
    String baseUrl = null;
    String apiKey = null;
    String apiKeyEnv = null;
    String visionBaseUrl = null;
    String visionApiKey = null;
    String visionApiKeyEnv = null;
    String visionModel = null;
    Integer visionMaxTokens = null;
    Double temperature = null;
    Integer maxTokens = null;
    Integer maxContextTokens = null;
    String reasoning = null;
    boolean yolo = false;
    String resume = null;
    boolean continueSession = false;
    boolean listSessions = false;
    boolean toolsFlag = false;
    String config = null;
    String home = null;
    String system = null;
    String language = null;
    String cwd = null;
    String workspace = null;
    boolean demo = false;
    boolean web = false;
    boolean repl = false;
    boolean noOpen = false;
    Integer port = null;
    String host = null;
    String webToken = null;
    boolean subAgents = false;
    String wallpapers = null;
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
        case "--subagents" -> {
          subAgents = true;
          i += 1;
        }
        case "--web-token" -> {
          webToken = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--wallpapers" -> {
          wallpapers = take(args, i, name, inline);
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
        case "--vision-base-url" -> {
          visionBaseUrl = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--vision-model" -> {
          visionModel = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--vision-api-key" -> {
          visionApiKey = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--vision-api-key-env" -> {
          visionApiKeyEnv = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--vision-max-tokens" -> {
          visionMaxTokens = integer(name, take(args, i, name, inline));
          i += inline == null ? 2 : 1;
        }
        case "--max-tokens" -> {
          maxTokens = integer(name, take(args, i, name, inline));
          i += inline == null ? 2 : 1;
        }
        case "--max-context-tokens" -> {
          maxContextTokens = integer(name, take(args, i, name, inline));
          i += inline == null ? 2 : 1;
        }
        case "--reasoning" -> {
          reasoning = take(args, i, name, inline);
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
        case "--language" -> {
          language = take(args, i, name, inline);
          i += inline == null ? 2 : 1;
        }
        case "--workspace" -> {
          workspace = take(args, i, name, inline);
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
      throw new UsageException("--repl 与 --web 要求不同的前端；请二选一");
    }

    return new CliOptions(
        print,
        model,
        provider,
        baseUrl,
        apiKey,
        apiKeyEnv,
        visionBaseUrl,
        visionApiKey,
        visionApiKeyEnv,
        visionModel,
        visionMaxTokens,
        temperature,
        maxTokens,
        maxContextTokens,
        reasoning,
        yolo,
        resume,
        continueSession,
        listSessions,
        toolsFlag,
        config,
        home,
        system,
        language,
        cwd,
        workspace,
        demo,
        web,
        repl,
        noOpen,
        port,
        host,
        webToken,
        subAgents,
        wallpapers,
        help,
        version);
  }

  public static String usage() {
    return """
        用法: ccj [options]

    模式:
          (无 flag)                启动 web UI，并在浏览器中打开它
              --repl               在本终端里开启交互式 REPL
              --web                默认值，只是显式写出来（供脚本使用）
              --no-open            启动 web UI 但不打开浏览器
          -p, --print <prompt>     运行单个回合，打印答案后退出

    演示:
          --demo                   不需要模型也不需要密钥：read/run/list/search 变成真实的工具
                                   调用，因此可以把主循环、工具和审批提示都试一遍

    模型:
              --provider <name>    要使用的提供方（openai、anthropic……）
              --model <name>       模型标识符
              --base-url <url>     覆盖提供方的 base URL
              --api-key <key>      API 密钥字面值
              --api-key-env <var>  保存 API 密钥的环境变量
              --temperature <n>    采样温度
              --max-tokens <n>     响应 token 上限
              --max-context-tokens <n>
                                   提示词预算：超过它，旧的工具结果会被省略，更早的往来会在请求
                                   发出之前被丢弃
              --reasoning <level>  模型该思考多少：low、high 或 max
              --system <text>      本次运行的系统提示词
              --language <name>    无论用户用什么语言书写，都用这种语言思考和回答
                                   （"auto" 交给模型决定）；见「设置」里的列表

    视觉（图片先由这个模型描述，然后才进入对话）:
              --vision-base-url <url>    视觉模型的端点
              --vision-model <name>      该端点上的模型标识符
              --vision-api-key <key>     它的 API 密钥字面值
              --vision-api-key-env <var> 保存那把密钥的环境变量
              --vision-max-tokens <n>    一次描述可以占用的空间（默认 8192）。推理模型会先把它
                                   花完才开始写任何东西，所以给得太少会返回空答案，而不是短答案
                                   以上都不设置时，图片描述功能关闭。不会复用主提供方：看截图
                                   和写代码是不同的取舍，而私密照片用本地模型才合适。把这些设置
                                   写进 config.json 的 "vision" 块即可保留。

    会话:
              --resume <id>        按 id 重新打开一个会话
              --continue           重新打开最近的一个会话
              --list-sessions      打印会话后退出
              --workspace <name>   使用某个工作区：它的目录，以及它自己的会话

    Web:
              --port <n>           web UI 端口（默认 6767）
              --host <addr>        在何处提供服务（默认：127.0.0.1，且当本机位于 tailnet 上时，
                                   连它的 tailnet 地址一起监听；'tailscale' 表示只监听 tailnet
                                   地址；除 loopback 之外的任何地址都需要 token）
              --web-token <token>  要求每个 web 请求都带上这个 token
                                   （也可以设置 CCJ_WEB_TOKEN；flag 优先）
              --subagents          允许代理委派给子代理（会额外消耗 token）
              --wallpapers <dir>   页面轮换作为背景的图片
                                   （默认：~/Pictures/ccj-backgrounds，或 CCJ_WALLPAPERS）

        运行:
              --config <file>      配置文件（默认：<home>/config.json）
              --home <dir>         应用主目录（默认：~/.oh-my-ccj）
          -C, --cwd <dir>          工具解析相对路径时依据的工作目录
              --tools              打印可用的工具后退出
              --yolo               批准每一个工具调用，别名 --auto-approve
          -h, --help               打印这份帮助
          -v, --version            打印版本号

        web UI 是默认前端；--repl 提供终端前端。在 REPL 里输入 /help 查看命令。模型设置位于
        web UI 中，可以在运行时于那里更改。
        """;
  }

  private static String take(String[] args, int index, String name, String inline) {
    if (inline != null) {
      return inline;
    }
    if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
      throw new UsageException("选项 " + name + " 需要一个值");
    }
    return args[index + 1];
  }

  private static String unexpected(String arg) {
    if (arg.startsWith("-")) {
      return "未知选项：" + arg;
    }
    return "多余的参数：" + arg + "（用 -p 传入提示词）";
  }

  private static Integer integer(String name, String raw) {
    try {
      return Integer.valueOf(raw.strip());
    } catch (NumberFormatException e) {
      throw new UsageException("选项 " + name + " 需要整数，实际得到：" + raw);
    }
  }

  private static Double decimal(String name, String raw) {
    try {
      return Double.valueOf(raw.strip());
    } catch (NumberFormatException e) {
      throw new UsageException("选项 " + name + " 需要数字，实际得到：" + raw);
    }
  }
}
