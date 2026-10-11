package com.ccj.agent.perf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 三笔开销能被量出来，也各有一条上界。
 *
 * <p>这些用例的界限刻意留得松——它们是回归防线，不是基准测试。真跑一次量出来的数字在
 * {@code docs/PERF.md} 里，而这里的每一次打印都与它对齐：一条不再能被复现的数字，比没有数字更糟。
 *
 * <p>它们量的东西分三类：进程边界上的墙钟（起一个真 JVM）、一段确定性的估算（前缀 token），以及一次文件
 * 系统繁忙路径（列一个几兆字节的会话）。前两者在一台负载正常的机器上稳定到几毫秒，第三者受缓存与磁盘
 * 影响，所以只有它带一条明显宽松的界。
 */
class OverheadLedgerTest {

  @Test
  void theFixedPrefixPerTurnIsCountedAndStaysUnderItsBound() {
    OverheadLedger.Prefix prefix = OverheadLedger.prefix();
    System.out.printf(
        "前缀：系统提示 %d + 工具 %d = %d token（%d 个工具，%d 字符）%n",
        prefix.systemTokens(),
        prefix.toolTokens(),
        prefix.totalTokens(),
        prefix.toolCount(),
        prefix.chars());

    assertTrue(prefix.toolCount() >= 8, "标准工具表至少有八个工具：" + prefix.toolCount());
    // 一条上界，不是目标：每个回合都会原样发一遍这一份，所以它变大是有人要看见的事。这个数字刻意留得
    // 比现在宽一倍多——它防的是一次失控的增长（一个把整本手册塞进 description 的工具），不是装修。
    assertTrue(
        prefix.totalTokens() > 500 && prefix.totalTokens() < 8000,
        "每个回合固定的前缀应当在几百到几千 token 之间，实际 " + prefix.totalTokens());
  }

  @Test
  void listingAMultiMegabyteSessionIsNotAStall() throws Exception {
    OverheadLedger.Listing listing = OverheadLedger.listing(2 * 1024 * 1024);
    System.out.printf(
        "列会话：%d 字节 / %d 条消息 → 缓存落空 %d ms，命中 %d ms%n",
        listing.bytes(), listing.messages(), listing.coldMillis(), listing.warmMillis());

    // 侧栏在每个回合结束后都要重读一次列表，而刚被写入的那个会话每次都要重新推导，所以缓存落空那一笔
    // 必须便宜。本机量到的是十毫秒量级；这条界是它的几十倍，留给 CI 上更慢的磁盘。
    assertTrue(
        listing.coldMillis() < 500,
        "列出 "
            + listing.bytes()
            + " 字节的会话花了 "
            + listing.coldMillis()
            + "ms；一次列表不能变成一个回合的停顿");
    assertTrue(listing.warmMillis() <= listing.coldMillis() || listing.warmMillis() < 5,
        "没动过的会话不该比动过的那个更贵：" + listing);
    assertTrue(listing.messages() > 1000, "这个文件应当足够大：" + listing.messages());
  }

  @Test
  void theCliStartsAndIsReadyInUnderItsBound() throws Exception {
    OverheadLedger.Startup entry = OverheadLedger.entry();
    OverheadLedger.Startup ready = OverheadLedger.ready();
    System.out.printf(
        "启动：%s → %d ms；%s → %d ms%n",
        entry.stage(), entry.millis(), ready.stage(), ready.millis());

    // 这两个数字一起把「启动」夹在中间：一个是 JVM 的底价，一个是工具表建好、可以收下第一条消息的那一刻。
    // 差值是配置与工具注册表的代价，而它在这台机器上是两百毫秒量级。
    assertTrue(entry.millis() < 10_000, "JVM 启动花了 " + entry.millis() + "ms");
    assertTrue(
        ready.millis() >= entry.millis(),
        "能收下第一条消息不能比打印版本更快：" + ready.millis() + " vs " + entry.millis());
    assertTrue(ready.millis() < 20_000, "就绪花了 " + ready.millis() + "ms");
  }
}
