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
 * {@link AgentHub} 的 HTTP 面：静态页面、JSON 端点，以及一条长命的 SSE 流。
 *
 * <p>两个值得知道的决定：
 *
 * <ul>
 *   <li>请求由一个带缓存的线程池服务。默认执行器是单线程，所以一条打开的 SSE 流会冻住其他所有请求。
 *   <li>token 一旦设置，就从查询字符串里接受，随后记进一个 HttpOnly cookie。这样浏览器自己的
 *       {@code EventSource} 和 {@code fetch} 调用照常工作，客户端不需要任何 token 管道。
 * </ul>
 *
 * <p>它不是公开服务器：这里没有任何东西是按面向互联网设计的，而 CLI 拒绝在没有 token 的情况下绑定
 * 非环回地址。
 */
public final class HttpApi implements AutoCloseable {

  private static final String TOKEN_COOKIE = "ccj_token";
  private static final long HEARTBEAT_MILLIS = 15_000;

  private final AgentHub hub;
  /** 每个绑定地址一个服务器——同一套路由、同一个 hub，两条进来的路。 */
  private final List<HttpServer> servers;
  private final String token;
  /** 页面可以用作背景的图片；目录不存在就表示一张也没有。 */
  private final Wallpapers wallpapers;
  /**
   * 每个交换一个虚拟线程。
   *
   * <p>一条 SSE 流只要页面开着就一直占着自己的线程，而浏览器可以愉快地开上几个小时。放在平台线程上，
   * 那就是每个打开的标签页一个 OS 线程——这个代价把「一个连接一个线程」从设计变成了负担，而且一个卡住的
   * 客户端会让所有人替它买单。虚拟线程让它变得便宜，而虚拟线程里的阻塞式 socket 写入停放的是续体，而不是
   * OS 线程。
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
        // 一个端口、几个地址：要端口 0 就是「随便给个空闲端口」，而且只给一个空闲端口——第二个地址绑到
        // 第二个任意号码，同一个页面就会有两个不同的 URL，那不是「在这里和那里都提供服务」能有的意思。
        InetSocketAddress resolved =
            bind.getPort() == 0 && !created.isEmpty()
                ? new InetSocketAddress(bind.getAddress(), created.get(0).getAddress().getPort())
                : bind;
        created.add(serverOn(resolved));
      }
    } catch (IOException e) {
      // 半绑定的服务器会在一个地址上应答、在另一个地址上拒绝，这是没人要的状态：把已经建起来的服务器
      // 关掉，并把失败报出去。
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
   * 在给出的每一个地址上提供同一个页面。
   *
   * <p>之所以不止一个，是因为两条进来的路确实不同：环回是用户正坐着的这台机器，tailnet 地址是他们的
   * 手机。改成绑通配地址还会把这个端口放到咖啡馆的 wifi 上，所以地址是一个一个点名的。
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

  /** 实际绑上的端口——调用方要的是 0 时有用。 */
  public int port() {
    return servers.get(0).getAddress().getPort();
  }

  /**
   * 人能点击的每一个地址，按绑定的顺序排列，各自只在需要时才带上 token。
   *
   * <p>环回不是「另一台设备」：到达那里的请求就是在这台机器上发出的，所以 token 把守的是网络，而不是
   * 用户自己的浏览器。正因如此，{@code ccj} 可以一直打开 {@code http://127.0.0.1:6767} 而什么都不附，
   * 同一个服务器却会向手机索要那点机密。
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

  /** 人能点击的一个 URL，需要时带上 token。 */
  public String url() {
    return urls().get(0);
  }

  /** 从这台机器以外的地方可达的地址返回 true。 */
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
      // 两条进来的路，报错方式不同是有意的。没有证明 token 的调用方，在有 token 可证明时会被告知*这一点*
      // ——那是他们能修的东西。没有 token 的服务器则改为就它的 Host 向调用方追责，消息里也这么说。证明了
      // token 的调用方不会再被问别的：那点机密就是网络绑定要付的全部代价。
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
                  + "' 不是环回地址；浏览器可以从它访问的任何页面到达这个服务器，所以请用"
                  + " localhost/127.0.0.1 打开它，或带 token 启动 ccj");
        }
        return;
      }
      String path = exchange.getRequestURI().getPath();
      // 对任何会改变状态的请求做一次跨源检查，这是环回和 Host 两道检查都给不了的那一层防护。
      //
      // 那两道回答的是「它是不是到了本机」，而用户浏览器里的一个页面完美满足这一条：浏览器就跑在这台机器
      // 上，所以它的连接是环回，它的 Host 是 127.0.0.1。它们看不到的是*哪一个页面*造成了这次请求。在这道
      // 检查存在之前实测过：一个带 `Origin: https://evil.example`、发往 /api/auto-approve 的 POST 被正常
      // 处理，而且它真的把自动审批打开了——此后代理在运行命令之前就不再询问。整个攻击就是这样：访问一个
      // 页面，页面就把守卫关掉了。
      //
      // 只对改变状态的请求检查，所以一个不泄露任何东西的 GET 不受影响；也只在浏览器确实发了 Origin 时才
      // 检查——curl 或脚本什么也不发，把那种情况当成敌意，会为了防住一个浏览器本来就不会放行的调用方，而
      // 打断每一个正当调用方。
      if (stateChanging(exchange) && !sameOrigin(exchange)) {
        error(
            exchange,
            403,
            "跨源（cross-origin）请求被拒绝：位于 "
                + exchange.getRequestHeaders().getFirst("Origin")
                + " 的页面不得改变本服务器的状态。浏览器无法从另一个站点访问 ccj，"
                + "这里是把它明确落实，而不是假定如此。");
        return;
      }
      // 在下面的分派表之前先处理一个前缀：壁纸的名字是路径的一部分，而这个名字就是目录里恰好有的东西，
      // 所以没有任何 case 标签能匹配它。
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
        case "/api/undo" -> undo(exchange);
        case "/api/attachment/thumb" -> thumbnail(exchange);
        case "/api/approval" -> approval(exchange);
        case "/api/auto-approve" -> autoApprove(exchange);
        case "/api/session" -> session(exchange);
        case "/api/workspaces" -> workspaces(exchange);
        case "/api/workspace" -> workspace(exchange);
        case "/api/models" -> models(exchange);
        case "/api/providers" -> providers(exchange);
        case "/api/config" -> config(exchange);
        case "/api/config/test" -> configTest(exchange);
        case "/api/rules" -> rules(exchange);
        case "/api/checks" -> checks(exchange);
        default -> error(exchange, 404, "没有这个端点：" + path);
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
      error(exchange, 405, "需要 POST");
      return;
    }
    JsonNode posted = Json.parse(readBody(exchange));
    String text = posted.path("text").asText("");
    // 图片是可选的，而且它必须是这条会话已经持有的那一张：上传就是「我要发它」，所以这个名字是一次一致性
    // 检查而不是一个文件名参数。正文只有在没有图片时才必须存在。
    String picture = posted.path("picture").asText("");
    if (text.isBlank() && picture.isBlank()) {
      error(exchange, 400, "字段 'text' 是必需的");
      return;
    }
    // 两种情况下都是 202：消息被接受了，无论它是开启了一个回合，还是在正在跑的那个后面排队。页面会说清
    // 是哪一种，而输入框在两种情况下都保持可用。
    AgentHub.Submit submit = hub.submit(text, picture);
    respond(
        exchange,
        202,
        Json.object().put("accepted", true).put("queued", submit == AgentHub.Submit.QUEUED));
  }

  /**
   * 一张图片：保存它，让视觉模型描述它，把它挂在这条会话上等着。
   *
   * <p>它过去会立刻开启一个回合，而现在不再这样——描述就停在这里，直到用户说出想让模型拿它做什么。
   *
   * <p>请求体就是图片本身，而不是装着 base64 的 JSON 信封，所以这些字节从不在客户端编码、再在这里解码：
   * 上传就是一个带着图片的请求，而存放时用的名字是 {@code name} 查询参数。
   *
   * <p>读取有两重上界，而且都在字节被拿住之前：声明长度超过上限的直接拒绝，读取本身也在上限处停下，这样
   * 一个谎报长度的请求体——或者根本不带长度的流式请求体——没法把上限变成一句建议。拒绝是 413，并带上尺寸，
   * 那是客户端能据以行动的东西。
   */
  private void attachment(HttpExchange exchange) throws IOException {
    if ("DELETE".equals(exchange.getRequestMethod())) {
      // 丢掉这张待发送的图片。DELETE，不是 POST /api/attachment/discard：被丢掉的就是这个资源本身，而
      // 它没有别的东西可读。
      String dropped = hub.discardPicture();
      respond(exchange, 200, Json.object().put("discarded", dropped == null ? null : dropped));
      return;
    }
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "需要 POST 或 DELETE");
      return;
    }
    byte[] bytes;
    try {
      bytes = AttachmentStore.readBounded(exchange.getRequestBody(), declaredLength(exchange));
    } catch (IOException e) {
      // 拒掉之前先把剩下的请求体读掉（有界）。不读的下场是客户端看到的不是这条 413，而是「响应被截断」：
      // 它还在上传，而我们已经把连接关了。ubuntu 的 CI 上实测到过一次（macOS 上碰巧没撞上、本机一直绿），
      // 而「fixed content-length: 72, bytes received: 0」不是一个客户端能据以行动的答案。
      drain(exchange.getRequestBody(), declaredLength(exchange));
      error(exchange, 413, e.getMessage());
      return;
    }
    respond(exchange, 202, hub.describePicture(queryParam(exchange, "name"), bytes));
  }

  /**
   * 请求体里拒绝之后还会剩下的字节上限。
   *
   * <p>这里的目的是让一个正常的客户端把话说完、然后读到我们的回复，而不是配合一个不肯停下来的发送方：超过
   * 这个数就直接关掉，那条 413 仍然发出去了，只是对方可能看不到。
   */
  private static final int DRAIN_LIMIT_BYTES = 16 * 1024 * 1024;

  /** 把请求体剩下的部分读掉并丢掉，最多 {@link #DRAIN_LIMIT_BYTES} 字节。 */
  private static void drain(InputStream body, long declaredLength) {
    long budget =
        declaredLength > 0 ? Math.min(declaredLength, DRAIN_LIMIT_BYTES) : DRAIN_LIMIT_BYTES;
    byte[] scratch = new byte[8 * 1024];
    long read = 0;
    try {
      while (read < budget) {
        int chunk = body.read(scratch, 0, (int) Math.min(scratch.length, budget - read));
        if (chunk < 0) {
          return;
        }
        read += chunk;
      }
    } catch (IOException e) {
      // 读不动就算了：响应照发，连接由服务器关掉。
    }
  }

  /** 客户端发了 {@code Content-Length} 时就是它；分块请求体则为 -1。 */
  private static long declaredLength(HttpExchange exchange) {
    try {
      String header = exchange.getRequestHeaders().getFirst("Content-Length");
      return header == null || header.isBlank() ? -1 : Long.parseLong(header.strip());
    } catch (NumberFormatException e) {
      // 解析不了的长度并不比没发长度更糟：两种情况下读取都有上界，在这里拒绝会拒掉一个完全可读的请求体。
      return -1;
    }
  }

  private void abort(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "需要 POST");
      return;
    }
    // 会话是可选的：不带 id 就是屏幕上那个对话，这正是输入框的停止按钮的含义。带 id 则停掉用户没有在看
    // 的那个对话里正在跑的回合，也就是树里的运行标记把他们引过去的那种情况。
    String id = queryParam(exchange, "id");
    boolean aborted = id == null || id.isBlank() ? hub.abortAll() : hub.abort(id);
    respond(exchange, 200, Json.object().put("aborted", aborted));
  }

  /**
   * 压缩屏幕上的这个会话。回答里给出替换掉了什么、代价是多少，而对话本身以 {@code compacted} 事件到达
   * ——页面得知自己的转录变了的方式，和它得知其他一切事情的方式相同。
   */
  /**
   * 把上一回合改动过的东西放回去。
   *
   * <p>回答里给出恢复了什么、还有多少个回合可以退回，这样页面两件事都能说；对话本身则通过一条通知说明，
   * 和服务器做的其他每件事一样。
   */
  /**
   * 退回：{@code GET} 是预览，{@code POST} 是执行。
   *
   * <p>两件事共用一个路径，因为它们是同一个动作的两半——而预览存在的理由，是退回会覆盖磁盘上的文件。
   * 一个会覆盖文件的按钮，值得先说出它要覆盖什么。
   */
  private void undo(HttpExchange exchange) throws IOException {
    if ("GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 200, hub.undoPreview());
      return;
    }
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "需要 GET 或 POST");
      return;
    }
    respond(exchange, 200, hub.undoTurn());
  }

  /**
   * 这条会话附件目录里某个文件的小图。
   *
   * <p>名字必须就是转录里写的那个，而且只在这条会话自己的附件目录里找：这个路径参数是调用方给的，所以
   * 一次 {@code ../} 就能把它变成「读服务器上的任意文件」。它服务的是**这条会话里的任何一张图片**，不只是
   * 待发送的那一张——历史消息里的图片也要画得出小图。
   *
   * <p>画不出小图不是错误：{@code Thumbnails.of} 解不出来的格式会原样交回字节，所以一张 WebP 拿到的
   * 是它自己，而页面照样显示得出来。
   */
  private void thumbnail(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "需要 GET");
      return;
    }
    AgentHub.PictureFile picture;
    try {
      picture = hub.attachmentThumbnail(queryParam(exchange, "name"));
    } catch (IllegalArgumentException e) {
      error(exchange, 400, e.getMessage());
      return;
    }
    if (picture == null) {
      error(exchange, 404, "这条会话里没有这张图片");
      return;
    }
    exchange.getResponseHeaders().add("Content-Type", picture.mediaType());
    // 图片在同一秒里不会变两次，但它**会**变：同一个名字在被取代之后可能是另一张图。所以让它每次
    // 都问一次——这些字节是本机磁盘上的，代价比一个陈旧的缩略图小。
    exchange.getResponseHeaders().add("Cache-Control", "no-cache");
    exchange.sendResponseHeaders(200, picture.bytes().length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(picture.bytes());
    }
  }

  private void compact(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "需要 POST");
      return;
    }
    respond(exchange, 200, hub.compact());
  }

  private void approval(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "需要 POST");
      return;
    }
    JsonNode body = Json.parse(readBody(exchange));
    String id = body.path("id").asText("");
    boolean allow = body.path("allow").asBoolean(false);
    boolean remember = body.path("remember").asBoolean(false);
    String answer = body.path("answer").asText(null);
    if (id.isBlank()) {
      error(exchange, 400, "字段 'id' 是必需的");
      return;
    }
    if (!hub.resolveApproval(id, ApprovalDesk.answerOf(allow, remember, answer))) {
      error(exchange, 404, "没有 id 为 " + id + " 的待处理审批");
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
      error(exchange, 405, "需要 GET 或 POST");
      return;
    }
    JsonNode body = Json.parse(readBody(exchange));
    String path = body.path("path").asText("");
    String name = body.path("name").asText("");
    if (path.isBlank()) {
      // 在这里检查，而不是丢给 Path.of("")，那是进程自己的目录，看上去会像一个完全可以添加的文件夹。
      error(exchange, 400, "工作区需要一个目录");
      return;
    }
    // `name` 是有意可选的：挑过目录的浏览器已经说过这个工作区叫什么了，就是那个文件夹的名字。请求里
    // 带着名字时，仍然以它为准。
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
    error(exchange, 405, "需要 POST 或 DELETE");
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
    error(exchange, 405, "需要 GET 或 DELETE");
  }

  /**
   * 打开桌面的文件夹选择器。浏览器自己做不到这一点——Web 的 File System Access API 有意隐藏绝对路径
   * ——但提供这个页面的进程就坐在同一台机器上，所以它可以直接去问桌面。
   */
  private void browse(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "需要 POST");
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
    error(exchange, 405, "需要 GET、POST 或 DELETE");
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
    error(exchange, 405, "需要 GET、POST、PUT 或 DELETE");
  }

  private void config(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if ("GET".equals(method)) {
      respond(exchange, 200, hub.configJson());
      return;
    }
    if (!"POST".equals(method)) {
      error(exchange, 405, "需要 GET 或 POST");
      return;
    }
    // 校验失败是 IllegalArgumentException，route() 会用 400 回答它。
    respond(exchange, 200, hub.applyConfig(Json.parse(readBody(exchange))));
  }

  private void configTest(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "需要 POST");
      return;
    }
    respond(exchange, 200, hub.testConfiguration(Json.parse(readBody(exchange))));
  }

  /**
   * 审批规则：读它们，或者加一条/删一条。GET 回的是那个项目此刻的规则与它们的读法；POST 回的是一份同样
   * 形状的新列表，所以面板不必再发一次 GET——它看到的永远刚刚写下的那份文件。
   */
  private void rules(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if ("GET".equals(method)) {
      respond(exchange, 200, hub.rulesJson());
      return;
    }
    if (!"POST".equals(method)) {
      error(exchange, 405, "需要 GET 或 POST");
      return;
    }
    // 校验失败是 IllegalArgumentException，route() 会用 400 回答它；消息来自 ApprovalRules 自己。
    respond(exchange, 200, hub.applyRules(Json.parse(readBody(exchange))));
  }

  /**
   * 编辑后检查：读它们，或者整份替换。校验用的是 {@code Checks} 自己的解析，所以 400 里的那句话与工具
   * 运行时报告的是同一句。
   */
  private void checks(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if ("GET".equals(method)) {
      respond(exchange, 200, hub.checksJson());
      return;
    }
    if (!"POST".equals(method)) {
      error(exchange, 405, "需要 GET 或 POST");
      return;
    }
    respond(exchange, 200, hub.applyChecks(Json.parse(readBody(exchange))));
  }

  private void autoApprove(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "需要 POST");
      return;
    }
    JsonNode body = Json.parse(readBody(exchange));
    if (!body.has("enabled")) {
      error(exchange, 400, "字段 'enabled' 是必需的");
      return;
    }
    hub.setAutoApprove(body.path("enabled").asBoolean(false));
    respond(exchange, 200, Json.object().put("autoApprove", hub.autoApprove()));
  }

  private void session(HttpExchange exchange) throws IOException {
    if ("DELETE".equals(exchange.getRequestMethod())) {
      String id = queryParam(exchange, "id");
      if (id == null || id.isBlank()) {
        error(exchange, 400, "查询参数 'id' 是必需的");
        return;
      }
      respond(exchange, 200, hub.deleteSession(queryParam(exchange, "workspace"), id));
      return;
    }
    if (!"POST".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "需要 POST 或 DELETE");
      return;
    }
    JsonNode body = Json.parse(readBody(exchange));
    String action = body.path("action").asText("");
    switch (action) {
      case "new" -> hub.newSession();
      case "resume" -> hub.resumeSession(body.path("id").asText(""));
      default -> {
        error(exchange, 400, "action 必须是 'new' 或 'resume'");
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
    List<EventStream.Event> missed = hub.subscribe(client);
    try {
      // 重放缓冲区只为一个目的存在：补上重连的页面错过的那段空白。全新的连接没有空白——它从
      // /api/history 取得对话，所以在这里重放缓冲区会把每个近期事件渲染第二遍。
      if (lastEventId > 0) {
        for (EventStream.Event event : missed) {
          if (event.id() > client.lastSent()) {
            client.send(event);
          }
        }
      }
      // 刚连上的浏览器还没有任何状态，而契约承诺会有一个 status 事件。
      hub.publishStatus();
      // 这条线程在流的整个生命期里独占这个 socket：它写队列里的东西，队列一直空着时就发心跳。这里从没有
      // 别的写入者，正因如此，一个停止读取的浏览器阻塞不了发布事件的人。
      while (!client.closed()) {
        EventStream.Event event = client.take(HEARTBEAT_MILLIS);
        if (client.closed()) {
          break;
        }
        if (event == null) {
          client.heartbeat();
          continue;
        }
        client.send(event);
        for (EventStream.Event queued = client.poll(); queued != null; queued = client.poll()) {
          client.send(queued);
        }
      }
    } catch (InterruptedException e) {
      // 唯一会中断这条线程的，是它自己的客户端溢出，而这个标志正是本类为这次唤醒设置的：它被清除，而不是
      // 继续传播，因为这条线程会回到池里，那里残留的中断会弄坏下一个请求。
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
   * 独占一个 socket 的订阅者。
   *
   * <p>事件由发布它们的人入队，由服务这个连接的那条线程写出。直接从 {@code publish} 里写，会把一次阻塞式
   * socket 写入塞进 hub 的重放监视器内部：那样一个停止读取的浏览器——被挂起的标签页、卡住的网络——就会
   * 卡死 agent 的回合线程，连带其他每一个客户端。现在，一个停止读取的客户端只填满自己的队列。
   *
   * <p>那个队列是有意设有上界的：一个可能再也回不来的客户端，不能让它无限地长堆。溢出的客户端被丢弃，页面
   * 会重连并从 {@code /api/history} 重读历史——反正它每次断开后都会这么做。
   */
  private static final class SseClient implements Consumer<EventStream.Event> {

    /** 一个慢客户端在被丢弃之前可以落后多少条事件。 */
    private static final int QUEUE_LIMIT = 4096;

    private final OutputStream out;
    private final BlockingQueue<EventStream.Event> pending = new ArrayBlockingQueue<>(QUEUE_LIMIT);
    private volatile Thread reader;
    private volatile boolean closed;
    private long lastSent;

    SseClient(OutputStream out, long lastSent) {
      this.out = out;
      this.lastSent = lastSent;
    }

    /** 在发布线程上调用：这里从不阻塞，也从不写入。 */
    @Override
    public void accept(EventStream.Event event) {
      if (closed) {
        return;
      }
      if (pending.offer(event)) {
        return;
      }
      // 落后太多追不上了：丢弃这个客户端并唤醒它的线程，让它不再等待。
      closed = true;
      Thread waiting = reader;
      if (waiting != null) {
        waiting.interrupt();
      }
    }

    /** 等下一个事件，或等 {@code timeoutMillis} 过去而没有什么可发。 */
    EventStream.Event take(long timeoutMillis) throws InterruptedException {
      reader = Thread.currentThread();
      try {
        return pending.poll(timeoutMillis, TimeUnit.MILLISECONDS);
      } finally {
        reader = null;
      }
    }

    /** 已经排在队列里的其余东西，不等待。 */
    EventStream.Event poll() {
      return pending.poll();
    }

    void send(EventStream.Event event) {
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
     * 让连接看起来一直活着。
     *
     * <p>是一个真正的数据帧，而不是 SSE 服务器通常发的 {@code : ping} 注释。注释根本不会投递给页面
     * ——{@code EventSource} 只分派带 {@code data} 字段的帧——所以页面的 {@code lastEventAt} 从不前进，
     * 它那个 20 秒的「流已经死了」计时器会对着一条完全健康的连接触发。实测：一个等待审批的回合，在页面于
     * 其下方重连时，展示出的提示会闪烁，而这个请求明明还开着，看上去却像是被撤回了。
     *
     * <p>发送时不带 {@code id}，所以它不会扰乱真实事件维持的续传位置；名字取 {@code ping} 而不是默认
     * 消息类型：页面在自己的 switch 里忽略它，于是它唯一做的事就是证明连接还活着——这正是它该干的活。
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
        error(exchange, 404, "找不到资源 " + resource + "（jar 是不是在加入它之前就构建好了？）");
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
   * 页面可以轮换使用的名字。没有目录可读时，空列表是诚实的答案：用它的那个控件会把自己藏起来，而不是
   * 提供一片空白。
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
   * 一张壁纸的字节，流式发出而不是读进内存：一张 4K 图片有好几兆，其中没有一个字节有理由在去往 socket
   * 的路上经过堆。名字由 {@link Wallpapers#resolve} 检查，正是这一点让这个端点没有变成读取机器上任意
   * 文件的一条路。
   */
  private void wallpaper(HttpExchange exchange, String name) throws IOException {
    Optional<Path> file = wallpapers.resolve(name);
    if (file.isEmpty()) {
      error(exchange, 404, "没有这张壁纸");
      return;
    }
    exchange
        .getResponseHeaders()
        .add("Content-Type", wallpapers.contentType(file.get()).orElse("application/octet-stream"));
    // 轮换会反复看同样的图片，而它们是很少变的文件：几分钟的缓存，就是让切换背景不至于每次都重读几兆的
    // 原因。
    exchange.getResponseHeaders().add("Cache-Control", "private, max-age=300");
    exchange.sendResponseHeaders(200, Files.size(file.get()));
    try (OutputStream out = exchange.getResponseBody()) {
      Files.copy(file.get(), out);
    }
  }

  private void get(HttpExchange exchange, ObjectNode body) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      error(exchange, 405, "需要 GET");
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
    respond(exchange, status, Json.object().put("error", message == null ? "失败" : message));
  }

  /** 被拒绝的请求体，让巨大的上传没法变成一次 OutOfMemoryError。 */
  private static final class PayloadTooLargeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PayloadTooLargeException(String message) {
      super(message);
    }
  }

  /** UI 提交的一切都是小表单或一条消息；一兆已经很宽裕了。 */
  private static final int MAX_BODY_BYTES = 1024 * 1024;

  private String readBody(HttpExchange exchange) throws IOException {
    byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
    if (body.length > MAX_BODY_BYTES) {
      throw new PayloadTooLargeException(
          "请求体大于 " + MAX_BODY_BYTES + " 字节");
    }
    return new String(body, StandardCharsets.UTF_8);
  }

  private boolean tokenPresented(HttpExchange exchange) {
    if (token == null) {
      return false; // 没有任何东西可出示，这和「已经出示过」不是一回事
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
   * 一个<em>没有</em>证明 token 的请求仍可被服务时为 true：它到达于环回接口<em>并且</em>它点名的 Host
   * 是环回。
   *
   * <p>这是本地这种情形，而且有意是唯一一种。环回不是挡浏览器的边界：用户访问的任何页面都能不经预检就
   * POST 到 127.0.0.1，而 DNS 重绑定还让那个页面能读到回答——所以 Host 那一半是检查出来的，不是假定
   * 出来的，像 {@code dead.beef} 这样的名字正是那种攻击。页面被访问时所用的地址是环回，说明请求来自这台
   * 机器；Host 头说明它瞄准的是这个服务器，而不是一个恰好解析到这里的名字。
   *
   * <p>所以 token 把守的是<em>网络</em>，不是用户自己的浏览器：{@code http://127.0.0.1:6767} 一直可用
   * 且什么都不必附，同一个服务器却会向手机索要那点机密。另一种做法——每个请求都带 token——意味着这台机器
   * 自己的用户，为了到达自己启动的服务器，还得随身带一个机密。
   */
  /**
   * 可能改变某些东西的请求返回 true。
   *
   * <p>按方法判断，而不是按路径清单：新端点不必记得主动加入，而只读的那些，恰好就是协议规定不改变状态的
   * 那些。
   */
  private static boolean stateChanging(HttpExchange exchange) {
    String method = exchange.getRequestMethod();
    return !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
  }

  /**
   * 请求不是来自另一个站点、或者没有说它来自哪里时为 true。
   *
   * <p>缺少 {@code Origin} 算作同源。浏览器会把它附加到跨源请求上，也会附加到它发出的每一个改变状态的
   * 请求上，所以它缺失意味着调用方不是浏览器页面——是脚本、测试、CLI。拒绝这些，会为了防住一个浏览器本来
   * 就不会放行的调用方，而打断每一个正当调用方。
   *
   * <p>它存在时，必须点名这个服务器，而页面只有两种诚实的方式做到这一点：一个环回地址（服务器运行所在的
   * 机器），或者请求所瞄准的那个主机——{@code Origin} 与 {@code Host} 一致。两者都需要。开头只有第一种，
   * 而它让来自 tailnet 上手机的所有改变状态的请求都变成 403：那个页面是从 tailnet 地址提供的，所以它的
   * {@code Origin} 点名的是 tailnet 地址，那既不是环回，也不是这道检查会接受的任何东西——这是拿一个手机
   * 形状的请求（{@code Origin: http://100.64.0.1:6767}，与页面来自同一个主机）对着真实服务器实测的，
   * 当时发消息、停回合、保存设置、回答审批、上传图片全都被拒。页面是从哪个地址加载的，正是「这是哪个页面」
   * 唯一诚实的答案，而它不是事先能知道的——服务器绑的是用户点名的那些地址，而 tailnet 那个是他们的。
   *
   * <p>拿它和 {@code Host} 比较，而不是接受任何主机，是这件事没有成为漏洞的原因。另一个站点上的页面发出
   * 的是它自己的源，那不是请求所瞄准的主机，所以仍被拒绝。一个通过重绑定名字到达这个服务器的页面，会在两个
   * 头里都发那个名字，于是自己与自己一致——正因如此，一致并不是唯一的守卫：没有 token 的服务器在本段运行
   * 之前，已经因为点名了非环回 {@code Host} 而拒绝了它；有 token 的服务器已经因为它没带 token 到达而拒绝
   * 了它。剩下的情况，是一个从本服务器应答所用的那个地址提供的页面，同一台机器、同一个用户，与环回那种情形
   * 一直给予的同样的信任。
   *
   * <p>端口不一致是允许的，因为用户可能在一个与最初打开的页面不同的端口上启动了 ccj，而这些地址中的每一个
   * 都是这台机器本身。
   */
  private static boolean sameOrigin(HttpExchange exchange) {
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin == null || origin.isBlank()) {
      return true;
    }
    String clean = origin.strip();
    // "null" 是沙箱 iframe 或 file:// 页面发的东西。它不是本服务器。
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
        // 有意与 Host 头遵守同一条规则：关于「这台机器」只有一个概念，而不是两个可能互相打架的概念；
        // 拒绝解析名字的环回字面量检查，在这里也正是想要的。
        return true;
      }
      // 否则，页面必须是从这次请求所瞄准的主机提供的，这正是用户自己收藏的 tailnet 地址书签发来的东西。
      String aimedAt = hostName(exchange.getRequestHeaders().getFirst("Host"));
      return aimedAt != null && aimedAt.equalsIgnoreCase(host);
    } catch (IllegalArgumentException e) {
      // 解析不了的 Origin 不是本服务器的。
      return false;
    }
  }

  private boolean permitted(HttpExchange exchange) {
    return loopbackPeer(exchange) && loopbackHost(exchange.getRequestHeaders().getFirst("Host"));
  }

  /** 这个连接是在环回地址上被接受的时候为 true。 */
  private static boolean loopbackPeer(HttpExchange exchange) {
    InetSocketAddress peer = exchange.getRemoteAddress();
    return peer != null && peer.getAddress() != null && peer.getAddress().isLoopbackAddress();
  }

  /** {@code Host} 头里的主机部分，不含端口；没有则为 null。 */
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
   * {@code localhost} 以及环回<em>字面量</em>返回 true。
   *
   * <p>带点的名字在这里从不做解析：{@code dead.beef} 是完全可以合法的主机名，而解析攻击者选定的名字
   * 正是重绑定攻击的全部要点。只接受 {@code 127.0.0.0/8} 里的四段十进制数字，以及字面量 IPv6（它不
   * 可能是 DNS 名字）。
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
                "本服务器需要一个 token；请打开 ccj 打印的 URL（其中带有 ?token=…），"
                    + "或发送 Authorization: Bearer <token>"));
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
