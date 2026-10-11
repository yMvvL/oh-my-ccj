package com.ccj.agent.provider;

import com.ccj.agent.core.Provider;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 各具体提供方共用的 HTTP 管道：超时、重试策略与错误上报。
 *
 * <p>集中在一处，意味着两个厂家以同样的方式重试、以同样形状的消息失败，而这正是让提供方的失败在控制台
 * 上可诊断的原因。
 */
final class Transport {

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

  /** 一个长回合可能流式输出好几分钟，所以请求的截止时间必须给得宽裕。 */
  static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

  /** 一次瞬时故障算作致命之前的总尝试次数——首次发送加上重试。 */
  static final int MAX_ATTEMPTS = 3;

  private static final long BASE_BACKOFF_MILLIS = 150;
  private static final long MAX_JITTER_MILLIS = 100;
  private static final int ERROR_BODY_LIMIT = 500;

  private Transport() {}

  /**
   * 每个客户端一个闸门（限速 + 熔断），见 {@link Throttle}。
   *
   * <p>按**身份**索引而不是按名字，因为这个关系的形状就是那样：闸门属于那份提供方凭据所对应的客户端，而不
   * 属于某个叫 "openai" 的字符串。{@code HttpClient} 没有重写 {@code equals}，所以一张 {@code WeakHashMap}
   * 就是按身份索引的——而弱引用是刻意的：改一次设置就会换掉一个提供方（连带它的客户端），一张强引用的登记表
   * 会让那些旧客户端永远活着。它也不会漂——这个程序里的每一个 {@link HttpClient} 都从 {@link #newClient}
   * 出来，所以「注册过闸门的客户端」与「存在的客户端」是同一批东西。
   */
  private static final Map<HttpClient, Throttle> THROTTLES =
      Collections.synchronizedMap(new WeakHashMap<>());

  /** 每分钟最多发出多少请求；0 表示不限速。默认值见 {@link #configure}。 */
  private static volatile int requestsPerMinute = 600;

  /** 连续失败多少次之后暂停这个端点；0 表示不熔断。 */
  private static volatile int failuresBeforeOpen = 5;

  /** 暂停多久之后放一次试探。 */
  private static volatile Duration breakerCooldown = Duration.ofSeconds(30);

  /**
   * 设定这一次运行的闸门。由 {@code Providers.create} 在读环境变量之后调一次。
   *
   * <p>默认给的是 600 rpm（10 次/秒）与「连续 5 次失败暂停 30 秒」，两条都不是拍脑袋：
   * 一个人坐在终端前敲字，一分钟发不出 600 个请求；而一个端点连续失败五次，几乎总是它挂了或钥匙错了，不是
   * 网络抖了一下。三样都可以用环境变量改（见 docs/USAGE.md 的配置一节），0 表示关掉那一项。
   */
  static void configure(int perMinute, int breakAfter, Duration cooldown) {
    requestsPerMinute = Math.max(0, perMinute);
    failuresBeforeOpen = Math.max(0, breakAfter);
    breakerCooldown = cooldown == null || cooldown.isNegative() ? Duration.ZERO : cooldown;
  }

  /** 现在生效的闸门配置，给状态行、测试与「我到底配了什么」这类问题用。 */
  static String describeThrottle() {
    if (requestsPerMinute <= 0 && failuresBeforeOpen <= 0) {
      return "不限速、不熔断";
    }
    String rate =
        requestsPerMinute <= 0 ? "不限速" : "每分钟最多 " + requestsPerMinute + " 次请求";
    String breaker =
        failuresBeforeOpen <= 0
            ? "不熔断"
            : "连续失败 " + failuresBeforeOpen + " 次暂停 " + breakerCooldown.toSeconds() + " 秒";
    return rate + "，" + breaker;
  }

  /** 测试用：把闸门恢复到默认值。 */
  static void resetThrottle() {
    configure(600, 5, Duration.ofSeconds(30));
  }

  static ExecutorService newExecutor(String threadName) {
    return Executors.newCachedThreadPool(
        runnable -> {
          Thread thread = new Thread(runnable, threadName);
          thread.setDaemon(true);
          return thread;
        });
  }

  static HttpClient newClient(ExecutorService executor) {
    HttpClient client =
        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).executor(executor).build();
    THROTTLES.put(
        client,
        new Throttle(
            requestsPerMinute / 60.0,
            // 桶容量给一秒的量：一个回合里的连续调用不必自己卡自己，而一次真的突发仍然被限住。
            Math.max(1, requestsPerMinute / 60.0),
            failuresBeforeOpen,
            breakerCooldown));
    return client;
  }

  /**
   * 发送 {@code request}，返回第一个 2xx 响应，其响应体仍是尚未消费的流。传输层故障、408、429 和
   * 5xx 会以指数退避加抖动重试，每一次都通过 {@code listener} 播报。其他任何非 2xx 立刻抛出：400
   * 意味着请求本身有问题，重复发送只是浪费用户的时间。
   */
  /**
   * 发出 {@code request} 之前先过那道闸门，发送之后把结果告诉它。
   *
   * <p>闸门包在**整个重试序列**外面，而不是每一次尝试上：一个最终成功的重试序列不该被记成失败，而一个重试
   * 三次仍然失败的请求只算一次失败——因为它就是一次「这个端点现在不行」的证据。
   */
  static HttpResponse<Stream<String>> send(
      HttpClient http, HttpRequest request, Consumer<Provider.Event> listener) throws Exception {
    Throttle throttle = THROTTLES.get(http);
    if (throttle == null) {
      return sendOnce(http, request, listener);
    }
    try {
      // 等令牌在这里，抛「门关着」也在这里：那一次请求根本不该发出去。
      throttle.await(listener);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Exception("等待模型 API 的发送许可时被中断", e);
    }
    try {
      HttpResponse<Stream<String>> response = sendOnce(http, request, listener);
      throttle.recordSuccess();
      return response;
    } catch (Exception | Error e) {
      throttle.recordFailure();
      throw e;
    }
  }

  private static HttpResponse<Stream<String>> sendOnce(
      HttpClient http, HttpRequest request, Consumer<Provider.Event> listener) throws Exception {
    for (int attempt = 1; ; attempt++) {
      try {
        HttpResponse<Stream<String>> response = http.send(request, BodyHandlers.ofLines());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
          return response;
        }
        String body = readBody(response.body());
        if (!retryable(status) || attempt >= MAX_ATTEMPTS) {
          throw new Exception("HTTP " + status + ": " + truncate(body));
        }
        // 被限流的端点通常会说明该等多久，而比它要求的更早重试，正是 429 变成更长时间封禁的原因。
        awaitRetry(listener, attempt, "HTTP " + status, retryAfterMillis(response));
      } catch (IOException e) {
        if (attempt >= MAX_ATTEMPTS) {
          throw new Exception("请求在 " + attempt + " 次尝试后失败：" + e, e);
        }
        awaitRetry(listener, attempt, e.toString(), -1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new Exception("等待模型 API 时被中断", e);
      }
    }
  }

  /**
   * 对「2xx 响应体却不是事件流」的诊断。
   *
   * <p>上游失败的中继，或者无视 {@code stream: true} 的服务器，会用 JSON 回一个 200。帧解码器在其中
   * 找不到任何东西，所以如果不说一句收到的是什么，这个回合就会以一句无声的空回答结束——这是用户唯一无法
   * 诊断的失败。以返回而非抛出的方式给出，好让每个提供方报出自己的名字。
   */
  static String noEvents(HttpResponse<?> response, String arrived) {
    String contentType = response.headers().firstValue("content-type").orElse("");
    return "端点没有返回任何事件（"
        + (contentType.isBlank() ? "缺少 content type" : contentType)
        + "）："
        + (arrived.isBlank() ? "（空响应体）" : truncate(arrived));
  }

  /** 留下响应体开头的一两 KB，供上一条消息使用。 */
  static void remember(StringBuilder buffer, String line) {
    if (buffer.length() < 2048) {
      buffer.append(line).append('\n');
    }
  }

  /** 服务器给出的等待时间的上限，免得一个错误的首部把一个回合停住一小时。 */
  static final long MAX_RETRY_AFTER_MILLIS = 60_000;

  /**
   * 响应要求的等待时长，单位毫秒；没有可用的要求时返回 -1。这个首部的合法形式要么是秒数，要么是 HTTP
   * 日期，两种都读。
   */
  static long retryAfterMillis(HttpResponse<?> response) {
    String header = response.headers().firstValue("retry-after").orElse("").strip();
    if (header.isEmpty()) {
      return -1;
    }
    try {
      return Math.max(0, Long.parseLong(header) * 1000L);
    } catch (NumberFormatException numberOfSecondsExpected) {
      // 不是数字：HTTP 日期是同一个首部的另一种合法形式。
    }
    try {
      ZonedDateTime when = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME);
      return Math.max(0, Duration.between(Instant.now(), when.toInstant()).toMillis());
    } catch (DateTimeParseException e) {
      return -1;
    }
  }

  static boolean retryable(int status) {
    return status == 408 || status == 429 || status >= 500;
  }

  /** 刚刚失败的这次尝试的退避时间；{@code failedAttempt} 从 1 开始计数。 */
  static long backoffMillis(int failedAttempt) {
    long exponential = BASE_BACKOFF_MILLIS << (failedAttempt - 1);
    return exponential + ThreadLocalRandom.current().nextLong(MAX_JITTER_MILLIS + 1);
  }

  /**
   * 播报这次等待并真的等下去。{@code requestedMillis} 是服务器要求的时长，-1 表示回退到本地退避曲线。
   */
  private static void awaitRetry(
      Consumer<Provider.Event> listener, int attempt, String reason, long requestedMillis)
      throws InterruptedException {
    long delay =
        requestedMillis < 0
            ? backoffMillis(attempt)
            : Math.min(requestedMillis, MAX_RETRY_AFTER_MILLIS);
    listener.accept(new Provider.Event.Retry(attempt, reason, delay));
    Thread.sleep(delay);
  }

  /**
   * 读取一段错误响应，一旦超过 {@link #ERROR_BODY_LIMIT} 就停下。
   *
   * <p>是在读取过程中设限，而不是读完之后。先前的版本把整个响应体收下来、格式化时才截断，于是那道显示上限
   * 本想省下的内存早就花掉了：任意大小的错误页面——配置错误的网关返回的大 HTML 页、代理的启动画面——在被
   * 丢掉之前都完整地占用着内存。上限只有施加在读取上，才是真的上限。
   *
   * <p>无论如何都会关闭流，因为撇下一个不关闭的响应体会泄漏连接。
   */
  static String readBody(Stream<String> lines) {
    try (lines) {
      StringBuilder kept = new StringBuilder();
      for (String line : (Iterable<String>) lines::iterator) {
        if (kept.length() > 0) {
          kept.append('\n');
        }
        kept.append(line);
        // 给格式化器要加的那句标记留出余地，这样一个刚好卡在上限的响应体不会被重读一遍才发现放不下。
        if (kept.length() > ERROR_BODY_LIMIT + 64) {
          break;
        }
      }
      return kept.toString();
    }
  }

  static String truncate(String body) {
    String flat = body == null ? "" : body.strip();
    return flat.length() <= ERROR_BODY_LIMIT
        ? flat
        : flat.substring(0, ERROR_BODY_LIMIT) + "… （已截断）";
  }
}
