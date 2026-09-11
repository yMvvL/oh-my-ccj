package com.ccj.agent.core;

/**
 * Token and tool accounting for one session.
 *
 * <p>Persisted next to the conversation so reopening a session continues its totals instead of
 * pretending the earlier turns were free. {@code cacheReported} exists because "no information" and
 * "nothing was cached" are different facts: only one of them is a 0% hit rate.
 */
public record UsageTotals(
    long inputTokens,
    long outputTokens,
    long cachedInputTokens,
    int userTurns,
    int modelTurns,
    int toolCalls,
    int toolErrors,
    long elapsedMillis,
    boolean cacheReported) {

  public static UsageTotals empty() {
    return new UsageTotals(0, 0, 0, 0, 0, 0, 0, 0, false);
  }

  /** Cache hit rate, or null when the provider never reported cache figures. */
  public Double cacheHitRate() {
    if (!cacheReported) {
      return null;
    }
    if (inputTokens <= 0) {
      return 0.0;
    }
    return Math.round(cachedInputTokens * 1000.0 / inputTokens) / 1000.0;
  }

  public UsageTotals plus(
      int input,
      int output,
      Integer cached,
      int userTurnDelta,
      int modelTurnDelta,
      int toolCallDelta,
      int toolErrorDelta,
      long elapsedDelta) {
    return new UsageTotals(
        inputTokens + input,
        outputTokens + output,
        cachedInputTokens + (cached == null ? 0 : cached),
        userTurns + userTurnDelta,
        modelTurns + modelTurnDelta,
        toolCalls + toolCallDelta,
        toolErrors + toolErrorDelta,
        elapsedMillis + elapsedDelta,
        cacheReported || cached != null);
  }

  public UsageTotals withElapsed(long elapsedDelta) {
    return new UsageTotals(
        inputTokens,
        outputTokens,
        cachedInputTokens,
        userTurns,
        modelTurns,
        toolCalls,
        toolErrors,
        elapsedMillis + elapsedDelta,
        cacheReported);
  }
}
