package com.ccj.agent.perf;

import com.ccj.agent.core.Prompts;
import com.ccj.agent.core.TokenEstimate;
import com.ccj.agent.core.ToolSpec;
import com.ccj.agent.session.SessionStore;
import com.ccj.agent.tool.Tools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 这个工具自己有多贵：三笔开销，以及量它们的方法。
 *
 * <p>它是一个不到三千行的核心的代理，而「小」如果只体现在行数上，就只是一种审美。这个类把三个会被用户
 * 感觉到的数字量出来——<strong>从进程启动到能收下第一条消息</strong>、<strong>每个回合固定发出去的前缀
 * （系统提示加工具 schema）</strong>、以及<strong>列一次会话目录</strong>——写成 {@link #main} 能直接跑的
 * 一份报告，再由 {@link OverheadLedgerTest} 在同一台机器上守住它们不悄悄变坏。
 *
 * <p>它刻意是一个手写的 harness，而不是 JMH。JMH 会带来第二个构建期依赖（加上注解处理器），而它买到的
 * 分叉隔离与统计检验，是这三个数字不需要的：前两个是进程边界上的墙钟时间，第三个是一次文件系统调用，
 * 三个都在几十毫秒到几百毫秒的量级，中位数就足够说明问题。这不是「JMH 不好」，是「这里的代价付得不值」——
 * 而一旦某个数字变得需要更严格的测量，正确的做法是给那一项单独建一个模块，而不是把整个仓库拖进去。
 *
 * <p>测量方法写在每个方法上，因为一个没有方法的数字不是测量，是一句断言：
 * {@link #firstTurnMillis} 起的是<em>真的</em>进程，跑的是<em>真的</em> CLI 与工具；{@link #prefix}
 * 数的是<em>真的</em> schema；{@link #listing} 读的是<em>真的</em>会话文件。它们都不是估算。
 */
public final class OverheadLedger {

  /** 一个数重复量几次取最好的一次：这台机器上的一次抖动就能把一次墙钟测量翻倍。 */
  private static final int ROUNDS = 3;

  private OverheadLedger() {}

  /** 每个回合固定重发的那一份：系统提示与每个工具的 schema。 */
  public record Prefix(int systemTokens, int toolTokens, int totalTokens, int toolCount, int chars) {}

  /** 一次会话列表：多大的文件、多少条消息、缓存落空与命中各花了多久。 */
  public record Listing(long bytes, int messages, long coldMillis, long warmMillis) {}

  /** 一次进程边界上的墙钟测量：这一档启动到哪一步，花了多少毫秒。 */
  public record Startup(String stage, long millis) {}

  /**
   * 进程启动的第一个可测量点：解析 flag、打印版本、退出。
   *
   * <p>它主要是 JVM 的启动时间（类加载、JIT 预热前的解释执行），而这个仓库对那一部分无能为力——所以它是
   * 后面两个数字的基线，而不是一个优化目标。
   */
  public static Startup entry() throws IOException, InterruptedException {
    return new Startup("ccj --version（JVM 启动 + 入口）", bestOf(() -> run("--version")));
  }

  /**
   * 一个终端运行真正可以开始干活的那一刻：配置叠加完、工作区打开、工具注册表建好、每个工具的 schema 生成
   * 完——{@code --tools} 走完这些正好就退出，所以它量的正是「能收下第一条消息」之前的那一半。
   */
  public static Startup ready() throws IOException, InterruptedException {
    return new Startup("ccj --tools（配置 + 工作区 + 工具表）", bestOf(() -> run("--tools")));
  }

  /**
   * 从进程启动到一个完成回合的整段路，用一个不需要密钥、不需要网络的 {@code --demo} 回合量：
   * 启动、读文件、回喂、模型写散文、会话落盘——这个仓库能离线走完的最完整的一次运行。
   */
  public static Startup firstTurn() throws IOException, InterruptedException {
    Path home = Files.createTempDirectory("ccj-overhead-home");
    try {
      return new Startup(
          "ccj --demo -p \"read pom.xml\"（启动 + 一个回合）",
          bestOf(() -> run(home, "--demo", "-p", "read pom.xml")));
    } finally {
      delete(home);
    }
  }

  /**
   * 每个回合固定发出去的前缀有多大，以估算 token 计。
   *
   * <p>用 {@link TokenEstimate}——与上下文预算用的是同一个启发式——因为这里要的不是计费口径的精确数字
   * （那需要一种本运行时刻意不带的 tokenizer），而是量级：工具 schema 比系统提示大多少、加一个 MCP 工具
   * 服务器值多少 token。它只数内建提示词与标准工具表，所以被项目 {@code CCJ.md} 加上的那一段不在这里——
   * 那一段属于项目，不属于运行时。
   */
  public static Prefix prefix() {
    List<ToolSpec> specs = Tools.standard().specs();
    int system = TokenEstimate.of(Prompts.system(null, null));
    int tools = 0;
    int chars = 0;
    for (ToolSpec spec : specs) {
      String cost = spec.name() + spec.description() + spec.parametersJson();
      tools += TokenEstimate.of(cost);
      chars += cost.length();
    }
    return new Prefix(system, tools, system + tools, specs.size(), chars);
  }

  /**
   * 列一次会话目录要多久，分缓存落空与命中两种：多兆字节、几千条消息的会话。
   *
   * <p>两者都要量，因为它们回答的是两个不同的问题。缓存落空是「这个会话自上次被列出来之后动过」——
   * 而侧栏在每个回合结束后重读列表，刚刚被写入的那个会话正是这种状态，所以这是每个回合都要付的那一笔。
   * 缓存命中是「什么也没动」——那是把一个不能变的数字变便宜的那部分，值得看到它确实几乎不要钱。
   */
  public static Listing listing(long targetBytes) throws IOException {
    Path warmup = Files.createTempDirectory("ccj-overhead-warmup");
    Path directory = Files.createTempDirectory("ccj-overhead-sessions");
    try {
      // 预热用的是另一个目录、另一个同样大的文件：本 JVM 里第一次解码一个几兆字节的会话要花一百多
      // 毫秒，而那主要是 JIT 还没把 Jackson 的那条路编译出来——把它算进「列一次会话」里，量到的就是
      // 别的东西。预热必须发生在另一个目录上，因为在同一个目录上列两次，第二次命中索引缓存，于是要量的
      // 那次落空就没了。
      writeBigSession(warmup.resolve("20260101-000000-warm1.jsonl"), targetBytes);
      SessionStore.list(warmup);
      SessionStore.list(warmup);

      Path file = directory.resolve("20260101-000000-over1.jsonl");
      int messages = writeBigSession(file, targetBytes);

      long started = System.nanoTime();
      List<SessionStore.Summary> cold = SessionStore.list(directory);
      long coldMillis = (System.nanoTime() - started) / 1_000_000;

      started = System.nanoTime();
      List<SessionStore.Summary> warm = SessionStore.list(directory);
      long warmMillis = (System.nanoTime() - started) / 1_000_000;

      if (cold.size() != 1 || warm.size() != 1) {
        throw new IllegalStateException("期待一个会话，实际是 " + cold + " / " + warm);
      }
      return new Listing(Files.size(file), messages, coldMillis, warmMillis);
    } finally {
      delete(warmup);
      delete(directory);
    }
  }

  /**
   * 一个像真会话一样笨重的 {@code <id>.jsonl}：一条用户消息，然后是一串助手回合与它们的大块工具输出。
   *
   * <p>形状取自真的会话文件，而不是一串重复的填充：工具结果占了 agent 上下文的绝大部分，所以它们也该占
   * 这个文件的大部分，否则量出来的就不是那条路径。
   *
   * @return 写进去的消息条数（记账行不算消息）
   */
  private static int writeBigSession(Path file, long targetBytes) throws IOException {
    StringBuilder content = new StringBuilder();
    content.append("{\"type\":\"user\",\"text\":\"the first thing asked\"}\n");
    String filler = "output ".repeat(200);
    int pairs = 0;
    while (content.length() < targetBytes) {
      content
          .append("{\"type\":\"assistant\",\"text\":\"step ")
          .append(pairs)
          .append("\",\"tool_calls\":[]}\n")
          .append("{\"type\":\"tool_result\",\"tool_call_id\":\"c")
          .append(pairs)
          .append("\",\"tool_name\":\"bash\",\"content\":\"")
          .append(filler)
          .append("\",\"error\":false}\n");
      pairs++;
    }
    Files.createDirectories(file.getParent());
    Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
    return pairs * 2 + 1;
  }

  /**
   * 三笔开销，各量一次，打印成一张表。
   *
   * <p>它跑一个真的 JVM 子进程，所以别在测试里当断言用——{@link OverheadLedgerTest} 用的是同样的方法，
   * 但带着宽松的边界，因为一台负载很高的 CI 机器偶尔会慢上两倍，而那不是回归。
   */
  public static void main(String[] args) throws Exception {
    for (Startup startup : List.of(entry(), ready(), firstTurn())) {
      System.out.printf("%-46s %5d ms%n", startup.stage(), startup.millis());
    }
    Prefix prefix = prefix();
    System.out.printf(
        "%-46s 系统提示 %d + 工具 %d = %d token（%d 个工具，%d 字符）%n",
        "每个回合固定的前缀（估算）",
        prefix.systemTokens(),
        prefix.toolTokens(),
        prefix.totalTokens(),
        prefix.toolCount(),
        prefix.chars());
    Listing listing = listing(2 * 1024 * 1024);
    System.out.printf(
        "%-46s 缓存落空 %d ms / 命中 %d ms（%.1f MiB / %d 条消息）%n",
        "列一次会话目录",
        listing.coldMillis(),
        listing.warmMillis(),
        listing.bytes() / (1024.0 * 1024.0),
        listing.messages());
  }

  /** 同一件事量 {@link #ROUNDS} 次，取最好的一次：抖动的方向只有一个。 */
  private static long bestOf(Call call) throws IOException, InterruptedException {
    long best = Long.MAX_VALUE;
    for (int i = 0; i < ROUNDS; i++) {
      best = Math.min(best, call.millis());
    }
    return best;
  }

  @FunctionalInterface
  private interface Call {
    long millis() throws IOException, InterruptedException;
  }

  /**
   * 在一个真的子进程里跑一次 CLI，量到它退出为止。
   *
   * <p>用的类路径就是本进程自己的——测试与 CI 都是这么跑的，所以它与「用户拿到的那份东西」之间只差一个
   * 打包步骤，而这一步不改变任何被量的东西（jar 里的类由同一个类加载器从同一个位置读，只是换成了压缩的
   * 容器）。
   */
  private static long run(String... args) throws IOException, InterruptedException {
    return run(null, args);
  }

  private static long run(Path home, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add("com.ccj.agent.cli.Main");
    command.addAll(List.of(args));
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD);
    if (home != null) {
      builder.environment().put("CCJ_HOME", home.toString());
    }
    long started = System.nanoTime();
    Process process = builder.start();
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("子进程 60 秒没有退出：" + command);
    }
    return (System.nanoTime() - started) / 1_000_000;
  }

  private static void delete(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    try (var walk = Files.walk(directory)) {
      for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
