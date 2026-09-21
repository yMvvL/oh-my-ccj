package com.ccj.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Optional;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 跨源、token 与 Host：谁能改状态、谁只能读、以及网络监听上的那把钥匙。
 *
 * <p>单独成类，是因为这一组用裸 socket 和自选的 Host 头说话，和其余用例所依赖的 HttpClient
 * 不是同一种说话方式。
 */
class WebSecurityTest extends WebHarness {

  @Test
  void theTokenGateProtectsTheNetworkAndRemembersTheBrowser() throws Exception {
    api.close();
    start("s3cret");

    // token 是为了从别处够到这台服务器，而一个点名了非 loopback 主机的请求就属于这种情况，
    // 哪怕它是在本机上发出的——这正是回答「自己域名解析到 127.0.0.1 的页面」的那一半。
    String denied = rawResponse("rebind.example", "/api/status");
    assertTrue(denied.startsWith("HTTP/1.1 401"), denied);
    assertTrue(denied.contains("token"), denied);

    String refused = rawResponse("rebind.example", "/api/status?token=wrong");
    assertTrue(refused.startsWith("HTTP/1.1 401"), refused);

    // 本机不算「别处」：在 127.0.0.1 上打开页面，就是坐在键盘前的用户使用 ccj 的方式，在那里
    // 还要输一个秘密，等于在人家自己的命令行上加了个密码。
    assertEquals(200, get("/api/status").statusCode(), "本机自己被放行");

    HttpResponse<String> allowed = get("/api/status?token=s3cret");
    assertEquals(200, allowed.statusCode());

    HttpResponse<String> page = get("/?token=s3cret");
    assertEquals(200, page.statusCode());
    String cookie = page.headers().firstValue("Set-Cookie").orElse("");
    assertTrue(cookie.startsWith("ccj_token=s3cret"), "页面加载必须记住 token");
    assertTrue(cookie.contains("HttpOnly"), cookie);
    assertTrue(page.body().contains("<html"), "页面本身仍然必须被提供");

    HttpResponse<String> withCookie =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/status"))
                .header("Cookie", "ccj_token=s3cret")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, withCookie.statusCode(), "浏览器不必再要一次 token");

    HttpResponse<String> withHeader =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/status"))
                .header("Authorization", "Bearer s3cret")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, withHeader.statusCode());
  }

  @Test
  void aTokenlessServerRefusesRequestsAddressedToAnotherHost() throws Exception {
    // 没有 token 的服务器，用户访问的任何页面都够得着；正是 Host 头拦住了「一个解析到
    // 127.0.0.1 的名字（DNS 重绑定）」被当成这台服务器。
    assertTrue(
        rawResponse("rebind.example", "/api/status").startsWith("HTTP/1.1 403"),
        "不是 loopback 的名字就不是这台服务器");
    assertTrue(
        rawResponse("127.0.0.1:" + api.port(), "/api/status").startsWith("HTTP/1.1 200"),
        "而 loopback 字面量是");
  }

  /**
   * 一个裸请求，因为 {@code HttpClient} 不让测试自己选 {@code Host} 头——而那个头在这里有三处
   * 正是被测的东西。
   */
  private String rawResponse(String hostHeader, String path) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", api.port())) {
      socket
          .getOutputStream()
          .write(
              ("GET " + path + " HTTP/1.1\r\nHost: " + hostHeader + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.UTF_8));
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void bothAddressesServeTheSamePageAndOnlyTheNetworkOneAsksForTheToken() throws Exception {
    // 「直接跑 ccj」是什么意思：本机在 127.0.0.1 上打开页面，什么都不用附带；而同一个服务器
    // 会向手机要 token——同一个进程、同一个 hub、同一个对话。这个测试要有第二个地址才成立，所以
    // 只有 loopback 地址的机器会跳过它，而不是假装通过。
    Optional<InetAddress> elsewhere = anAddressOtherThanLoopback();
    assumeTrue(elsewhere.isPresent(), "这台机器除了 loopback 没有别的地址");

    api.close();
    api =
        HttpApi.start(
            hub,
            List.of(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                new InetSocketAddress(elsewhere.get(), 0)),
            "s3cret",
            new Wallpapers(null));
    List<String> urls = api.urls();

    assertEquals(2, urls.size(), urls.toString());
    assertTrue(urls.get(0).startsWith("http://127.0.0.1:"), urls.toString());
    assertFalse(urls.get(0).contains("token"), "本机自己不会被索取秘密");
    assertTrue(urls.get(1).contains("?token=s3cret"), urls.toString());

    assertEquals(200, plainGet("http://127.0.0.1:" + api.port() + "/api/status"), "loopback");
    assertEquals(
        401,
        plainGet("http://" + elsewhere.get().getHostAddress() + ":" + api.port() + "/api/status"),
        "网络地址不带 token 时");
    assertEquals(
        200,
        plainGet(
            "http://"
                + elsewhere.get().getHostAddress()
                + ":"
                + api.port()
                + "/api/status?token=s3cret"),
        "带上 token 时");
  }

  /**
   * 本机拥有的一个非 loopback 地址，没有就是空。
   *
   * <p>故意不写死一个：这个测试的意义在于一个真实的第二地址会从服务器拿到不同的答案，而那是
   * 哪个地址取决于机器。
   */
  private static Optional<InetAddress> anAddressOtherThanLoopback() throws Exception {
    java.util.Enumeration<java.net.NetworkInterface> interfaces =
        java.net.NetworkInterface.getNetworkInterfaces();
    while (interfaces != null && interfaces.hasMoreElements()) {
      java.net.NetworkInterface candidate = interfaces.nextElement();
      if (!candidate.isUp() || candidate.isLoopback()) {
        continue;
      }
      java.util.Enumeration<InetAddress> addresses = candidate.getInetAddresses();
      while (addresses.hasMoreElements()) {
        InetAddress address = addresses.nextElement();
        if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
          return Optional.of(address);
        }
      }
    }
    return Optional.empty();
  }

  @Test
  void aRequestBodyTooLargeToBeASettingsFormIsRefused() throws Exception {
    HttpResponse<String> response =
        post("/api/config", "{\"system\":\"" + "x".repeat(1024 * 1024 + 64) + "\"}");

    assertEquals(413, response.statusCode(), response.body());
    assertTrue(response.body().contains("请求体大于 1048576 字节"), response.body());
  }

  @Test
  void aCrossOriginRequestCannotChangeState() throws Exception {
    // 这里堵上的攻击，是在这道检查存在之前复现出来的：另一个站点上的页面 POST 到
    // /api/auto-approve，loopback 和 Host 两道检查都通过了——浏览器跑在这台机器上，所以它的连接
    // *就是* loopback、它的 Host *就是* 127.0.0.1——于是自动批准真的被打开了。从那以后代理在跑
    // 命令之前就不再问了。
    assertFalse(json("/api/status").path("autoApprove").asBoolean());

    HttpResponse<String> refused =
        postFrom(HttpExchangeOrigin.EVIL, "/api/auto-approve", "{\"enabled\":true}");

    assertEquals(403, refused.statusCode(), refused.body());
    assertTrue(refused.body().contains("cross-origin"), refused.body());
    assertFalse(
        json("/api/status").path("autoApprove").asBoolean(),
        "守卫仍然开着：那个页面什么都没改变");
  }

  @Test
  void theSameOriginTheServerItselfServesIsAccepted() throws Exception {
    // ccj 提供的页面必须继续能用：它自己的 POST 会带 Origin。
    assertEquals(
        200, postFrom(HttpExchangeOrigin.SELF, "/api/auto-approve", "{\"enabled\":true}").statusCode());
    assertTrue(json("/api/status").path("autoApprove").asBoolean());
    postFrom(HttpExchangeOrigin.SELF, "/api/auto-approve", "{\"enabled\":false}");
  }

  @Test
  void everyLoopbackSpellingIsAcceptedAsSelf() throws Exception {
    // 打开 localhost 的用户和监听 127.0.0.1 的服务器，是同一台机器上的同一个人；拒绝其中一种
    // 写法会是一个读起来像安全功能的 bug。
    assertEquals(
        200,
        postFrom(HttpExchangeOrigin.LOCALHOST, "/api/auto-approve", "{\"enabled\":true}")
            .statusCode());
    postFrom(HttpExchangeOrigin.LOCALHOST, "/api/auto-approve", "{\"enabled\":false}");
  }

  @Test
  void anOpaqueOriginIsRefused() throws Exception {
    // 沙箱化的 iframe 或 file:// 页面会发送 Origin: null。它不是这台服务器，而它恰恰就是一个
    // 被注入的框架会有的形状。
    assertEquals(
        403,
        postFrom(HttpExchangeOrigin.OPAQUE, "/api/auto-approve", "{\"enabled\":true}").statusCode());
    assertFalse(json("/api/status").path("autoApprove").asBoolean());
  }

  @Test
  void aCallerThatSendsNoOriginIsStillServed() throws Exception {
    // curl、测试、CLI：都不是浏览器页面，而没有这个头，浏览器也不会把跨源请求递过来。拒绝它们
    // 会为了防一个不可能这样到达的调用者，而弄坏每一个正当的调用者。
    assertEquals(200, post("/api/auto-approve", "{\"enabled\":true}").statusCode());
    assertTrue(json("/api/status").path("autoApprove").asBoolean());
    post("/api/auto-approve", "{\"enabled\":false}");
    assertFalse(json("/api/status").path("autoApprove").asBoolean());
  }

  @Test
  void readingIsNotBlockedByOrigin() throws Exception {
    // 这道检查针对的是会改变东西的请求。GET 反正也漏不出跨源页面读得到的东西——浏览器会把响应
    // 扣下——所以拦它只有代价，没有收获。
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/api/status"))
                .header("Origin", HttpExchangeOrigin.EVIL.value())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode(), response.body());
  }

  @Test
  void aPageServedFromTheAddressItWasOpenedOnMayChangeState() throws Exception {
    // 手机这条，而且它曾经是坏的：从 tailnet 地址打开页面时，页面会把这个地址当作自己的 Origin
    // 发出来，而它既不是 loopback，也不是这台服务器能事先知道的任何东西——于是来自手机的每一个
    // 会改变状态的请求都被拒了。对着一个按手机接入方式启动的真实服务器量过：发消息、中止回合、
    // 保存设置、作答审批、上传图片全都返回 403，而同样一个请求只要带上 loopback 的 Origin 就被
    // 服务了。那张图片本身没有任何特别之处；它只是用户从手机上试的第一件事。
    start("t0ken");

    Raw ok = postByHand("100.64.0.1:6767", "http://100.64.0.1:6767", "/api/auto-approve",
        "{\"enabled\":true}", "t0ken");

    assertEquals(200, ok.status(), ok.body());
    assertTrue(json("/api/status").path("autoApprove").asBoolean(), "这个改动真的发生了");
    postByHand("100.64.0.1:6767", "http://100.64.0.1:6767", "/api/auto-approve",
        "{\"enabled\":false}", "t0ken");
  }

  @Test
  void anotherSiteIsStillRefusedWhenTheRequestNamesARealHost() throws Exception {
    // 让上面那条规则不成为一个漏洞的东西：另一个站点上的页面发的是它自己的源，而那并不是这个
    // 请求所瞄准的主机。
    start("t0ken");

    Raw refused = postByHand("100.64.0.1:6767", "https://evil.example", "/api/auto-approve",
        "{\"enabled\":true}", "t0ken");

    assertEquals(403, refused.status(), refused.body());
    assertFalse(json("/api/status").path("autoApprove").asBoolean());
  }

  @Test
  void aRebindingNameAgreesWithItselfAndStillGetsNowhere() throws Exception {
    // 经由一个解析到这里的名字，Origin 和 Host 都是 dead.beef——所以这个一致性故意不是唯一的
    // 守卫。没有 token 的服务器在 origin 检查跑之前就已经拒掉了非 loopback 的 Host；而有 token
    // 的服务器会因为这个请求没带凭证而拒掉它，而另一个站点上的页面拿不到它并不拥有的名字的
    // 凭证。
    Raw tokenless =
        postByHand("dead.beef:6767", "http://dead.beef:6767", "/api/auto-approve",
            "{\"enabled\":true}", null);
    assertEquals(403, tokenless.status(), tokenless.body());

    start("t0ken");
    Raw withoutToken =
        postByHand("dead.beef:6767", "http://dead.beef:6767", "/api/auto-approve",
            "{\"enabled\":true}", null);
    assertEquals(401, withoutToken.status(), withoutToken.body());
    assertFalse(json("/api/status").path("autoApprove").asBoolean());
  }

  /** 从 socket 上读到的响应：来的是什么就是什么，不掺客户端库的意见。 */
  private record Raw(int status, String body) {}

  /**
   * 一个手写的请求。
   *
   * <p>{@code Host} 在 {@code HttpClient} 里是受限的头——设不了，而一个 {@code Origin} 点名了
   * 自己所瞄准主机的请求，恰恰就是手机发出来的形状。没法让 Java HTTP 客户端给出那种形状，所以
   * 只能把字节写出来。
   */
  private Raw postByHand(String host, String origin, String path, String json, String token)
      throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    StringBuilder head = new StringBuilder()
        .append("POST ").append(path).append(" HTTP/1.1\r\n")
        .append("Host: ").append(host).append("\r\n")
        .append("Origin: ").append(origin).append("\r\n")
        .append("Content-Type: application/json\r\n")
        .append("Content-Length: ").append(body.length).append("\r\n");
    if (token != null) {
      head.append("Authorization: Bearer ").append(token).append("\r\n");
    }
    head.append("Connection: close\r\n\r\n");

    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), api.port())) {
      socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().write(body);
      socket.getOutputStream().flush();
      String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int firstSpace = raw.indexOf(' ');
      int status = Integer.parseInt(raw.substring(firstSpace + 1, firstSpace + 4));
      int split = raw.indexOf("\r\n\r\n");
      return new Raw(status, split < 0 ? raw : raw.substring(split + 4).strip());
    }
  }

  @Test
  void everyStateChangingEndpointRefusesAnotherSite() throws Exception {
    // 这道防御是按方法来的，不是按一张路径清单来的，正是为了让新端点没法忘记加入。这里检查的是
    // 今天已经存在的那些端点确实就是这样表现的。
    for (String path : List.of("/api/message", "/api/attachment", "/api/abort", "/api/compact",
        "/api/session", "/api/workspaces", "/api/workspace", "/api/config", "/api/auto-approve",
        "/api/approval")) {
      HttpResponse<String> refused = postFrom(HttpExchangeOrigin.EVIL, path, "{}");
      assertEquals(
          403,
          refused.statusCode(),
          "另一个站点上的页面绝不能碰到 " + path + ": " + refused.body());
    }
  }
}
