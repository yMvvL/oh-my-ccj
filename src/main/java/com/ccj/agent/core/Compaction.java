package com.ccj.agent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 一段对话较早的回合最终压缩成了什么，用模型自己的话写。
 *
 * <p>这是项目里唯一会替换（而不是投影）对话内容的东西，所以它遵循的规则刻意保守：
 *
 * <ul>
 *   <li><b>最新的若干轮对话保持原样。</b>对刚发生的事做摘要比不摘要更糟，因为模型马上要据它行动。切割发生在
 *       一轮对话的边界——一条 {@link Message.User}——这样工具结果永远不会失去提出它的助手回合，这同时也是
 *       {@link ContextBudget} 与 {@link SessionRepair} 各自出于自己的理由所维护的同一条不变式。
 *   <li><b>保留下来的部分是被引用的，不是被压缩的。</b>尾部原样复制；只有较早的部分被摘要。这里不会把模型
 *       自己仍能读到的东西改写成另一种说法。
 *   <li><b>摘要按一组事实来索取。</b>{@link #INSTRUCTIONS} 点名了「接下这项工作的模型」真正需要的几类
 *       信息——包括它否则得重新发现的准确错误文本和路径——因为「总结一下」产出的是一篇叙述，而叙述恰恰会
 *       丢掉下一步所依赖的细节。
 * </ul>
 */
public final class Compaction {

  /**
   * 最近的多少轮对话保持原样。
   *
   * <p>五是个判断，不是自然常数：一个人重新拾起工作往回翻，差不多也就翻到这里；而当用户的下一条消息说到
   * 「那个错误」或「你刚改的文件」时，模型需要的也正是这个窗口。尾巴留得越大，省下的越少；留得越小，代价
   * 是模型不得不被重新告知它本该已经知道的事。
   */
  public static final int KEEP_EXCHANGES = 5;

  /**
   * 把一段对话变成摘要的指令。
   *
   * <p>写成清单，是因为散文式摘要的失败形态众所周知且非常具体：模型写出一篇流畅的始末，却把每个它需要的
   * 标识符都丢掉了。点名这些类别，才能留住文件路径、管用的命令，以及报错的原文。
   */
  public static final String INSTRUCTIONS =
      """
      You are compacting a coding session so it can continue with less context. Below is the earlier \
      part of the conversation. Write a summary that a model resuming this work can act on.

      Cover these, in this order, keeping concrete facts rather than describing the process:

      1. Goal — what was asked for, and any constraint stated up front.
      2. Decisions — what was chosen, and why, including approaches that were tried and rejected.
      3. Files — every file created, modified or read for a reason, with its path, and what changed.
      4. Verification — what was run or checked, and its outcome. Quote exit codes and test results.
      5. Open — what is unfinished, broken, or still uncertain.
      6. Details worth not losing — exact error messages, command lines, API shapes, versions.

      Use the paths, identifiers and commands as they were written: they are the part that cannot be \
      guessed later. Be specific and complete rather than brief. Do not add anything that is not in \
      the conversation, and do not claim work was finished when it was not. Write the summary itself; \
      no preamble, no headings other than the numbered ones above.""".strip();

  /** 放在摘要前面的标记，模型在哪里读到摘要都会看到它。 */
  private static final String PREAMBLE =
      "[Summary of earlier work in this session, written by the model when the conversation was "
          + "compacted]";

  /** 摘要里说明真实对话仍在何处的那些话。 */
  private static final String SOURCE_NOTE =
      "[The full conversation, verbatim, is in %s — read it if you need a detail this summary does "
          + "not have.]";

  private Compaction() {}

  /**
   * 较早部分被替换成的那段对话。
   *
   * @param messages 摘要在前，随后是保留下来的若干轮对话
   * @param summarised 有多少条消息被摘要替换掉
   * @param kept 有多少条消息原样保留
   * @param summaryTokens 替换它们的摘要的估算值
   * @param replacedTokens 被它替换掉的消息的估算值；按定义摘要比它小
   */
  public record Result(
      List<Message> messages, int summarised, int kept, int summaryTokens, int replacedTokens) {

    /** 摘要让被替换部分省下了多少，以百分比计。 */
    public int savedPercent() {
      return replacedTokens <= 0 ? 0 : 100 - Math.round(summaryTokens * 100f / replacedTokens);
    }
  }

  /** 存在值得压缩的较早部分时为 true：有东西可摘要，也有尾巴要保留。 */
  public static boolean possible(List<Message> messages) {
    return cutPoint(messages) > 0;
  }

  /**
   * 摘要会替换掉它之前一切的那个下标。
   *
   * <p>尾巴必须从一轮对话的开头开始，所以这里返回从末尾数第 {@code KEEP_EXCHANGES} 轮对话的位置——从最新
   * 的往前数，而不是数过那么多就停下，那样会晚一条消息，把一轮对话劈开。返回 0 表示无事可做：对话轮数还不
   * 够尾巴保留的，压缩什么都替换不了，白白丢掉细节却一点也没省下。
   */
  public static int cutPoint(List<Message> messages) {
    if (messages == null || messages.isEmpty()) {
      return 0;
    }
    List<Integer> starts = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      // 摘要也算一轮对话的开头：第二次压缩不能把摘要当成原始历史去摘要，否则第二遍会在第一遍的压缩上再压
      // 一遍。
      if (messages.get(i) instanceof Message.User || messages.get(i) instanceof Message.Summary) {
        starts.add(i);
      }
    }
    if (starts.size() <= KEEP_EXCHANGES) {
      return 0;
    }
    return starts.get(starts.size() - KEEP_EXCHANGES);
  }

  /**
   * 压缩一段对话，或者报告压缩它并不划算。
   *
   * <p>只有摘要比它替换掉的东西更小时，压缩才是省；而这一点不是自动成立的：摘要有一个前言、会点名它来自
   * 哪个文件，而且按「完整」来写——所以一段早期回合大多是工具调用管道的对话，摘要出来可能比被它移除的文本
   * <em>更长</em>。这项检查针对的是这次切割实际会吃掉的那些消息，而不是整段对话，因为被交易的就是那一部分。
   *
   * @param messages 当下的对话
   * @param summary 模型写下的内容
   * @param source 被摘要的那些消息仍在其中的文件
   * @param cwd 用来把来源显示为可读的相对路径
   * @throws NotWorthIt 当结果不会比它所替换的对话更小时
   */
  public static Result apply(
      List<Message> messages, String summary, String source, java.nio.file.Path cwd) {
    int cut = cutPoint(messages);
    if (cut <= 0) {
      throw new IllegalArgumentException("这段对话没有可压缩的内容");
    }
    List<Message> replaced = messages.subList(0, cut);
    List<Message> kept = new ArrayList<>(messages.subList(cut, messages.size()));
    String label = display(source, cwd);
    StringBuilder text = new StringBuilder(PREAMBLE).append(' ');
    if (!label.isEmpty()) {
      text.append(String.format(SOURCE_NOTE, label)).append(' ');
    }
    text.append('\n').append(summary == null ? "" : summary.strip());
    Message.Summary head = new Message.Summary(text.toString(), cut, label);

    // 实测，而不是假设：摘要写好之前拿不到它的估算值，所以比较放在此刻进行，亏本的交易会被拒绝，而不是被
    // 写下来。
    int replacedTokens = TokenEstimate.of(replaced);
    int summaryTokens = TokenEstimate.of(head);
    if (summaryTokens >= replacedTokens) {
      throw new NotWorthIt(replacedTokens, summaryTokens);
    }

    List<Message> out = new ArrayList<>(kept.size() + 1);
    out.add(head);
    out.addAll(kept);
    return new Result(List.copyOf(out), cut, kept.size(), summaryTokens, replacedTokens);
  }

  /**
   * 被拒绝：摘要没能比它要替换的内容更小。
   *
   * <p>两个数字都带着，因为对「为什么不」最诚实的回答就是这次比较本身：调用方可以说对话还不够长，而不是
   * 报告一次失败。
   */
  public static final class NotWorthIt extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int replacedTokens;
    private final int summaryTokens;

    NotWorthIt(int replacedTokens, int summaryTokens) {
      super(
          "摘要为 "
              + summaryTokens
              + " token，而它要替换的是 "
              + replacedTokens
              + " token，压缩腾不出任何空间");
      this.replacedTokens = replacedTokens;
      this.summaryTokens = summaryTokens;
    }

    public int replacedTokens() {
      return replacedTokens;
    }

    public int summaryTokens() {
      return summaryTokens;
    }
  }

  /**
   * 被摘要的那部分，以纯文本、按「请求它写摘要的那次请求」应当携带的形状呈现。
   *
   * <p>刻意从线路格式本来会发送的那些消息渲染而来——角色点名、工具调用与它们的结果并排展示——而不是用原始
   * JSONL，因为摘要必须能被要写它的那个模型读懂。
   */
  public static String transcript(List<Message> messages) {
    int cut = cutPoint(messages);
    List<Message> part = cut <= 0 ? List.of() : messages.subList(0, cut);
    StringBuilder text = new StringBuilder();
    for (Message message : part) {
      switch (message) {
        case Message.System system -> append(text, "system", system.text());
        case Message.User user -> append(text, "user", user.text());
        case Message.Summary summary -> append(text, "summary of still earlier work", summary.text());
        case Message.Assistant assistant -> {
          StringBuilder body = new StringBuilder(assistant.text());
          for (Message.ToolCall call : assistant.toolCalls()) {
            if (!body.isEmpty()) {
              body.append('\n');
            }
            body.append("called ").append(call.name()).append(' ').append(call.arguments());
          }
          append(text, "assistant", body.toString());
        }
        case Message.ToolResult result ->
            append(
                text,
                result.error() ? "tool result (error)" : "tool result",
                result.toolName() + ": " + result.content());
      }
    }
    return text.toString();
  }

  private static void append(StringBuilder text, String role, String body) {
    text.append("\n### ").append(role).append('\n').append(body == null ? "" : body.strip()).append('\n');
  }

  /** 来源路径在提示词里应有的样子：位于 cwd 之内时用相对路径。 */
  private static String display(String source, java.nio.file.Path cwd) {
    if (source == null || source.isBlank()) {
      return "";
    }
    try {
      java.nio.file.Path path = java.nio.file.Path.of(source).toAbsolutePath().normalize();
      if (cwd != null) {
        java.nio.file.Path base = cwd.toAbsolutePath().normalize();
        if (path.startsWith(base)) {
          java.nio.file.Path relative = base.relativize(path);
          return relative.toString().isEmpty() ? "." : relative.toString();
        }
      }
      return path.toString();
    } catch (RuntimeException e) {
      // 不是一个可用的路径：与其打印出模型会去尝试打开然后失败的东西，不如把这条注记省掉。摘要本身不受影响。
      return "";
    }
  }
}
