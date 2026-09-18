package com.ccj.agent.web;

import com.ccj.agent.core.Json;
import com.ccj.agent.session.AttachmentStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The HTTP face of {@link AgentHub}: static page, JSON endpoints, and one long-lived SSE stream.
 *
 * <p>Two decisions worth knowing:
 *
 * <ul>
 *   <li>Requests are served by a cached pool. The default executor is a single thread, so one open
 *       SSE stream would freeze every other request.
 *   <li>The token, when set, is accepted from the query string and then remembered in an HttpOnly
 *       cookie. That keeps the browser's own {@code EventSource} and {@code fetch} calls working
 *       without any client-side token plumbing.
 * </ul>
 *
 * <p>It is not a public server: nothing here is designed to face the internet, and the CLI refuses
 * to bind a non-loopback address without a token.
 */
public final class HttpApi implements AutoCloseable {

  private static final String TOKEN_COOKIE = "ccj_token";
  private static final long HEARTBEAT_MILLIS = 15_000;

  private final AgentHub hub;
  /** One server per bind address — the same routing, the same hub, two ways in. */
  private final List<HttpServer> servers;
  private final String token;
  /** The pictures the page may use as a background; an absent directory means none. */
  private final Wallpapers wallpapers;
  /**
   * One virtual thread per exchange.
   *
   * <p>An SSE stream keeps its thread for as long as the page is open, and a browser happily keeps
   * one open for hours. On platform threads that is one OS thread per open tab — a cost that makes
   * "one thread per connection" a liability instead of a design, and one a stalled client would pay
   * for on behalf of everyone. Virtual threads make it cheap, and a blocking socket write inside one
   * parks a continuation rather than an OS thread.
   */
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

  private HttpApi(AgentHub hub, List<InetSocketAddress> binds, String token, Wallpapers wallpapers)
      throws IOException {
    this.hub = hub;
    this.token = token == null || token.isBlank() ? null : token.strip();
    this.wallpapers = wallpapers == null ? new Wallpapers(null) : wallpapers;
    List<HttpServer> created = new ArrayList<>();
    try {
      for (InetSocketAddress bind : binds) {
        // One port, several addresses: asking for port 0 means "any free port", and it means one free
        // port — a second address bound to a second arbitrary number would give the same page two
        // different URLs, which is not what "serve it here and there" can mean.
        InetSocketAddress resolved =
            bind.getPort() == 0 && !created.isEmpty()
                ? new InetSocketAddress(bind.getAddress(), created.get(0).getAddress().getPort())
                : bind;
        created.add(serverOn(resolved));
      }
    } catch (IOException e) {
      // A half-bound server would answer on one address and refuse on another, which is a state
      // nobody asked for: the servers made so far are closed and the failure is reported.
      created.forEach(server -> server.stop(0));
      throw e;
    }
    this.servers = List.copyOf(created);
  }

  public static HttpApi start(AgentHub hub, InetSocketAddress bind, String token) throws IOException {
    return start(hub, bind, token, new Wallpapers(null));
  }

  public static HttpApi start(
      AgentHub hub, InetSocketAddress bind, String token, Wallpapers wallpapers)
      throws IOException {
    return start(hub, List.of(bind), token, wallpapers);
  }

  /**
   * Serves the same page on every address given.
   *
   * <p>More than one because the two ways in are genuinely different: loopback is the machine the
   * user is sitting at, and the tailnet address is their phone. Binding a wildcard instead would
   * also put the port on the café wifi, so the addresses are named one by one.
   */
  public static HttpApi start(
      AgentHub hub, List<InetSocketAddress> binds, String token, Wallpapers wallpapers)
      throws IOException {
    HttpApi api = new HttpApi(hub, binds, token, wallpapers);
    api.servers.forEach(HttpServer::start);
    return api;
  }

  private HttpServer serverOn(InetSocketAddress bind) throws IOException {
    HttpServer server = HttpServer.create(bind, 0);
    server.setExecutor(workers);
    server.createContext("/", exchange -> route(exchange));
    return server;
  }

  /** The port actually bound — useful when the caller asked for 0. */
  public int port() {
    return servers.get(0).getAddress().getPort();
  }

  /**
   * Every address a human can click, in the order they were bound, each carrying the token only when
   * that address needs one.
   *
   * <p>Loopback is not "another device": a request that arrives there was made on this machine, so
   * the token gates the network and not the user's own browser. That is what lets {@code ccj} keep
   * opening {@code http://127.0.0.1:6767} with nothing attached while the same server asks the phone
   * for the secret.
   */
  public List<String> urls() {
    List<String> urls = new ArrayList<>(servers.size());
    for (HttpServer server : servers) {
      String host = server.getAddress().getAddress().isAnyLocalAddress()
          ? "127.0.0.1"
          : server.getAddress().getHostString();
      if (host.contains(":") && !host.startsWith("[")) {
        host = "[" + host + "]";
      }
      String base = "http://" + host + ":" + server.getAddress().getPort() + "/";
      urls.add(needsToken(server.getAddress()) && token != null ? base + "?token=" + token : base);
    }
    return urls;
  }

  /** A URL a human can click, carrying the token when one is required. */
  public String url() {
    return urls().get(0);
  }

  /** True for an address reachable from somewhere other than this machine. */
  private static boolean needsToken(InetSocketAddress address) {
    return !address.getAddress().isLoopbackAddress();
  }

  @Override
  public void close() {
    servers.forEach(server -> server.stop(0));
    workers.shutdownNow();
  }

  private void route(HttpExchange exchange) throws IOException {
    try {
      // Two ways in, and they are reported differently on purpose. A caller who has not proved the
      // token is told *that* when there is a token to prove — it is the thing they can fix. A caller
      // to a tokenless server is answering for its Host instead, and the message says so. A caller who
      // did prove the token is asked nothing else: the secret is the whole price of a network bind.
      boolean provedToken = token != null && tokenPresented(exchange);
      if (!provedToken && !permitted(exchange)) {
        if (token != null) {
          unauthorized(exchange);
        } else {
          error(
              exchange,
              403,
              "Host '"
                  + exchange.getRequestHeaders().getFirst("Host")
                  + "' is not a loopback address; a browser can reach this server from any page it"
                  + " visits, so either open it as localhost/127.0.0.1 or start ccj with a token");
        }
        return;
      }
      String path = exchange.getRequestURI().getPath();
      // A cross-origin check for anything that changes state, and it is the one defence the loopback
      // and Host checks cannot provide.
      //
      // Those two answer "did this arrive on the machine itself", which a page in the user's browser
      // satisfies perfectly: the browser runs on the machine, so its connection is loopback and its
      // Host is 127.0.0.1. What they cannot see is *which page* caused it. Measured before this
      // existed: a POST to /api/auto-approve carrying `Origin: https://evil.example` was served, and
      // it really did switch auto-approval on — after which the agent stops asking before it runs
      // commands. That is the whole attack: visit a page, and the page turns off the guard.
      //
      // Checked only for state-changing requests, so a GET that leaks nothing is unaffected, and only
      // when the browser sent an Origin at all — a curl or a script sends none, and treating that as
      // hostile would break every legitimate caller to protect against one that a browser would not
      // let through anyway.
      if (stateChanging(exchange) && !sameOrigin(exchange)) {
        error(
            exchange,
            403,
            "cross-origin request refused: a page on "
                + exchange.getRequestHeaders().getFirst("Origin")
                + " must not change this server's state. A browser cannot reach ccj from another"
                + " site, and this is what enforces that rather than assuming it.");
        return;
      }
      // One prefix before the table below: a wallpaper's name is part of the path, and the name is
      // whatever the directory happens to hold, so there is no case label that could match it.
      if (path.startsWith("/wallpaper/")) {
        wallpaper(exchange, path.substring("/wallpaper/".length()));
        return;
      }
      switch (path) {
        case "/", "/index.html" -> page(exchange);
        case "/app.js" -> staticResource(exchange, "/web/app.js", "text/javascript; charset=utf-8");
        case "/style.css" -> staticResource(exchange, "/web/style.css", "text/css; charset=utf-8");
        case "/theme.css" -> staticResource(exchange, "/web/theme.css", "text/css; charset=utf-8");
        case "/api/wallpapers" -> wallpapers(exchange);
        case "/api/status" -> get(exchange, hub.status());
        case "/api/sessions" -> sessions(exchange);
        case "/api/workspaces/browse" -> browse(exchange);
        case "/api/history" -> get(exchange, hub.historyJson());
        case "/api/events" -> events(exchange);
        case "/api/message" -> message(exchange);
        case "/api/attachment" -> attachment(exchange);
        case "/api/abort" -> abort(exchange);
        case "/api/compact" -> compact(exchange);
        case "/api/approval" -> approval(exchange);
        case "/api/auto-approve" -> autoApprove(exchange);
        case "/api/session" -> session(exchange);
        case "/api/workspaces" -> workspaces(exchange);
        case "/api/workspace" -> workspace(exchange);
        case "/api/models" -> models(exchange);
        case "/api/providers" -> providers(exchange);
        case "/api/config" -> config(exchange);
        case "/api/config/test" -> configTest(exchange);
        default -> error(exchange, 404, "no such endpoint: " + path);
      }
    } catch (IllegalArgumentException e) {
      error(exchange, 400, e.getMessage());
    } catch (PayloadTooLargeException e) {
      error(exchange, 413, e.getMessage());
    } catch (IllegalStateException e) {
      error(exchange, 409, e.getMessage());
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      error(exchange, 500, message);
    }
  }

  // ------------------------------------------------------------------ endpoints

  private void message(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "POST required");
      return;
    }
    String text = Json.parse(readBody(exchange)).path("text").asText("");
    if (text.isBlank()) {
      error(exchange, 400, "field 'text' is required");
      return;
    }
    if (!hub.submit(text)) {
      error(exchange, 409, "a turn is already running");
      return;
    }
    respond(exchange, 202, Json.object().put("accepted", true));
  }

  /**
   * One picture, and the turn it becomes.
   *
   * <p>The body is the image itself rather than a JSON envelope with base64 in it, so the bytes are
   * never encoded on the client and re-decoded here: the upload is one request with the picture in
   * it, and the name it is to be stored under is the {@code name} query parameter.
   *
   * <p>Reading is bounded twice over, and both times before the bytes are held: a declared length
   * over the limit is refused outright, and the read itself stops at the limit so a body that lies
   * about its length — or streams with no length at all — cannot turn the limit into a suggestion.
   * The refusal is a 413 with the size in it, which is what a client can act on.
   */
  private void attachment(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "POST required");
      return;
    }
    byte[] bytes;
    try {
      bytes = AttachmentStore.readBounded(exchange.getRequestBody(), declaredLength(exchange));
    } catch (IOException e) {
      error(exchange, 413, e.getMessage());
      return;
    }
    respond(exchange, 202, hub.describePicture(queryParam(exchange, "name"), bytes));
  }

  /** {@code Content-Length} when the client sent one, or -1 for a chunked body. */
  private static long declaredLength(HttpExchange exchange) {
    try {
      String header = exchange.getRequestHeaders().getFirst("Content-Length");
      return header == null || header.isBlank() ? -1 : Long.parseLong(header.strip());
    } catch (NumberFormatException e) {
      // A length that does not parse is no worse than one that was not sent: the read is bounded
      // either way, and a refusal here would reject a body that is perfectly readable.
      return -1;
    }
  }

  private void abort(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "POST required");
      return;
    }
    // The session is optional: with no id this is the conversation on screen, which is what the
    // composer's stop button means. An id stops a turn running in a conversation the user is not
    // looking at, which is the case the tree's running marker leads them to.
    String id = queryParam(exchange, "id");
    boolean aborted = id == null || id.isBlank() ? hub.abort() : hub.abort(id);
    respond(exchange, 200, Json.object().put("aborted", aborted));
  }

  /**
   * Compacts the session on screen. Answers with what was replaced and what it cost, and the
   * conversation itself arrives as a {@code compacted} event — the page learns its transcript changed
   * the same way it learns about everything else.
   */
  private void compact(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "POST required");
      return;
    }
    respond(exchange, 200, hub.compact());
  }

  private void approval(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "POST required");
      return;
    }
    JsonNode body = Json.parse(readBody(exchange));
    String id = body.path("id").asText("");
    boolean allow = body.path("allow").asBoolean(false);
    boolean remember = body.path("remember").asBoolean(false);
    String answer = body.path("answer").asText(null);
    if (id.isBlank()) {
      error(exchange, 400, "field 'id' is required");
      return;
    }
    if (!hub.resolveApproval(id, AgentHub.answerOf(allow, remember, answer))) {
      error(exchange, 404, "no pending approval with id " + id);
      return;
    }
    respond(exchange, 200, Json.object().put("resolved", true).put("allow", allow));
  }

  private void workspaces(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if ("GET".equals(method)) {
      respond(exchange, 200, hub.workspacesJson());
      return;
    }
    if (!"POST".equals(method)) {
      error(exchange, 405, "GET or POST required");
      return;
    }
    JsonNode body = Json.parse(readBody(exchange));
    String path = body.path("path").asText("");
    String name = body.path("name").asText("");
    if (path.isBlank()) {
      // Checked here, not left to Path.of(""), which is the process's own directory and would look
      // like a perfectly good folder to add.
      error(exchange, 400, "a workspace needs a directory");
      return;
    }
    // `name` is optional on purpose: a browser that picked a directory has already said what the
    // workspace is called, and the folder names it. A request that carries a name still wins.
    respond(
        exchange,
        200,
        name.isBlank() ? hub.addWorkspace(Path.of(path)) : hub.addWorkspace(name, path));
  }

  private void workspace(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if ("POST".equals(method)) {
      JsonNode body = Json.parse(readBody(exchange));
      respond(exchange, 200, hub.switchWorkspace(body.path("name").asText("")));
      return;
    }
    if ("DELETE".equals(method)) {
      respond(exchange, 200, hub.removeWorkspace(queryParam(exchange, "name")));
      return;
    }
    error(exchange, 405, "POST or DELETE required");
  }

  private void sessions(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if ("GET".equals(method)) {
      String workspace = queryParam(exchange, "workspace");
      ObjectNode body = Json.object();
      body.put("workspace", workspace == null ? "" : workspace);
      body.set("sessions", hub.sessionsJson(workspace));
      respond(exchange, 200, body);
      return;
    }
    if ("DELETE".equals(method)) {
      respond(exchange, 200, hub.deleteAllSessions(queryParam(exchange, "workspace")));
      return;
    }
    error(exchange, 405, "GET or DELETE required");
  }

  /**
   * Opens the desktop's folder chooser. A browser cannot do this itself — the web File System
   * Access API deliberately hides absolute paths — but the process serving the page is sitting on
   * the same machine, so it can ask the desktop directly.
   */
  private void browse(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "POST required");
      return;
    }
    java.io.IOException[] failure = new java.io.IOException[1];
    java.util.Optional<java.nio.file.Path> chosen = hub.chooseFolder(failure);
    if (failure[0] != null) {
      error(exchange, 400, failure[0].getMessage());
      return;
    }
    if (chosen.isEmpty()) {
      respond(exchange, 200, Json.object().put("cancelled", true));
      return;
    }
    respond(exchange, 200, Json.object().put("path", chosen.get().toString()));
  }

  private void models(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if ("GET".equals(method)) {
      respond(exchange, 200, hub.modelsJson());
      return;
    }
    if ("POST".equals(method)) {
      JsonNode body = Json.parse(readBody(exchange));
      respond(
          exchange,
          200,
          hub.addModel(body.path("provider").asText(""), body.path("model").asText("")));
      return;
    }
    if ("DELETE".equals(method)) {
      respond(
          exchange,
          200,
          hub.removeModel(queryParam(exchange, "provider"), queryParam(exchange, "model")));
      return;
    }
    error(exchange, 405, "GET, POST or DELETE required");
  }

  private void providers(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if ("POST".equals(method)) {
      respond(exchange, 200, hub.addProvider(Json.parse(readBody(exchange))));
      return;
    }
    if ("DELETE".equals(method)) {
      respond(exchange, 200, hub.removeProvider(queryParam(exchange, "name")));
      return;
    }
    if ("PUT".equals(method)) {
      respond(
          exchange,
          200,
          hub.addBuiltInProvider(Json.parse(readBody(exchange)).path("name").asText("")));
      return;
    }
    if ("GET".equals(method)) {
      respond(exchange, 200, hub.modelsJson());
      return;
    }
    error(exchange, 405, "GET, POST, PUT or DELETE required");
  }

  private void config(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if ("GET".equals(method)) {
      respond(exchange, 200, hub.configJson());
      return;
    }
    if (!"POST".equals(method)) {
      error(exchange, 405, "GET or POST required");
      return;
    }
    // Validation failures are IllegalArgumentException, which route() answers with 400.
    respond(exchange, 200, hub.applyConfig(Json.parse(readBody(exchange))));
  }

  private void configTest(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "POST required");
      return;
    }
    respond(exchange, 200, hub.testConfiguration(Json.parse(readBody(exchange))));
  }

  private void autoApprove(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "POST required");
      return;
    }
    JsonNode body = Json.parse(readBody(exchange));
    if (!body.has("enabled")) {
      error(exchange, 400, "field 'enabled' is required");
      return;
    }
    hub.setAutoApprove(body.path("enabled").asBoolean(false));
    respond(exchange, 200, Json.object().put("autoApprove", hub.autoApprove()));
  }

  private void session(HttpExchange exchange) throws IOException {
    if ("DELETE".equals(exchange.getRequestMethod())) {
      String id = queryParam(exchange, "id");
      if (id == null || id.isBlank()) {
        error(exchange, 400, "query parameter 'id' is required");
        return;
      }
      respond(exchange, 200, hub.deleteSession(queryParam(exchange, "workspace"), id));
      return;
    }
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "POST or DELETE required");
      return;
    }
    JsonNode body = Json.parse(readBody(exchange));
    String action = body.path("action").asText("");
    switch (action) {
      case "new" -> hub.newSession();
      case "resume" -> hub.resumeSession(body.path("id").asText(""));
      default -> {
        error(exchange, 400, "action must be 'new' or 'resume'");
        return;
      }
    }
    respond(exchange, 200, hub.status());
  }

  // ------------------------------------------------------------------ SSE

  private void events(HttpExchange exchange) throws IOException {
    Headers headers = exchange.getResponseHeaders();
    headers.add("Content-Type", "text/event-stream; charset=utf-8");
    headers.add("Cache-Control", "no-cache, no-transform");
    headers.add("Connection", "keep-alive");
    exchange.sendResponseHeaders(200, 0);

    OutputStream out = exchange.getResponseBody();
    long lastEventId = lastEventId(exchange);
    SseClient client = new SseClient(out, lastEventId);
    List<AgentHub.Event> missed = hub.subscribe(client);
    try {
      // The replay buffer exists for one purpose: filling the gap a reconnecting page missed. A
      // fresh connection has no gap — it gets the conversation from /api/history, so replaying the
      // buffer here would render every recent event a second time.
      if (lastEventId > 0) {
        for (AgentHub.Event event : missed) {
          if (event.id() > client.lastSent()) {
            client.send(event);
          }
        }
      }
      // A browser that just connected has no state yet, and the contract promises a status event.
      hub.publishStatus();
      // This thread owns the socket for the life of the stream: it writes what the queue holds and
      // pings when the queue stays empty. Nothing else ever writes here, which is what keeps a
      // browser that stops reading from blocking whoever published.
      while (!client.closed()) {
        AgentHub.Event event = client.take(HEARTBEAT_MILLIS);
        if (client.closed()) {
          break;
        }
        if (event == null) {
          client.heartbeat();
          continue;
        }
        client.send(event);
        for (AgentHub.Event queued = client.poll(); queued != null; queued = client.poll()) {
          client.send(queued);
        }
      }
    } catch (InterruptedException e) {
      // The only thing that interrupts this thread is its own client overflowing, and the flag was
      // set by this class for exactly this wake-up: it is cleared rather than propagated, because
      // the thread goes back into the pool and a stale interrupt there would break the next request.
    } finally {
      hub.unsubscribe(client);
      exchange.close();
    }
  }

  private static long lastEventId(HttpExchange exchange) {
    String raw = exchange.getRequestHeaders().getFirst("Last-Event-ID");
    if (raw == null || raw.isBlank()) {
      return 0;
    }
    try {
      return Long.parseLong(raw.strip());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * A subscriber that owns one socket.
   *
   * <p>Events are queued by whoever publishes them and written by the thread serving the connection.
   * Writing straight from {@code publish} would put a blocking socket write inside the hub's replay
   * monitor: one browser that stops reading — a suspended tab, a stalled network — would then wedge
   * the agent's turn thread, and with it every other client. Here a client that stops reading fills
   * only its own queue.
   *
   * <p>That queue is bounded on purpose: a client that may never come back must not be able to grow
   * the heap without limit. An overflowing client is dropped, and the page reconnects and re-reads
   * its history from {@code /api/history}, which it does after every disconnect anyway.
   */
  private static final class SseClient implements Consumer<AgentHub.Event> {

    /** Events one slow client may fall behind before it is dropped. */
    private static final int QUEUE_LIMIT = 4096;

    private final OutputStream out;
    private final BlockingQueue<AgentHub.Event> pending = new ArrayBlockingQueue<>(QUEUE_LIMIT);
    private volatile Thread reader;
    private volatile boolean closed;
    private long lastSent;

    SseClient(OutputStream out, long lastSent) {
      this.out = out;
      this.lastSent = lastSent;
    }

    /** Called on the publishing thread: this never blocks and never writes. */
    @Override
    public void accept(AgentHub.Event event) {
      if (closed) {
        return;
      }
      if (pending.offer(event)) {
        return;
      }
      // Too far behind to catch up: drop the client and wake its thread so it stops waiting.
      closed = true;
      Thread waiting = reader;
      if (waiting != null) {
        waiting.interrupt();
      }
    }

    /** Waits for the next event, or for {@code timeoutMillis} to pass with nothing to send. */
    AgentHub.Event take(long timeoutMillis) throws InterruptedException {
      reader = Thread.currentThread();
      try {
        return pending.poll(timeoutMillis, TimeUnit.MILLISECONDS);
      } finally {
        reader = null;
      }
    }

    /** Whatever else is already queued, without waiting. */
    AgentHub.Event poll() {
      return pending.poll();
    }

    void send(AgentHub.Event event) {
      write(
          "id: "
              + event.id()
              + "\ndata: "
              + Json.write(event.payload())
              + "\n\n");
      if (!closed) {
        lastSent = Math.max(lastSent, event.id());
      }
    }

    /**
     * Keeps the connection visibly alive.
     *
     * <p>A real data frame, not the {@code : ping} comment an SSE server usually sends. A comment is
     * not delivered to the page at all — {@code EventSource} dispatches only frames with a
     * {@code data} field — so the page's {@code lastEventAt} never moved and its 20-second
     * "the stream is dead" timer fired on a perfectly healthy connection. Measured: a turn waiting on
     * an approval showed a prompt that flickered while the page reconnected underneath it, and the
     * request looked like it had been withdrawn when it was still open.
     *
     * <p>Sent with no {@code id}, so it cannot disturb the resume position the real events maintain,
     * and named {@code ping} rather than the default message type: the page ignores it in its switch,
     * so the only thing it does is prove the connection is alive — which is exactly the job.
     */
    void heartbeat() {
      write("event: ping\ndata: {}\n\n");
    }

    private void write(String frame) {
      if (closed) {
        return;
      }
      try {
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
      } catch (IOException e) {
        closed = true;
      }
    }

    boolean closed() {
      return closed;
    }

    long lastSent() {
      return lastSent;
    }
  }

  // ------------------------------------------------------------------ plumbing

  private void page(HttpExchange exchange) throws IOException {
    if (token != null && token.equals(queryParam(exchange, "token"))) {
      exchange
          .getResponseHeaders()
          .add("Set-Cookie", TOKEN_COOKIE + "=" + token + "; HttpOnly; SameSite=Strict; Path=/");
    }
    staticResource(exchange, "/web/index.html", "text/html; charset=utf-8");
  }

  private void staticResource(HttpExchange exchange, String resource, String contentType)
      throws IOException {
    try (InputStream in = HttpApi.class.getResourceAsStream(resource)) {
      if (in == null) {
        error(exchange, 404, "missing resource " + resource + " (was the jar built before it was added?)");
        return;
      }
      byte[] bytes = in.readAllBytes();
      exchange.getResponseHeaders().add("Content-Type", contentType);
      exchange.getResponseHeaders().add("Cache-Control", "no-store");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  /**
   * The names the page may rotate through. An empty list is the honest answer when there is no
   * directory to read: the control that uses this hides itself rather than offering nothing.
   */
  private void wallpapers(HttpExchange exchange) throws IOException {
    ObjectNode body = Json.object();
    var names = body.putArray("wallpapers");
    for (String name : wallpapers.names()) {
      names.add(name);
    }
    byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /**
   * One wallpaper's bytes, streamed rather than read into memory: a 4K picture is several
   * megabytes, and none of it has a reason to pass through the heap on its way to the socket. The
   * name is checked by {@link Wallpapers#resolve}, which is what keeps this endpoint from becoming
   * a way to read any file on the machine.
   */
  private void wallpaper(HttpExchange exchange, String name) throws IOException {
    Optional<Path> file = wallpapers.resolve(name);
    if (file.isEmpty()) {
      error(exchange, 404, "no such wallpaper");
      return;
    }
    exchange
        .getResponseHeaders()
        .add("Content-Type", wallpapers.contentType(file.get()).orElse("application/octet-stream"));
    // A rotation revisits the same pictures, and they are files that rarely change: a few minutes of
    // caching is what keeps switching backgrounds from re-reading a megabyte every time.
    exchange.getResponseHeaders().add("Cache-Control", "private, max-age=300");
    exchange.sendResponseHeaders(200, Files.size(file.get()));
    try (OutputStream out = exchange.getResponseBody()) {
      Files.copy(file.get(), out);
    }
  }

  private void get(HttpExchange exchange, ObjectNode body) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "GET required");
      return;
    }
    respond(exchange, 200, body);
  }

  private void respond(HttpExchange exchange, int status, ObjectNode body) throws IOException {
    byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private void error(HttpExchange exchange, int status, String message) throws IOException {
    respond(exchange, status, Json.object().put("error", message == null ? "failed" : message));
  }

  /** Rejected request body, so a huge upload cannot be turned into an OutOfMemoryError. */
  private static final class PayloadTooLargeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PayloadTooLargeException(String message) {
      super(message);
    }
  }

  /** Everything the UI posts is a small form or a message; a megabyte is already generous. */
  private static final int MAX_BODY_BYTES = 1024 * 1024;

  private String readBody(HttpExchange exchange) throws IOException {
    byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
    if (body.length > MAX_BODY_BYTES) {
      throw new PayloadTooLargeException(
          "request body is larger than " + MAX_BODY_BYTES + " bytes");
    }
    return new String(body, StandardCharsets.UTF_8);
  }

  private boolean tokenPresented(HttpExchange exchange) {
    if (token == null) {
      return false; // there is nothing to present, which is not the same as having presented it
    }
    if (token.equals(queryParam(exchange, "token"))) {
      return true;
    }
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    if (header != null && header.startsWith("Bearer ") && token.equals(header.substring(7).strip())) {
      return true;
    }
    List<String> cookies = exchange.getRequestHeaders().get("Cookie");
    if (cookies != null) {
      for (String cookie : cookies) {
        for (String pair : cookie.split(";")) {
          int eq = pair.indexOf('=');
          if (eq > 0
              && TOKEN_COOKIE.equals(pair.substring(0, eq).strip())
              && token.equals(pair.substring(eq + 1).strip())) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * True when a request that did <em>not</em> prove the token may be served anyway: it arrived on the
   * loopback interface <em>and</em> it names a loopback Host.
   *
   * <p>This is the local case, and it is deliberately the only one. Loopback is not a boundary
   * against a browser: any page the user visits can POST to 127.0.0.1 without a preflight, and DNS
   * rebinding lets that page read the answers too — which is why the Host half is checked and not
   * assumed, a name like {@code dead.beef} being exactly the attack. An address the page was reached
   * on being loopback says the request came from this machine; the Host header says it was aimed at
   * this server rather than at a name that happens to resolve here.
   *
   * <p>So a token gates the <em>network</em>, not the user's own browser: {@code http://127.0.0.1:6767}
   * keeps working with nothing attached while the same server asks the phone for the secret. The
   * alternative — a token on every request — would mean the machine's own user has to carry a secret
   * to reach a server they started.
   */
  /**
   * True for a request that can change something.
   *
   * <p>Methods rather than a list of paths: a new endpoint must not have to remember to opt in, and
   * the ones that read are exactly the ones the protocol says do not change state.
   */
  private static boolean stateChanging(HttpExchange exchange) {
    String method = exchange.getRequestMethod();
    return !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
  }

  /**
   * True when the request did not come from another site, or did not say where it came from.
   *
   * <p>An absent {@code Origin} counts as same-origin. Browsers attach it to cross-origin requests and
   * to every state-changing request they make at all, so its absence means the caller is not a browser
   * page — a script, a test, the CLI. Refusing those would break every legitimate caller to defend
   * against one the browser would not deliver in the first place.
   *
   * <p>When it is present it has to name this server, in one of the two ways a page can honestly do
   * that: a loopback address (the machine the server runs on), or the host the request was aimed at —
   * {@code Origin} and {@code Host} agreeing. Both are needed. Only the first was here at the start,
   * and it made every state-changing request from a phone on the tailnet a 403: that page is served
   * from the tailnet address, so its {@code Origin} names the tailnet address, which is neither
   * loopback nor anything the check would accept — measured from a phone-shaped request
   * ({@code Origin: http://100.72.92.41:6767}, the same host the page came from) against a real
   * server, where sending a message, aborting a turn, saving settings, answering an approval and
   * uploading a picture were all refused. The address the page was loaded from is the one honest
   * answer to "which page is this", and it is not knowable in advance — the server binds whatever
   * addresses the user named, and the tailnet one is theirs.
   *
   * <p>Comparing against {@code Host} rather than accepting any host is what keeps this from being a
   * hole. A page on another site sends its own origin, which is not the host the request is aimed at,
   * so it is still refused. A page that reaches this server through a rebinding name sends that name
   * in both headers and would agree with itself — which is why the agreement is not the only guard:
   * a tokenless server has already refused the request for naming a non-loopback {@code Host} before
   * this runs, and a token server has already refused it for arriving without the token. What is left
   * is a page served from the very address this server answers on, which is the same machine, the
   * same user, and the same trust the loopback case has always granted.
   *
   * <p>A port mismatch is allowed because the user may have started ccj on a different port than the
   * page they first opened, and every one of these addresses is the machine itself.
   */
  private static boolean sameOrigin(HttpExchange exchange) {
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin == null || origin.isBlank()) {
      return true;
    }
    String clean = origin.strip();
    // "null" is what a sandboxed iframe or a file:// page sends. It is not this server.
    if ("null".equalsIgnoreCase(clean)) {
      return false;
    }
    try {
      java.net.URI parsed = java.net.URI.create(clean);
      String host = parsed.getHost();
      if (host == null) {
        return false;
      }
      if (loopbackHost(host)) {
        // The same rule the Host header is held to, on purpose: one notion of "this machine" rather
        // than two that can disagree, and the loopback-literal check that refuses to resolve a name is
        // exactly what is wanted here too.
        return true;
      }
      // Otherwise the page has to be served from the host this request was aimed at, which is what a
      // user's own bookmark to the tailnet address sends.
      String aimedAt = hostName(exchange.getRequestHeaders().getFirst("Host"));
      return aimedAt != null && aimedAt.equalsIgnoreCase(host);
    } catch (IllegalArgumentException e) {
      // An Origin that does not parse is not this server's.
      return false;
    }
  }

  private boolean permitted(HttpExchange exchange) {
    return loopbackPeer(exchange) && loopbackHost(exchange.getRequestHeaders().getFirst("Host"));
  }

  /** True when this connection was accepted on a loopback address. */
  private static boolean loopbackPeer(HttpExchange exchange) {
    InetSocketAddress peer = exchange.getRemoteAddress();
    return peer != null && peer.getAddress() != null && peer.getAddress().isLoopbackAddress();
  }

  /** The host part of a {@code Host} header, without its port, or null when there is none. */
  private static String hostName(String header) {
    if (header == null || header.isBlank()) {
      return null;
    }
    String host = header.strip();
    if (host.startsWith("[")) {
      int end = host.indexOf(']');
      return end < 0 ? null : host.substring(1, end);
    }
    int colon = host.lastIndexOf(':');
    return colon == host.indexOf(':') && colon >= 0 ? host.substring(0, colon) : host;
  }

  /**
   * True for {@code localhost} and for loopback <em>literals</em>.
   *
   * <p>A dotted name is never resolved here: {@code dead.beef} is a perfectly legal hostname, and
   * resolving attacker-chosen names is the whole point of a rebinding attack. Only four decimal
   * groups in {@code 127.0.0.0/8}, and literal IPv6 (which cannot be a DNS name), are accepted.
   */
  private static boolean loopbackHost(String header) {
    String host = hostName(header);
    if (host == null || host.isEmpty()) {
      return false;
    }
    if (host.equalsIgnoreCase("localhost")) {
      return true;
    }
    if (host.indexOf(':') >= 0) {
      try {
        return InetAddress.getByName(host).isLoopbackAddress();
      } catch (UnknownHostException e) {
        return false;
      }
    }
    String[] groups = host.split("\\.", -1);
    if (groups.length != 4 || !groups[0].equals("127")) {
      return false;
    }
    for (String group : groups) {
      if (group.isEmpty() || group.length() > 3) {
        return false;
      }
      for (int i = 0; i < group.length(); i++) {
        if (group.charAt(i) < '0' || group.charAt(i) > '9') {
          return false;
        }
      }
    }
    return true;
  }

  private void unauthorized(HttpExchange exchange) throws IOException {
    respond(
        exchange,
        401,
        Json.object()
            .put(
                "error",
                "this server requires a token; open the URL printed by ccj (it carries ?token=…),"
                    + " or send Authorization: Bearer <token>"));
  }

  private static String queryParam(HttpExchange exchange, String name) {
    String query = exchange.getRequestURI().getRawQuery();
    if (query == null || query.isBlank()) {
      return null;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && name.equals(pair.substring(0, eq))) {
        return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }
}
