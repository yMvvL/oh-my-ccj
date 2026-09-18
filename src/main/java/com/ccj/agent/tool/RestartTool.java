package com.ccj.agent.tool;

import com.ccj.agent.core.ApprovalRequest;
import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把一个新构建出来的 jar 放到本进程正在运行的那个 jar 的位置上，让代理刚做的重新构建生效。
 *
 * <p>有两个事实让它成为一次操作，而不是一行 shell。覆盖一个 JVM 正在执行的 jar 会杀死那个 JVM——
 * 死在半途，抛出一个对起因什么都没说的 {@code NoClassDefFoundError}——所以新 jar 是以<em>重命名</em>
 * 的方式就位的：同一个文件系统，因此重命名是原子的，正在运行的进程也保留着它已经打开的那个 inode。
 * 而一个进程无法替换自己的代码：一旦换完，这个进程运行的字节就不再位于磁盘上，所以它以一个只有启动器
 * 才会处理的退出状态退出，由启动器启动新的 jar。这就是为什么结局是一句明确的「重启我」，而不是崩溃，
 * 也不是成功。
 *
 * <p>它和所有会改动东西的工具一样受审批闸门管控，而审批详情会点名两个文件：哪个 jar 即将变成哪个，
 * 就是整个决定的内容。
 *
 * <p>这次运行以这个调用告终：{@link ToolContext#endRun()} 让循环就地停下。安装是这个进程里最后一
 * 件值得做的事，而让模型再要一步，只会产生一个需要交代的失败。
 */
public final class RestartTool implements Tool {

  /** 启动器运行的那个 jar，也是这个项目唯一发布的 jar。 */
  private static final String INSTALLED_JAR = "ccj.jar";

  /**
   * 临时构建写出的 jar（{@code -Djar.name=ccj-next}），等着被换上去。它的文件名必须与已安装的那个
   * 不同：产出它的构建会截断自己写入的那条路径，而截断已安装的 jar 正是杀死从它运行的进程的原因。
   */
  private static final String STAGED_JAR = "ccj-next.jar";

  /**
   * 表示「重新启动我」的退出状态。75 是 EX_TEMPFAIL：这里没有任何东西会抛出它，而它必须穿过启动器
   * 所运行的那层 shell 存活下来，这就是为什么它不是 CLI 自己的状态码之一（0 成功、1 运行时失败、
   * 2 用法错误）。
   */
  public static final int RESTART_EXIT = 75;

  /**
   * 本进程是否要以重启收场。用静态标志而不是返回值，因为这个决定必须从一次工具调用里走出来，穿过循环
   * 和这次运行，进入 {@code Cli}：为这一个比特在一路上每个签名里穿线，就是尾巴摇狗，而这里只是一个
   * 进程问自己一个问题。
   */
  private static final AtomicBoolean RESTART_REQUESTED = new AtomicBoolean();

  /** 已经请求重启时为 true；CLI 在它那次运行结束后检查它。 */
  public static boolean restartRequested() {
    return RESTART_REQUESTED.get();
  }

  /**
   * 忘掉这个 JVM 里更早那次运行发出的请求。
   *
   * <p>这个标志回答的是关于某一次运行的问题，但进程可能比它活得久——测试套件和任何嵌入方都会多次调用
   * {@code Cli}——而一个活到下一次运行的请求，会叫它为一次它从未请求过的重启而退出 75。所以一次运行
   * 开始时先声明「不是我这次」，这个答案就始终被限制在发出它的那次运行之内。
   */
  public static void clearRequest() {
    RESTART_REQUESTED.set(false);
  }

  @Override
  public String name() {
    return "restart";
  }

  @Override
  public String description() {
    return "Install a freshly built jar in place of the one running and restart on it. Call this "
        + "after building this project, so the new code takes effect. This process exits and the "
        + "launcher starts the new jar; the session is on disk, so it can be resumed. Call it last.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "built": {
              "type": "string",
              "description": "The jar a scratch build produced: build with -Djar.name=ccj-next, which writes target/ccj-next.jar."
            }
          },
          "required": ["built"],
          "additionalProperties": false
        }""";
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) throws IOException {
    JsonNode args = ToolSupport.args(argumentsJson);
    Path built = ctx.resolve(ToolSupport.requireText(args, "built"));
    Path installed = ctx.cwd().resolve("target").resolve(INSTALLED_JAR);
    Path staged = ctx.cwd().resolve("target").resolve(STAGED_JAR);
    String self = ToolSupport.display(ctx, built);

    if (!Files.isRegularFile(built)) {
      return ToolResult.error(
          self
              + " 处没有 jar — 先构建这个项目：mvn -q -DskipTests -Djar.name=ccj-next package，"
              + "它会写出 "
              + STAGED_JAR);
    }
    if (built.toAbsolutePath().normalize().equals(installed.toAbsolutePath().normalize())) {
      return ToolResult.error(
          self
              + " 已经是当前安装的 jar；请传那个暂存的（"
              + STAGED_JAR
              + "），这样替换才有来源");
    }
    if (!Files.isRegularFile(installed)) {
      return ToolResult.error(
          ToolSupport.display(ctx, installed)
              + " 处没有 "
              + INSTALLED_JAR
              + " — restart 安装的是启动器运行的那个 jar，而这里没有");
    }
    if (!built.toAbsolutePath().normalize().equals(staged.toAbsolutePath().normalize())) {
      return ToolResult.error(
          self
              + " 不是构建所暂存的那个 jar（"
              + ToolSupport.display(ctx, staged)
              + "）；装别的 jar 是完全另一回事");
    }

    String detail =
        ToolSupport.display(ctx, built)
            + "  ->  "
            + ToolSupport.display(ctx, installed)
            + "\n(以重命名的方式覆盖正在运行的 jar：JVM 保留它已经打开的那个文件，所以这不会杀死"
            + "本进程)\n"
            + "随后本进程以 "
            + RESTART_EXIT
            + " 退出，启动器会启动新的 jar";
    String refusal = ctx.refusal(ApprovalRequest.tool("restart", detail));
    if (refusal != null) {
      return ToolResult.error(refusal);
    }

    try {
      Files.move(
          built, installed, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      // 两条路径之间隔着两个挂载点；普通的 move 仍然是一次重命名，而不是覆盖写入。
      Files.move(built, installed, StandardCopyOption.REPLACE_EXISTING);
    }
    RESTART_REQUESTED.set(true);
    // 这之后什么都发不出去了：本进程即将被替换。在这里结束这次运行，才能让转录不会以一个没人要过的
    // 提供方错误收尾。
    ctx.endRun();
    return ToolResult.ok("已安装 " + ToolSupport.display(ctx, installed) + "；即将在它上面重启");
  }
}
