package com.ccj.agent.tool;

import com.ccj.agent.core.ToolContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

  private static final String SHELL = "/bin/bash";
  private static final long CANCEL_POLL_MILLIS = 150;
  private static final int PUMP_JOIN_MILLIS = 2000;

  private ProcessRunner() {}

  /**
   * 一条结束的命令留下的东西。
   *
   * @param exitCode shell 的状态码；它从未自行退出时为 -1
   * @param finished 截止时间或取消终结了它时为 false，这正是它被 kill 的原因
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
   * 在 {@code cwd} 里通过 {@code /bin/bash -lc} 运行 {@code command}。
   *
   * <p>{@code outputLimitBytes} 是调用方给的，不是上下文给的：一次 `bash` 调用把整个回合的预算都花在模型
   * 的命令上，而每次编辑之后运行的检查只花几 KB，因为它的输出会进入一份用户此后每个回合都要付费的结果。
   */
  static Result run(
      String command, Path cwd, int timeoutSeconds, int outputLimitBytes, ToolContext ctx)
      throws InterruptedException {
    long started = System.nanoTime();
    Process process;
    try {
      process =
          new ProcessBuilder(SHELL, "-lc", command)
              .directory(cwd.toFile())
              .redirectErrorStream(true)
              .start();
    } catch (IOException e) {
      return new Result(-1, false, ctx.isCancelled(), "无法启动 " + SHELL + ": " + e.getMessage(), 0);
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
