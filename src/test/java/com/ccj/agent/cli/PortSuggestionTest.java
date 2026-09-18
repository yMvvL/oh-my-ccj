package com.ccj.agent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

/**
 * 端口被占用是第一次运行最常见的失败方式，所以消息必须点名一个真正能用的端口，而不是一个用户
 * 随后发现也被占用的数字。
 */
class PortSuggestionTest {

  @Test
  void suggestsAPortThatIsActuallyFree() throws IOException {
    int occupied;
    try (ServerSocket taken = new ServerSocket()) {
      taken.setReuseAddress(true);
      taken.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
      occupied = taken.getLocalPort();

      int suggested = Cli.freePortFrom(occupied);

      assertTrue(suggested > occupied, "被占用的那个端口本身不行：" + suggested);
      try (ServerSocket second = new ServerSocket()) {
        second.setReuseAddress(true);
        second.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), suggested), 1);
      }
    }
  }

  @Test
  void returnsMinusOneWhenTheSearchFindsNothing() {
    assertEquals(-1, Cli.freePortFrom(70_000), "不可能的区间不能凭空编出一个答案");
  }
}
