package com.ccj.agent.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * 工具需要了解的、关于其运行环境的一切。
 *
 * @param cwd 会话启动时的工作目录；工具的相对路径以它为基准解析
 * @param approver 副作用关卡
 * @param outputLimitBytes 工具交还给模型的任何批量输出所适用的上限
 * @param cancelled 由拥有此上下文的运行来回答：一旦有人要求它停下就为 true。可能耗时数分钟的工具——
 *     shell 命令是明显的一例——会检查它而不是一路跑完，这样「停」就是停，而不是「等超时」
 * @param endsRun 由「结果会让本次运行剩下的部分毫无意义」的工具调用。安装一个 jar 以便重启就是需要它的
 *     场景：这个进程马上要死了，再向模型要一个它永远发不出去的后续回合，对谁都没好处
 */
public record ToolContext(
    Path cwd,
    Approver approver,
    int outputLimitBytes,
    BooleanSupplier cancelled,
    Runnable endsRun) {

  public ToolContext {
    if (cwd == null) {
      cwd = Path.of("").toAbsolutePath();
    }
    cwd = cwd.toAbsolutePath().normalize();
    if (approver == null) {
      approver = Approver.ALWAYS;
    }
    if (outputLimitBytes <= 0) {
      outputLimitBytes = 32 * 1024;
    }
    if (cancelled == null) {
      cancelled = () -> false;
    }
    if (endsRun == null) {
      endsRun = () -> {};
    }
  }

  /** 无法被取消的上下文，普通工具调用要的就是它。 */
  public ToolContext(Path cwd, Approver approver, int outputLimitBytes) {
    this(cwd, approver, outputLimitBytes, () -> false, null);
  }

  /** 运行不会被工具结束的上下文，供没有运行可结束的调用方使用。 */
  public ToolContext(
      Path cwd, Approver approver, int outputLimitBytes, BooleanSupplier cancelled) {
    this(cwd, approver, outputLimitBytes, cancelled, null);
  }

  public static ToolContext of(Path cwd) {
    return new ToolContext(cwd, Approver.ALWAYS, 0);
  }

  /** 拥有此上下文的运行被要求停下后为 true。 */
  public boolean isCancelled() {
    return cancelled.getAsBoolean();
  }

  /**
   * 告诉拥有此上下文的运行：本回合结束了。今天唯一的调用方是「安装一个 jar 以便重启」的工具：进程即将
   * 退出，所以不能再向模型要下一个步骤。
   */
  public void endRun() {
    endsRun.run();
  }

  /** 把用户或模型给出的路径解析到 {@link #cwd} 之下。 */
  public Path resolve(String path) {
    Path p = Path.of(path);
    return (p.isAbsolute() ? p : cwd.resolve(p)).normalize();
  }

  /**
   * 请求许可，并返回要交还回去的拒绝理由——获准时返回 null。
   *
   * <p>返回的是答复而不是请求的文本，因为审批现在有四种结果：可以一次、本会话可以、一直可以，以及两种对
   * 日后翻转录的人来说读法不同的「不行」。
   */
  public String refusal(ApprovalRequest request) {
    ApprovalAnswer answer = approver.approve(request);
    return answer.allowed() ? null : answer.refusal();
  }

  /**
   * {@code path} 停留在会话工作目录内时为 true。
   *
   * <p>「这个名字看起来在里面吗」的纯字面答案，回答的不是同一个问题。一个位于工作目录内却指向目录外的
   * 符号链接溜了出去，而它的名字仍然说自己在里面——审批提示恰恰是这项检查存在的目的。所以两侧都先做解析：
   * 路径中有多少存在就解析多少，剩下的按原样保留，这才让一个即将被创建的文件也能得到判断。两侧都比较真实
   * 路径，还能避免一个经由符号链接到达的工作目录（macOS 的 {@code /tmp}）把每条路径都显得像是外来的。
   */
  public boolean insideCwd(Path path) {
    Path target = resolveExisting(path.toAbsolutePath().normalize());
    Path base = resolveExisting(cwd);
    return target.startsWith(base);
  }

  /**
   * 该路径中，最长的已存在前缀解析为真实路径，其余部分按原样接在后面。完全解析不出任何东西的路径原样返回，
   * 于是答案退化为比较调用方给出的字面内容。
   */
  private static Path resolveExisting(Path path) {
    Path prefix = path;
    Path tail = null;
    while (prefix != null) {
      Path real = realPath(prefix);
      if (real != null) {
        return tail == null ? real : real.resolve(tail);
      }
      Path name = prefix.getFileName();
      if (name == null) {
        return path;
      }
      tail = tail == null ? name : name.resolve(tail);
      prefix = prefix.getParent();
    }
    return path;
  }

  /** 解析后的路径；不存在或无法解析时为 null。 */
  private static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return null;
    }
  }
}
