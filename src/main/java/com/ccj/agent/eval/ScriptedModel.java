package com.ccj.agent.eval;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 一个按剧本说话的模型：第 n 次请求回第 n 段台词，一个字不多。
 *
 * <p>它存在的理由与 {@code --demo} 的那个替身不同。那个替身是<em>工具路由</em>——它看着提示决定调哪个
 * 工具，所以它能演示循环，却不能重复。这个是<em>录像</em>：同样的一段剧本永远产生同样的一段对话、同样的
 * 工具调用和同样的 token 计数，因此「这个改动有没有让某个任务变差」是一个能被回答的问题。评测套件要的正
 * 是这一点——一个会自己变主意的基线，不是基线。
 *
 * <p>调用 id 是算出来的（{@code call_1}、{@code call_2}……）而不是随机的：会话文件、trace 与失败信息里都
 * 会带上它们，而一份每次都不同的证据没法被 diff。
 */
public final class ScriptedModel implements Provider {

  /**
   * 一次答复：要么说一段话，要么要求调用工具，要么两者都有。
   *
   * @param text 这段散文；空串表示这一次不说话
   * @param toolCalls 这一段要求调用的工具，按顺序
   * @param usage 要上报的 token 账；null 表示什么都不报。带上它，评测才能把「换了这段提示词多花了多少
   *     token」当数字看，而不是当感觉看
   */
  public record Step(String text, List<Message.ToolCall> toolCalls, int[] usage) {

    public Step {
      text = text == null ? "" : text;
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static Step say(String text) {
      return new Step(text, List.of(), null);
    }

    /** 一段要求调用一个工具的答复。 */
    public static Step call(String name, String argumentsJson) {
      return new Step("", List.of(new Message.ToolCall("call_" + name, name, argumentsJson)), null);
    }

    /**
     * 从一行 JSON 读一段台词。
     *
     * <pre>
     * {"text": "读完了。"}                                  // 只说话
     * {"tool": {"name": "read", "arguments": {"path": "a"}}}   // 调一个工具
     * {"tools": [ … ], "text": "…", "usage": [700, 40, 512]}   // 一次调好几个，并报一笔账
     * </pre>
     *
     * <p>调用 id 由这里分配：剧本不该操心它们，而它们必须是稳定的。
     */
    static Step fromJson(JsonNode node, int index) {
      List<Message.ToolCall> calls = new ArrayList<>();
      JsonNode one = node.path("tool");
      JsonNode many = node.path("tools");
      int counter = 0;
      if (one.isObject()) {
        calls.add(call(index, counter++, one));
      }
      if (many.isArray()) {
        for (JsonNode entry : many) {
          calls.add(call(index, counter++, entry));
        }
      }
      int[] usage = null;
      if (node.path("usage").isArray() && node.path("usage").size() >= 2) {
        JsonNode reported = node.path("usage");
        usage =
            new int[] {
              reported.get(0).asInt(),
              reported.get(1).asInt(),
              reported.size() > 2 && reported.get(2).isInt() ? reported.get(2).asInt() : -1
            };
      }
      return new Step(node.path("text").asText(""), calls, usage);
    }

    private static Message.ToolCall call(int step, int position, JsonNode node) {
      String name = node.path("name").asText("");
      if (name.isBlank()) {
        throw new IllegalArgumentException("剧本第 " + (step + 1) + " 段的工具没有名字");
      }
      JsonNode arguments = node.path("arguments");
      String json = arguments.isMissingNode() || arguments.isNull() ? "{}" : Json.write(arguments);
      return new Message.ToolCall("call_" + (step + 1) + "_" + (position + 1), name, json);
    }
  }

  private final List<Step> script;
  private int cursor;

  public ScriptedModel(List<Step> script) {
    this.script = List.copyOf(script);
  }

  @Override
  public String name() {
    return "scripted";
  }

  @Override
  public Message.Assistant complete(Request request, Consumer<Event> listener) {
    // 剧本用完之后的每一次请求都得到同一句话，而它是一句**回答**（没有工具调用），所以循环会就此结束。
    // 一个越写越长的任务会因此在步数上露出来，而不是挂在那里：这也是「步数超出预期」那条断言的意义。
    if (cursor >= script.size()) {
      String text = "（剧本到头了：这一段任务要的往返比它写下来的多）";
      listener.accept(new Event.TextDelta(text));
      return Message.Assistant.text(text);
    }
    Step step = script.get(cursor++);
    if (!step.text().isEmpty()) {
      listener.accept(new Event.TextDelta(step.text()));
    }
    for (Message.ToolCall call : step.toolCalls()) {
      listener.accept(new Event.ToolCallStart(call.id(), call.name()));
    }
    if (step.usage() != null) {
      listener.accept(
          new Event.Usage(
              step.usage()[0],
              step.usage()[1],
              step.usage()[2] < 0 ? null : step.usage()[2]));
    }
    return new Message.Assistant(step.text(), step.toolCalls(), List.of());
  }
}
