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
    int compactions,
    boolean cacheReported) {

  /**
   * The books as they were before compaction existed: no compactions counted.
   *
   * <p>Kept as an overload rather than making every caller spell out a field that is almost always
   * zero — and it is what a session file written by an older build decodes to.
   */
  public UsageTotals(
      long inputTokens,
      long outputTokens,
      long cachedInputTokens,
      int userTurns,
      int modelTurns,
      int toolCalls,
      int toolErrors,
      long elapsedMillis,
      boolean cacheReported) {
    this(
        inputTokens,
        outputTokens,
        cachedInputTokens,
        userTurns,
        modelTurns,
        toolCalls,
        toolErrors,
        elapsedMillis,
        0,
        cacheReported);
  }

  public static UsageTotals empty() {
    return new UsageTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, false);
  }

  /** True when nothing has been recorded: what a session that never ran a turn looks like. */
  public boolean isEmpty() {
    return inputTokens == 0
        && outputTokens == 0
        && cachedInputTokens == 0
        && userTurns == 0
        && modelTurns == 0
        && toolCalls == 0
        && toolErrors == 0
        && elapsedMillis == 0
        && !cacheReported;
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
        compactions,
        cacheReported || cached != null);
  }

  /**
   * One compaction, which costs a real request but is not a turn of the conversation.
   *
   * <p>Counted separately because folding it into {@code modelTurns} would make the panel's "model
   * turns" mean two different things, and folding it into nothing at all would hide tokens the user
   * paid for. Its own field is the only shape where both stay true.
   */
  public UsageTotals plusCompaction() {
    return new UsageTotals(
        inputTokens,
        outputTokens,
        cachedInputTokens,
        userTurns,
        modelTurns,
        toolCalls,
        toolErrors,
        elapsedMillis,
        compactions + 1,
        cacheReported);
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
        compactions,
        cacheReported);
  }
}
