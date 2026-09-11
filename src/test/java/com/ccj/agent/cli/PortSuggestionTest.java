package com.ccj.agent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

/**
 * A busy port is the most common way a first run fails, so the message has to name a port that
 * actually works rather than a number the user then finds occupied too.
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

      assertTrue(suggested > occupied, "the busy port itself is no good: " + suggested);
      try (ServerSocket second = new ServerSocket()) {
        second.setReuseAddress(true);
        second.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), suggested), 1);
      }
    }
  }

  @Test
  void returnsMinusOneWhenTheSearchFindsNothing() {
    assertEquals(-1, Cli.freePortFrom(70_000), "an impossible range must not invent an answer");
  }
}
