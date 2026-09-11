package com.ccj.agent.demo;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A stand-in for a model that does not exist.
 *
 * <p>It routes the prompt straight to a tool call — {@code read <path>}, {@code run <command>},
 * {@code list [glob]}, {@code search <regex>} — and answers anything else with that vocabulary. Once
 * a tool result comes back it always replies in prose, which is what ends the loop.
 *
 * <p>It is not a model and never pretends to be one. It exists so {@code ccj --demo} can show the
 * real loop, the real tools, the real approval prompts and the real web UI in one command, with no
 * key, no network and no second process.
 */
public final class DemoProvider implements Provider {

  private static final String HELP =
      "(demo model) there is no model here, only a tool router. Try:"
          + " read <path> | run <command> | list [glob] | search <regex>. You said: \"";

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

  /** What this turn does. Public so it can be pinned by tests without a transport in the way. */
  public Message.Assistant decide(List<Message> messages) {
    Message last = messages == null || messages.isEmpty() ? null : messages.get(messages.size() - 1);

    if (last instanceof Message.ToolResult result) {
      String content = result.content();
      long lines = content.lines().count();
      String first = content.lines().findFirst().orElse("(no output)");
      return Message.Assistant.text(
          "tool said: " + first + (lines > 1 ? " … (" + lines + " lines)" : ""));
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
