package com.ccj.agent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 让一段对话在被打断之后重新可以发送。
 *
 * <p>两种线路格式校验的是同一种形状：一个请求了工具调用的助手回合，在紧随其后的那串消息里，必须为每个调用各
 * 跟一条结果。然而会话是只追加的，而循环在调用运行<em>之前</em>就把助手消息追加了进去——所以在两者之间中止，
 * 或调用进行到一半进程被杀，都会留下一段没有提供方会接受的历史。这个故障是永久且彻底的：此后每个回合都会重发
 * 同一段坏历史并被拒绝，于是对话再也无法继续。
 *
 * <p>修复不能改写文件（只追加正是让「恢复会话」可信的性质），所以它施加在上线的投影上。记录下来的事实被保留，
 * 缺失的结果被补上：「未运行」正是那些调用实际遭遇的事，而且它是模型能读懂的一条结果。
 *
 * <p>答复是<em>按调用</em>归集的，而不是按相邻关系。一段对话可能在某个回合的答复中间多出一条消息——同一个会话
 * 上的第二个前端、用户在回合运行期间打字、文件里多出的一次写入——于是那条结果就不在线路格式期望的位置上。这样
 * 的结果会被带回它所属的回合，既回到它该在的地方，也是 API 会接受的地方，而不是作为一个没有提供方能安放的
 * 孤儿发出去。没有任何已记录调用在等的答复则完全无处可去：它被留在投影之外，通知里也会说明，因为只有当每条结果
 * 都回答了一个调用时，请求才可以发送。
 */
public final class SessionRepair {

  /** 缺失的结果所写的话；这是关于它唯一确定无疑的事实。 */
  public static final String NOT_RUN = "未运行：这次调用完成之前，本回合就被中断了";

  /**
   * 修复后的对话。
   *
   * @param messages 同一批消息，但每个无人回答的调用都在提出它的助手消息之后补上了一条合成结果
   * @param filled 补上了多少条结果
   * @param moved 有多少答复不得不被带回提出它们的那个回合
   * @param dropped 有多少结果因为回答不了任何调用而不在投影里
   */
  public record Result(List<Message> messages, int filled, int moved, int dropped) {

    public boolean repaired() {
      return filled > 0 || moved > 0 || dropped > 0;
    }

    /** 转录里的一行；没有什么可修时为 null。 */
    public String notice() {
      if (!repaired()) {
        return null;
      }
      List<String> parts = new ArrayList<>(3);
      if (filled > 0) {
        parts.add(filled + " 次被中断的工具调用被标记为未运行");
      }
      if (moved > 0) {
        parts.add(moved + " 条工具结果被带回了提出它们的那个回合");
      }
      if (dropped > 0) {
        // 这条结果完全不会被发送，而对它只字不提，就会把一个真实存在的答复藏起来，不让那个本可以去翻它
        // 仍所在文件的人看到。
        parts.add(dropped + " 条回答不了任何调用的工具结果未放进请求");
      }
      return "历史已修复：" + String.join("，", parts);
    }
  }

  private SessionRepair() {}

  /**
   * 返回 {@code messages}，其中每个工具调用与每条工具结果都正确配对：答复被归拢到提出调用的那个回合里，按
   * 线路格式要求的调用顺序排列；没有结果的调用补上一条「未运行」结果。
   */
  public static Result apply(List<Message> messages) {
    if (messages == null || messages.isEmpty()) {
      return new Result(List.of(), 0, 0, 0);
    }
    List<Message> out = new ArrayList<>(messages.size() + 4);
    // 仍在收集答复的那个回合，以及它在等待期间到达的一切：这些消息不能写在答复之前，因为那正是两种线路格式
    // 都会拒绝的形状，所以它们在这里等，等回合完整了就离开。
    Open open = null;
    List<Message> deferred = new ArrayList<>();
    int filled = 0;
    int moved = 0;
    int dropped = 0;

    for (Message message : messages) {
      if (message instanceof Message.ToolResult result) {
        int slot = open == null ? -1 : open.slotFor(result);
        if (slot < 0) {
          // 没有任何东西在等它：要么它的调用从未被记录，要么它来了两次。线路格式没有地方放这样的结果，所以
          // 发送它就是本投影存在的意义所在——防止请求被拒。
          dropped++;
          continue;
        }
        if (slot < open.arrived || !deferred.isEmpty()) {
          // 它必须被带回自己的回合：两者之间来了别的东西，或者答复按自己的顺序到达。落点 *越过* 下一个空位
          // 的不算搬移——那是一次更早的、还没有答复的调用，由下面的 flush 补上。
          moved++;
        }
        open.answer(slot, result);
        if (open.complete()) {
          filled += flush(out, open, deferred);
          open = null;
        }
        continue;
      }
      if (message instanceof Message.Assistant assistant && assistant.hasToolCalls()) {
        if (open != null) {
          filled += flush(out, open, deferred);
        }
        open = new Open(assistant);
        continue;
      }
      if (open == null) {
        out.add(message);
      } else {
        deferred.add(message);
      }
    }
    if (open != null) {
      filled += flush(out, open, deferred);
    }
    return new Result(List.copyOf(out), filled, moved, dropped);
  }

  /**
   * 完整写出一个回合：助手消息，每个调用按调用顺序各一条结果——有真结果就用真的，没有就写「未运行」——然后
   * 是等在它后面的东西。
   *
   * @return 补上了多少条结果
   */
  private static int flush(List<Message> out, Open open, List<Message> deferred) {
    List<Message.ToolCall> calls = open.assistant.toolCalls();
    out.add(open.assistant);
    int filled = 0;
    for (int i = 0; i < calls.size(); i++) {
      Message.ToolResult answer = open.answers.get(i);
      if (answer != null) {
        out.add(answer);
        continue;
      }
      Message.ToolCall call = calls.get(i);
      out.add(new Message.ToolResult(call.id(), call.name(), NOT_RUN, true));
      filled++;
    }
    out.addAll(deferred);
    deferred.clear();
    return filled;
  }

  /**
   * 正在收集答复的一个助手回合。
   *
   * <p>答复按位置与调用匹配，而不是只看 id：确实存在完全省略调用 id 的服务端，而两个共享同一个空 id 的
   * 调用仍然是两个调用。第一条 id 匹配且尚未有答复的调用收下这条结果，正是这一点让这种对话仍然可发送。
   */
  private static final class Open {

    private final Message.Assistant assistant;
    private final List<Message.ToolResult> answers;
    /** 已经到达多少条答复；如果什么都没搬动，下一个答复该落的就是这个位置。 */
    private int arrived;

    Open(Message.Assistant assistant) {
      this.assistant = assistant;
      List<Message.ToolResult> slots = new ArrayList<>(assistant.toolCalls().size());
      for (int i = 0; i < assistant.toolCalls().size(); i++) {
        slots.add(null);
      }
      this.answers = slots;
    }

    /** 这条结果所回答的位置；不属于本回合任何调用时为 -1。 */
    int slotFor(Message.ToolResult result) {
      List<Message.ToolCall> calls = assistant.toolCalls();
      for (int i = 0; i < answers.size(); i++) {
        if (answers.get(i) == null && calls.get(i).id().equals(result.toolCallId())) {
          return i;
        }
      }
      return -1;
    }

    void answer(int slot, Message.ToolResult result) {
      answers.set(slot, result);
      arrived++;
    }

    boolean complete() {
      return arrived == answers.size();
    }
  }
}
