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
 * OpenAI {@code /responses} API（Responses API）的提供方。
 *
 * <p>它和 chat-completions 那一族最大的差别在回合的形状。请求的 {@code input} 是一个条目数组：每条
 * {@link Message} 各自翻译成一个条目，工具调用与工具结果是平铺的 {@code function_call} /
 * {@code function_call_output} 条目，而不是塞在助手回合里的 {@code tool_calls}；系统提示词搬到顶层的
 * {@code instructions}。工具声明同样少一层 {@code function} 包装。于是这里没有需要合并的东西。
 *
 * <p>助手的历史不能让 input 里的消息项承载——那一侧只认 {@code user}/{@code system}/{@code developer}
 * 三个角色——所以它作为<em>输出项</em>回填：{@code type: "message"} 加一个 {@code output_text} 内容块。
 * 这也解释了 {@code call_id}：模型发出的调用与它的结果靠这个字段配对。
 *
 * <p>{@code store} 被显式写成 {@code false}。它的默认值是 {@code true}，也就是让 OpenAI 把整段会话留
 * 30 天；这个代理的叙事是「会话在本地的文件里」，默认值不属于我们的策略，只有写出来才算数。
 *
 * <p>回应侧的事件把类型放在载荷的 {@code type} 里（{@code event} 行只是可选的回退），所以一个认不出来的
 * 类型只说明这个提供方比 API 旧：它不带我们映射得了的内容，丢掉它，这个回合照样能走完。
 *
 * <p>一次工具调用的名字与 {@code call_id} 只在 {@code response.output_item.added} 里到达，而参数片段除了
 * {@code output_index} 什么都不带。组装因此按索引合流，而不是按到达顺序——这也正是参数被切在任意字节处还能
 * 拼回来的原因。
 */
public final class ResponsesProvider implements Provider {

  public static final String NAME = "openai-responses";

  /**
   * 档位开到顶时给补全预算的下限。
   *
   * <p>官方文档写明 {@code max_output_tokens} 的上限「包含可见输出 token 和推理 token」，也就是说推理会
   * 先花掉这个额度，剩下的才是答案。把档位开到 max 的用户要的是尽量深的推理，所以那个额度不能还是配置里
   * 按普通回合填的小数字——这是这条路上仅剩的另一根杠杆。
   */
  private static final int MAX_EFFORT_OUTPUT_FLOOR = 32_768;

  private final String baseUrl;
  private final String apiKey;
  private final ExecutorService executor;
  private final HttpClient http;

  public ResponsesProvider(String baseUrl, String apiKey) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("openai-responses 需要 base URL");
    }
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.apiKey = apiKey == null ? "" : apiKey;
    this.executor = Transport.newExecutor("ccj-responses-http");
    this.http = Transport.newClient(executor);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Message.Assistant complete(Request request, Consumer<Event> listener) throws Exception {
    Consumer<Event> sink = listener == null ? event -> {} : listener;
    HttpResponse<Stream<String>> response = Transport.send(http, buildRequest(request), sink);
    try (Stream<String> lines = response.body()) {
      return consume(response, lines, sink);
    } catch (UncheckedIOException e) {
      throw new Exception("流式读取响应时连接断开：" + e.getCause(), e);
    }
  }

  @Override
  public void close() {
    executor.shutdown();
  }

  private HttpRequest buildRequest(Request request) {
    String body = Json.write(buildBody(request));
    return HttpRequest.newBuilder(URI.create(baseUrl + "/responses"))
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
      throw new IllegalArgumentException("openai-responses 请求需要 model");
    }
    ObjectNode root = Json.object();
    root.put("model", model);
    if (request.system() != null && !request.system().isBlank()) {
      root.put("instructions", request.system());
    }
    ArrayNode input = root.putArray("input");
    for (Message message : request.messages()) {
      appendMessage(message, input);
    }
    if (!request.tools().isEmpty()) {
      ArrayNode tools = root.putArray("tools");
      for (ToolSpec tool : request.tools()) {
        // 这个形状是平铺的：chat/completions 里包着 name/description/parameters 的那层 `function` 在这
        // 里没有。
        ObjectNode entry = tools.addObject();
        entry.put("type", "function");
        entry.put("name", tool.name());
        entry.put("description", tool.description());
        entry.set("parameters", Json.parse(tool.parametersJson()));
      }
    }
    root.put("stream", true);
    // 默认值是 true，也就是让 OpenAI 把整段会话留 30 天。这个代理的叙事是「会话在本地文件里」，所以每一个
    // 请求都要显式关掉它：默认值不是我们的策略，只有写出来才算数。
    root.put("store", false);
    if (request.temperature() != null) {
      root.put("temperature", request.temperature().doubleValue());
    }
    applyEffortAndOutputCap(root, request);
    return root;
  }

  /**
   * 推理档位与输出上限。
   *
   * <p>内部档位是 {@code low}/{@code high}/{@code max}（见 {@code Config.REASONING_LEVELS}），而官方文档
   * 给 {@code reasoning.effort} 列的取值是 {@code none}、{@code minimal}、{@code low}、{@code medium}、
   * {@code high}、{@code xhigh}、{@code max}：三个内部档位逐一原样对上，包括 {@code max}——这比
   * chat/completions 那边干净，那边最高只到 {@code high}，多出来的意图只能靠补全预算表达。
   */
  private static void applyEffortAndOutputCap(ObjectNode root, Request request) {
    String effort = request.reasoning();
    if (effort != null) {
      root.putObject("reasoning").put("effort", effort);
    }
    Integer maxTokens = request.maxTokens();
    if (maxTokens == null) {
      if ("max".equals(effort)) {
        root.put("max_output_tokens", MAX_EFFORT_OUTPUT_FLOOR);
      }
      return;
    }
    root.put(
        "max_output_tokens",
        "max".equals(effort) ? Math.max(maxTokens, MAX_EFFORT_OUTPUT_FLOOR) : maxTokens);
  }

  private static void appendMessage(Message message, ArrayNode input) {
    switch (message) {
      case Message.System system -> input.add(textItem("system", system.text()));
      case Message.User user -> input.add(textItem("user", user.text()));
      // 摘要以 user 条目上线，理由和另外两条线路一致：这里没有「模型必须读、但不是它写的散文」这种形状，而
      // 假装它是助手自己先前的回答，正是让模型把它当成自己的话的原因。
      case Message.Summary summary -> input.add(textItem("user", summary.text()));
      case Message.Assistant assistant -> appendAssistant(assistant, input);
      case Message.ToolResult result -> input.add(toolOutputItem(result));
    }
  }

  private static void appendAssistant(Message.Assistant assistant, ArrayNode input) {
    if (!assistant.text().isEmpty()) {
      // input 里的消息项只认 user/system/developer，所以助手的历史按<em>输出项</em>的形状回填：一个
      // assistant 消息，内容是 output_text 块。少一层包装，模型就会把这段散文读成用户说的话。
      ObjectNode item = input.addObject();
      item.put("type", "message");
      item.put("role", "assistant");
      item.putArray("content")
          .addObject()
          .put("type", "output_text")
          .put("text", assistant.text());
    }
    for (Message.ToolCall call : assistant.toolCalls()) {
      ObjectNode item = input.addObject();
      item.put("type", "function_call");
      // Responses 用 call_id 把一次调用和它的结果配起来，没有单独的 tool_call_id。
      item.put("call_id", call.id());
      item.put("name", call.name());
      item.put("arguments", argumentsOf(call.arguments()));
    }
    // 思考不回放：无状态重发要带上推理条目的 encrypted_content，而 store:false 时它正是那条唯一的路，
    // 这里并没有请求它。
  }

  private static ObjectNode textItem(String role, String text) {
    ObjectNode node = Json.object();
    node.put("role", role);
    node.put("content", text);
    return node;
  }

  private static ObjectNode toolOutputItem(Message.ToolResult result) {
    ObjectNode item = Json.object();
    item.put("type", "function_call_output");
    item.put("call_id", result.toolCallId());
    // 这个条目上没有错误通道，所以失败只能以散文到达模型，和 chat/completions 那侧同一处理。
    item.put("output", result.error() ? result.content() + " (error)" : result.content());
    return item;
  }

  /**
   * 交给下一次请求的工具参数。
   *
   * <p>一次在调用中途被切断的流——模型撞上输出上限、连接断了——会留下只写了一半的参数，而那段文本已经进了
   * 对话历史，之后每个回合都会原样重建它。这里用的字段声明为 JSON 字符串，把一段读不成 JSON 的文本交回去
   * 就赌上了这个会话唯一没有出路的那种失败：每个后续回合都会重建同一段历史，于是它再也发不出去。改成一个空
   * 对象，工具仍然会跑起来，并把缺少参数这件事回报给模型，让对话继续走下去。（与 Anthropic 那侧同一理由。）
   */
  private static String argumentsOf(String arguments) {
    if (arguments == null || arguments.isBlank()) {
      return "{}";
    }
    try {
      return Json.parse(arguments).isObject() ? arguments : "{}";
    } catch (IllegalArgumentException e) {
      return "{}";
    }
  }

  /** 把事件流读到结束，只有在线路上观察到的增量会被转发。 */
  private Message.Assistant consume(
      HttpResponse<?> response, Stream<String> lines, Consumer<Event> sink) throws Exception {
    StringBuilder text = new StringBuilder();
    Map<Integer, ToolCallBuffer> buffers = new TreeMap<>();
    boolean usageEmitted = false;
    StringBuilder arrived = new StringBuilder();
    int frames = 0;
    try (Stream<String> peeking = lines.peek(line -> Transport.remember(arrived, line));
        Sse sse = Sse.of(peeking)) {
      for (Sse.Event event = sse.next(); event != null; event = sse.next()) {
        frames++;
        if (event.isDone()) {
          break;
        }
        JsonNode payload = Json.parse(event.data());
        String type = payload.path("type").asText(event.event());
        switch (type) {
          case "response.output_text.delta" -> appendText(payload.get("delta"), text, sink);
          case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
              appendReasoning(payload.get("delta"), sink);
          case "response.output_item.added" -> openItem(payload, buffers, sink);
          case "response.function_call_arguments.delta" -> appendArguments(payload, buffers);
          case "response.completed", "response.incomplete" -> {
            // 记账只在终局事件里齐备，而且只算一次：把同一份 usage 重复放在多帧里的网关（终局帧被重放、代理
            // 自己合成一个）如果被算两遍，看起来只是「这个回合更大」，比漏报更难被发现。
            if (!usageEmitted) {
              JsonNode usage = payload.path("response").path("usage");
              JsonNode input = number(usage.get("input_tokens"));
              JsonNode output = number(usage.get("output_tokens"));
              if (input != null || output != null) {
                sink.accept(
                    new Event.Usage(
                        input == null ? 0 : input.asInt(),
                        output == null ? 0 : output.asInt(),
                        cachedTokens(usage)));
                usageEmitted = true;
              }
            }
          }
          case "error", "response.error" ->
              throw new Exception("openai-responses 错误：" + describe(payload));
          case "response.failed" -> {
            JsonNode error = payload.path("response").path("error");
            throw new Exception(
                "openai-responses 回合失败："
                    + describe(error.isObject() ? error : payload.path("response")));
          }
          default -> {
            // 内置工具、音频、结构化的文本收尾等等都各带自己的事件，而它们的载荷里没有这里映射得了的东西。
            // 认得的事件比 API 少只说明这个提供方旧，把它当错误抛出去，会让 API 的一次升级连带弄坏一个本来
            // 能用的回合。
          }
        }
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
    // 推理摘要只经由监听者离开：它让长回合不至于是个空转的圈，但没有 encrypted_content 的推理条目重放不了。
    return new Message.Assistant(text.toString(), calls);
  }

  /** 空增量会被跳过：转发一个空串只会让 UI 白白重绘一次。 */
  private static void appendText(JsonNode delta, StringBuilder text, Consumer<Event> sink) {
    if (delta == null || !delta.isTextual() || delta.asText().isEmpty()) {
      return;
    }
    text.append(delta.asText());
    sink.accept(new Event.TextDelta(delta.asText()));
  }

  /**
   * 推理摘要的增量。
   *
   * <p>设了推理档位后，模型可能先想很久；丢掉这些增量，用户就只会盯着一个转圈、无字可读。
   */
  private static void appendReasoning(JsonNode delta, Consumer<Event> sink) {
    if (delta == null || !delta.isTextual() || delta.asText().isEmpty()) {
      return;
    }
    sink.accept(new Event.ReasoningDelta(delta.asText()));
  }

  /**
   * 一个输出条目开场。
   *
   * <p>一次工具调用的名字与 {@code call_id} 只在这里到达：后面的参数片段除了索引什么都不带，所以槽位必须在
   * 这里建好，片段才有地方续。
   */
  private static void openItem(
      JsonNode payload, Map<Integer, ToolCallBuffer> buffers, Consumer<Event> sink) {
    JsonNode item = payload.path("item");
    if (!"function_call".equals(item.path("type").asText(""))) {
      // 推理条目和消息条目的内容都在别的事件里流式送达。
      return;
    }
    ToolCallBuffer buffer =
        buffers.computeIfAbsent(
            payload.path("output_index").asInt(0), index -> new ToolCallBuffer());
    buffer.id = item.path("call_id").asText(buffer.id);
    buffer.name = item.path("name").asText(buffer.name);
    String arguments = item.path("arguments").asText("");
    if (!arguments.isEmpty() && buffer.arguments.isEmpty()) {
      // 文档中的流把参数全交给增量；一个直接给出整个调用的网关，否则会让这次调用带着空参数跑起来。
      buffer.arguments.append(arguments);
    }
    if (!buffer.announced && !buffer.name.isEmpty()) {
      buffer.announced = true;
      sink.accept(new Event.ToolCallStart(buffer.id, buffer.name));
    }
  }

  private static void appendArguments(JsonNode payload, Map<Integer, ToolCallBuffer> buffers) {
    String delta = payload.path("delta").asText("");
    if (delta.isEmpty()) {
      return;
    }
    buffers
        .computeIfAbsent(payload.path("output_index").asInt(0), index -> new ToolCallBuffer())
        .arguments
        .append(delta);
  }

  /**
   * 端点报告的缓存统计：{@code input_tokens_details.cached_tokens}。字段缺失<em>或</em>显式为 null 时返回
   * null——「没有上报」和「没有命中缓存」是两个不同的事实，都渲染成 0% 会让 UI 把一次未知显示成一次测量。
   *
   * <p>不像 Anthropic 那侧要相加：这里是 {@code input_tokens} 的<em>分解</em>，缓存命中已经算在它里面了。
   */
  private static Integer cachedTokens(JsonNode usage) {
    JsonNode cached = usage.path("input_tokens_details").path("cached_tokens");
    return cached.isNumber() ? cached.asInt() : null;
  }

  /**
   * 一个 JSON 数字；字段缺失或显式为 null 时返回 null。
   *
   * <p>Jackson 见到 null 时交回的是 {@code NullNode} 而不是 Java 的 null，所以只有 {@code isNumber()} 才
   * 分得清「上报了零」和「什么都没说」。
   */
  private static JsonNode number(JsonNode node) {
    return node == null || !node.isNumber() ? null : node;
  }

  /**
   * 错误事件里那句可读的话。
   *
   * <p>形状不止一种：{@code error} 把 {@code code}/{@code message} 放在顶层，{@code response.failed} 把
   * 它们藏在 {@code response.error} 里，而有的线路实现还在错误事件里又套一层 {@code error} 对象。先从这
   * 三层里取出装着消息的那个，读不到就把整个对象交出去——一句原始 JSON 也好过一句什么都没有的「出错了」。
   */
  private static String describe(JsonNode payload) {
    JsonNode error = payload.path("error").isObject() ? payload.path("error") : payload;
    String message = error.path("message").asText("");
    if (message.isEmpty()) {
      return error.toString();
    }
    String code = error.path("code").asText("");
    return code.isEmpty() ? message : code + ": " + message;
  }

  private static String stripTrailingSlash(String url) {
    String stripped = url.strip();
    return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
  }

  /** 一次流式工具调用的可变状态；参数片段按索引找到它，而不是按顺序。 */
  private static final class ToolCallBuffer {
    private String id = "";
    private String name = "";
    private final StringBuilder arguments = new StringBuilder();
    private boolean announced;
  }
}
