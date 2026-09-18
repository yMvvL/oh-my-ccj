package com.ccj.agent.demo;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 一个不存在的模型的替身。
 *
 * <p>它把提示直接路由成工具调用——{@code read <path>}、{@code run <command>}、{@code list [glob]}、
 * {@code search <regex>}——别的一律用这套词汇作答。工具结果一回来，它总是用散文作答，这正是结束循环的
 * 方式。
 *
 * <p>它不是模型，也从不假装是。它存在的意义是让 {@code ccj --demo} 用一条命令展示真实的循环、真实的
 * 工具、真实的审批提示和真实的 web UI，不需要密钥、不需要网络、也不需要第二个进程。
 */
public final class DemoProvider implements Provider {

  private static final String HELP =
      "（demo 模型）这里没有模型，只有一个工具路由。试试："
          + " read <path> | run <command> | list [glob] | search <regex>。你说的是：\"";

  private final AtomicInteger nextCallId = new AtomicInteger();

  @Override
  public String name() {
    return "demo";
  }

  @Override
  public Message.Assistant complete(Request request, Consumer<Event> listener) {
    Message.Assistant reply = decide(request.messages());
    if (!reply.text().isEmpty()) {
      listener.accept(new Event.TextDelta(reply.text()));
    }
    for (Message.ToolCall call : reply.toolCalls()) {
      listener.accept(new Event.ToolCallStart(call.id(), call.name()));
    }
    return reply;
  }

  /** 这个回合做什么。公开是为了让测试能在中间没有传输层的情况下钉住它。 */
  public Message.Assistant decide(List<Message> messages) {
    Message last = messages == null || messages.isEmpty() ? null : messages.get(messages.size() - 1);

    if (last instanceof Message.ToolResult result) {
      String content = result.content();
      long lines = content.lines().count();
      String first = content.lines().findFirst().orElse("（无输出）");
      return Message.Assistant.text(
          "工具说：" + first + (lines > 1 ? " …（共 " + lines + " 行）" : ""));
    }

    String text = last instanceof Message.User user ? user.text().strip() : "";
    String lower = text.toLowerCase(Locale.ROOT);
    int space = text.indexOf(' ');
    String rest = space < 0 ? "" : text.substring(space + 1).strip();

    if (lower.startsWith("read ") && !rest.isEmpty()) {
      return tool("read", "path", rest);
    }
    if ((lower.startsWith("run ") || lower.startsWith("bash ")) && !rest.isEmpty()) {
      return tool("bash", "command", rest);
    }
    if (lower.equals("list") || lower.startsWith("list ")) {
      return tool("glob", "pattern", rest.isEmpty() ? "**/*" : rest);
    }
    if (lower.startsWith("search ") && !rest.isEmpty()) {
      return tool("grep", "pattern", rest);
    }
    return Message.Assistant.text(HELP + text + "\"");
  }

  private Message.Assistant tool(String name, String field, String value) {
    Message.ToolCall call =
        new Message.ToolCall(
            "demo-" + name + "-" + nextCallId.incrementAndGet(),
            name,
            Json.write(Json.object().put(field, value)));
    return new Message.Assistant("", List.of(call));
  }
}
