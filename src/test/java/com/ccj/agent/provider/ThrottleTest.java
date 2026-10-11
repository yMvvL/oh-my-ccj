package com.ccj.agent.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.Provider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 闸门本身：令牌按速率发、突发有上界、失败到阈值就关门、冷却之后放一次试探。
 *
 * <p>这些用例量的都是**时间**，所以界限刻意留得松——它们是回归防线，不是基准测试。要紧的是方向：一个不限速
 * 的实现会让「二十次请求至少花掉多少毫秒」这条断言红，而一个把速率配反了的实现（每秒换成了每分钟）会让它红
 * 得更彻底。真正精确的那一半（「门关着的时候请求根本没发出去」）在 {@code ProviderPacingTest} 里对着一个记数
 * 的假端点断言。
 */
class ThrottleTest {

  private static Throttle unlimited() {
    return Throttle.unrestricted();
  }

  @Test
  void permitsComeOutAtTheConfiguredRate() throws Exception {
    // 50/秒、桶容量 1：二十次取用完十九个间隔，至少 380 毫秒。
    Throttle throttle = new Throttle(50, 1, 0, Duration.ZERO);

    long started = System.nanoTime();
    for (int i = 0; i < 20; i++) {
      throttle.await(null);
    }
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

    assertTrue(elapsedMillis >= 300, "二十次取用不该是瞬间的，实际 " + elapsedMillis + "ms");
    assertTrue(elapsedMillis < 2_000, "也不该慢得离谱，实际 " + elapsedMillis + "ms");
  }

  @Test
  void aBurstIsAllowedUpToTheBucketSizeAndNoFurther() throws Exception {
    Throttle throttle = new Throttle(20, 5, 0, Duration.ZERO);

    long started = System.nanoTime();
    for (int i = 0; i < 5; i++) {
      throttle.await(null);
    }
    long burstMillis = (System.nanoTime() - started) / 1_000_000;
    assertTrue(burstMillis < 50, "桶里的五个令牌应当立刻可用，实际 " + burstMillis + "ms");

    throttle.await(null); // 第六个要等补一个
    long sixthMillis = (System.nanoTime() - started) / 1_000_000;
    assertTrue(sixthMillis >= 40, "第六个令牌要等补给，实际 " + sixthMillis + "ms");
  }

  @Test
  void anUnlimitedGateNeverWaits() throws Exception {
    long started = System.nanoTime();
    for (int i = 0; i < 200; i++) {
      unlimited().await(null);
    }
    assertTrue((System.nanoTime() - started) / 1_000_000 < 50, "不限速时两百次取用必须是瞬间的");
    assertFalse(unlimited().limited());
  }

  @Test
  void waitingIsSaidOutLoudBecauseSilenceLooksLikeAHang() throws Exception {
    Throttle throttle = new Throttle(10, 1, 0, Duration.ZERO);
    throttle.await(null); // 用掉桶里那一个
    List<Provider.Event> events = new ArrayList<>();

    throttle.await(events::add);

    assertEquals(1, events.size(), "等一次令牌该播报一次：" + events);
    Provider.Event.Retry retry = (Provider.Event.Retry) events.get(0);
    assertTrue(retry.reason().contains("限流"), retry.reason());
    assertTrue(retry.reason().contains("每分钟最多 600 次"), retry.reason());
    assertTrue(retry.delayMillis() > 0, "播报里要有「等多久」，实际 " + retry.delayMillis());
  }

  @Test
  void theBreakerOpensAfterConsecutiveFailuresAndThenFailsFast() throws Exception {
    Throttle throttle = new Throttle(0, 1, 3, Duration.ofSeconds(30));
    for (int i = 0; i < 3; i++) {
      throttle.recordFailure();
    }

    long started = System.nanoTime();
    Exception refusal = assertThrows(Exception.class, () -> throttle.await(null));
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

    assertTrue(throttle.open());
    assertTrue(elapsedMillis < 50, "门关着的时候不该等，实际 " + elapsedMillis + "ms");
    assertTrue(refusal.getMessage().contains("连续失败 3 次"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("还要"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("重启"), "报错要说清怎么办：" + refusal.getMessage());
  }

  @Test
  void aSuccessOpensTheDoorAgain() throws Exception {
    Throttle throttle = new Throttle(0, 1, 2, Duration.ofSeconds(30));
    throttle.recordFailure();
    throttle.recordFailure();
    assertTrue(throttle.open());

    throttle.recordSuccess();

    assertFalse(throttle.open());
    throttle.await(null); // 不再抛
  }

  @Test
  void theCooldownExpiresAndLetsOneRequestThrough() throws Exception {
    // 冷却过后开门是刻意的：不然一次长时间的抖动会把一个本来健康的端点永久挡在外面。
    Throttle throttle = new Throttle(0, 1, 1, Duration.ofMillis(120));
    throttle.recordFailure();
    assertTrue(throttle.open());

    Thread.sleep(150);

    assertFalse(throttle.open(), "冷却过去了，门该开着");
    throttle.await(null); // 这一次试探真的放出去了
  }

  @Test
  void aTrialThatFailsClosesTheDoorForAnotherCooldown() throws Exception {
    Throttle throttle = new Throttle(0, 1, 1, Duration.ofMillis(120));
    throttle.recordFailure();
    Thread.sleep(150);
    assertFalse(throttle.open());

    throttle.recordFailure(); // 试探失败

    assertTrue(throttle.open(), "试探失败之后应当重新关上");
    assertThrows(Exception.class, () -> throttle.await(null));
  }

  @Test
  void noBreakerAndNoLimitAreBothExpressible() throws Exception {
    Throttle off = new Throttle(0, 1, 0, Duration.ZERO);
    for (int i = 0; i < 10; i++) {
      off.recordFailure();
      off.await(null);
    }
    assertFalse(off.open(), "阈值 0 的闸门不熔断");
    assertEquals("不限速", off.rate());
    assertTrue(Transport.describeThrottle().contains("每分钟最多 600 次请求"));
  }

  @Test
  void waitingForATokenCanBeInterrupted() {
    Throttle throttle = new Throttle(0.5, 1, 0, Duration.ZERO);
    assertThrows(InterruptedException.class, () -> {
      throttle.await(null); // 用掉那一个
      Thread.currentThread().interrupt();
      try {
        throttle.await(null);
      } finally {
        Thread.interrupted();
      }
    });
  }
}
