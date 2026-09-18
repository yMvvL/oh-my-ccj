package com.ccj.agent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一段对话塞进提示词预算，同时不破坏线路格式。
 *
 * <p>会话文件无上限地增长，而提供方的上下文有上限，所以一段长对话在发送前必须被投影到更小的版本上。三条
 * 规则决定怎么做：
 *
 * <ol>
 *   <li><b>先动工具输出。</b>旧的工具结果是代理上下文里的大头，也是其中最不值钱的部分：二十个回合前读的
 *       文件通常已经过时，而提出那次读取的那句话仍然要紧。略去它们的<em>内容</em>能保住 API 要求的
 *       调用/结果配对，于是对话既保持合法，又缩小几个数量级。
 *   <li><b>然后整轮对话一起走。</b>丢弃从最旧的一端开始，并且绝不把助手回合与它所做调用的结果拆开——那种
 *       配对正是两种线路格式都要校验的，拆一半不是让请求更小，而是让请求被拒。
 *   <li><b>当前这轮对话永远不丢。</b>模型正在回答它。如果光它自己就装不下，就给它里面最大的结果加一个明确
 *       标记后截断，这是剩下唯一诚实的做法。
 * </ol>
 *
 * <p>会话保留一切；这里只决定一次请求携带什么。
 */
public final class ContextBudget {

  private static final String ELIDED = "(已略去：来自较早回合的 %d token 的工具输出)";

  /** 截断标记自身的开销，好让切割量能算得刚好落在预算之下。 */
  private static final int MARKER_TOKENS = 32;

  /**
   * 一次裁剪做了什么，用循环所报告的数字表示。
   *
   * @param messages 要发送的投影
   * @param beforeTokens 对话原样的估算值
   * @param afterTokens 剩下的部分的估算值
   * @param elidedResults 内容被替换掉的工具结果
   * @param droppedTurns 被整个移除的若干轮对话
   * @param truncated 当当前这轮对话中的某一条结果不得不被裁剪时为 true
   */
  public record Result(
      List<Message> messages,
      int beforeTokens,
      int afterTokens,
      int elidedResults,
      int droppedTurns,
      boolean truncated,
      boolean overBudget) {

    /** 投影带走了东西时为 true：它返回的对话更小了。 */
    public boolean trimmed() {
      return elidedResults > 0 || droppedTurns > 0 || truncated;
    }

    /** 转录里的一行；没有裁剪、也没有超出预算时为 null。 */
    public String notice() {
      if (!trimmed() && !overBudget) {
        return null;
      }
      StringBuilder text =
          new StringBuilder("上下文：")
              .append(beforeTokens)
              .append(" → ")
              .append(afterTokens)
              .append(" token");
      if (elidedResults > 0) {
        text.append("，").append(elidedResults).append(" 条旧的工具结果已略去");
      }
      if (droppedTurns > 0) {
        text.append("，").append(droppedTurns).append(" 轮较早的对话已丢弃");
      }
      if (truncated) {
        text.append("，最新的结果被截短");
      }
      if (overBudget) {
        // 把它说出来，是「模型拿到的比你想象的少」与「请求悄悄超出了它的预算窗口」之间的差别。
        text.append("，仍然超出预算：已经没有别的可裁了");
      }
      return text.toString();
    }
  }

  private ContextBudget() {}

  /**
   * 把 {@code messages} 投影到至多 {@code maxTokens} 个估算 token。
   *
   * @param maxTokens 预算；&le; 0 的任何值表示「没有预算」：对话原样返回，这也是从未配置过预算的运行所
   *     期望的
   */
  public static Result apply(List<Message> messages, int maxTokens) {
    List<Message> source = messages == null ? List.of() : List.copyOf(messages);
    int before = TokenEstimate.of(source);
    if (maxTokens <= 0 || before <= maxTokens) {
      return new Result(source, before, before, 0, 0, false, false);
    }

    // 只测量一次，之后靠算术传递：下面每一步都确切知道自己改了什么，而在循环里对整个对话反复估算会让一次
    // 投影变成 O(消息数 × 字符数)——长会话上每个模型回合都要烧掉几秒纯 CPU。
    Trimmed working = new Trimmed(new ArrayList<>(source), before);

    int elided = elideToolOutputs(working, maxTokens);

    int dropped = 0;
    while (working.tokens > maxTokens) {
      int end = firstExchangeEnd(working.messages);
      if (end <= 0) {
        break; // 只剩下正在被回答的那一轮对话
      }
      working.dropPrefix(end);
      dropped++;
    }

    boolean truncated = truncateLargest(working, maxTokens);

    // 报告的数字是测量出来的，不是累加出来的：估算本身就是启发式的，告诉调用方「发了多少」应该就是估算器
    // 说发了多少。
    int after = TokenEstimate.of(working.messages);
    return new Result(
        List.copyOf(working.messages), before, after, elided, dropped, truncated, after > maxTokens);
  }

  /**
   * 正在被裁剪的一段对话，连同随身携带的 token 总数。
   *
   * <p>这里的每次改动都配着相应的算术，正因如此一次投影与对话规模成线性关系，而不是二次。
   */
  private static final class Trimmed {

    private final List<Message> messages;
    private int tokens;

    Trimmed(List<Message> messages, int tokens) {
      this.messages = messages;
      this.tokens = tokens;
    }

    /** 把一条消息换成另一条，并按这次替换实际省下的量调整总数。 */
    void replace(int index, Message replacement) {
      tokens += TokenEstimate.of(replacement) - TokenEstimate.of(messages.get(index));
      messages.set(index, replacement);
    }

    /** 移除最前面的 {@code end} 条消息。 */
    void dropPrefix(int end) {
      for (int i = 0; i < end; i++) {
        tokens -= TokenEstimate.of(messages.get(i));
      }
      messages.subList(0, end).clear();
    }
  }

  /**
   * 替换旧工具结果的内容，从最旧的开始，直到对话装得下、或当前这轮对话之外的每条结果都已略去。当前对话
   * 轮里的结果不动：那是模型此刻正在据以推理的数据，为了给它那句提出请求的话腾地方而略去它，完全是本末
   * 倒置。
   *
   * @return 略去了多少条结果
   */
  private static int elideToolOutputs(Trimmed working, int maxTokens) {
    int currentExchange = lastExchangeStart(working.messages);
    int elided = 0;
    for (int i = 0; i < working.messages.size() && i < currentExchange; i++) {
      if (working.tokens <= maxTokens) {
        break;
      }
      if (!(working.messages.get(i) instanceof Message.ToolResult result)
          || result.content().isEmpty()) {
        continue;
      }
      int saved = TokenEstimate.of(result.content());
      working.replace(
          i,
          new Message.ToolResult(
              result.toolCallId(), result.toolName(), String.format(ELIDED, saved), result.error()));
      elided++;
    }
    return elided;
  }

  /** 最新一轮对话第一条消息的下标：从那里往后，是模型正在作答的内容。 */
  private static int lastExchangeStart(List<Message> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof Message.User) {
        return i;
      }
    }
    return 0;
  }

  /**
   * 最旧一轮对话末尾的下一个位置：从它的第一条用户消息，直到开启下一轮的第二条用户消息。开头那些不属于
   * 任何用户消息的消息（系统提示词）跟着它一起走。返回 0 表示只剩一轮对话——正在被回答的那一轮——它永远
   * 不会被丢弃。
   */
  private static int firstExchangeEnd(List<Message> messages) {
    int seen = 0;
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i) instanceof Message.User) {
        seen++;
        if (seen == 2) {
          return i;
        }
      }
    }
    return 0;
  }

  /**
   * 在剩下的内容中——必然在当前这轮对话里——裁剪最大的工具结果，直到请求装得下，或者裁剪再也换不来什么。
   * 标记会说明这件事，因为给模型看半份构建日志却不告诉它，它就会去推理缺失的那一半。
   *
   * <p>循环停在「一无所获的一趟」上，而不是固定的若干趟之后：估算本身是启发式的，所以「刚刚好」不是一趟
   * 能承诺的，而设一个上限会把超大的请求留着发出去然后被拒。一趟没缩小任何东西，意味着剩下的文本已经到了
   * 下限，此时调用方报告这次未达标，而不是假装成功。标记自身的 token 也计入预算，所以它们是减出来的，而不
   * 是事后发现的。
   *
   * @return 有东西被裁掉时为 true
   */
  private static boolean truncateLargest(Trimmed working, int maxTokens) {
    boolean cut = false;
    while (working.tokens > maxTokens) {
      int largest = largestResult(working.messages);
      if (largest < 0) {
        return cut; // 只剩下散文了，而把问题裁掉不算修复
      }
      Message.ToolResult result = (Message.ToolResult) working.messages.get(largest);
      String content = result.content();
      int contentTokens = Math.max(1, TokenEstimate.of(content));
      int keepTokens = Math.max(16, contentTokens - (working.tokens - maxTokens) - MARKER_TOKENS);
      // 按这段文本实际测出的比率来折算，这样 CJK 的结果不会被保留成 ASCII 的四倍那么长。
      int keepChars =
          (int) Math.max(256L, (long) content.length() * keepTokens / contentTokens);
      keepChars = Math.min(keepChars, content.length());
      if (keepChars >= content.length()) {
        return cut; // 再也没有可让的了
      }
      int was = working.tokens;
      working.replace(
          largest,
          new Message.ToolResult(
              result.toolCallId(),
              result.toolName(),
              content.substring(0, keepChars)
                  + "\n...（已截短：这条结果装不进上下文预算）...",
              result.error()));
      if (working.tokens >= was) {
        return cut; // 一无所获的一趟就是终点，不是用来重复的
      }
      cut = true;
    }
    return cut;
  }

  /** 剩下最大的工具结果的下标；没有则为 -1。 */
  private static int largestResult(List<Message> messages) {
    int largest = -1;
    int largestTokens = 0;
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i) instanceof Message.ToolResult result) {
        int tokens = TokenEstimate.of(result.content());
        if (tokens > largestTokens) {
          largest = i;
          largestTokens = tokens;
        }
      }
    }
    return largest;
  }
}
