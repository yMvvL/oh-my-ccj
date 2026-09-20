package com.ccj.agent.tool;

import com.ccj.agent.core.ToolContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 按这个代理执行命令的方式运行一条命令：一个 shell、关闭的 stdin、有界的捕获、一个截止时间，以及能杀到
 * 整棵进程树的 kill。
 *
 * <p>由两个会运行东西的地方共用——`bash` 工具，命令来自模型；以及编辑后检查，命令来自用户的配置。它们
 * 出于同样的理由需要这同样的四件事，而当初只存在于 `bash` 内部的那版实现，本来就已经是第二个调用方会直接
 * 复制粘贴的版本。
 *
 * <p>它刻意不做的事，是决定是否允许运行。审批，以及一条命令来自模型还是来自配置文件的问题，属于知道答案
 * 的那个调用方。
 */
final class ProcessRunner {

  /** 没有配置 shell 时，POSIX 机器上跑命令的那个程序。 */
  public static final String POSIX_DEFAULT = "/bin/bash";

  /** {@code COMSPEC} 没设时 Windows 上跑命令的那个程序；每一台 Windows 都有它。 */
  private static final String WINDOWS_FALLBACK = "cmd.exe";

  private static final long CANCEL_POLL_MILLIS = 150;
  private static final int PUMP_JOIN_MILLIS = 2000;

  private ProcessRunner() {}

  /**
   * 用哪个程序跑命令：配置里写了就用那个（两端空白去掉），没写就交给这个平台。
   *
   * <p>它是一个静态入口，而不是某处字段的默认值，因为「没配置」这件事有不止一个调用方要按同样的方式
   * 回答——`bash` 工具与编辑后检查——而两份各自写出来的平台判断早晚会漂成两种。
   *
   * @param shell 配置里的那个程序，null 或空白表示没配置
   */
  static String resolve(String shell) {
    if (shell != null && !shell.isBlank()) {
      return shell.strip();
    }
    return platformDefault(System.getProperty("os.name"), System.getenv("COMSPEC"));
  }

  /**
   * 平台默认的那一半。
   *
   * <p>它带参数、而不是自己去读环境，是为了能被测到：这台机器和 CI 上都没有 Windows，而「Windows 上没
   * 配置时用 {@code COMSPEC}」是一条否则永远不会被跑到、也就永远不知道自己坏了的判断。{@code osName}
   * 看的是开头而不是相等，因为取值长这样：`Windows 11`、`Windows Server 2022`。
   */
  static String platformDefault(String osName, String comspec) {
    if (osName == null || !osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
      return POSIX_DEFAULT;
    }
    return comspec == null || comspec.isBlank() ? WINDOWS_FALLBACK : comspec.strip();
  }

  /**
   * 一条命令怎么交给这个 shell：程序本身，后面是让它执行一个字符串所需要的那些参数。
   *
   * <p>只有三种形状可以猜。`cmd` 用 `/c`。PowerShell 用 `-NoProfile -Command`——`-NoProfile` 是因为一
   * 台机器的启动脚本不是这条命令的一部分，而它每次调用都要付一遍。bash、sh、zsh 这类用 `-lc`，这是本
   * 工具一直以来的做法，`-l` 让用户 PATH 上的东西照常可见。**其余的一律按 `-lc` 处理**：那是对一个没
   * 见过的 shell 唯一还能做的猜测，而一个不认它的 shell 会报「无法识别的选项」这种足够具体的错误，
   * 改一下配置就绕过去了。
   *
   * <p>判的是文件名——路径最后一段、大小写无关、去掉 `.exe`——因为 Windows 上的配置写的会是
   * `C:\Windows\system32\cmd.exe` 这样的完整路径。
   */
  static List<String> argv(String shell, String command) {
    return switch (executableName(shell)) {
      case "cmd" -> List.of(shell, "/c", command);
      case "powershell", "pwsh" -> List.of(shell, "-NoProfile", "-Command", command);
      default -> List.of(shell, "-lc", command);
    };
  }

  /** 路径最后一段，小写，去掉 `.exe`：`C:\Windows\System32\CMD.EXE` 和 `cmd` 是同一个程序。 */
  private static String executableName(String shell) {
    int separator = Math.max(shell.lastIndexOf('/'), shell.lastIndexOf('\\'));
    String name = shell.substring(separator + 1).toLowerCase(Locale.ROOT);
    return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
  }

  /**
   * 一条结束的命令留下的东西。
   *
   * @param exitCode shell 的状态码；它从未自行退出时为 -1
   * @param finished 截止时间或取消终结了它时为 false，这正是它被 kill 的原因；进程根本没起来时也为
   *     true——没有任何东西在跑，也就没有任何东西被终结
   * @param cancelled 拥有 {@code ctx} 的那次运行要求停止时为 true
   * @param output 合并后的 stdout 与 stderr，受上下文的输出上限限制
   * @param millis 它跑了多久，给想说明这一点的调用方用
   */
  record Result(
      int exitCode, boolean finished, boolean cancelled, String output, long millis) {

    /** 命令跑到结束并报告成功时为 true。 */
    boolean succeeded() {
      return finished && exitCode == 0;
    }
  }

  /**
   * 在 {@code cwd} 里通过 {@code shell} 运行 {@code command}；参数形状由 {@link #argv} 决定。
   *
   * <p>{@code shell} 为 null 时按平台默认（见 {@link #resolve}）。调用方通常已经在构造时把它定下来
   * 了，因为工具描述和错误消息都要报出那个程序是谁，而在这里再解析一次是让「没配置」这个答案不在任何
   * 路径上漏掉。
   *
   * <p>{@code outputLimitBytes} 是调用方给的，不是上下文给的：一次 `bash` 调用把整个回合的预算都花在模型
   * 的命令上，而每次编辑之后运行的检查只花几 KB，因为它的输出会进入一份用户此后每个回合都要付费的结果。
   */
  static Result run(
      String shell,
      String command,
      Path cwd,
      int timeoutSeconds,
      int outputLimitBytes,
      ToolContext ctx)
      throws InterruptedException {
    String program = resolve(shell);
    long started = System.nanoTime();
    Process process;
    try {
      process =
          new ProcessBuilder(argv(program, command))
              .directory(cwd.toFile())
              .redirectErrorStream(true)
              .start();
    } catch (IOException e) {
      // 什么都没起来，所以这里没有东西在等：这不是超时，而把它说成超时，会把读它的人送到错误的下一步
      // 去——等一个从来不会开跑的进程。
      return new Result(-1, true, ctx.isCancelled(), cannotStart(program, e), 0);
    }
    try {
      // 没有人会对着这个进程打字：代理没有终端可以交给它。留着管道开着，会让每条读取 stdin 的命令——
      // `cat`、`sort`、脚本里的 `read`——一直等永远不会到来的输入，直到超时把它杀掉。关闭管道是给它们
      // EOF，这正是一条由脚本运行的命令应当看到的东西。
      process.getOutputStream().close();
    } catch (IOException e) {
      // 已经没了；退出状态两种情况都会报告。
    }

    int limit = Math.max(1, outputLimitBytes);
    int headCapacity = Math.max(1, limit * 6 / 10);
    Capture capture = new Capture(headCapacity, Math.max(1, limit - headCapacity));
    Thread pump =
        Thread.ofVirtual()
            .name("command-output")
            .start(
                () -> {
                  try (InputStream in = process.getInputStream()) {
                    capture.drain(in);
                  } catch (IOException ignored) {
                    // 进程在读取中途被杀掉；保留已经捕获到的内容。
                  }
                });

    boolean finished = waitFor(process, timeoutSeconds, ctx);
    if (!finished) {
      killTree(process);
    }
    pump.join(PUMP_JOIN_MILLIS);

    long millis = (System.nanoTime() - started) / 1_000_000;
    int exit = finished ? process.exitValue() : -1;
    return new Result(exit, finished, ctx.isCancelled(), capture.render(limit), millis);
  }

  /**
   * 起不来的原因，外加三种换一个程序的办法。
   *
   * <p>只说「无法启动」是在报告一件读它的人无法行动的事：那个程序来自配置，所以这条消息必须点名那个
   * 路径和它的三个设置点——配置文件、命令行、环境变量——否则下一步只能靠猜。
   */
  private static String cannotStart(String shell, IOException e) {
    return "无法启动 "
        + shell
        + ": "
        + e.getMessage()
        + "\n  换一个跑命令的程序：config.json 里的 \"shell\"，或者 --shell <文件>，"
        + "或者环境变量 CCJ_SHELL";
  }

  /**
   * 等待命令结束、它自己的超时到期，或者本次运行被取消——最后这一项才让「停止」真的意味着停止，而不是
   * 「等满十分钟的超时」。
   */
  private static boolean waitFor(Process process, int timeoutSeconds, ToolContext ctx)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (true) {
      if (process.waitFor(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
        return true;
      }
      if (ctx.isCancelled() || System.nanoTime() >= deadline) {
        return false;
      }
    }
  }

  /** 杀掉这条命令以及它启动的一切，因为 shell 的子进程会比 shell 活得久。 */
  private static void killTree(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    try {
      process.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * 头尾捕获：最前面的 {@code headCapacity} 字节，加上一个保存最后 {@code tailCapacity} 字节的环形缓冲；
   * 总数也一并统计，以便精确报告省略了多少。
   */
  private static final class Capture {

    private final byte[] head;
    private final byte[] tail;
    private int headLength;
    private int tailPosition;
    private int tailLength;
    private long total;

    Capture(int headCapacity, int tailCapacity) {
      this.head = new byte[headCapacity];
      this.tail = new byte[tailCapacity];
    }

    void drain(InputStream in) throws IOException {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        write(buffer, read);
      }
    }

    private void write(byte[] buffer, int length) {
      total += length;
      int index = 0;
      while (index < length && headLength < head.length) {
        head[headLength++] = buffer[index++];
      }
      while (index < length && tail.length > 0) {
        tail[tailPosition] = buffer[index++];
        tailPosition = (tailPosition + 1) % tail.length;
        if (tailLength < tail.length) {
          tailLength++;
        }
      }
    }

    String render(int limit) {
      byte[] last = tailBytes();
      if (total <= limit) {
        // 什么都没丢，所以两块缓冲是连续的，作为一个数组解码：跨在两者边界上的字符不能变成两个替换
        // 字形。
        byte[] whole = new byte[headLength + tailLength];
        System.arraycopy(head, 0, whole, 0, headLength);
        System.arraycopy(last, 0, whole, headLength, tailLength);
        return new String(whole, StandardCharsets.UTF_8);
      }
      // 有东西被省略了，所以每一半各自解码——而在字符中间被切开的那一半会丢掉这个残缺序列，而不是把它
      // 渲染成乱码。
      int headEnd = headLength - partialTail(head, headLength);
      int tailStart = partialHead(last, tailLength);
      String headText = new String(head, 0, headEnd, StandardCharsets.UTF_8);
      String tailText = new String(last, tailStart, tailLength - tailStart, StandardCharsets.UTF_8);
      long omitted = total - headLength - tailLength;
      return headText + "\n... 省略了 " + omitted + " 字节 ...\n" + tailText;
    }

    /**
     * 切片末尾那些字节，它们是一个 UTF-8 序列的开头，而该序列其余的字节不在这个切片里——也就是在字符
     * 中间切一刀留下的东西。
     */
    private static int partialTail(byte[] bytes, int length) {
      int lead = -1;
      for (int i = length - 1; i >= 0 && i >= length - 4; i--) {
        int b = bytes[i] & 0xFF;
        if ((b & 0xC0) != 0x80) {
          lead = i;
          break;
        }
      }
      if (lead < 0) {
        return 0;
      }
      int expected = sequenceLength(bytes[lead] & 0xFF);
      int present = length - lead;
      return expected > present ? present : 0;
    }

    /** 切片开头那些续接字节，它们的首字符在切片之前就开始了。 */
    private static int partialHead(byte[] bytes, int length) {
      int skip = 0;
      while (skip < length && skip < 3 && (bytes[skip] & 0xC0) == 0x80) {
        skip++;
      }
      return skip;
    }

    private static int sequenceLength(int lead) {
      if (lead >= 0xF0) {
        return 4;
      }
      if (lead >= 0xE0) {
        return 3;
      }
      if (lead >= 0xC0) {
        return 2;
      }
      return 1;
    }

    private byte[] tailBytes() {
      byte[] ordered = new byte[tailLength];
      if (tailLength == 0) {
        return ordered;
      }
      int start = (tailPosition - tailLength + tail.length) % tail.length;
      for (int i = 0; i < tailLength; i++) {
        ordered[i] = tail[(start + i) % tail.length];
      }
      return ordered;
    }
  }
}
