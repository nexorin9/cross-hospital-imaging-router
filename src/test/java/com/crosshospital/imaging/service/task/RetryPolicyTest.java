package com.crosshospital.imaging.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crosshospital.imaging.service.routing.FailureCodeRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 21 — RetryPolicy 单元测试（纯逻辑，不碰数据库、不碰网络）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>6 种退避时间序列（attempt 0–5，基数 1000ms）：1s/2s/4s/8s/16s/30s（封顶）；</li>
 *   <li>抖动范围 ±25%（随机 200 次 + 上下边界精确值）；</li>
 *   <li>重试预算封顶（attempt=3 / max_retries=3 → 不再重试）；</li>
 *   <li>失败码可重试与否（口径唯一出自 FailureCodeRegistry）；</li>
 *   <li>边界：负 attempt、非正基数、超大 attempt 不溢出、封顶后不超 30s。</li>
 * </ul>
 */
@DisplayName("Task 21 — RetryPolicy 指数退避 + 抖动 + 重试预算")
class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy();

    // ──────── 退避时间序列 ────────

    @Test
    @DisplayName("6 种退避时间序列：attempt 0–5 → 1s/2s/4s/8s/16s/30s（封顶）")
    void exponentialSequenceForSixAttempts() {
        policy.setJitterSource(() -> 0.0d); // 关掉抖动，只看基线

        assertEquals(1000L, policy.baselineBackoffMs(0, 1000L));
        assertEquals(2000L, policy.baselineBackoffMs(1, 1000L));
        assertEquals(4000L, policy.baselineBackoffMs(2, 1000L));
        assertEquals(8000L, policy.baselineBackoffMs(3, 1000L));
        assertEquals(16000L, policy.baselineBackoffMs(4, 1000L));
        assertEquals(30000L, policy.baselineBackoffMs(5, 1000L), "attempt=5 的 32s 必须封顶到 30s");
    }

    @Test
    @DisplayName("实际退避时长（含抖动）在 attempt 0–5 上都落在基线 ±25% 内")
    void jitteredBackoffStaysInsideEnvelopeForEveryAttempt() {
        policy.setJitterSource(() -> 0.0d);
        long[] baselines = new long[6];
        for (int attempt = 0; attempt < 6; attempt++) {
            baselines[attempt] = policy.baselineBackoffMs(attempt, 1000L);
        }
        policy.setJitterSource(null); // 恢复随机抖动

        for (int attempt = 0; attempt < 6; attempt++) {
            long baseline = baselines[attempt];
            for (int i = 0; i < 200; i++) {
                long actual = policy.computeBackoffMs(attempt, 1000L);
                assertTrue(actual >= (long) (baseline * 0.75d),
                        "attempt=" + attempt + " 抖动下界越界: " + actual + " < " + (baseline * 0.75d));
                assertTrue(actual <= (long) (baseline * 1.25d),
                        "attempt=" + attempt + " 抖动上界越界: " + actual + " > " + (baseline * 1.25d));
            }
        }
    }

    @Test
    @DisplayName("抖动上下边界精确可控：+25% / -25% / 0%")
    void jitterBoundariesAreExact() {
        long baseline = policy.baselineBackoffMs(3, 1000L); // 8000

        policy.setJitterSource(() -> 1.0d);
        assertEquals((long) (baseline * 1.25d), policy.computeBackoffMs(3, 1000L));

        policy.setJitterSource(() -> -1.0d);
        assertEquals((long) (baseline * 0.75d), policy.computeBackoffMs(3, 1000L));

        policy.setJitterSource(() -> 0.0d);
        assertEquals(baseline, policy.computeBackoffMs(3, 1000L));
    }

    @Test
    @DisplayName("封顶作用在抖动之后：实际 sleep 永不超过 30s")
    void capAppliesAfterJitter() {
        policy.setJitterSource(() -> 1.0d);
        // attempt=5 的基线已是 30s（封顶），叠加 +25% 后仍必须是 30s
        assertEquals(RetryPolicy.MAX_BACKOFF_MS, policy.computeBackoffMs(5, 1000L));
        // 更小的基数也不会突破封顶
        assertTrue(policy.computeBackoffMs(20, 1000L) <= RetryPolicy.MAX_BACKOFF_MS);
    }

    // ──────── 重试预算 ────────

    @Test
    @DisplayName("max_retries=3：attempt 0–2 继续重试，attempt=3 判定失败")
    void maxRetriesCapsTheRetryBudget() {
        for (int attempt = 0; attempt <= 2; attempt++) {
            RetryPolicy.RetryDecision decision =
                    policy.decide(attempt, 3, 1000L, FailureCodeRegistry.CONNECTION_REFUSED);
            assertTrue(decision.shouldRetry(), "attempt=" + attempt + " 应当继续重试");
            assertTrue(decision.backoffMs() > 0L);
        }

        RetryPolicy.RetryDecision exhausted =
                policy.decide(3, 3, 1000L, FailureCodeRegistry.CONNECTION_REFUSED);
        assertFalse(exhausted.shouldRetry(), "attempt=3 且上限 3 时必须停止重试");
        assertEquals(0L, exhausted.backoffMs());
        assertTrue(exhausted.reason().contains("预算耗尽"), exhausted.reason());
    }

    @Test
    @DisplayName("max_retries=0：一次都不重试")
    void zeroBudgetMeansNoRetry() {
        RetryPolicy.RetryDecision decision =
                policy.decide(0, 0, 1000L, FailureCodeRegistry.CONNECTION_REFUSED);
        assertFalse(decision.shouldRetry());
    }

    @Test
    @DisplayName("负的重试上限按 0 处理（不重试）")
    void negativeBudgetIsClampedToZero() {
        assertFalse(policy.decide(0, -5, 1000L, FailureCodeRegistry.CONNECTION_REFUSED).shouldRetry());
    }

    // ──────── 失败码可重试与否 ────────

    @Test
    @DisplayName("配置型失败码不可重试：AE 未登记 / TLS 配错 / 传输语法无交集 / 外院确无该检查")
    void configurationFailuresAreNotRetried() {
        for (String code : new String[]{"association_rejected", "tls_mismatch",
                "transfer_syntax_empty", "sop_class_unsupported", "no_study_found"}) {
            assertFalse(policy.isRetryable(code), code + " 不应重试");
            RetryPolicy.RetryDecision decision = policy.decide(0, 3, 1000L, code);
            assertFalse(decision.shouldRetry(), code + " 不应重试");
            assertTrue(decision.reason().contains(code), decision.reason());
        }
    }

    @Test
    @DisplayName("瞬态失败码可重试：连不上 / 查询超时 / 拉回失败 / 写入失败")
    void transientFailuresAreRetried() {
        for (String code : new String[]{"connection_refused", "connect_timeout", "query_timeout",
                "query_failed", "retrieve_failed", "move_timeout", "sub_op_failed",
                "partial_failure", "store_failed", "store_timeout"}) {
            assertTrue(policy.isRetryable(code), code + " 应可重试");
            assertTrue(policy.decide(0, 3, 1000L, code).shouldRetry(), code + " 应可重试");
        }
    }

    @Test
    @DisplayName("未知失败码按不可重试处理（不瞎重试）")
    void unknownFailureCodeIsNotRetried() {
        assertFalse(policy.isRetryable("unknown"));
        assertFalse(policy.isRetryable("stuck_task_timeout"));
        assertFalse(policy.isRetryable(null));
        assertFalse(policy.isRetryable("随便一个不存在的码"));
        assertFalse(policy.decide(0, 3, 1000L, (String) null).shouldRetry());
    }

    @Test
    @DisplayName("放弃重试的理由里带上运维处置建议（信息科照着就能找到该找谁）")
    void giveUpReasonCarriesSuggestedAction() {
        RetryPolicy.RetryDecision decision =
                policy.decide(0, 3, 1000L, FailureCodeRegistry.TLS_MISMATCH);
        assertFalse(decision.shouldRetry());
        assertTrue(decision.reason().contains("tls_mismatch"));
        assertTrue(decision.reason().contains(FailureCodeRegistry.TLS_MISMATCH.suggestedAction()),
                decision.reason());
    }

    // ──────── 边界 ────────

    @Test
    @DisplayName("负 attempt → 0 毫秒（不退避）")
    void negativeAttemptGivesZero() {
        assertEquals(0L, policy.baselineBackoffMs(-1, 1000L));
        assertEquals(0L, policy.computeBackoffMs(-1, 1000L));
    }

    @Test
    @DisplayName("非正基数回落默认 1000ms")
    void nonPositiveBaseFallsBackToDefault() {
        assertEquals(1000L, policy.baselineBackoffMs(0, 0L));
        assertEquals(1000L, policy.baselineBackoffMs(0, -100L));
        assertEquals(2000L, policy.baselineBackoffMs(1, 0L));
    }

    @Test
    @DisplayName("超大 attempt 不溢出：结果恒为 30s，不出现负数")
    void hugeAttemptDoesNotOverflow() {
        assertEquals(RetryPolicy.MAX_BACKOFF_MS, policy.baselineBackoffMs(31, 1000L));
        assertEquals(RetryPolicy.MAX_BACKOFF_MS, policy.baselineBackoffMs(40, 1000L));
        assertEquals(RetryPolicy.MAX_BACKOFF_MS, policy.baselineBackoffMs(1000, 1000L));
        assertTrue(policy.computeBackoffMs(1000, 1000L) > 0L);
    }

    @Test
    @DisplayName("基数本身超过封顶时直接取封顶")
    void baseAboveCapIsClamped() {
        assertEquals(RetryPolicy.MAX_BACKOFF_MS, policy.baselineBackoffMs(0, 60000L));
    }

    @Test
    @DisplayName("重试决定记录了退避时长与理由")
    void decisionCarriesBackoffAndReason() {
        policy.setJitterSource(() -> 0.0d);
        RetryPolicy.RetryDecision decision =
                policy.decide(0, 3, 1000L, FailureCodeRegistry.CONNECTION_REFUSED);
        assertTrue(decision.shouldRetry());
        assertEquals(1000L, decision.backoffMs());
        assertTrue(decision.reason().contains("1/3"), decision.reason());
    }
}
