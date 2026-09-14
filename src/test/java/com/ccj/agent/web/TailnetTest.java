package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Finding the tailnet address is what makes {@code --host tailscale} a one-word answer instead of an
 * IP the user has to look up, so the two things that can go wrong are pinned here: reading the wrong
 * line out of the CLI's output, and accepting an address that is not a tailnet one.
 *
 * <p>{@link Tailnet#address()} itself is machine-dependent by nature — the case for it is that this
 * host answers, and the case for every other host is that it does not — so what is asserted is the
 * invariant that matters either way: an address is only ever returned for an interface that looks
 * like Tailscale's, and it is always inside the range Tailscale allocates from.
 */
class TailnetTest {

  @Test
  void theFirstTailnetAddressInTheOutputWins() {
    assertEquals(
        "100.72.92.41",
        Tailnet.parse("100.72.92.41\n").map(InetAddress::getHostAddress).orElse(""));

    // A banner, a blank line and the tailnet address: the CLI has printed more than an address
    // before now, and the answer is the line that is one.
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
    assertEquals(Optional.empty(), Tailnet.parse("192.168.1.20\n"), "a LAN address is not one");
    assertEquals(Optional.empty(), Tailnet.parse("fd7a:115c:a1e0::1\n"), "and not an IPv6 one");
    assertEquals(Optional.empty(), Tailnet.parse("100.63.255.255\n"), "just below the range");
    assertEquals(Optional.empty(), Tailnet.parse("100.128.0.0\n"), "and just above it");
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
    assertFalse(Tailnet.isTailnetInterface("utun4"), "macOS names its tun devices nothing like this");
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
      // Nothing to inspect: the assertion below is then vacuous, which is the honest outcome.
    }

    Optional<InetAddress> found = Tailnet.address();
    assertFalse(
        found.isPresent() && !Tailnet.isTailnetAddress(found.get()),
        "a tailnet address is in the tailnet range: " + found);
    if (anyTailnetInterface) {
      assertTrue(found.isPresent(), "an interface named like Tailscale carries the address");
    }
  }
}
