package com.ccj.agent.provider;

import com.ccj.agent.core.Provider;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * 一个提供方端点的闸门：发请求之前先取一个令牌，而端点一直失败时先把门关上。
 *
 * <p>它解决两件不同的事，而它们值得有两个旋钮：
 *
 * <ul>
 *   <li><strong>速率。</strong>一个跑飞的循环（或者五个同时在跑的对话）会以机器能够的最快速度把请求发出去，
 *       而提供方对此的回答是限流甚至封禁——一次封禁比慢几秒贵得多。所以这里是一个令牌桶：按配置的速率补令牌，
 *       桶的容量给一点突发余地（一个回合里的连续调用不该被自己卡住）。
 *   <li><strong>失败。</strong>一个端点连续失败五次，通常不是抖动，而是它挂了或者钥匙错了——而那时候继续
 *       每几秒发一次请求，只是在等一个不会来的答复，还把失败写得满屏都是。所以连续失败到阈值就先关上门，
 *       冷却一段时间，再放一次试探。
 * </ul>
 *
 * <p>两条刻意的不做：**不重试**（重试的策略在 {@link Transport} 里，一处就够了；这里只决定「能不能发」），
 * 以及**不替代超时**（等令牌与等答复是两件事，各自有自己的上界）。等令牌的时候会播报一次——一次悄无声息的
 * 几秒等待，看起来和一次卡死没有区别。
 *
 * <p>状态是进程内的、每个客户端一份（见 {@link Transport#newClient}）：这个程序是给一个人的，而闸门保护的
 * 是那一份提供方凭据，不是某个会话。
 */
final class Throttle {

  /** 令牌桶的粒度：等待以这个为上界切片，好让中断与「等多久」都有一个数字可以报。 */
  private static final long SLEEP_SLICE_MILLIS = 100;

  private final double permitsPerSecond;
  private final double burst;
  private final int breakAfter;
  private final Duration cooldown;
  /** 一个回合里最多报一次「正在等令牌」：同一句话每 100 毫秒重复一遍只会把转录淹掉。 */
  private final java.util.concurrent.atomic.AtomicBoolean announced = new java.util.concurrent.atomic.AtomicBoolean();

  private double tokens;
  private long lastRefillNanos = System.nanoTime();

  private int consecutiveFailures;
  private long openedAtNanos;

  /**
   * @param permitsPerSecond 每秒补多少令牌；{@code <= 0} 表示不限速
   * @param burst 桶的容量（也就是允许的一次突发）——至少 1
   * @param breakAfter 连续失败多少次之后关门；{@code <= 0} 表示不熔断
   * @param cooldown 关门之后等多久才放一次试探
   */
  Throttle(double permitsPerSecond, double burst, int breakAfter, Duration cooldown) {
    this.permitsPerSecond = permitsPerSecond <= 0 ? 0 : permitsPerSecond;
    this.burst = Math.max(1, burst);
    this.breakAfter = Math.max(0, breakAfter);
    this.cooldown = cooldown == null ? Duration.ofSeconds(30) : cooldown;
    this.tokens = this.burst;
  }

  /** 不限速、不熔断：配置里写 0 时得到的就是它。 */
  static Throttle unrestricted() {
    return new Throttle(0, 1, 0, Duration.ZERO);
  }

  /** 这个闸门限速吗；给测试与「谁配了什么」这类问题用。 */
  boolean limited() {
    return permitsPerSecond > 0;
  }

  /** 门关着吗（冷却期内）。 */
  boolean open() {
    if (breakAfter <= 0 || consecutiveFailures < breakAfter || openedAtNanos == 0) {
      return false;
    }
    return System.nanoTime() - openedAtNanos < cooldown.toNanos();
  }

  /**
   * 取一个令牌；取不到就等，等到为止。门关着的时候不取令牌，而是抛出——那一次请求根本不该发出去。
   *
   * @param notice 播报等待的地方；{@code null} 表示不播报
   */
  void await(Consumer<Provider.Event> notice) throws Exception {
    if (open()) {
      long remainingMillis = Math.max(1, (cooldown.toNanos() - (System.nanoTime() - openedAtNanos)) / 1_000_000);
      throw new Exception(
          "提供方连续失败 "
              + consecutiveFailures
              + " 次，已暂停请求，还要 "
              + remainingMillis / 1000
              + " 秒才放一次试探（冷却期内不会再发出请求；改配置或换端点后重启即可立刻重试）");
    }
    if (permitsPerSecond <= 0) {
      return;
    }
    while (true) {
      double waitSeconds = take();
      if (waitSeconds <= 0) {
        return;
      }
      if (notice != null && announced.compareAndSet(false, true)) {
        // 借用重试那一个事件：它的形状正是「我们正在等，而等多久」，而两个前端都已经会画它。
        notice.accept(
            new Provider.Event.Retry(
                0, rate(), Math.round(waitSeconds * 1000)));
      }
      long sliceMillis = Math.min(SLEEP_SLICE_MILLIS, Math.max(1, Math.round(waitSeconds * 1000)));
      Thread.sleep(sliceMillis);
    }
  }

  /** 一次成功的请求：把失败计数抹掉，门就此打开。 */
  void recordSuccess() {
    consecutiveFailures = 0;
    openedAtNanos = 0;
    announced.set(false);
  }

  /**
   * 一次失败的请求（重试都试过了还是失败）：数一次。
   *
   * <p>数的是**请求**，不是尝试：一个最终成功的重试序列不该把门关上，而一个重试三次仍然失败的请求应当算
   * 一次——因为它就是一次「这个端点现在不行」的证据。
   */
  void recordFailure() {
    if (breakAfter <= 0) {
      return;
    }
    consecutiveFailures++;
    if (consecutiveFailures >= breakAfter) {
      openedAtNanos = System.nanoTime();
    }
  }

  /** 一句话说清现在的速率，给播报与测试用。 */
  String rate() {
    if (permitsPerSecond <= 0) {
      return "不限速";
    }
    double perMinute = permitsPerSecond * 60;
    return perMinute >= 1
        ? "限流：每分钟最多 " + Math.round(perMinute) + " 次请求"
        : "限流：每 " + Math.round(1 / permitsPerSecond) + " 秒最多 1 次请求";
  }

  /**
   * 取一个令牌，返回还需要等多少秒（0 表示拿到了）。
   *
   * <p>连续补令牌而不是按整秒跳：一个整秒一跳的桶会让 60 rpm 变成「每次都在同一瞬间放 60 个」，而那正是
   * 限流的另一端要防的事。
   */
  private double take() {
    long now = System.nanoTime();
    double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
    lastRefillNanos = now;
    tokens = Math.min(burst, tokens + elapsedSeconds * permitsPerSecond);
    if (tokens >= 1) {
      tokens -= 1;
      return 0;
    }
    return (1 - tokens) / permitsPerSecond;
  }
}
