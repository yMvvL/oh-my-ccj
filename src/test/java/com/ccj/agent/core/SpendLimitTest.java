package com.ccj.agent.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpendLimitTest {

  @Test
  void aConversationWithoutALimitIsNeverStopped() {
    assertTrue(SpendLimit.allowsStart(0, null));
    assertTrue(SpendLimit.allowsStart(9_999_999L, null));
    assertNull(SpendLimit.refusal(9_999_999L, null));
  }

  @Test
  void theTurnThatReachesTheLimitStillRuns() {
    // 上限说的是「最多花这么多」，所以正好花到它的那个回合开始得起来；只有已经越过它之后的下一回合
    // 才被挡下——否则「最多 10000」会变成「最多 9999」，而这是用户永远不会写下的数。
    assertTrue(SpendLimit.allowsStart(0, 10000));
    assertTrue(SpendLimit.allowsStart(9999, 10000));
    assertTrue(SpendLimit.allowsStart(10000, 10000));
    assertFalse(SpendLimit.allowsStart(10001, 10000));
  }

  @Test
  void theRefusalSaysWhatWasSpentWhatTheLimitIsAndHowToRaiseIt() {
    String refusal = SpendLimit.refusal(12000, 10000);

    assertTrue(refusal.contains("12000"), refusal);
    assertTrue(refusal.contains("10000"), refusal);
    assertTrue(refusal.contains("--max-total-tokens"), refusal);
    assertTrue(refusal.contains("maxTotalTokens"), refusal);
    assertTrue(refusal.contains("CCJ_MAX_TOTAL_TOKENS"), refusal);
    // 还能开始时没有可拒绝的东西：调用方靠 null 分辨这两种情况，所以它必须真的是 null。
    assertNull(SpendLimit.refusal(10000, 10000));
    assertNull(SpendLimit.refusal(0, null));
  }
}
