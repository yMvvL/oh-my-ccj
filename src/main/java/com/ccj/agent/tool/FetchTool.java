package com.ccj.agent.tool;

import com.ccj.agent.core.ApprovalRequest;
import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * 通过 http 或 https 取回一个 URL，并把响应体作为文本返回。
 *
 * <p>第一个会离开本机的工具，所以它的触及范围正是规则存在的理由。在拨出任何连接之前先检查 scheme：
 * {@code file:}、{@code ftp:}、{@code javascript:} 以及光秃秃的路径都会被点名拒绝。正是这个工具反驳了
 * 「代理无法向外伸手」，这也恰恰是它绝不能变成文件读取器的原因；而且每一跳重定向都要重新检查一遍，因为
 * 重定向落到哪里是服务器而不是模型的选择。
 *
 * <p>响应体在读取时就被限住，而不是先读进来再截断：读满 {@code max_bytes} 就停，结果里说明少了多少。
 * 与 `bash` 输出和视觉回复同一条规则，理由也相同——在字节已经进了内存之后才施加的限制，什么也没省下。
 *
 * <p>响应体按原样返回，HTML 标签也不动，因为剥掉标记是一种猜测而不是事实，而猜测正是模型之后会引用回去
 * 的东西。没有声明为文本的响应会被拒绝，并点名它的内容类型，而不是当作文本交出去。响应体按 UTF-8 解码。
 */
public final class FetchTool implements Tool {

  /** 模型没有指定大小的时候返回多少字节：一篇散文，而不是整个站点。 */
  private static final int DEFAULT_MAX_BYTES = 200_000;

  /**
   * {@code max_bytes} 的上限，无论模型要多少。
   *
   * <p>一次 fetch 只是某个回合的一步，而整个回合的输出用户在此后每个回合都要付费，所以这个工具决定
   * 单个 URL 可以花掉多少上下文：一个兆字节送到不合适的分词端点已经是几十万 token 了，想要更多的模型
   * 可以再要下一页。
   */
  private static final int HARD_MAX_BYTES = 1024 * 1024;

  /**
   * 连接可以被接受的时间上限。
   *
   * <p>十秒：到那时还没接受连接的主机不会回答这次调用，而在此之后的每一秒，都是模型拿不到结果的一秒。
   */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /**
   * 整个交换可以花的时间上限。
   *
   * <p>三十秒足以覆盖一个慢页面和一次慢 TLS 握手，而不至于把回合停在那里；模型自己那次请求的截止时间
   * 是分钟级的，所以一次卡住的 fetch 会在任何东西察觉之前就被全额付费。
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /**
   * 在把这条链条报成死循环之前，最多跟随多少跳重定向。
   *
   * <p>五跳：文档 URL 会重定向一两次，而一条链条并不受 {@code max_bytes} 约束——每一跳都是一次全新的
   * 请求和一次全新的超时——所以它需要自己的界限。
   */
  private static final int MAX_REDIRECTS = 5;

  /**
   * 内容仍然是文本的非 {@code text/*} 媒体类型。
   *
   * <p>这份清单之短正是重点：不在这里的类型会被拒绝并点名，模型可以据此行动，而不是当成文本返回、
   * 到手却是一堆乱码。以 {@code +json} 或 {@code +xml} 结尾的类型出于同样的理由被接受，不必逐个
   * 列出——{@code application/ld+json} 与 {@code application/vnd.api+json} 按构造就是文本。
   */
  private static final Set<String> TEXT_TYPES =
      Set.of(
          "application/json",
          "application/xml",
          "application/javascript",
          "application/ecmascript",
          "application/x-javascript",
          "application/x-ndjson",
          "application/yaml",
          "application/x-yaml",
          "application/toml",
          "application/sql");

  /**
   * 整个进程共用一个客户端。
   *
   * <p>{@code HttpClient} 自带一个 selector 线程和一个连接池，所以每次调用都新建一个，会让每次 fetch
   * 都启动一个线程，彼此之间什么也复用不到。重定向不交给它：{@code NORMAL} 会在本工具看清之前就跟着
   * {@code Location} 跑到服务器指向的任何地方，而 scheme 规则必须对这一跳成立，也必须对模型敲进来的
   * URL 成立。所以下面自己跟随跳转，一次一个经过 scheme 检查的请求。
   */
  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .connectTimeout(CONNECT_TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @Override
  public String name() {
    return "fetch";
  }

  /**
   * 刻意返回 false。一次 fetch 在本机上什么也没改，但它是唯一一个效果落在别人身上的工具，而循环读这个
   * 标志来决定什么可以并行、什么必须先过审批。在这个意义上，一次网络请求就是副作用。
   */
  @Override
  public boolean readOnly() {
    return false;
  }

  @Override
  public String description() {
    return "Fetch an http or https URL and return the status line and the body as text. Use it for "
        + "documentation, changelogs and release notes; use read, glob and grep for anything on "
        + "this machine, which fetch cannot open.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "url": {
              "type": "string",
              "description": "Absolute http or https URL to fetch."
            },
            "max_bytes": {
              "type": "integer",
              "description": "Maximum number of body bytes to return. Defaults to 200000, maximum 1048576."
            }
          },
          "required": ["url"],
          "additionalProperties": false
        }""";
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) throws Exception {
    JsonNode args = ToolSupport.args(argumentsJson);
    String given = ToolSupport.requireNonBlank(args, "url").strip();
    int limit = ToolSupport.optionalInt(args, "max_bytes", DEFAULT_MAX_BYTES, 1, HARD_MAX_BYTES);

    URI target;
    try {
      target = new URI(given);
    } catch (URISyntaxException e) {
      return ToolResult.error("不是 URL: " + given + "（" + e.getReason() + "）");
    }
    String complaint = schemeComplaint(target, given);
    if (complaint != null) {
      return ToolResult.error("已拒绝：" + complaint);
    }
    if (target.getHost() == null || target.getHost().isBlank()) {
      return ToolResult.error("该 URL 没有指定主机: " + given);
    }

    // 在第一个字节离开本机之前先审批，而规则匹配的是*URL*：这是唯一一个请求会到达第三方的工具，
    // 「哪个主机」就是全部问题。上面的 scheme 与主机检查先跑，这样提示里展示的是一个值得批准的 URL，
    // 而不是一个反正都会被拒绝的东西。
    String refusal =
        ctx.refusal(
            new ApprovalRequest("fetch", target.toString(), null, "fetch", "GET " + target));
    if (refusal != null) {
      return ToolResult.error(refusal);
    }

    for (int hop = 0; ; hop++) {
      HttpResponse<InputStream> response;
      try {
        response = HTTP.send(get(target), HttpResponse.BodyHandlers.ofInputStream());
      } catch (IOException e) {
        return ToolResult.error("无法访问 " + target + ": " + e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return ToolResult.error("抓取 " + target + " 时被中断");
      }

      String location = response.headers().firstValue("location").orElse("").strip();
      if (!isRedirect(response.statusCode()) || location.isEmpty()) {
        return read(target, response, limit);
      }
      close(response);
      if (hop >= MAX_REDIRECTS) {
        return ToolResult.error(
            "重定向太多（超过 " + MAX_REDIRECTS + " 次），抓取 " + given + " 时");
      }
      URI next = resolve(target, location);
      if (next == null) {
        return ToolResult.error(
            "已拒绝：来自 " + target + " 的重定向指向的目标不是 URL: " + location);
      }
      String redirectComplaint = schemeComplaint(next, location);
      if (redirectComplaint != null) {
        return ToolResult.error(
            "已拒绝：" + redirectComplaint + "（重定向自 " + target + "）");
      }
      target = next;
    }
  }

  /**
   * 2xx 响应的文本，或者没有文本的原因。
   *
   * <p>状态码与内容类型都在碰响应体之前就定了：404 的响应体或八位字节流根本不会被读取，因此这次拒绝
   * 不花任何代价，也不会有任何本工具不会返回的字节流经这个进程。
   */
  private static ToolResult read(URI url, HttpResponse<InputStream> response, int limit) {
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      close(response);
      return ToolResult.error("抓取 " + url + " 返回 HTTP " + status);
    }
    String contentType = response.headers().firstValue("content-type").orElse("").strip();
    if (!isText(mediaType(contentType))) {
      close(response);
      return ToolResult.error(
          "已拒绝："
              + url
              + " 返回的 "
              + (contentType.isEmpty() ? "没有内容类型" : contentType)
              + " 不是文本；fetch 只返回文本");
    }

    byte[] kept = new byte[limit];
    int length = 0;
    boolean truncated = false;
    try (InputStream body = response.body()) {
      while (length < limit) {
        int read = body.read(kept, length, limit - length);
        if (read < 0) {
          break;
        }
        length += read;
      }
      // 越过限制读的那一个字节，是用来把「正好等于限制」和「在这里截断」区分开的，它也是响应体其余
      // 部分中唯一会被读取的东西。
      if (length == limit) {
        truncated = body.read() != -1;
      }
    } catch (IOException e) {
      return ToolResult.error(url + " 返回了 HTTP " + status + "，但响应体被截断了: " + e);
    }

    StringBuilder content = new StringBuilder(length + 64);
    content
        .append("HTTP ")
        .append(status)
        .append(' ')
        .append(contentType)
        .append(" (")
        .append(length)
        .append(" 字节");
    if (truncated) {
      long declared = declaredSize(response);
      content.append("，已截断；");
      if (declared > length) {
        content.append(declared).append(" 字节中省略了 ").append(declared - length);
      } else {
        // 数清剩下的部分，意味着去下载这个限制存在的目的正是要避免下载的响应体，所以它被报成未知，
        // 而不是猜一个数。
        content.append("其余部分未读取，也没有声明它的大小");
      }
    }
    return ToolResult.ok(
        content.append(")\n").append(new String(kept, 0, length, StandardCharsets.UTF_8)).toString());
  }

  private static HttpRequest get(URI target) {
    return HttpRequest.newBuilder(target).timeout(REQUEST_TIMEOUT).GET().build();
  }

  /**
   * 某个 URL 为何不能被 fetch，可以时为 null。拒绝消息会点名它看到的 scheme，「光秃秃路径」那种情况
   * 则点名 scheme 缺失，因为一句「invalid URL」会让模型去猜它面对的到底是哪一种。
   */
  private static String schemeComplaint(URI uri, String given) {
    String scheme = uri.getScheme();
    if (scheme == null) {
      return "\""
          + given
          + "\" 没有指定 scheme：fetch 只读 http 与 https URL，而路径不是 URL——本机上的文件请用"
          + " read";
    }
    if (!isHttp(scheme)) {
      return "\""
          + given
          + "\" 用的是 "
          + scheme
          + " scheme：fetch 只读 http 与 https URL。它存在的目的是到达网络，所以不会读文件，"
          + "也不会运行脚本";
    }
    return null;
  }

  private static boolean isHttp(String scheme) {
    return scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https");
  }

  private static boolean isRedirect(int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
  }

  /** {@code Location} 指向哪里，相对于发出它的那个 URL 解析；不是 URI 时为 null。 */
  private static URI resolve(URI from, String location) {
    try {
      return from.resolve(location);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** 只留媒体类型并转成小写：{@code text/html; charset=utf-8} 变成 {@code text/html}。 */
  private static String mediaType(String contentType) {
    int parameters = contentType.indexOf(';');
    String type = parameters < 0 ? contentType : contentType.substring(0, parameters);
    return type.strip().toLowerCase(Locale.ROOT);
  }

  private static boolean isText(String mediaType) {
    return mediaType.startsWith("text/")
        || mediaType.endsWith("+json")
        || mediaType.endsWith("+xml")
        || TEXT_TYPES.contains(mediaType);
  }

  /**
   * 响应声明的响应体大小，或者 -1。不是数字的头会被报成「未声明」而不是抛出去：这个大小只是截断说明上
   * 的一番好意，一个坏掉的值不能把一页完整到达的内容变成错误。
   */
  private static long declaredSize(HttpResponse<?> response) {
    try {
      return response.headers().firstValueAsLong("content-length").orElse(-1);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** 释放一个不会被读取的响应体：被丢下的流会让它的连接一直开着。 */
  private static void close(HttpResponse<InputStream> response) {
    try {
      response.body().close();
    } catch (IOException ignored) {
      // 已经关掉了，或者连接断了；两种情况本来也不会从它读任何东西。
    }
  }
}
