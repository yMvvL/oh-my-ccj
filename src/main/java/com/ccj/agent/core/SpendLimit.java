package com.ccj.agent.core;

/**
 * 一条会话能花多少 token 的策略：给定已经用掉的数、一个上限，回答还能不能开始新回合。
 *
 * <p>它与 {@code maxContextTokens} 是两件事，所以这里不碰请求的形状：上下文预算管一次请求能带多少内容，
 * 花到预算就省略旧结果、丢掉更早的往来；花费上限管这条会话<em>一共</em>能花多少，越过它就不再有下一个回合。
 * 前者是「这次带得下吗」，后者是「还值得接着跑吗」，把其中一个当成另一个，会让其中一个问题得不到回答。
 *
 * <p>上限为 null 表示不设上限，与配置里每个可空字段一样：「没说」与「说了 0」是两回事。要挡住的是
 * <em>再往前一步</em>：正好花到上限的那个回合是允许开始的，因为上限说的就是「最多花这么多」；只有已经严格
 * 超过它的下一回合才被挡下。
 */
public final class SpendLimit {

  private SpendLimit() {}

  /** 已用 token 还没超过上限时为 true；上限为 null 时永远为 true。 */
  public static boolean allowsStart(long usedTokens, Integer maxTotalTokens) {
    return maxTotalTokens == null || usedTokens <= maxTotalTokens;
  }

  /**
   * 不能开始新回合时那句拒绝信息；还能开始时为 null。
   *
   * <p>三个改法一起说出来，而不是只报数字：这句话出现的地方可能是 web UI，那里没有命令行，能编辑的只有配置
   * 文件或环境变量；而在终端里跑起来的人看到的是 flag。少了哪一个，就有一半的读者拿到一句说了问题、却没说
   * 下一步的话。
   */
  public static String refusal(long usedTokens, Integer maxTotalTokens) {
    if (allowsStart(usedTokens, maxTotalTokens)) {
      return null;
    }
    return "本会话已用 "
        + usedTokens
        + " token，超过花费上限 "
        + maxTotalTokens
        + "，不再开始新回合；要接着跑，请提高 --max-total-tokens、"
        + "配置文件里的 \"maxTotalTokens\"，或环境变量 CCJ_MAX_TOTAL_TOKENS。";
  }
}
