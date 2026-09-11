package com.ccj.agent.provider;

import com.ccj.agent.core.Provider;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Shared HTTP plumbing for the concrete providers: timeouts, retry policy and error reporting.
 *
 * <p>Keeping this in one place means both vendors retry the same way and fail with the same
 * message shape, which is what makes provider failures diagnosable from the console.
 */
final class Transport {

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

  /** A long turn can stream for minutes, so the request deadline has to be generous. */
  static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

  /** Attempts in total — the first send plus the retries — before a transient fault is fatal. */
  static final int MAX_ATTEMPTS = 3;

  private static final long BASE_BACKOFF_MILLIS = 150;
  private static final long MAX_JITTER_MILLIS = 100;
  private static final int ERROR_BODY_LIMIT = 500;

  private Transport() {}

  static ExecutorService newExecutor(String threadName) {
    return Executors.newCachedThreadPool(
        runnable -> {
          Thread thread = new Thread(runnable, threadName);
          thread.setDaemon(true);
          return thread;
        });
  }

  static HttpClient newClient(ExecutorService executor) {
    return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).executor(executor).build();
  }

  /**
   * Sends {@code request} and returns the first 2xx response, whose body is still an unconsumed
   * stream. Transport faults, 408, 429 and 5xx are retried with exponential backoff plus jitter,
   * announcing each one through {@code listener}. Any other non-2xx is thrown immediately: a 400
   * means the request itself is wrong, so repeating it only wastes the user's time.
   */
  static HttpResponse<Stream<String>> send(
      HttpClient http, HttpRequest request, Consumer<Provider.Event> listener) throws Exception {
    for (int attempt = 1; ; attempt++) {
      try {
        HttpResponse<Stream<String>> response = http.send(request, BodyHandlers.ofLines());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
          return response;
        }
        String body = readBody(response.body());
        if (!retryable(status) || attempt >= MAX_ATTEMPTS) {
          throw new Exception("HTTP " + status + ": " + truncate(body));
        }
        awaitRetry(listener, attempt, "HTTP " + status);
      } catch (IOException e) {
        if (attempt >= MAX_ATTEMPTS) {
          throw new Exception("request failed after " + attempt + " attempts: " + e, e);
        }
        awaitRetry(listener, attempt, e.toString());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new Exception("interrupted while waiting for the model API", e);
      }
    }
  }

  static boolean retryable(int status) {
    return status == 408 || status == 429 || status >= 500;
  }

  /** Backoff for the attempt that just failed; {@code failedAttempt} is 1-based. */
  static long backoffMillis(int failedAttempt) {
    long exponential = BASE_BACKOFF_MILLIS << (failedAttempt - 1);
    return exponential + ThreadLocalRandom.current().nextLong(MAX_JITTER_MILLIS + 1);
  }

  private static void awaitRetry(Consumer<Provider.Event> listener, int attempt, String reason)
      throws InterruptedException {
    long delay = backoffMillis(attempt);
    listener.accept(new Provider.Event.Retry(attempt, reason, delay));
    Thread.sleep(delay);
  }

  /** Drains and closes a body stream; used on error responses, which must fit in memory. */
  static String readBody(Stream<String> lines) {
    try (lines) {
      return lines.collect(Collectors.joining("\n"));
    }
  }

  static String truncate(String body) {
    String flat = body == null ? "" : body.strip();
    return flat.length() <= ERROR_BODY_LIMIT
        ? flat
        : flat.substring(0, ERROR_BODY_LIMIT) + "... (truncated)";
  }
}
