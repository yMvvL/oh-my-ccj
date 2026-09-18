package com.ccj.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FetchToolTest {

  @TempDir Path dir;

  @Test
  void aGetReturnsTheStatusLineAndTheBodyAsItArrived() throws Exception {
    String page = "<h1>release notes</h1>";

    try (Stub stub = Stub.start(send(200, "text/html", page))) {
      ToolResult result = fetch(stub.url("/notes"));

      assertFalse(result.error(), result.content());
      assertTrue(result.content().startsWith("HTTP 200 text/html ("), result.content());
      assertTrue(result.content().contains("(" + page.length() + " bytes)\n"), result.content());
      // Markup is left alone: stripping tags would be this tool guessing at the document.
      assertTrue(result.content().endsWith(page), result.content());
    }
  }

  @Test
  void aRefusedFetchDialsNothing() throws Exception {
    // Approval before the first byte leaves the machine, and "nothing was dialled" is the property
    // worth asserting: a prompt that arrives after the request has been made is not a gate.
    try (Stub stub = Stub.start(send(200, "text/plain", "should never be read"))) {
      ToolResult result =
          new FetchTool()
              .execute(
                  "{\"url\":\"" + stub.url("/secret") + "\"}",
                  new ToolContext(
                      dir, request -> com.ccj.agent.core.ApprovalAnswer.DENY, 4096));

      assertTrue(result.error(), result.content());
      assertEquals("rejected by user", result.content());
      assertEquals(0, stub.count(), "the request must not have been made");
    }
  }

  @Test
  void theApprovalNamesTheUrlBecauseThatIsWhatARuleMatches() throws Exception {
    try (Stub stub = Stub.start(send(200, "text/plain", "hello"))) {
      java.util.List<com.ccj.agent.core.ApprovalRequest> asked = new java.util.ArrayList<>();
      new FetchTool()
          .execute(
              "{\"url\":\"" + stub.url("/page") + "\"}",
              new ToolContext(
                  dir,
                  request -> {
                    asked.add(request);
                    return com.ccj.agent.core.ApprovalAnswer.ALLOW_ONCE;
                  },
                  4096));

      assertEquals(1, asked.size());
      assertEquals("fetch", asked.get(0).tool());
      assertEquals(stub.url("/page"), asked.get(0).command(), "the URL is the rule's subject");
      assertTrue(asked.get(0).detail().startsWith("GET http://127.0.0.1:"), asked.get(0).detail());
    }
  }

  @Test
  void fetchIsNotReadOnlyBecauseItLeavesTheMachine() {
    // The loop reads this flag to decide what may overlap and what must be approved; a fetch that
    // claimed to be read-only would run beside a write, unasked.
    assertFalse(new FetchTool().readOnly());
  }

  @Test
  void aNonHttpSchemeIsRefusedByItsNameAndNothingIsDialled() throws Exception {
    try (Stub stub = Stub.start(send(200, "text/plain", "a local secret"))) {
      String host = "127.0.0.1:" + stub.port();

      ToolResult file = fetch("file://" + host + "/etc/passwd");
      ToolResult ftp = fetch("ftp://" + host + "/pub");
      ToolResult script = fetch("javascript:alert(1)");

      assertTrue(file.error(), file.content());
      assertTrue(file.content().contains("file"), file.content());
      assertTrue(ftp.error(), ftp.content());
      assertTrue(ftp.content().contains("ftp"), ftp.content());
      assertTrue(script.error(), script.content());
      assertTrue(script.content().contains("javascript"), script.content());
      assertEquals(0, stub.count(), "a refused scheme must not be dialled");
    }
  }

  @Test
  void aBarePathIsRefusedAsHavingNoScheme() throws Exception {
    ToolResult result = fetch("/etc/passwd");

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains("/etc/passwd"), result.content());
    assertTrue(result.content().contains("no scheme"), result.content());
  }

  @Test
  void a404IsAnErrorNamingTheStatusAndTheUrl() throws Exception {
    try (Stub stub = Stub.start(send(404, "text/html", "<h1>not here</h1>"))) {
      String url = stub.url("/missing");

      ToolResult result = fetch(url);

      assertTrue(result.error(), result.content());
      assertTrue(result.content().contains("HTTP 404"), result.content());
      assertTrue(result.content().contains(url), result.content());
    }
  }

  @Test
  void aBodyLongerThanTheLimitIsCutAndSaysHowMuchWasOmitted() throws Exception {
    String page = "x".repeat(100);

    try (Stub stub = Stub.start(send(200, "text/plain", page))) {
      ToolResult result =
          new FetchTool()
              .execute(
                  "{\"url\":\"" + stub.url("/big") + "\",\"max_bytes\":32}", ToolContext.of(dir));

      assertFalse(result.error(), result.content());
      assertTrue(result.content().startsWith("HTTP 200 text/plain (32 bytes, truncated; "),
          result.content());
      assertTrue(result.content().contains("68 bytes omitted of 100"), result.content());
      assertFalse(result.content().contains("x".repeat(33)), result.content());
    }
  }

  @Test
  void aBodyWhoseSizeWasNotDeclaredIsStillCutAtTheLimit() throws Exception {
    try (Stub stub =
        Stub.start(
            exchange -> {
              byte[] bytes = "y".repeat(80).getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("content-type", "text/plain");
              // 0 rather than a length: the response is chunked and declares nothing, which is what
              // a streaming endpoint does.
              exchange.sendResponseHeaders(200, 0);
              try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
              }
            })) {
      ToolResult result =
          new FetchTool()
              .execute(
                  "{\"url\":\"" + stub.url("/chunked") + "\",\"max_bytes\":16}",
                  ToolContext.of(dir));

      assertFalse(result.error(), result.content());
      assertTrue(result.content().contains("(16 bytes, truncated;"), result.content());
      assertTrue(result.content().contains("size was not declared"), result.content());
      assertEquals(16, result.content().split("\n", 2)[1].length(), result.content());
    }
  }

  @Test
  void aNonTextContentTypeIsRefusedWithTheTypeNamed() throws Exception {
    try (Stub stub = Stub.start(send(200, "application/octet-stream", "not for a model"))) {
      ToolResult result = fetch(stub.url("/blob"));

      assertTrue(result.error(), result.content());
      assertTrue(result.content().contains("application/octet-stream"), result.content());
      assertFalse(result.content().contains("not for a model"), "the body must not be returned");
    }
  }

  @Test
  void aRequestToAClosedPortFailsNamingTheUrlWithoutAStackTrace() throws Exception {
    int port;
    try (ServerSocket free = new ServerSocket(0)) {
      port = free.getLocalPort();
    }
    String url = "http://127.0.0.1:" + port + "/nothing";

    ToolResult result = fetch(url);

    assertTrue(result.error(), result.content());
    assertTrue(result.content().contains(url), result.content());
    assertFalse(result.content().contains("\tat "), result.content());
    assertFalse(result.content().contains("Exception in thread"), result.content());
  }

  @Test
  void aRedirectIsFollowedToItsDestination() throws Exception {
    try (Stub stub =
        Stub.start(
            exchange -> {
              if (exchange.getRequestURI().getPath().equals("/old")) {
                exchange.getResponseHeaders().set("location", "/new");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
              } else {
                send(200, "text/plain", "the moved page").answer(exchange);
              }
            })) {
      ToolResult result = fetch(stub.url("/old"));

      assertFalse(result.error(), result.content());
      assertTrue(result.content().contains("the moved page"), result.content());
      assertEquals(2, stub.count());
    }
  }

  @Test
  void aRedirectToANonHttpSchemeIsRefusedByItsName() throws Exception {
    try (Stub stub =
        Stub.start(
            exchange -> {
              exchange.getResponseHeaders().set("location", "file:///etc/passwd");
              exchange.sendResponseHeaders(302, -1);
              exchange.close();
            })) {
      ToolResult result = fetch(stub.url("/away"));

      assertTrue(result.error(), result.content());
      assertTrue(result.content().contains("file"), result.content());
      assertTrue(result.content().contains("http and https"), result.content());
      assertEquals(1, stub.count(), "the hop must be refused before it is dialled");
    }
  }

  @Test
  void maxBytesAboveTheHardCeilingIsRefused() throws Exception {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> fetch("http://127.0.0.1:9/never", 2 * 1024 * 1024));

    assertTrue(thrown.getMessage().contains("1048576"), thrown.getMessage());
  }

  private ToolResult fetch(String url) throws Exception {
    return new FetchTool().execute("{\"url\":\"" + url + "\"}", ToolContext.of(dir));
  }

  private ToolResult fetch(String url, int maxBytes) throws Exception {
    return new FetchTool()
        .execute(
            "{\"url\":\"" + url + "\",\"max_bytes\":" + maxBytes + "}", ToolContext.of(dir));
  }

  /** A canned answer: status, content type and the body written with a declared length. */
  private static Responder send(int status, String contentType, String body) {
    return exchange -> {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("content-type", contentType);
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    };
  }

  /** What a stub answers one request with. */
  @FunctionalInterface
  private interface Responder {
    void answer(HttpExchange exchange) throws IOException;
  }

  /** A loopback server for one test, counting every request it is asked to answer. */
  private static final class Stub implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger requests;

    private Stub(HttpServer server, AtomicInteger requests) {
      this.server = server;
      this.requests = requests;
    }

    static Stub start(Responder responder) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      AtomicInteger requests = new AtomicInteger();
      server.createContext(
          "/",
          exchange -> {
            requests.incrementAndGet();
            responder.answer(exchange);
          });
      server.start();
      return new Stub(server, requests);
    }

    int port() {
      return server.getAddress().getPort();
    }

    String url(String path) {
      return "http://127.0.0.1:" + port() + path;
    }

    int count() {
      return requests.get();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
