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
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Google Gemini {@code models/*:streamGenerateContent} API 的提供方。
 *
 * <p>三个线路细节塑造了这个类。第一，模型名在 URL 里
 * （{@code /v1beta/models/<模型>:streamGenerateContent?alt=sse}），而<i>不在</i>请求体里：proto 的
 * {@code google.api.http} 把 {@code {model=models/*}} 绑成路径参数，而路径参数不属于 body。第二，对话
 * 只有两个角色（{@code user} 与 {@code model}）：系统提示词走独立的 {@code systemInstruction}，工具结果
 * 是 {@code user} 回合里的一个 {@code functionResponse} part，所以相邻的同类回合会并成一个
 * {@code Content}——否则一段「工具结果后面紧跟用户追问」的历史会变成两个挨着的 {@code user} 回合。第三，
 * {@code FunctionCall.args} 在这个线路格式里是 JSON <em>对象</em>，而 {@link Message.ToolCall} 存的是字符
 * 串，所以两个方向都要在边界上转换一次。
 *
 * <p><b>核实情况</b>（每条都标在用到它的地方）：端点路径、请求与响应的字段名、
 * {@code functionCall.args} 的对象形状，以及 {@code usageMetadata} 的字段名，来自
 * {@code googleapis/googleapis} 仓库的 v1beta proto。而 {@code ?alt=sse} 与「密钥放
 * {@code x-goog-api-key} 头部」这两条，在本仓库的开发机上访问不到 {@code ai.google.dev}，<b>未核实</b>
 * ，取自 Google 公开 REST 文档的记忆，也<b>没有</b>对着真实端点跑过。
 *
 * <p>刻意不做的一件事：Gemini 的 {@code thoughtSignature} 不回送。把模型产出的思考原样交还，是 Gemini 3
 * 系列上带工具的思考回合所需要的，但那需要一种 {@link Message} 里还没有的形状（签名挂在
 * {@code functionCall} part 上，而不是一个独立的思考 part 上）。
 */
public final class GeminiProvider implements Provider {

  public static final String NAME = "gemini";

  /** 原生端点的版本段；路径形式见 proto 的 {@code google.api.http} 声明。 */
  private static final String API_VERSION = "v1beta";

  /**
   * 推理档位对应的思考预算，单位 token。未核实：proto 只说 {@code thinkingConfig} 存在，各档该给多少是这里
   * 定的，与 Anthropic 那边保持同一套数字，免得同一句话在两个提供方上买到不同的思考量。
   */
  private static final int LOW_THINKING_BUDGET = 2_048;

  private static final int HIGH_THINKING_BUDGET = 8_192;
  private static final int MAX_THINKING_BUDGET = 32_768;

  /** 思考预算被算进 {@code maxOutputTokens}，所以上限至少要比预算高出这么多，回答才留得下位置。 */
  private static final int THINKING_HEADROOM = 1_024;

  private final String baseUrl;
  private final String apiKey;
  private final ExecutorService executor;
  private final HttpClient http;

  public GeminiProvider(String baseUrl, String apiKey) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("gemini 需要 base URL");
    }
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.apiKey = apiKey == null ? "" : apiKey;
    this.executor = Transport.newExecutor("ccj-gemini-http");
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
    // 密钥放在头部而不放进 URL：查询参数里的凭据会被代理日志和 shell 历史记下来。另一种写法是同一个 URL 上
    // 的 ?key=<密钥>，那是 Google 的老形式——两种都还认，这里选的是头部。未核实：两者都没有对着真实端点试过。
    return HttpRequest.newBuilder(URI.create(baseUrl + endpoint(model(request.model()))))
        .timeout(Transport.REQUEST_TIMEOUT)
        .header("content-type", "application/json")
        .header("accept", "text/event-stream")
        .header("x-goog-api-key", apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
  }

  private static String endpoint(String model) {
    return "/" + API_VERSION + "/models/" + model + ":streamGenerateContent?alt=sse";
  }

  /**
   * 请求体里的模型名。
   *
   * <p>配置里的模型名常常带着 API 自己的前缀（{@code models/gemini-2.5-flash}），而路径里已经有
   * {@code models/} 了；不剥掉就会得到 {@code models/models/…} 这样一个 404。
   */
  private static String model(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("gemini 请求需要 model");
    }
    String stripped = value.strip();
    String prefix = "models/";
    if (stripped.startsWith(prefix)) {
      stripped = stripped.substring(prefix.length());
    }
    if (stripped.isBlank()) {
      throw new IllegalArgumentException("gemini 请求需要 model");
    }
    return stripped;
  }

  private ObjectNode buildBody(Request request) {
    ObjectNode root = Json.object();
    if (request.system() != null && !request.system().isBlank()) {
      // systemInstruction 是一个没有角色的 Content：contents 里的角色只有 user 和 model，系统提示词住在
      // 自己那个槽位里。
      ObjectNode instruction = root.putObject("systemInstruction");
      instruction.putArray("parts").add(textPart(request.system()));
    }
    if (!request.messages().isEmpty()) {
      appendMessages(request.messages(), root.putArray("contents"));
    }
    if (!request.tools().isEmpty()) {
      ArrayNode declarations = root.putArray("tools").addObject().putArray("functionDeclarations");
      for (ToolSpec tool : request.tools()) {
        ObjectNode declaration = declarations.addObject();
        declaration.put("name", tool.name());
        declaration.put("description", tool.description());
        declaration.set("parameters", Json.parse(tool.parametersJson()));
      }
    }
    ObjectNode generation = generationConfig(request);
    if (!generation.isEmpty()) {
      root.set("generationConfig", generation);
    }
    return root;
  }

  private static ObjectNode generationConfig(Request request) {
    ObjectNode config = Json.object();
    String effort = request.reasoning();
    Integer cap = request.maxTokens();
    if (cap != null) {
      config.put("maxOutputTokens", cap);
    }
    if (request.temperature() != null) {
      config.put("temperature", request.temperature().doubleValue());
    }
    if (effort != null) {
      ObjectNode thinking = config.putObject("thinkingConfig");
      int budget = budgetFor(effort);
      thinking.put("thinkingBudget", budget);
      // 未核实：proto 只留下 includeThoughts 这个开关，没有说默认值；不打开它，模型会思考而 UI 一片空白。
      thinking.put("includeThoughts", true);
      // 思考预算算在 maxOutputTokens 之内，一个比预算还小的上限会让模型想完就把名额用光、一句回答都发不出
      // 来。抬高上限而不是压低预算：档位是用户要的东西。未核实：真实端点上是否强制这条约束。
      if (cap != null && cap <= budget) {
        config.put("maxOutputTokens", budget + THINKING_HEADROOM);
      }
    }
    return config;
  }

  private static int budgetFor(String effort) {
    return switch (effort) {
      case "low" -> LOW_THINKING_BUDGET;
      case "high" -> HIGH_THINKING_BUDGET;
      default -> MAX_THINKING_BUDGET;
    };
  }

  /**
   * 写出这段对话，把相邻的同类回合并成一个 {@code Content}。
   *
   * <p>Gemini 的 {@code contents[]} 把「一连串工具结果」当作一个 user 回合的内容，就像成串的
   * {@code tool_result} 之于 Anthropic；而系统提示词、摘要和用户消息都落在 {@code user} 上，所以一段
   * 「摘要 + 用户追问」的历史也必须并起来，否则就是两个挨着的 user 回合。
   */
  private static void appendMessages(List<Message> source, ArrayNode contents) {
    ObjectNode open = null;
    String openRole = null;
    for (Message message : source) {
      String role = wireRole(message);
      if (open == null || !role.equals(openRole)) {
        open = contents.addObject();
        open.put("role", role);
        open.putArray("parts");
        openRole = role;
      }
      appendParts(message, (ArrayNode) open.get("parts"));
    }
  }

  /** Gemini 只有两个角色；一切「模型必须读、但不是它写的」内容都落在 user 上。 */
  private static String wireRole(Message message) {
    return message instanceof Message.Assistant ? "model" : "user";
  }

  private static void appendParts(Message message, ArrayNode parts) {
    switch (message) {
      case Message.System system -> parts.add(textPart(system.text()));
      case Message.User user -> parts.add(textPart(user.text()));
      // 摘要以 user 回合上线：contents 里没有「模型必须读、但不是它说的」这种形状，而文本里的标记正是让它
      // 不会读成自己先前的话的原因。
      case Message.Summary summary -> parts.add(textPart(summary.text()));
      case Message.Assistant assistant -> appendAssistant(assistant, parts);
      case Message.ToolResult result -> parts.add(functionResponse(result));
    }
  }

  private static void appendAssistant(Message.Assistant assistant, ArrayNode parts) {
    if (!assistant.text().isEmpty()) {
      parts.add(textPart(assistant.text()));
    }
    for (Message.ToolCall call : assistant.toolCalls()) {
      ObjectNode wrapper = parts.addObject();
      ObjectNode functionCall = wrapper.putObject("functionCall");
      functionCall.put("name", call.name());
      // 参数在这个线路格式里是对象；模型自己解析它们。未核实：id 是 v1beta proto 里的可选字段，回送它是为了
      // 让两个同名函数的并行调用能各归各位。
      functionCall.set("args", argsOf(call.arguments()));
      if (!call.id().isEmpty()) {
        functionCall.put("id", call.id());
      }
    }
    if (parts.isEmpty()) {
      // 空的 parts 数组会被拒；一个沉默的助手回合仍然需要一个 part。
      parts.add(textPart(""));
    }
  }

  private static ObjectNode textPart(String text) {
    return Json.object().put("text", text);
  }

  private static ObjectNode functionResponse(Message.ToolResult result) {
    ObjectNode wrapper = Json.object();
    ObjectNode response = wrapper.putObject("functionResponse");
    response.put("name", result.toolName());
    if (!result.toolCallId().isEmpty()) {
      response.put("id", result.toolCallId());
    }
    // 工具结果就是 functionResponse 里那个 Struct。未核实：output / error 这两个键名取自 Google 客户端的
    // 约定；模型两边都读得懂，而分开它们，是因为 Gemini 的 functionResponse 没有 error 通道，一个失败的工具
    // 如果伪装成成功，模型会照着它编下去。
    ObjectNode payload = Json.object();
    payload.put(result.error() ? "error" : "output", result.content());
    response.set("response", payload);
    return wrapper;
  }

  /**
   * 按 {@code functionCall.args} 必须是对象的要求给出的工具参数。
   *
   * <p>反过来做会毁掉整条会话：一次在调用中途被切断的流——模型撞上 token 上限、连接断了——留下的是只写了
   * 一半的参数文本，严格解析它会在构建<i>下一个</i>请求时抛出，而之后每个回合都会重建同一段历史。
   */
  private static JsonNode argsOf(String arguments) {
    JsonNode parsed;
    try {
      parsed = Json.parse(arguments);
    } catch (IllegalArgumentException e) {
      return Json.object();
    }
    return parsed.isObject() ? parsed : Json.object();
  }

  /**
   * 把事件流读到结束，一个回合只播报一次用量。
   *
   * <p>端点在若干个 chunk 上重复带着 {@code usageMetadata}，而输出计数要等最后才长齐；所以只留下看到的最新一
   * 组数字，等流结束再播报，而不是每见到一次就报一次——一个还没有输出计数的用量，不是一个调用方能据以行动的
   * 测量。
   */
  private Message.Assistant consume(
      HttpResponse<?> response, Stream<String> lines, Consumer<Event> sink) {
    StringBuilder text = new StringBuilder();
    List<Message.ToolCall> calls = new ArrayList<>();
    Usage usage = new Usage();
    StringBuilder arrived = new StringBuilder();
    int frames = 0;
    try (Stream<String> peeking = lines.peek(line -> Transport.remember(arrived, line));
        Sse sse = Sse.of(peeking)) {
      for (Sse.Event event = sse.next(); event != null; event = sse.next()) {
        frames++;
        if (event.isDone()) {
          break;
        }
        handleChunk(Json.parse(event.data()), text, calls, usage, sink);
      }
    }
    if (frames == 0) {
      // 没有任何帧的响应体不是一个回合：把它当作空回答报出去，用户只会看到一次无声的停止，看不出这个端点根本
      // 没在说这个协议。
      throw new IllegalStateException(Transport.noEvents(response, arrived.toString()));
    }
    if (usage.reported()) {
      sink.accept(new Event.Usage(usage.inputTokens, usage.outputTokens, usage.cachedInputTokens));
    }
    return new Message.Assistant(text.toString(), calls);
  }

  /**
   * 一个 chunk 是一个完整的 {@code GenerateContentResponse}，所以文本可能被切在任意位置；按到达顺序追加，就
   * 是拼回它的方式。
   */
  private static void handleChunk(
      JsonNode chunk,
      StringBuilder text,
      List<Message.ToolCall> calls,
      Usage usage,
      Consumer<Event> sink) {
    JsonNode error = chunk.get("error");
    if (error != null && !error.isNull()) {
      throw new IllegalStateException("gemini 错误：" + error);
    }
    JsonNode candidates = chunk.path("candidates");
    if (candidates.isArray() && !candidates.isEmpty()) {
      JsonNode parts = candidates.get(0).path("content").path("parts");
      if (parts.isArray()) {
        for (JsonNode part : parts) {
          appendPart(part, text, calls, sink);
        }
      }
    }
    JsonNode metadata = chunk.get("usageMetadata");
    if (metadata != null && metadata.isObject()) {
      usage.read(metadata);
    }
  }

  /**
   * 一个 {@code Part} 是 oneof。认得出的是文案与函数调用；{@code inlineData}、{@code executableCode}
   * 以及将来新增的种类都没有文字可显示，忽略它们正是一个前向兼容的客户端该做的。
   */
  private static void appendPart(
      JsonNode part, StringBuilder text, List<Message.ToolCall> calls, Consumer<Event> sink) {
    JsonNode functionCall = part.get("functionCall");
    if (functionCall != null && functionCall.isObject()) {
      appendFunctionCall(functionCall, calls, sink);
      return;
    }
    JsonNode value = part.get("text");
    if (value == null || !value.isTextual() || value.asText().isEmpty()) {
      return;
    }
    if (part.path("thought").asBoolean(false)) {
      // 带 thought 标志的文本 part 就是推理增量。未核实：真实端点上是否总是带上这个标志。
      sink.accept(new Event.ReasoningDelta(value.asText()));
      return;
    }
    text.append(value.asText());
    sink.accept(new Event.TextDelta(value.asText()));
  }

  private static void appendFunctionCall(
      JsonNode call, List<Message.ToolCall> calls, Consumer<Event> sink) {
    String name = call.path("name").asText("");
    if (name.isEmpty()) {
      // 没有名字的调用执行不了，结果也送不回去；丢掉它，而不是让整个回合卡在这里。
      return;
    }
    // FunctionCall.id 是可选的，而 Message.ToolCall 必须有 id——它是工具结果回来时唯一的对号方式。模型没给
    // 的时候按本回合的顺序编一个；同名函数的并行调用因此仍然分得开。
    String id = call.path("id").asText("");
    if (id.isEmpty()) {
      id = "call_" + (calls.size() + 1);
    }
    JsonNode args = call.get("args");
    calls.add(new Message.ToolCall(id, name, args == null || args.isNull() ? "{}" : Json.write(args)));
    sink.accept(new Event.ToolCallStart(id, name));
  }

  /**
   * 一个 JSON 数字；字段缺失<em>或</em>显式为 null 时返回 null：null 的意思是「没有上报」，把它当作零会让
   * 总计里多出一个凭空捏造的测量值。
   */
  private static JsonNode number(JsonNode node) {
    return node == null || !node.isNumber() ? null : node;
  }

  private static String stripTrailingSlash(String url) {
    String stripped = url.strip();
    return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
  }

  /**
   * 跨 chunk 累积的用量。
   *
   * <p>注意别用错同名的那个 {@code UsageMetadata}：{@code CountTokens} 与 {@code BatchEmbedContents} 用的
   * 是另一个消息，字段名不同（{@code responseTokenCount} 而不是 {@code candidatesTokenCount}）。这里是
   * {@code GenerateContentResponse} 的那个。
   */
  private static final class Usage {
    private int inputTokens;
    private int outputTokens;
    private Integer cachedInputTokens;
    private boolean seen;

    private void read(JsonNode metadata) {
      JsonNode input = number(metadata.get("promptTokenCount"));
      JsonNode output = number(metadata.get("candidatesTokenCount"));
      if (input == null && output == null) {
        return;
      }
      seen = true;
      if (input != null) {
        inputTokens = input.asInt();
      }
      if (output != null) {
        outputTokens = output.asInt();
      }
      // 「从来没有缓存」与「缓存了零个 token」是两回事，而网关还可能在后面的 chunk 里把这个字段省掉；只有真
      // 的报了数字才覆盖，null 才留得住。
      JsonNode cached = number(metadata.get("cachedContentTokenCount"));
      if (cached != null) {
        cachedInputTokens = cached.asInt();
      }
    }

    private boolean reported() {
      return seen;
    }
  }
}
