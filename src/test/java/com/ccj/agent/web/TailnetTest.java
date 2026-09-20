package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 找到 tailnet 地址，才让 {@code --host tailscale} 成为一个词的答案，而不是一个用户还得去查的
 * IP；所以这里钉住了两件可能出错的事：从 CLI 输出里读错了行，以及接受一个并不是 tailnet 的
 * 地址。
 *
 * <p>{@link Tailnet#address()} 本身就依赖机器——它成立的理由是这台主机答得出，而对其他任何主机
 * 成立的理由是它答不出——所以断言的是无论哪种情况都重要的那条不变量：只有看起来像 Tailscale 的
 * 接口才会被返回地址，而且那个地址永远落在 Tailscale 分配的范围内。
 */
class TailnetTest {

  @Test
  void theFirstTailnetAddressInTheOutputWins() {
    assertEquals(
        "100.64.0.1",
        Tailnet.parse("100.64.0.1\n").map(InetAddress::getHostAddress).orElse(""));

    // 一段横幅、一个空行，然后是 tailnet 地址：CLI 以前就打印过比一个地址更多的东西，而答案
    // 就是那行确实是个地址的行。
    assertEquals(
        "100.101.102.103",
        Tailnet.parse("warning: no state\n\n100.101.102.103\n")
            .map(InetAddress::getHostAddress)
            .orElse(""));
  }

  @Test
  void anythingThatIsNotATailnetAddressIsRefused() {
    assertEquals(Optional.empty(), Tailnet.parse(""));
    assertEquals(Optional.empty(), Tailnet.parse(null));
    assertEquals(Optional.empty(), Tailnet.parse("tailscale: command not found"));
    assertEquals(Optional.empty(), Tailnet.parse("192.168.1.20\n"), "局域网地址不算");
    assertEquals(Optional.empty(), Tailnet.parse("fd7a:115c:a1e0::1\n"), "IPv6 的也不算");
    assertEquals(Optional.empty(), Tailnet.parse("100.63.255.255\n"), "刚好在范围之下");
    assertEquals(Optional.empty(), Tailnet.parse("100.128.0.0\n"), "以及刚好在它之上");
  }

  @Test
  void theRangeIsCheckedAtItsEdges() throws Exception {
    assertTrue(Tailnet.isTailnetAddress(InetAddress.getByName("100.64.0.1")));
    assertTrue(Tailnet.isTailnetAddress(InetAddress.getByName("100.127.255.254")));
    assertFalse(Tailnet.isTailnetAddress(InetAddress.getByName("100.63.255.255")));
    assertFalse(Tailnet.isTailnetAddress(InetAddress.getByName("100.128.0.0")));
    assertFalse(Tailnet.isTailnetAddress(InetAddress.getByName("10.0.0.1")));
    assertFalse(Tailnet.isTailnetAddress(InetAddress.getByName("fd7a:115c:a1e0::1")));
  }

  @Test
  void onlyTailscalesOwnInterfacesAreConsidered() {
    assertTrue(Tailnet.isTailnetInterface("tailscale0"));
    assertTrue(Tailnet.isTailnetInterface("Tailscale"));
    assertTrue(Tailnet.isTailnetInterface("tailscale1"));
    assertFalse(Tailnet.isTailnetInterface("eth0"));
    assertFalse(Tailnet.isTailnetInterface("utun4"), "macOS 给它的 tun 设备起的名字完全不是这样");
    assertFalse(Tailnet.isTailnetInterface(null));
  }

  @Test
  void theAddressFoundHereIsATailnetOne() {
    boolean anyTailnetInterface = false;
    try {
      var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
      while (interfaces != null && interfaces.hasMoreElements()) {
        if (Tailnet.isTailnetInterface(interfaces.nextElement().getName())) {
          anyTailnetInterface = true;
        }
      }
    } catch (Exception e) {
      // 没有东西可查：下面的断言于是成了空话，而这是诚实的结果。
    }

    Optional<InetAddress> found = Tailnet.address();
    assertFalse(
        found.isPresent() && !Tailnet.isTailnetAddress(found.get()),
        "tailnet 地址就在 tailnet 范围里：" + found);
    if (anyTailnetInterface) {
      assertTrue(found.isPresent(), "名字像 Tailscale 的接口就带着这个地址");
    }
  }
}
