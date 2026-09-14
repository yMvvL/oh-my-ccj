package com.ccj.agent.web;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The address this machine has on its tailnet, for {@code --host tailscale}.
 *
 * <p>Binding the tailnet address rather than {@code 0.0.0.0} is the whole point: a wildcard bind puts
 * the web UI on every network this machine is on — café wifi included — while the tailnet address is
 * reachable only by devices in the tailnet, which is a set the user controls from the Tailscale admin
 * console. It is still not loopback, so {@code --web-token} is required by the check in {@code Cli};
 * that is deliberate, and the token is the second thing standing between a stolen phone and a shell.
 *
 * <p>Found by looking for the interface Tailscale creates ({@code tailscale0} on Linux, {@code
 * Tailscale} on Windows) and, failing that, by asking the CLI ({@code tailscale ip -4}) — macOS
 * carries its tailnet over a {@code utun} interface that is not named after Tailscale, and the CLI is
 * the documented way to ask. Both paths accept only an address in {@code 100.64.0.0/10}, the range
 * Tailscale hands out, so a machine with something else on a {@code utun} interface cannot make this
 * return the wrong address.
 */
public final class Tailnet {

  /** What {@code --host} takes to mean "the address this machine has on its tailnet". */
  public static final String FLAG_VALUE = "tailscale";

  private static final Duration CLI_TIMEOUT = Duration.ofSeconds(5);

  private Tailnet() {}

  /** The tailnet address, or empty when this machine is not on a tailnet. */
  public static Optional<InetAddress> address() {
    Optional<InetAddress> fromInterface = fromInterfaces();
    return fromInterface.isPresent() ? fromInterface : fromCli();
  }

  /** True for the interface names Tailscale gives its own: {@code tailscale0}, {@code Tailscale}. */
  static boolean isTailnetInterface(String name) {
    return name != null && name.toLowerCase(Locale.ROOT).contains(FLAG_VALUE);
  }

  /**
   * True for {@code 100.64.0.0/10}, the CGNAT range Tailscale allocates from.
   *
   * <p>Checked rather than assumed: the names above are conventions, and a machine can have an
   * interface called {@code tailscale0} that is not carrying the address we want, or a {@code utun}
   * interface that has nothing to do with Tailscale at all.
   */
  static boolean isTailnetAddress(InetAddress address) {
    if (!(address instanceof Inet4Address)) {
      return false;
    }
    byte[] octets = address.getAddress();
    int first = octets[0] & 0xff;
    int second = octets[1] & 0xff;
    return first == 100 && second >= 64 && second <= 127;
  }

  /** The first tailnet address in the output of {@code tailscale ip -4}, if there is one. */
  static Optional<InetAddress> parse(String output) {
    if (output == null) {
      return Optional.empty();
    }
    for (String line : output.split("\\R")) {
      String candidate = line.strip();
      if (candidate.isEmpty()) {
        continue;
      }
      try {
        InetAddress parsed = InetAddress.getByName(candidate);
        if (isTailnetAddress(parsed)) {
          return Optional.of(parsed);
        }
      } catch (IOException e) {
        // Not an address: the CLI printed something else (a warning, a version banner).
      }
    }
    return Optional.empty();
  }

  private static Optional<InetAddress> fromInterfaces() {
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces != null && interfaces.hasMoreElements()) {
        NetworkInterface candidate = interfaces.nextElement();
        if (!isTailnetInterface(candidate.getName()) || !candidate.isUp()) {
          continue;
        }
        Enumeration<InetAddress> addresses = candidate.getInetAddresses();
        while (addresses.hasMoreElements()) {
          InetAddress address = addresses.nextElement();
          if (isTailnetAddress(address)) {
            return Optional.of(address);
          }
        }
      }
    } catch (SocketException e) {
      // No answer from the network stack at all: the CLI below is the other way in.
    }
    return Optional.empty();
  }

  private static Optional<InetAddress> fromCli() {
    Process process = null;
    try {
      process = new ProcessBuilder(FLAG_VALUE, "ip", "-4").redirectErrorStream(true).start();
      boolean exited = process.waitFor(CLI_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        return Optional.empty();
      }
      if (process.exitValue() != 0) {
        return Optional.empty();
      }
      return parse(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      return Optional.empty(); // not installed: an ordinary machine, not an error
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }
}
