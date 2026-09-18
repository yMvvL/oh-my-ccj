package com.ccj.agent.core;

/**
 * 一个会话的 token 与工具用量记账。
 *
 * <p>与对话存放在一起，这样重新打开会话时总计能接着算，而不是假装早先的回合不要钱。
 * {@code cacheReported} 之所以存在，是因为「没有信息」和「没有命中缓存」是两个不同的事实：只有后者是
 * 0% 命中率。
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
   * 压缩功能出现之前的账目形态：不计压缩次数。
   *
   * <p>保留为重载，而不是让每个调用方都写出一个几乎总是零的字段——而且旧版本写下的会话文件解码出来就是
   * 它。
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

  /** 什么都没记录时为 true：一个从未跑过回合的会话就长这样。 */
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

  /** 缓存命中率；提供方从未报告过缓存数字时为 null。 */
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
   * 一次压缩，它花掉一次真实请求，但不是对话的一个回合。
   *
   * <p>单独计数，是因为把它并进 {@code modelTurns} 会让面板上的「模型回合」有两种含义，而完全不记又会
   * 藏起用户付了钱的 token。只有给它自己的字段，两者才都成立。
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
