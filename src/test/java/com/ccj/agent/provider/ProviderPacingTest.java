package com.ccj.agent.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Message;
import com.ccj.agent.core.Provider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 闸门在真的请求上：限速会等（并且说出来），端点一直失败之后**请求根本不再发出去**。
 *
 * <p>后一条是这一层最值得钉住的主张，而它只有对着一个**数着请求**的假端点才能被钉住：门关着的时候，服务器
 * 的计数必须一动不动。「更快地失败」与「失败得少了」在日志里长得很像，而它们的区别就在这里。
 */
class ProviderPacingTest {

  private static final String STREAM =
      """
      data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"ok"}}]}

      data: [DONE]

      """;

  @AfterEach
  void restoreDefaults() {
    Transport.resetThrottle();
  }

  private static Provider.Request request() {
    return new Provider.Request("gpt-4o-mini", "sys", List.of(new Message.User("hi")), List.of(), null, null, null);
  }

  @Test
  void aThrottledProviderWaitsBeforeSendingAndSaysSo() throws Exception {
    // 每分钟 60 次 = 每秒一个令牌、桶里只有一个：第二次请求必须等。
    Transport.configure(60, 0, Duration.ZERO);
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(STREAM))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");
      List<Provider.Event> events = new ArrayList<>();

      provider.complete(request(), events::add);
      long started = System.nanoTime();
      provider.complete(request(), events::add);
      long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

      assertTrue(elapsedMillis >= 700, "第二次请求应当等到有令牌，实际 " + elapsedMillis + "ms");
      assertEquals(2, server.count());
      assertTrue(
          events.stream()
              .anyMatch(
                  event ->
                      event instanceof Provider.Event.Retry retry && retry.reason().contains("限流")),
          "等令牌要在事件流里说出来，否则它看起来就是卡死：" + events);
      provider.close();
    }
  }

  @Test
  void anUnlimitedProviderDoesNotWait() throws Exception {
    Transport.configure(0, 0, Duration.ZERO);
    try (FakeServer server = FakeServer.start(FakeServer.Reply.sse(STREAM))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");

      long started = System.nanoTime();
      for (int i = 0; i < 5; i++) {
        provider.complete(request(), event -> {});
      }
      long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

      assertTrue(elapsedMillis < 1_000, "不限速时五次请求不该等，实际 " + elapsedMillis + "ms");
      assertEquals(5, server.count());
      provider.close();
    }
  }

  @Test
  void onceTheEndpointKeepsFailingTheNextCallDoesNotEvenLeaveTheProcess() throws Exception {
    // 两次失败的请求（每次内部重试三遍）之后门关上：第三次请求必须立刻失败，而且服务器上的计数一动不动。
    Transport.configure(0, 2, Duration.ofSeconds(30));
    try (FakeServer server =
        FakeServer.start(
            FakeServer.Reply.status(503),
            FakeServer.Reply.status(503),
            FakeServer.Reply.status(503),
            FakeServer.Reply.status(503),
            FakeServer.Reply.status(503),
            FakeServer.Reply.status(503))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");

      assertThrows(Exception.class, () -> provider.complete(request(), event -> {}));
      assertThrows(Exception.class, () -> provider.complete(request(), event -> {}));
      int afterTwoFailures = server.count();
      assertEquals(6, afterTwoFailures, "每次请求内部重试三遍");

      long started = System.nanoTime();
      Exception refusal = assertThrows(Exception.class, () -> provider.complete(request(), event -> {}));
      long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

      assertTrue(elapsedMillis < 200, "门关着的时候不该等重试，实际 " + elapsedMillis + "ms");
      assertTrue(refusal.getMessage().contains("连续失败 2 次"), refusal.getMessage());
      assertEquals(afterTwoFailures, server.count(), "门关着的时候请求不该发出去");
      provider.close();
    }
  }

  @Test
  void theCooldownEndsAndARealRequestGoesOutAgain() throws Exception {
    Transport.configure(0, 1, Duration.ofMillis(200));
    try (FakeServer server =
        FakeServer.start(
            FakeServer.Reply.status(503),
            FakeServer.Reply.status(503),
            FakeServer.Reply.status(503),
            FakeServer.Reply.sse(STREAM))) {
      OpenAiProvider provider = new OpenAiProvider(server.url(), "sk-test");

      assertThrows(Exception.class, () -> provider.complete(request(), event -> {}));
      assertThrows(Exception.class, () -> provider.complete(request(), event -> {})); // 门已经关着
      int whileOpen = server.count();

      Thread.sleep(250); // 冷却过去

      Message.Assistant assistant = provider.complete(request(), event -> {});
      assertEquals("ok", assistant.text(), "冷却之后的那一次试探真的发出去了，而且成功了");
      assertTrue(server.count() > whileOpen, "服务器的计数必须往前走");
      provider.close();
    }
  }

  @Test
  void aMalformedEnvironmentValueIsReportedRatherThanSilentlyIgnored() {
    // 「我设了」与「我什么都没设」是两件事，而一个读不出来的整数会把前者悄悄变成后者。
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> Providers.create(com.ccj.agent.core.Config.layered(null, java.util.Map.of("CCJ_BREAKER_FAILURES", "five"), null), java.util.Map.of("CCJ_BREAKER_FAILURES", "five", "CCJ_API_KEY", "k")));

    assertTrue(failure.getMessage().contains("CCJ_BREAKER_FAILURES"), failure.getMessage());
    assertTrue(failure.getMessage().contains("five"), failure.getMessage());
  }
}
