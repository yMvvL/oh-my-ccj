package com.ccj.agent.provider;

import com.ccj.agent.core.Json;
import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import com.ccj.agent.core.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * OpenAI {@code /chat/completions} API 及其模仿者（DeepSeek、Groq、Ollama……）的提供方。
 *
 * <p>两个线路细节决定了这套映射。工具调用的参数以 JSON <em>字符串</em>、而非对象的形式传输，所以从流式
 * 片段拼出来的调用必须保留它的原始文本。另外，流式工具调用是以 {@code index} 为键的片段到达的：第一个片段
 * 给这次调用命名，后续片段追加参数文本，这就是组装器按索引缓冲、而不是假定顺序的原因。
 */
public final class OpenAiProvider implements Provider {

  public static final String NAME = "openai";

  private static final List<String> REASONING_FIELDS = List.of("reasoning_content", "reasoning");

  private final String baseUrl;
  private final String apiKey;
  private final ExecutorService executor;
  private final HttpClient http;

  public OpenAiProvider(String baseUrl, String apiKey) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("openai 需要 base URL");
    }
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.apiKey = apiKey == null ? "" : apiKey;
    this.executor = Transport.newExecutor("ccj-openai-http");
    this.http = Transport.newClient(executor);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Message.Assistant complete(Request request, Consumer<Event> listener) throws Exception {
    Consumer<Event> downstream = listener == null ? event -> {} : listener;
    // 用量扣到最后再交出去，而且只交一次。契约把 usage 放在最后一帧，但线上不遵守契约的网关不少——每一个
    // 增量都重复一遍累计用量的那种，会让同一笔钱被记上几十遍；而「最后一帧」这个位置也让重试先发生完。
    UsageOnce sink = new UsageOnce(downstream);
    HttpResponse<Stream<String>> response = Transport.send(http, buildRequest(request), sink);
    try (Stream<String> lines = response.body()) {
      Message.Assistant answer = consume(response, lines, sink);
      sink.flush();
      return answer;
    } catch (UncheckedIOException e) {
      throw new Exception("流式读取响应时连接断开：" + e.getCause(), e);
    }
  }

  /**
   * 挡在提供方和调用方之间：usage 被扣住，流结束时才放行一次，取最后看到的那个值。
   *
   * <p>取最后而不是第一个：重复上报的那种网关发的是**累计**值，所以后面的比前面的更完整。
   */
  private static final class UsageOnce implements Consumer<Event> {
    private final Consumer<Event> downstream;
    private Event.Usage pending;

    UsageOnce(Consumer<Event> downstream) {
      this.downstream = downstream;
    }

    @Override
    public void accept(Event event) {
      if (event instanceof Event.Usage usage) {
        pending = usage;
        return;
      }
      downstream.accept(event);
    }

    void flush() {
      if (pending != null) {
        downstream.accept(pending);
        pending = null;
      }
    }
  }

  @Override
  public void close() {
    executor.shutdown();
  }

  private HttpRequest buildRequest(Request request) {
    String body = Json.write(buildBody(request));
    return HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
        .timeout(Transport.REQUEST_TIMEOUT)
        .header("content-type", "application/json")
        .header("accept", "text/event-stream")
        .header("authorization", "Bearer " + apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
  }

  private ObjectNode buildBody(Request request) {
    String model = request.model();
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("openai 请求需要 model");
    }
    ObjectNode root = Json.object();
    root.put("model", model);
    ArrayNode messages = root.putArray("messages");
    if (request.system() != null && !request.system().isBlank()) {
      messages.add(textTurn("system", request.system()));
    }
    for (Message message : request.messages()) {
      messages.add(toWire(message));
    }
    if (!request.tools().isEmpty()) {
      ArrayNode tools = root.putArray("tools");
      for (ToolSpec tool : request.tools()) {
        ObjectNode entry = tools.addObject();
        entry.put("type", "function");
        ObjectNode function = entry.putObject("function");
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.set("parameters", Json.parse(tool.parametersJson()));
      }
    }
    root.put("stream", true);
    root.putObject("stream_options").put("include_usage", true);
    if (request.temperature() != null) {
      root.put("temperature", request.temperature().doubleValue());
    }
    String effort = request.reasoning();
    if (effort == null) {
      if (request.maxTokens() != null) {
        root.put("max_tokens", request.maxTokens());
      }
    } else {
      // 推理类端点接受 "reasoning_effort"；协议最高只到 high，所以 "max" 要的是 high 再加上一个大得
      // 多的补全预算，那是它仅剩的另一根杠杆。
      root.put("reasoning_effort", "max".equals(effort) ? "high" : effort);
      int budget =
          "max".equals(effort)
              ? Math.max(request.maxTokens() == null ? 0 : request.maxTokens(), 32_768)
              : 0;
      if (budget > 0) {
        root.put("max_completion_tokens", budget);
      } else if (request.maxTokens() != null) {
        root.put("max_tokens", request.maxTokens());
      }
    }
    return root;
  }

  private static ObjectNode textTurn(String role, String text) {
    ObjectNode node = Json.object();
    node.put("role", role);
    node.put("content", text);
    return node;
  }

  private static ObjectNode toWire(Message message) {
    return switch (message) {
      case Message.System system -> textTurn("system", system.text());
      case Message.User user -> textTurn("user", user.text());
      // 摘要以 user 回合传输，带着标明它是什么的标记：chat API 没有「模型必须读、但不是它写的散文」这种
      // 形状，而另一条路——假装这是助手自己先前的回答——正是让模型相信一个凭空造物的原因。
      case Message.Summary summary -> textTurn("user", summary.text());
      case Message.Assistant assistant -> assistantTurn(assistant);
      case Message.ToolResult result -> toolTurn(result);
    };
  }

  private static ObjectNode assistantTurn(Message.Assistant assistant) {
    ObjectNode node = textTurn("assistant", assistant.text());
    if (assistant.hasToolCalls()) {
      ArrayNode calls = node.putArray("tool_calls");
      for (Message.ToolCall call : assistant.toolCalls()) {
        ObjectNode entry = calls.addObject();
        entry.put("id", call.id());
        entry.put("type", "function");
        ObjectNode function = entry.putObject("function");
        function.put("name", call.name());
        // 在这个线路格式里，参数保持为 JSON *字符串*；模型自己解析它们。
        function.put("arguments", call.arguments());
      }
    }
    return node;
  }

  private static ObjectNode toolTurn(Message.ToolResult result) {
    ObjectNode node = Json.object();
    node.put("role", "tool");
    node.put("tool_call_id", result.toolCallId());
    // 工具回合上没有错误通道，所以这个标志只能以散文的形式到达模型。
    node.put("content", result.error() ? result.content() + " (error)" : result.content());
    return node;
  }

  /**
   * 把流读到结束。只有在线路上观察到的增量会被转发；组装出的回合来自同一批片段，所以监听者和调用方永远不
   * 会各说各话。
   */
  private Message.Assistant consume(
      HttpResponse<?> response, Stream<String> lines, Consumer<Event> sink) {
    StringBuilder text = new StringBuilder();
    Map<Integer, ToolCallBuffer> buffers = new TreeMap<>();
    StringBuilder arrived = new StringBuilder();
    int frames = 0;
    try (Stream<String> peeking = lines.peek(line -> Transport.remember(arrived, line));
        Sse sse = Sse.of(peeking)) {
      for (Sse.Event event = sse.next(); event != null; event = sse.next()) {
        frames++;
        if (event.isDone()) {
          break;
        }
        handleChunk(Json.parse(event.data()), text, buffers, sink);
      }
    }
    if (frames == 0) {
      // 没有任何帧的响应体不是一个回合：把它当作空回答报出去，用户只会看到一次无声的停止，看不出这个端点根本
      // 没在说这个协议。
      throw new IllegalStateException(Transport.noEvents(response, arrived.toString()));
    }
    List<Message.ToolCall> calls = new ArrayList<>(buffers.size());
    for (ToolCallBuffer buffer : buffers.values()) {
      calls.add(new Message.ToolCall(buffer.id, buffer.name, buffer.arguments.toString()));
    }
    return new Message.Assistant(text.toString(), calls);
  }

  private static void handleChunk(
      JsonNode chunk,
      StringBuilder text,
      Map<Integer, ToolCallBuffer> buffers,
      Consumer<Event> sink) {
    JsonNode error = chunk.get("error");
    if (error != null && !error.isNull()) {
      throw new IllegalStateException("提供方错误：" + error);
    }
    JsonNode choices = chunk.path("choices");
    JsonNode choice = choices.isArray() && !choices.isEmpty() ? choices.get(0) : null;
    if (choice != null) {
      JsonNode delta = choice.path("delta");
      appendText(delta.get("content"), text, sink);
      for (String field : REASONING_FIELDS) {
        JsonNode reasoning = delta.get(field);
        if (reasoning != null && reasoning.isTextual() && !reasoning.asText().isEmpty()) {
          sink.accept(new Event.ReasoningDelta(reasoning.asText()));
        }
      }
      appendToolCalls(delta.get("tool_calls"), buffers, sink);
    }
    emitUsage(chunk.get("usage"), choice == null ? null : choice.get("usage"), sink);
  }

  /**
   * 空内容会被跳过：OpenAI 给每个流开场的都是一条只带 role 的 chunk，其 content 是 {@code ""}，转发它
   * 只会让 UI 白白重绘一次。
   */
  private static void appendText(JsonNode content, StringBuilder text, Consumer<Event> sink) {
    if (content == null || !content.isTextual() || content.asText().isEmpty()) {
      return;
    }
    text.append(content.asText());
    sink.accept(new Event.TextDelta(content.asText()));
  }

  private static void appendToolCalls(
      JsonNode fragments, Map<Integer, ToolCallBuffer> buffers, Consumer<Event> sink) {
    if (fragments == null || !fragments.isArray()) {
      return;
    }
    for (JsonNode fragment : fragments) {
      ToolCallBuffer buffer =
          buffers.computeIfAbsent(slot(fragment, buffers), index -> new ToolCallBuffer());
      JsonNode id = fragment.get("id");
      if (id != null && id.isTextual()) {
        buffer.id = id.asText();
      }
      JsonNode function = fragment.get("function");
      if (function != null) {
        JsonNode name = function.get("name");
        if (name != null && name.isTextual()) {
          buffer.name = buffer.name + name.asText();
        }
        JsonNode arguments = function.get("arguments");
        if (arguments != null && arguments.isTextual()) {
          buffer.arguments.append(arguments.asText());
        }
      }
      if (!buffer.announced && !buffer.name.isEmpty()) {
        buffer.announced = true;
        sink.accept(new Event.ToolCallStart(buffer.id, buffer.name));
      }
    }
  }

  /**
   * 一个片段属于哪个槽位。文档中的流总会带上 {@code index}，但一些兼容服务器会把它省掉；把那些默认成
   * 零，会把两次并行的调用合并成一次损坏的调用——一个参数被拼在一起的未知工具，以及一次模型从未听说过的调
   * 用。所以给调用命名的片段会开一个新槽位，除非那个 id 已经开着，那就还是同一次调用在说话；只带参数的片段
   * 则续在最新的槽位上。
   */
  private static int slot(JsonNode fragment, Map<Integer, ToolCallBuffer> buffers) {
    JsonNode index = fragment.get("index");
    if (index != null && index.isIntegralNumber()) {
      return index.asInt();
    }
    JsonNode id = fragment.get("id");
    if (id == null || !id.isTextual() || id.asText().isEmpty()) {
      return buffers.isEmpty() ? 0 : buffers.keySet().stream().max(Integer::compare).orElse(0);
    }
    for (Map.Entry<Integer, ToolCallBuffer> open : buffers.entrySet()) {
      if (id.asText().equals(open.getValue().id)) {
        // 一个丢掉 `index`、转发它构建出的整个调用对象的服务器，会在每个片段上重复这个 id。把它读成第二次
        // 调用，会把一次调用拆成两个：一个拿着被截断的参数，一个连名字都没有。
        return open.getKey();
      }
    }
    int next = 0;
    while (buffers.containsKey(next)) {
      next++;
    }
    return next;
  }

  /** 优先用顶层的 usage 对象，其次用某些网关发来的 choice 级副本。 */
  private static void emitUsage(JsonNode usage, JsonNode choiceUsage, Consumer<Event> sink) {
    JsonNode source = usage != null && usage.isObject() ? usage : choiceUsage;
    if (source == null || !source.isObject()) {
      return;
    }
    JsonNode input = number(source.get("prompt_tokens"));
    JsonNode output = number(source.get("completion_tokens"));
    if (input == null && output == null) {
      return;
    }
    sink.accept(
        new Event.Usage(
            input == null ? 0 : input.asInt(),
            output == null ? 0 : output.asInt(),
            cachedTokens(source)));
  }

  /**
   * 一个 JSON 数字；字段缺失<em>或</em>显式为 null 时返回 null：null 的意思是「没有上报」，把它当作零
   * 会让总计里多出一个凭空捏造的测量值。
   */
  private static JsonNode number(JsonNode node) {
    return node == null || !node.isNumber() ? null : node;
  }

  /**
   * 端点上报告时的缓存统计：OpenAI 把它放在 {@code prompt_tokens_details.cached_tokens} 下，DeepSeek
   * 之流则暴露 {@code prompt_cache_hit_tokens}。字段缺失意味着「没有上报」，这和零不是一回事——UI 分得
   * 清这两者。
   */
  private static Integer cachedTokens(JsonNode usage) {
    JsonNode details = usage.path("prompt_tokens_details").path("cached_tokens");
    if (details.isNumber()) {
      return details.asInt();
    }
    JsonNode hit = usage.path("prompt_cache_hit_tokens");
    if (hit.isNumber()) {
      return hit.asInt();
    }
    return null;
  }

  private static String stripTrailingSlash(String url) {
    String stripped = url.strip();
    return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
  }

  /** 一次流式工具调用的可变状态；片段按索引访问它，而不是按顺序。 */
  private static final class ToolCallBuffer {
    private String id = "";
    private String name = "";
    private final StringBuilder arguments = new StringBuilder();
    private boolean announced;
  }
}
