package com.ccj.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一个回合的收据：它做了什么，以及——这一半才是重点——它<em>没有</em>验证什么。
 *
 * <p>它存在的理由是这个工具的性格里最贵的那一条：每条主张都带着它的测量结果。这条规矩在此之前只活在
 * 提交信息与 CHANGELOG 里，也就是只活在维护者的流程里。收据把它做成产品的一部分——而这个工具最该承认的
 * 一件事，恰恰是它自己不知道的那部分：哪些编辑没有任何东西检查过、哪些调用被拒绝了、哪一个结果贴着输出上限
 * 因此可能只是原物的一部分。
 *
 * <p>它从已经存在的东西推导出来，而不是新记一份账：改动的文件来自检查点（与 {@code /api/undo} 的预览是
 * 同一份数据）、命令与退出码来自转录里「调用与它的结果」的配对、token 来自会话账本的前后两次快照。所以它
 * 不可能与那些地方对不上——它们本来就是同一个来源。
 *
 * <p>一条刻意的拒绝：**不检测「输出被截断」**。每个工具在省略内容时会写一句给人看的话，而那些话各写各的、
 * 形式不同；按句子做模式匹配的收据会猜，而一个会猜的收据比一个说「这一条我读不出来」的收据更糟。它改成报告
 * 每个贴着输出上限的结果（字节数与上限都是确定的），并说明为什么这一条不能被断言。
 */
public record Receipt(
    String sessionId,
    int steps,
    List<FileChange> changed,
    List<Command> commands,
    List<Call> calls,
    UsageTotals before,
    UsageTotals after,
    int outputLimitBytes,
    List<String> unverified) {

  /** 一个被改动过的文件，以及把它放回去会做的那件事——与检查点的预览用的是同一套说法。 */
  public record FileChange(String path, String action) {

    /** 这一个文件会被恢复成之前的样子。 */
    public boolean restore() {
      return "restore".equals(action);
    }
  }

  /**
   * 一条真的跑过的命令。
   *
   * @param exitCode 退出码；从结果里读不出来时为 null——收据宁可说「读不出来」，也不编一个 0
   */
  public record Command(String line, Integer exitCode, int outputBytes) {}

  /** 一次工具调用，以及它是否有东西可以证明自己跑过。 */
  public record Call(
      String tool, boolean ok, boolean refused, boolean neverRan, int outputBytes) {}

  public Receipt {
    sessionId = sessionId == null ? "" : sessionId;
    changed = changed == null ? List.of() : List.copyOf(changed);
    commands = commands == null ? List.of() : List.copyOf(commands);
    calls = calls == null ? List.of() : List.copyOf(calls);
    before = before == null ? UsageTotals.empty() : before;
    after = after == null ? UsageTotals.empty() : after;
    unverified = unverified == null ? List.of() : List.copyOf(unverified);
  }

  /**
   * 从一个回合的转录窗口里推出一份收据。
   *
   * @param messages 这一个回合的消息（即最后一条用户消息之后的所有消息），按时间顺序
   * @param changed 这一个回合改动过的文件，来自检查点
   * @param before 回合开始前的账本
   * @param after 回合结束后的账本
   * @param outputLimitBytes 工具的批量输出上限；0 表示没有上限（也就没有「贴着上限」这一说）
   * @param checks 这个项目声明的编辑后检查，用来回答「这个文件被改过，可是有没有东西检查过它」
   * @param cwd 检查的 glob 相对于哪个目录匹配，也就是工具跑命令的那个目录
   */
  public static Receipt of(
      String sessionId,
      List<Message> messages,
      List<FileChange> changed,
      UsageTotals before,
      UsageTotals after,
      int outputLimitBytes,
      Checks checks,
      Path cwd) {
    Map<String, Message.ToolCall> callsById = new LinkedHashMap<>();
    List<Call> calls = new ArrayList<>();
    List<Command> commands = new ArrayList<>();
    for (Message message : messages) {
      if (message instanceof Message.Assistant assistant) {
        for (Message.ToolCall call : assistant.toolCalls()) {
          callsById.put(call.id(), call);
        }
      }
    }
    for (Message message : messages) {
      if (!(message instanceof Message.ToolResult result)) {
        continue;
      }
      Message.ToolCall call = callsById.get(result.toolCallId());
      String arguments = call == null ? "" : call.arguments();
      boolean neverRan = SessionRepair.NOT_RUN.equals(result.content());
      // 拒绝一定是错误结果：一个「读到的文件正好以那句话开头」的成功结果不是一次拒绝。
      boolean refused =
          !neverRan && result.error() && ApprovalAnswer.looksLikeARefusal(result.content());
      calls.add(
          new Call(
              result.toolName(),
              !result.error(),
              refused,
              neverRan,
              result.content() == null ? 0 : result.content().length()));
      if ("bash".equals(result.toolName())) {
        commands.add(
            new Command(
                commandOf(arguments),
                exitCodeOf(result),
                result.content() == null ? 0 : result.content().length()));
      }
    }
    int steps = Math.max(0, after.modelTurns() - before.modelTurns());
    Receipt receipt =
        new Receipt(sessionId, steps, changed, commands, calls, before, after, outputLimitBytes, List.of());
    return new Receipt(
        sessionId, steps, changed, commands, calls, before, after, outputLimitBytes,
        receipt.workOut(checks, cwd));
  }

  /**
   * 这一轮的转录：最后一条用户消息之后的所有消息。
   *
   * <p>两个前端（终端与网页）都用它来切窗口，所以「一个回合从哪开始」只有一处说法——两处各切一次，收据里
   * 的清单迟早会与屏幕上那个回合对不上。
   */
  public static List<Message> lastTurn(List<Message> messages) {
    if (messages == null || messages.isEmpty()) {
      return List.of();
    }
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof Message.User) {
        return List.copyOf(messages.subList(i + 1, messages.size()));
      }
    }
    return List.copyOf(messages);
  }

  /** 这一轮花掉的 token（账本前后两次快照之差）。 */
  public UsageTotals spent() {
    return new UsageTotals(
        after.inputTokens() - before.inputTokens(),
        after.outputTokens() - before.outputTokens(),
        after.cachedInputTokens() - before.cachedInputTokens(),
        after.userTurns() - before.userTurns(),
        after.modelTurns() - before.modelTurns(),
        after.toolCalls() - before.toolCalls(),
        after.toolErrors() - before.toolErrors(),
        after.elapsedMillis() - before.elapsedMillis(),
        after.compactions() - before.compactions(),
        after.cacheReported());
  }

  /** 什么都发生过的回合：没有改动、没有命令、没有调用。 */
  public boolean quiet() {
    return changed.isEmpty() && commands.isEmpty() && calls.isEmpty();
  }

  /** 收据里「没有验证」那一节要说的话。 */
  private List<String> workOut(Checks checks, Path cwd) {
    List<String> gaps = new ArrayList<>();
    List<String> unchecked = new ArrayList<>();
    for (FileChange file : changed) {
      if (!file.restore()) {
        continue; // 只是被退回创建的文件：没有被改过的东西可以检查
      }
      if (cwd == null || checks == null || checks.forPath(cwd.resolve(file.path()), cwd).isEmpty()) {
        unchecked.add(file.path());
      }
    }
    if (!unchecked.isEmpty()) {
      gaps.add(
          "这些文件被改过，而没有配置任何适用于它们的编辑后检查（"
              + String.join("、", unchecked)
              + "）：改动的正确性没有被任何命令验证过");
    }
    List<String> refusals =
        calls.stream().filter(Call::refused).map(call -> call.tool()).distinct().toList();
    if (!refusals.isEmpty()) {
      gaps.add("有调用被人或规则拒绝了（" + String.join("、", refusals) + "）：那些事没有发生，也就没有被验证");
    }
    int neverRan = (int) calls.stream().filter(Call::neverRan).count();
    if (neverRan > 0) {
      gaps.add(neverRan + " 个调用在运行之前就停了（回合被中止）：它们在会话里记着「未运行」");
    }
    if (outputLimitBytes > 0) {
      List<String> nearLimit =
          calls.stream()
              .filter(call -> call.outputBytes() >= outputLimitBytes * 9 / 10)
              .map(call -> call.tool() + "（" + call.outputBytes() + " 字节 / 上限 " + outputLimitBytes + "）")
              .toList();
      if (!nearLimit.isEmpty()) {
        gaps.add(
            "这些结果贴着输出上限，所以可能只是原物的一部分："
                + String.join("、", nearLimit)
                + "——工具不会把「我被截断了」写进结果，而按句子猜的收据会猜错，所以这一条只能被读出来");
      }
    }
    if (spent().compactions() > 0) {
      gaps.add(
          "这一轮发生了一次压缩：被摘要替换掉的回合仍然在磁盘上（同一个会话的上一代文件），摘要本身不再被核对");
    }
    if (gaps.isEmpty() && !quiet()) {
      // 一个什么都没发生的回合由 render() 里那一句「没有改动，没有命令，没有验证」说清，所以这里不再添
      // 一句「没有发现没被验证的东西」——两句叠在一起，读起来像在互相取消。
      gaps.add("这一轮没有发现没被验证的东西——注意这只说明它没被这份收据发现");
    }
    return gaps;
  }

  /** 给人看的一份纯文本，可以直接复制进 issue 或提交信息。 */
  public String render() {
    UsageTotals spent = spent();
    StringBuilder out = new StringBuilder();
    out.append("收据 · 会话 ").append(sessionId).append(" · 这一轮 ").append(steps).append(" 步\n");
    out.append("─".repeat(64)).append('\n');

    out.append("改动（放回去会怎么走，与 /api/undo 的预览一致）：\n");
    if (changed.isEmpty()) {
      out.append("  没有改动任何文件\n");
    } else {
      for (FileChange file : changed) {
        out.append("  ").append(file.action()).append(' ').append(file.path()).append('\n');
      }
      out.append("  ").append(changed.size()).append(" 个文件，其中 ")
          .append(changed.stream().filter(FileChange::restore).count()).append(" 个的内容被改过\n");
    }

    out.append("命令：\n");
    if (commands.isEmpty()) {
      out.append("  没有跑任何命令\n");
    } else {
      int unknown = 0;
      for (Command command : commands) {
        if (command.exitCode() == null) {
          unknown++;
          out.append("  ????    ").append(command.line()).append("（退出码读不出来）\n");
        } else {
          out.append(String.format(Locale.ROOT, "  exit %-3d %s%n", command.exitCode(), command.line()));
        }
      }
      long failed = commands.stream().filter(command -> command.exitCode() != null && command.exitCode() != 0).count();
      out.append("  ").append(commands.size()).append(" 条命令，").append(failed).append(" 条非零退出");
      if (unknown > 0) {
        out.append("，").append(unknown).append(" 条的结果里没有退出码");
      }
      out.append('\n');
    }

    out.append("工具：").append(calls.size()).append(" 次调用")
        .append("（").append(calls.stream().filter(call -> !call.ok() && !call.neverRan()).count()).append(" 次返回错误")
        .append("、").append(calls.stream().filter(Call::refused).count()).append(" 次被拒绝")
        .append("、").append(calls.stream().filter(Call::neverRan).count()).append(" 次从未运行）\n");

    out.append("token：这一轮 ")
        .append(spent.inputTokens()).append(" 输入")
        .append(cache(spent))
        .append(" / ").append(spent.outputTokens()).append(" 输出");
    out.append("；会话累计 ")
        .append(after.inputTokens()).append(" / ").append(after.outputTokens());
    Double rate = after.cacheHitRate();
    if (rate != null) {
      out.append("（累计缓存命中 ").append(Math.round(rate * 100)).append("%）");
    } else {
      out.append("（这个提供方不报缓存，所以命中率是「未上报」而不是 0%）");
    }
    out.append('\n');

    out.append("没有验证：\n");
    for (String gap : unverified) {
      out.append("  · ").append(gap).append('\n');
    }
    if (quiet()) {
      out.append("  · 这一轮只有对话：没有改动，没有命令，没有验证\n");
    }
    return out.toString();
  }

  private static String cache(UsageTotals spent) {
    // 「没有信息」与「零」是两件事，收据里也一样：一个不报缓存的提供方不该被画成 0% 命中。
    return spent.cacheReported() ? "（缓存 " + spent.cachedInputTokens() + "）" : "";
  }

  /** 同一份收据的机器可读形状，给页面与别的工具用。 */
  public ObjectNode toJson() {
    ObjectNode node = Json.object();
    node.put("sessionId", sessionId);
    node.put("steps", steps);
    node.put("quiet", quiet());
    ArrayNode files = node.putArray("changed");
    for (FileChange file : changed) {
      files.addObject().put("path", file.path()).put("action", file.action());
    }
    ArrayNode commandList = node.putArray("commands");
    for (Command command : commands) {
      ObjectNode entry = commandList.addObject();
      entry.put("line", command.line());
      if (command.exitCode() == null) {
        entry.putNull("exitCode");
      } else {
        entry.put("exitCode", command.exitCode());
      }
      entry.put("outputBytes", command.outputBytes());
    }
    ArrayNode callList = node.putArray("calls");
    for (Call call : calls) {
      callList.addObject()
          .put("tool", call.tool())
          .put("ok", call.ok())
          .put("refused", call.refused())
          .put("neverRan", call.neverRan())
          .put("outputBytes", call.outputBytes());
    }
    UsageTotals spent = spent();
    ObjectNode tokens = node.putObject("tokens");
    tokens.put("input", spent.inputTokens());
    tokens.put("output", spent.outputTokens());
    tokens.put("cached", spent.cachedInputTokens());
    tokens.put("cacheReported", spent.cacheReported());
    tokens.put("sessionInput", after.inputTokens());
    tokens.put("sessionOutput", after.outputTokens());
    ArrayNode gaps = node.putArray("unverified");
    unverified.forEach(gaps::add);
    node.put("text", render());
    return node;
  }

  /**
   * 命令行那一行：工具收到的参数里 {@code command} 那个字段。
   *
   * <p>读不出来时给一个空串而不是原样的 JSON——收据要的是「跑了什么」，不是一份参数转储。
   */
  private static String commandOf(String argumentsJson) {
    try {
      JsonNode arguments = Json.parse(argumentsJson);
      return arguments.path("command").asText("");
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  /**
   * {@code bash} 的结果里那个退出码：它的第一行就是 {@code exit code N}。
   *
   * <p>读不出来就是 null。收据不会把一个缺席的数字想成一个 0——「这条命令成功了」与「这条命令的退出码不知道」
   * 是两件事。
   */
  private static Integer exitCodeOf(Message.ToolResult result) {
    String content = result.content();
    if (content == null || !content.startsWith("exit code ")) {
      return null;
    }
    int end = content.indexOf('\n');
    String head = end < 0 ? content : content.substring(0, end);
    String digits = head.substring("exit code ".length()).strip();
    try {
      return Integer.valueOf(digits);
    } catch (NumberFormatException e) {
      return null; // `exit code -1 (超时)` 之类：那不是一条命令的退出码，而是一句说明
    }
  }
}
