package com.ccj.agent.tool;

import com.ccj.agent.core.SubAgentReport;
import com.ccj.agent.core.SubAgentRole;
import com.ccj.agent.core.SubAgentRunner;
import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;

/**
 * 把活儿委托给一个子代理，并返回它的报告。
 *
 * <p>它存在的理由是上下文，不是速度。一个读了三十个文件才找出其中要紧的三个的代理，已经在对话里花掉
 * 三十个文件的 token，而此后每次请求都会把它们重发一遍——长会话最终之所以需要 {@code /compact}，正是
 * 这个原因。子代理在它自己的对话里读那三十个文件，读完就丢掉，只报告那三个。主对话增长的是结论，而不是
 * 一次搜索。
 *
 * <p>模型拿回来的是报告，绝不是子代理的转录：全部价值就在于阅读过程不跟着一起回来。而且子代理自己的
 * 注册表里没有 {@code task} 工具，所以子代理无法再往下委托——递归是被结构拒绝的，而不是靠一个深度计数。
 *
 * <p>会写的角色在主代理写的地方写，向同一个人请求许可。子代理在它读什么上是看不见的；在它做什么上并非如此。
 */
public final class TaskTool implements Tool {

  /** 一次工具调用是代表谁发出的。 */
  @FunctionalInterface
  public interface Runner {
    SubAgentReport run(SubAgentRole role, String task);
  }

  /**
   * 委托出去的运行，其开销记到哪里。
   *
   * <p>子代理的 token 就是父对话的 token——同一个模型、同一个账号、同一份账单——所以它们被加到委托出
   * 这次工作的会话上。只报报告不报开销，会让用量面板少算实际花掉的量，而一个悄悄算错的数字比没有数字
   * 更糟。
   */
  @FunctionalInterface
  public interface UsageSink {
    void add(com.ccj.agent.core.UsageTotals spent);
  }

  private final Runner runner;
  private final UsageSink usage;
  private final boolean writesAllowed;

  /**
   * @param runner 执行一个子代理；由调用方提供，因为它需要的提供方、设置和审批者属于那次对话，而不属于
   *     一个无状态的工具
   * @param usage 运行的开销加到哪里，null 表示什么都不计
   * @param writesAllowed 传 false 把每个角色都限制为只读，不希望委托出去的运行做任何写入的调用方就是
   *     这样表达的
   */
  public TaskTool(Runner runner, UsageSink usage, boolean writesAllowed) {
    this.runner = runner;
    this.usage = usage;
    this.writesAllowed = writesAllowed;
  }

  @Override
  public String name() {
    return "task";
  }

  /**
   * 不是只读的，而且这是刻意的：委托出去的任务可能会写，而循环用这个标志决定什么可以并行。两个子代理
   * 同时运行是一个关于文件的问题，而这个工具无法保证自己没有文件要碰。
   */
  @Override
  public boolean readOnly() {
    return false;
  }

  @Override
  public String description() {
    return "Delegate a self-contained piece of work to a sub-agent, which reads and works in its own"
        + " conversation and reports back only a summary. Use it to keep your own context small: send"
        + " it to find something across many files, to check work, or to produce a file, and you get"
        + " the conclusion instead of the reading. It cannot see this conversation, so the task must"
        + " be self-contained. `explore` and `verify` read only; `build` writes, and its changes ask"
        + " for the same permission yours do — tell it exactly what to produce, since it cannot ask"
        + " you a follow-up question.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "role": {
              "type": "string",
              "enum": ["explore", "verify", "build"],
              "description": "explore = find out; verify = check existing work and report, without changing it; build = produce a file."
            },
            "task": {
              "type": "string",
              "description": "What to do, self-contained. Say what you want back and how it should be shaped, since the sub-agent cannot ask a follow-up question."
            }
          },
          "required": ["role", "task"],
          "additionalProperties": false
        }""";
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) throws Exception {
    var args = ToolSupport.args(argumentsJson);
    String roleName = ToolSupport.requireText(args, "role");
    String task = ToolSupport.requireText(args, "task");
    SubAgentRole role =
        SubAgentRole.of(roleName)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "未知角色 '" + roleName + "'；期望是 " + SubAgentRole.names() + " 之一"));
    if (role.writes() && !writesAllowed) {
      return ToolResult.error(
          "'" + role.wireName() + "' 这个角色在这次对话里不可用；请改用 'explore'"
              + " 或 'verify'，它们只读取并报告，什么都不改");
    }
    // 失败的运行仍然带着它花掉的量——SubAgentRunner 在失败路径上也会附上这份计数——所以无论状态如何，
    // 开销都会被报告；而调用方自己的监听器已经把这个回合自身花掉的 token 记下了。
    SubAgentReport report = runner.run(role, task);
    if (usage != null && report.usage() != null) {
      usage.add(report.usage());
    }
    String rendered = report.render(ctx.cwd().toString());
    if ("failed".equals(report.status())) {
      // 失败的运行报成错误结果而不是成功，这样模型会把它看作一件没成的事，可以决定接下来怎么办，而不是
      // 把它读成一个答案。
      return ToolResult.error(rendered);
    }
    return ToolResult.ok(rendered);
  }
}
