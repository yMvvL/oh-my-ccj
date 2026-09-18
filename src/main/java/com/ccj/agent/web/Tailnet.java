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
 * 这台机器在 tailnet 上的地址，供 {@code --host tailscale} 使用。
 *
 * <p>绑 tailnet 地址而不是 {@code 0.0.0.0} 正是重点：通配绑定会把 Web UI 放到这台机器所在的每一个网络上
 * ——包括咖啡馆的 wifi——而 tailnet 地址只有 tailnet 里的设备能到达，那一组设备是用户在 Tailscale 管理
 * 后台自己控制的。它仍然不是环回地址，所以 {@code Cli} 里的检查会要求 {@code --web-token}；这是有意的，
 * token 是挡在被偷的手机和 shell 之间的第二道东西。
 *
 * <p>先找 Tailscale 建的那个接口（Linux 上是 {@code tailscale0}，Windows 上是 {@code Tailscale}），
 * 找不到再问 CLI（{@code tailscale ip -4}）——macOS 用一个不叫 Tailscale 的 {@code utun} 接口承载它的
 * tailnet，而 CLI 是官方文档给出的问法。两条路径都只接受 {@code 100.64.0.0/10} 里的地址，即 Tailscale
 * 分配的网段，所以 {@code utun} 接口上跑着别的东西的机器没法让这里返回错误的地址。
 */
public final class Tailnet {

  /** {@code --host} 取什么值表示「这台机器在 tailnet 上的地址」。 */
  public static final String FLAG_VALUE = "tailscale";

  private static final Duration CLI_TIMEOUT = Duration.ofSeconds(5);

  private Tailnet() {}

  /** tailnet 地址；这台机器不在 tailnet 上时为空。 */
  public static Optional<InetAddress> address() {
    Optional<InetAddress> fromInterface = fromInterfaces();
    return fromInterface.isPresent() ? fromInterface : fromCli();
  }

  /** Tailscale 给自己起的接口名返回 true：{@code tailscale0}、{@code Tailscale}。 */
  static boolean isTailnetInterface(String name) {
    return name != null && name.toLowerCase(Locale.ROOT).contains(FLAG_VALUE);
  }

  /**
   * 对 {@code 100.64.0.0/10}——Tailscale 分配地址所用的 CGNAT 网段——返回 true。
   *
   * <p>检查过才下结论，而不是假定：上面那些名字只是约定，一台机器可能有个叫 {@code tailscale0} 的接口
   * 却并不承载我们要的地址，也可能有个与 Tailscale 毫无关系的 {@code utun} 接口。
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

  /** {@code tailscale ip -4} 输出里的第一个 tailnet 地址，如果有的话。 */
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
        // 不是地址：CLI 打印的是别的东西（一条警告、一段版本横幅）。
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
      // 网络栈完全没给出答案：下面的 CLI 是另一条路。
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
      return Optional.empty(); // 没安装：普通机器而已，不是错误
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
