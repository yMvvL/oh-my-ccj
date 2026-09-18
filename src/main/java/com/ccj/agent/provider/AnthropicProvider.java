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
 * Anthropic {@code /v1/messages} API 的提供方。
 *
 * <p>Messages API 比 chat-completions 那一族更严格，有两点塑造了这个类。工具结果是 <em>user</em> 回合
 * 里的内容块，而一个助手回合的每个 {@code tool_result} 都必须待在同一个 user 回合里，所以连续的结果会被
 * 合并，而不是各发一条消息。另外，回复内容是按索引流式到达的有序块列表，所以组装时维护的是一张按索引键控的
 * 块表，而不是按到达顺序追加。
 */
public final class AnthropicProvider implements Provider {

  public static final String NAME = "anthropic";

  /** Messages API 没有按模型区分的 {@code max_tokens} 默认值；这个字段是必填的。 */
  public static final int DEFAULT_MAX_TOKENS = 4096;

  private static final String API_VERSION = "2023-06-01";

  private final String baseUrl;
  private final String apiKey;
  private final ExecutorService executor;
  private final HttpClient http;

  public AnthropicProvider(String baseUrl, String apiKey) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("anthropic 需要 base URL");
    }
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.apiKey = apiKey == null ? "" : apiKey;
    this.executor = Transport.newExecutor("ccj-anthropic-http");
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
    return HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
        .timeout(Transport.REQUEST_TIMEOUT)
        .header("content-type", "application/json")
        .header("accept", "text/event-stream")
        .header("x-api-key", apiKey)
        .header("anthropic-version", API_VERSION)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
  }

  private ObjectNode buildBody(Request request) {
    String model = request.model();
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("anthropic 请求需要 model");
    }
    ObjectNode root = Json.object();
    root.put("model", model);
    root.put("max_tokens", request.maxTokens() == null ? DEFAULT_MAX_TOKENS : request.maxTokens());
    if (request.system() != null && !request.system().isBlank()) {
      // 用块数组而不是裸字符串：前缀缓存需要有地方放断点，而系统提示词是这个代理发出的每个请求里最稳定的
      // 部分。
      ObjectNode block = root.putArray("system").addObject();
      block.put("type", "text");
      block.put("text", request.system());
      block.set("cache_control", ephemeral());
    }
    String effort = request.reasoning();
    ArrayNode messages = root.putArray("messages");
    appendMessages(request.messages(), messages, effort != null);
    markPrefixEnd(messages);
    if (!request.tools().isEmpty()) {
      ArrayNode tools = root.putArray("tools");
      for (ToolSpec tool : request.tools()) {
        ObjectNode entry = tools.addObject();
        entry.put("name", tool.name());
        entry.put("description", tool.description());
        entry.set("input_schema", Json.parse(tool.parametersJson()));
      }
      // 工具集和系统提示词一样稳定、也一样大：在最后一个工具之后放断点，才让这两者命中缓存，而不是每个回合
      // 都重写一遍。
      ((ObjectNode) tools.get(tools.size() - 1)).set("cache_control", ephemeral());
    }
    root.put("stream", true);
    if (effort != null) {
      // 扩展思考：一个以 token 计的预算，max_tokens 必须大于它，而且开着思考时不能自定义 temperature
      // ——API 会直接拒绝这个组合，而不是忽略它。
      int budget = "low".equals(effort) ? 2_048 : "high".equals(effort) ? 8_192 : 32_768;
      root.putObject("thinking").put("type", "enabled").put("budget_tokens", budget);
      int cap = root.path("max_tokens").asInt(4_096);
      if (cap <= budget) {
        root.put("max_tokens", budget + 1_024);
      }
    } else if (request.temperature() != null) {
      root.put("temperature", request.temperature().doubleValue());
    }
    return root;
  }

  /**
   * 每个标记都会附上的那个缓存断点。
   *
   * <p>五分钟，每次命中都会刷新，这是 API 提供的唯一一种——也是对代理有用的那种：同一段对话两个回合之间
   * 的等待只有几秒。
   */
  private static ObjectNode ephemeral() {
    return Json.object().put("type", "ephemeral");
  }

  /**
   * 把这段对话的缓存断点放到对话末尾。
   *
   * <p>就是这一个让代理循环回本。回合 *n+1* 会发出回合 *n* 发过的一切，再加上模型的回答和工具结果——所以
   * 整段前缀正是缓存存在的意义，而放在上一回合末尾的断点，把这几乎全部变成一次按输入价格十分之一计费的缓存
   * 读取。API 总共允许四个断点；这里用了三个，分别给系统提示词、工具集，以及此处。
   *
   * <p>必须由内容块来携带，而不是消息：线路格式只在内容块上接受 `cache_control`，所以一个纯文本回合会变成
   * 单块数组，而一个本来就有块的回合（工具结果）则在最后一块上带上它。
   */
  private static void markPrefixEnd(ArrayNode messages) {
    if (messages.isEmpty()) {
      return;
    }
    ObjectNode last = (ObjectNode) messages.get(messages.size() - 1);
    JsonNode content = last.get("content");
    if (content == null || content.isNull()) {
      return;
    }
    if (content.isArray()) {
      ArrayNode blocks = (ArrayNode) content;
      if (!blocks.isEmpty()) {
        ((ObjectNode) blocks.get(blocks.size() - 1)).set("cache_control", ephemeral());
      }
      return;
    }
    if (content.isTextual()) {
      ObjectNode block = Json.object();
      block.put("type", "text");
      block.put("text", content.asText());
      block.set("cache_control", ephemeral());
      ArrayNode blocks = Json.object().putArray("content");
      blocks.add(block);
      last.set("content", blocks);
    }
  }

  /** 写出这段对话，把成串的工具结果合并成 API 期望的那一个 user 回合。 */
  private static void appendMessages(List<Message> source, ArrayNode messages, boolean thinking) {
    int index = 0;
    while (index < source.size()) {
      if (source.get(index) instanceof Message.ToolResult) {
        ObjectNode turn = messages.addObject();
        turn.put("role", "user");
        ArrayNode blocks = turn.putArray("content");
        while (index < source.size() && source.get(index) instanceof Message.ToolResult result) {
          blocks.add(toolResultBlock(result));
          index++;
        }
        continue;
      }
      messages.add(toWire(source.get(index), thinking));
      index++;
    }
  }

  private static ObjectNode toWire(Message message, boolean thinking) {
    return switch (message) {
      case Message.System system -> textTurn(system.text());
      case Message.User user -> textTurn(user.text());
      // 摘要以 user 回合上线：Messages API 没有别的形状能承载「模型需要读、但不是它说的」散文，而文本里的
      // 标记正是让它不会读成自己先前的话的原因。
      case Message.Summary summary -> textTurn(summary.text());
      case Message.Assistant assistant -> assistantTurn(assistant, thinking);
      case Message.ToolResult result -> {
        ObjectNode turn = Json.object();
        turn.put("role", "user");
        turn.putArray("content").add(toolResultBlock(result));
        yield turn;
      }
    };
  }

  private static ObjectNode textTurn(String text) {
    ObjectNode node = Json.object();
    node.put("role", "user");
    node.put("content", text);
    return node;
  }

  private static ObjectNode assistantTurn(Message.Assistant assistant, boolean thinking) {
    ObjectNode node = Json.object();
    node.put("role", "assistant");
    ArrayNode blocks = node.putArray("content");
    if (thinking) {
      // 只要请求启用了扩展思考，API 就要求把模型自己的思考还回来，且放在回合的最前面——签名让这个块无法伪
      // 造，而丢掉它的回合会被拒绝。思考关闭时什么都不发：这些块属于一个请求已不再要求的设置。
      for (Message.Thinking block : assistant.thinking()) {
        if (!block.redacted() && block.signature().isEmpty()) {
          // 没有签名的思考无法重放：流在签名到达之前就被切断了。
          continue;
        }
        ObjectNode entry = blocks.addObject();
        if (block.redacted()) {
          entry.put("type", "redacted_thinking");
          entry.put("data", block.data());
        } else {
          entry.put("type", "thinking");
          entry.put("thinking", block.text());
          entry.put("signature", block.signature());
        }
      }
    }
    if (!assistant.text().isEmpty()) {
      ObjectNode text = blocks.addObject();
      text.put("type", "text");
      text.put("text", assistant.text());
    }
    for (Message.ToolCall call : assistant.toolCalls()) {
      ObjectNode use = blocks.addObject();
      use.put("type", "tool_use");
      use.put("id", call.id());
      use.put("name", call.name());
      use.set("input", inputOf(call.arguments()));
    }
    if (blocks.isEmpty()) {
      // 空的 content 数组会被拒绝，所以一个沉默的助手回合仍然需要一个块。
      ObjectNode text = blocks.addObject();
      text.put("type", "text");
      text.put("text", "");
    }
    return node;
  }

  /**
   * 按 {@code tool_use.input} 必须是对象的要求给出的工具参数。
   *
   * <p>Messages API 拒绝非对象的 input，而一次在调用中途被切断的流——模型撞上 token 上限、连接断了——会
   * 留下只写了一半的参数。严格解析那段文本会在构建*下一个*请求时抛出，而这是唯一一种没有出路的失败：之后每
   * 个回合都会重建同一段历史，于是这个会话再也发不出去。改为一个空 input，它仍会到达工具，工具再把缺少参数
   * 这件事回报给模型，让对话继续走下去。
   */
  private static JsonNode inputOf(String arguments) {
    JsonNode parsed;
    try {
      parsed = Json.parse(arguments);
    } catch (IllegalArgumentException e) {
      return Json.object();
    }
    return parsed.isObject() ? parsed : Json.object();
  }

  private static ObjectNode toolResultBlock(Message.ToolResult result) {
    ObjectNode block = Json.object();
    block.put("type", "tool_result");
    block.put("tool_use_id", result.toolCallId());
    block.put("content", result.content());
    block.put("is_error", result.error());
    return block;
  }

  /**
   * 把事件流读到结束。用量只上报一次，在 {@code message_delta} 把输出 token 数补成一个总数时，因为单独的
   * 输入计数不是一个调用方能据以行动的完整测量。
   */
  private Message.Assistant consume(
      HttpResponse<?> response, Stream<String> lines, Consumer<Event> sink) throws Exception {
    Map<Integer, Block> blocks = new TreeMap<>();
    int inputTokens = 0;
    int outputTokens = 0;
    Integer cachedInputTokens = null;
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
          case "message_start" -> {
            JsonNode usage = payload.path("message").path("usage");
            // 存在但为 null 的字段不是一次测量：只有数字才算上报，而网关会在 API 本身省略字段的地方发来显式
            // 的 null。
            JsonNode read = number(usage.get("cache_read_input_tokens"));
            JsonNode created = number(usage.get("cache_creation_input_tokens"));
            if (read == null && created == null) {
              JsonNode input = number(usage.get("input_tokens"));
              inputTokens = input == null ? inputTokens : input.asInt();
            } else {
              // 缓存读取与写入是*在 input_tokens 之外*另行上报的，所以提示词大小是它们的和——否则一个命中
              // 缓存的回合会显得比没命中的还小。
              int readTokens = read == null ? 0 : read.asInt();
              int createdTokens = created == null ? 0 : created.asInt();
              JsonNode input = number(usage.get("input_tokens"));
              inputTokens = (input == null ? 0 : input.asInt()) + readTokens + createdTokens;
              cachedInputTokens = readTokens;
            }
          }
          case "content_block_start" -> openBlock(payload, blocks, sink);
          case "content_block_delta" -> appendDelta(payload, blocks, sink);
          case "content_block_stop" -> {
            // 这个块的每一段片段都到齐了；没什么可做的。
          }
          case "message_delta" -> {
            outputTokens = payload.path("usage").path("output_tokens").asInt(outputTokens);
            sink.accept(new Event.Usage(inputTokens, outputTokens, cachedInputTokens));
            usageEmitted = true;
          }
          case "message_stop" -> {
            if (!usageEmitted && (inputTokens != 0 || outputTokens != 0)) {
              sink.accept(new Event.Usage(inputTokens, outputTokens, cachedInputTokens));
            }
          }
          case "error" -> throw new Exception("anthropic 错误：" + payload.path("error"));
          default -> {
            // ping 以及将来的事件类型都不带内容。
          }
        }
      }
    }
    StringBuilder text = new StringBuilder();
    List<Message.ToolCall> calls = new ArrayList<>();
    List<Message.Thinking> thinking = new ArrayList<>();
    if (frames == 0) {
      // 没有任何帧的响应体不是一个回合：把它当作空回答报出去，用户只会看到一次无声的停止，看不出这个端点根本
      // 没在说这个协议。
      throw new IllegalStateException(Transport.noEvents(response, arrived.toString()));
    }
    for (Block block : blocks.values()) {
      if (block.toolUse()) {
        calls.add(new Message.ToolCall(block.id, block.name, block.arguments.toString()));
      } else if (block.thinking() || block.redactedThinking()) {
        // 留给下一个请求：开着扩展思考时，API 期望把这些还回去。
        thinking.add(
            block.redactedThinking()
                ? Message.Thinking.redacted(block.payload)
                : Message.Thinking.of(block.text.toString(), block.signature.toString()));
      } else {
        text.append(block.text);
      }
    }
    return new Message.Assistant(text.toString(), calls, thinking);
  }

  private static void openBlock(
      JsonNode payload, Map<Integer, Block> blocks, Consumer<Event> sink) {
    JsonNode content = payload.path("content_block");
    String kind = content.path("type").asText("");
    Block block = new Block(kind);
    switch (kind) {
      case "tool_use" -> {
        block.id = content.path("id").asText("");
        block.name = content.path("name").asText("");
        JsonNode input = content.get("input");
        if (input != null && input.isObject() && !input.isEmpty()) {
          // 文档中的流以一个空 input 开场，再用 input_json_delta 流式送参数；否则一个在这里直接发来完整
          // 对象的网关，会让这次调用完全没有参数，工具也就会在无参数的情况下跑起来。
          block.arguments.append(Json.write(input));
        }
        sink.accept(new Event.ToolCallStart(block.id, block.name));
      }
      case "thinking", "redacted_thinking" -> {
        // 开场块可能已经带着开头的文字（对于被遮蔽的块，则是整个不透明载荷）；其余部分以增量到达。
        block.text.append(content.path("thinking").asText(""));
        block.payload = content.path("data").asText("");
        if (!block.text.isEmpty()) {
          sink.accept(new Event.ReasoningDelta(block.text.toString()));
        }
      }
      default -> {
        // 文档中的形状把文本块以空开场，每个字符都以增量流式送达，但网关完全可以改为把开头的文字放进开场块。
        // 丢掉它们就是在句子中间悄悄丢数据，所以按增量对待。
        String opening = content.path("text").asText("");
        if (!opening.isEmpty()) {
          block.text.append(opening);
          sink.accept(new Event.TextDelta(opening));
        }
      }
    }
    blocks.put(payload.path("index").asInt(0), block);
  }

  private static void appendDelta(
      JsonNode payload, Map<Integer, Block> blocks, Consumer<Event> sink) {
    int index = payload.path("index").asInt(0);
    JsonNode delta = payload.path("delta");
    switch (delta.path("type").asText("")) {
      case "text_delta" -> {
        String text = delta.path("text").asText("");
        if (!text.isEmpty()) {
          blocks.computeIfAbsent(index, i -> new Block("text")).text.append(text);
          sink.accept(new Event.TextDelta(text));
        }
      }
      case "input_json_delta" ->
          blocks
              .computeIfAbsent(index, i -> new Block("tool_use"))
              .arguments
              .append(delta.path("partial_json").asText(""));
      case "thinking_delta" -> {
        // 只要设了推理档位，扩展思考就是开着的，而一个回合可能在那里花很久；把它们转发出去，前端才不会看起来
        // 卡住，而保留它们才让下一个请求能把块交还回去。
        String thinking = delta.path("thinking").asText("");
        blocks.computeIfAbsent(index, i -> new Block("thinking")).text.append(thinking);
        if (!thinking.isEmpty()) {
          sink.accept(new Event.ReasoningDelta(thinking));
        }
      }
      case "signature_delta" ->
          blocks
              .computeIfAbsent(index, i -> new Block("thinking"))
              .signature
              .append(delta.path("signature").asText(""));
      default -> {
        // 被遮蔽的思考在开场块里整块到达；将来的增量类型都不带内容。
      }
    }
  }

  /**
   * 一个 JSON 数字；字段缺失<em>或</em>显式为 null 时返回 null。
   *
   * <p>Jackson 见到 null 时会交回一个 {@code NullNode} 而不是 Java 的 null，所以单纯判 null 会把「端点
   * 没有报缓存数字」读成「缓存了零个 token」，把一个未知变成一次测量。
   */
  private static JsonNode number(JsonNode node) {
    return node == null || !node.isNumber() ? null : node;
  }

  private static String stripTrailingSlash(String url) {
    String stripped = url.strip();
    return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
  }

  /**
   * 回复里的一个内容块：散文、一次工具调用，或一个思考块，以片段流式到达。它是哪一种决定了片段往哪里追加、
   * 完成的回合又携带什么。
   */
  private static final class Block {
    private final String kind;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();
    private final StringBuilder signature = new StringBuilder();
    private String id = "";
    private String name = "";
    /** {@code redacted_thinking} 块的不透明载荷，它无法当作文本来读。 */
    private String payload = "";

    private Block(String kind) {
      this.kind = kind;
    }

    private boolean toolUse() {
      return "tool_use".equals(kind);
    }

    private boolean thinking() {
      return "thinking".equals(kind);
    }

    private boolean redactedThinking() {
      return "redacted_thinking".equals(kind);
    }
  }
}
