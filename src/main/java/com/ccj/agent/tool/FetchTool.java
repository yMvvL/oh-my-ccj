package com.ccj.agent.tool;

import com.ccj.agent.core.ApprovalRequest;
import com.ccj.agent.core.Tool;
import com.ccj.agent.core.ToolContext;
import com.ccj.agent.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Fetches one URL over http or https and returns the body as text.
 *
 * <p>The first tool that leaves the machine, so the reach is what the rules are for. The scheme is
 * checked before anything is dialled: {@code file:}, {@code ftp:}, {@code javascript:} and a bare
 * path are refused by name. This is the tool that disproves "the agent cannot reach out", which is
 * exactly why it must not become a file reader, and the check is repeated for every redirect hop,
 * because where a redirect lands is the server's choice rather than the model's.
 *
 * <p>The body is bounded while it is read, not truncated afterwards: the read stops at {@code
 * max_bytes} and the result says how much was left out. The same rule as `bash` output and vision
 * replies, for the same reason — a limit applied after the bytes are already in memory has saved
 * nothing at all.
 *
 * <p>The body is returned exactly as it arrived, HTML tags included, because stripping markup is a
 * guess rather than a fact and a guess is what the model would then quote back. A response that is
 * not declared as text is refused with its content type named instead of being handed over as
 * though it were. Bodies are decoded as UTF-8.
 */
public final class FetchTool implements Tool {

  /** Bytes returned when the model does not ask for a size: a page of prose, not a whole site. */
  private static final int DEFAULT_MAX_BYTES = 200_000;

  /**
   * Ceiling on {@code max_bytes}, whatever the model asks for.
   *
   * <p>A fetch is one step of a turn whose whole output the user pays for on every later turn, so
   * the tool decides how much context a single URL may spend: a megabyte is already several hundred
   * thousand tokens to the wrong endpoint, and a model that wants more can ask for the next page.
   */
  private static final int HARD_MAX_BYTES = 1024 * 1024;

  /**
   * How long a connection may take to be accepted.
   *
   * <p>Ten seconds: a host that has not accepted by then will not answer this call, and every second
   * past that is a second the model is not getting a result.
   */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /**
   * How long the whole exchange may take.
   *
   * <p>Thirty seconds covers a slow page and a slow TLS handshake without parking the turn; the
   * model's own request deadline is minutes, so a fetch that hung would be paid for in full before
   * anything noticed.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /**
   * Redirect hops followed before the chain is reported as a loop.
   *
   * <p>Five: documentation URLs redirect once or twice, and a chain is not bounded by {@code
   * max_bytes} — each hop is a fresh request and a fresh timeout — so it needs a bound of its own.
   */
  private static final int MAX_REDIRECTS = 5;

  /**
   * Non-{@code text/*} media types whose content is still text.
   *
   * <p>The short list is the point: a type that is not here is refused with its name, which the
   * model can act on, rather than returned as text that would arrive as garbage. Types ending in
   * {@code +json} or {@code +xml} are accepted for the same reason without an entry each — {@code
   * application/ld+json} and {@code application/vnd.api+json} are text by construction.
   */
  private static final Set<String> TEXT_TYPES =
      Set.of(
          "application/json",
          "application/xml",
          "application/javascript",
          "application/ecmascript",
          "application/x-javascript",
          "application/x-ndjson",
          "application/yaml",
          "application/x-yaml",
          "application/toml",
          "application/sql");

  /**
   * One client for the process.
   *
   * <p>{@code HttpClient} owns a selector thread and a connection pool, so building one per call
   * would start a thread per fetch and reuse nothing between them. Redirects are not left to it:
   * {@code NORMAL} would follow a {@code Location} to wherever the server points before this tool
   * gets to look at it, and the scheme rule has to hold for the hop as well as for the URL the model
   * typed. The hops are followed below instead, one scheme-checked request at a time.
   */
  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .connectTimeout(CONNECT_TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @Override
  public String name() {
    return "fetch";
  }

  /**
   * False on purpose. A fetch changes nothing on this machine, but it is the one tool whose effect
   * lands on somebody else, and the loop reads this flag to decide what may overlap and what must go
   * through the approver first. A network request is a side effect in that sense.
   */
  @Override
  public boolean readOnly() {
    return false;
  }

  @Override
  public String description() {
    return "Fetch an http or https URL and return the status line and the body as text. Use it for "
        + "documentation, changelogs and release notes; use read, glob and grep for anything on "
        + "this machine, which fetch cannot open.";
  }

  @Override
  public String parametersJson() {
    return """
        {
          "type": "object",
          "properties": {
            "url": {
              "type": "string",
              "description": "Absolute http or https URL to fetch."
            },
            "max_bytes": {
              "type": "integer",
              "description": "Maximum number of body bytes to return. Defaults to 200000, maximum 1048576."
            }
          },
          "required": ["url"],
          "additionalProperties": false
        }""";
  }

  @Override
  public ToolResult execute(String argumentsJson, ToolContext ctx) throws Exception {
    JsonNode args = ToolSupport.args(argumentsJson);
    String given = ToolSupport.requireNonBlank(args, "url").strip();
    int limit = ToolSupport.optionalInt(args, "max_bytes", DEFAULT_MAX_BYTES, 1, HARD_MAX_BYTES);

    URI target;
    try {
      target = new URI(given);
    } catch (URISyntaxException e) {
      return ToolResult.error("not a URL: " + given + " (" + e.getReason() + ")");
    }
    String complaint = schemeComplaint(target, given);
    if (complaint != null) {
      return ToolResult.error("refused: " + complaint);
    }
    if (target.getHost() == null || target.getHost().isBlank()) {
      return ToolResult.error("the URL names no host: " + given);
    }

    // Approval before the first byte leaves the machine, and the *URL* is what a rule matches: this
    // is the one tool whose request reaches a third party, and "which host" is the whole question.
    // The scheme and host checks above run first so that what the prompt shows is a URL worth
    // approving rather than something that was going to be refused anyway.
    String refusal =
        ctx.refusal(
            new ApprovalRequest("fetch", target.toString(), null, "fetch", "GET " + target));
    if (refusal != null) {
      return ToolResult.error(refusal);
    }

    for (int hop = 0; ; hop++) {
      HttpResponse<InputStream> response;
      try {
        response = HTTP.send(get(target), HttpResponse.BodyHandlers.ofInputStream());
      } catch (IOException e) {
        return ToolResult.error("could not reach " + target + ": " + e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return ToolResult.error("interrupted while fetching " + target);
      }

      String location = response.headers().firstValue("location").orElse("").strip();
      if (!isRedirect(response.statusCode()) || location.isEmpty()) {
        return read(target, response, limit);
      }
      close(response);
      if (hop >= MAX_REDIRECTS) {
        return ToolResult.error(
            "too many redirects (more than " + MAX_REDIRECTS + ") fetching " + given);
      }
      URI next = resolve(target, location);
      if (next == null) {
        return ToolResult.error(
            "refused: the redirect from " + target + " names a target that is not a URL: "
                + location);
      }
      String redirectComplaint = schemeComplaint(next, location);
      if (redirectComplaint != null) {
        return ToolResult.error(
            "refused: " + redirectComplaint + " (redirected from " + target + ")");
      }
      target = next;
    }
  }

  /**
   * The text of a 2xx response, or the reason there is none.
   *
   * <p>The status and the content type are decided before the body is touched: a 404 body or an
   * octet stream is not read at all, so the refusal costs nothing and no bytes of something the tool
   * will not return pass through this process.
   */
  private static ToolResult read(URI url, HttpResponse<InputStream> response, int limit) {
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      close(response);
      return ToolResult.error("HTTP " + status + " fetching " + url);
    }
    String contentType = response.headers().firstValue("content-type").orElse("").strip();
    if (!isText(mediaType(contentType))) {
      close(response);
      return ToolResult.error(
          "refused: "
              + url
              + " returned "
              + (contentType.isEmpty() ? "no content type" : contentType)
              + ", which is not text; fetch returns text only");
    }

    byte[] kept = new byte[limit];
    int length = 0;
    boolean truncated = false;
    try (InputStream body = response.body()) {
      while (length < limit) {
        int read = body.read(kept, length, limit - length);
        if (read < 0) {
          break;
        }
        length += read;
      }
      // One byte past the limit is what distinguishes "exactly the limit" from "cut here", and it is
      // the only part of the rest of the body that is ever read.
      if (length == limit) {
        truncated = body.read() != -1;
      }
    } catch (IOException e) {
      return ToolResult.error(url + " returned HTTP " + status + " but its body was cut short: " + e);
    }

    StringBuilder content = new StringBuilder(length + 64);
    content
        .append("HTTP ")
        .append(status)
        .append(' ')
        .append(contentType)
        .append(" (")
        .append(length)
        .append(" bytes");
    if (truncated) {
      long declared = declaredSize(response);
      content.append(", truncated; ");
      if (declared > length) {
        content.append(declared - length).append(" bytes omitted of ").append(declared);
      } else {
        // Counting the rest would mean downloading the body the limit exists to avoid, so it is
        // reported as unknown rather than guessed at.
        content.append("the rest was not read and its size was not declared");
      }
    }
    return ToolResult.ok(
        content.append(")\n").append(new String(kept, 0, length, StandardCharsets.UTF_8)).toString());
  }

  private static HttpRequest get(URI target) {
    return HttpRequest.newBuilder(target).timeout(REQUEST_TIMEOUT).GET().build();
  }

  /**
   * Why a URL may not be fetched, or null when it may. A refusal names the scheme it found, and the
   * bare-path case names the absence of one, because "invalid URL" would leave the model guessing
   * which of the two things it is looking at.
   */
  private static String schemeComplaint(URI uri, String given) {
    String scheme = uri.getScheme();
    if (scheme == null) {
      return "\""
          + given
          + "\" names no scheme: fetch reads http and https URLs only, and a path is not one — use"
          + " read for a file on this machine";
    }
    if (!isHttp(scheme)) {
      return "\""
          + given
          + "\" uses the "
          + scheme
          + " scheme: fetch reads http and https URLs only. It exists to reach the network, so it"
          + " will not read a file or run a script";
    }
    return null;
  }

  private static boolean isHttp(String scheme) {
    return scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https");
  }

  private static boolean isRedirect(int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
  }

  /** Where a {@code Location} points, resolved against the URL that sent it, or null if it is not a URI. */
  private static URI resolve(URI from, String location) {
    try {
      return from.resolve(location);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** The media type alone, lower-cased: {@code text/html; charset=utf-8} becomes {@code text/html}. */
  private static String mediaType(String contentType) {
    int parameters = contentType.indexOf(';');
    String type = parameters < 0 ? contentType : contentType.substring(0, parameters);
    return type.strip().toLowerCase(Locale.ROOT);
  }

  private static boolean isText(String mediaType) {
    return mediaType.startsWith("text/")
        || mediaType.endsWith("+json")
        || mediaType.endsWith("+xml")
        || TEXT_TYPES.contains(mediaType);
  }

  /**
   * The body size the response declared, or -1. A header that is not a number is reported as "not
   * declared" rather than thrown: the size is a courtesy on the truncation note, and a broken one
   * must not turn a page that arrived in full into an error.
   */
  private static long declaredSize(HttpResponse<?> response) {
    try {
      return response.headers().firstValueAsLong("content-length").orElse(-1);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** Releases a body that will not be read: an abandoned stream holds its connection open. */
  private static void close(HttpResponse<InputStream> response) {
    try {
      response.body().close();
    } catch (IOException ignored) {
      // Already closed, or the connection died; nothing was going to be read from it either way.
    }
  }
}
