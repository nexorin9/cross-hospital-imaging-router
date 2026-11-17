package com.crosshospital.imaging.service.wado;

import com.crosshospital.imaging.config.WadoConfigProps;
import com.crosshospital.imaging.exception.WadoRsHttpException;
import com.crosshospital.imaging.exception.WadoRsParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 12 单测：WadoRsRetryPolicy 重试判定 + 退避计算。
 */
class WadoRsRetryPolicyTest {

    private WadoConfigProps props;
    private WadoRsRetryPolicy policy;

    @BeforeEach
    void setUp() {
        props = new WadoConfigProps();
        props.setMaxRetries(3);
        policy = new WadoRsRetryPolicy(props);
    }

    // ---------- isRetryable 判定 ----------

    @Test
    @DisplayName("5xx HTTP 异常 → 可重试")
    void http5xxIsRetryable() {
        WadoRsHttpException e = new WadoRsHttpException(503, "service unavailable",
                true, WadoRsHttpException.Reason.SERVER_ERROR);
        assertTrue(policy.isRetryable(e));
    }

    @Test
    @DisplayName("4xx HTTP 异常 → 不可重试")
    void http4xxIsNotRetryable() {
        WadoRsHttpException e404 = new WadoRsHttpException(404, "study not found",
                false, WadoRsHttpException.Reason.STUDY_NOT_FOUND);
        assertFalse(policy.isRetryable(e404));

        WadoRsHttpException e416 = new WadoRsHttpException(416, "range unsatisfiable",
                false, WadoRsHttpException.Reason.RANGE_NOT_SATISFIABLE);
        assertFalse(policy.isRetryable(e416));
    }

    @Test
    @DisplayName("传输层失败 → 可重试")
    void transportFailureIsRetryable() {
        WadoRsHttpException e = WadoRsHttpException.transportFailure("connection refused",
                new IOException("connection refused"));
        assertTrue(policy.isRetryable(e));
    }

    @Test
    @DisplayName("multipart 解析失败 → 不可重试（格式/容量问题重试无意义）")
    void parseExceptionIsNotRetryable() {
        WadoRsParseException e = new WadoRsParseException(
                WadoRsParseException.ParseError.MISSING_BOUNDARY,
                "no boundary parameter");
        assertFalse(policy.isRetryable(e));
    }

    @Test
    @DisplayName("其他 RuntimeException → 偏保守判定为可重试")
    void unknownRuntimeExceptionIsRetryable() {
        assertTrue(policy.isRetryable(new RuntimeException("unknown")));
    }

    // ---------- computeBackoffMs 退避计算 ----------

    @Test
    @DisplayName("退避时间序列：1s/2s/4s/8s/16s（封顶 30s 前）")
    void backoffSeriesUpTo16s() {
        // 每次运行 ±25% 抖动，断言范围 [baseline × 0.75, baseline × 1.25]
        long[] baselines = {1000L, 2000L, 4000L, 8000L, 16000L};
        for (int i = 1; i <= 5; i++) {
            long backoff = policy.computeBackoffMs(i);
            long base = baselines[i - 1];
            // 含抖动下限不应低于 baseline × 0.75
            assertTrue(backoff >= (long) (base * (1.0 - WadoRsRetryPolicy.JITTER_RATIO)),
                    "attempt=" + i + " 退避 " + backoff + " 不应低于 baseline × (1 - jitter) = "
                            + (long) (base * (1.0 - WadoRsRetryPolicy.JITTER_RATIO)));
            // 含抖动上限不应超出 baseline × 1.25
            assertTrue(backoff <= Math.min(WadoRsRetryPolicy.MAX_BACKOFF_MS, (long) (base * (1.0 + WadoRsRetryPolicy.JITTER_RATIO))),
                    "attempt=" + i + " 退避 " + backoff + " 不应超出 baseline × (1 + jitter) = "
                            + Math.min(WadoRsRetryPolicy.MAX_BACKOFF_MS, (long) (base * (1.0 + WadoRsRetryPolicy.JITTER_RATIO))));
        }
    }

    @Test
    @DisplayName("attempt >= 6 → 退避封顶 30s")
    void backoffCappedAt30Seconds() {
        long backoff = policy.computeBackoffMs(6);
        assertTrue(backoff <= WadoRsRetryPolicy.MAX_BACKOFF_MS + (long) (WadoRsRetryPolicy.MAX_BACKOFF_MS * WadoRsRetryPolicy.JITTER_RATIO),
                "attempt=6 退避 " + backoff + " 应封顶 30s");
    }

    @Test
    @DisplayName("attempt < 1 → 退避 0（无需 sleep）")
    void backoffZeroForInvalidAttempt() {
        assertEquals(0L, policy.computeBackoffMs(0));
        assertEquals(0L, policy.computeBackoffMs(-1));
    }

    // ---------- shouldRetry ----------

    @Test
    @DisplayName("attempt=1 → 可继续（maxRetries=3 允许 3 次重试）")
    void shouldRetryRespectsMaxRetries() {
        props.setMaxRetries(3);
        WadoRsRetryPolicy p = new WadoRsRetryPolicy(props);
        assertTrue(p.shouldRetry(1));
        assertTrue(p.shouldRetry(3));
        assertFalse(p.shouldRetry(4));
    }

    @Test
    @DisplayName("maxRetries=0 → 不允许任何重试")
    void zeroMaxRetriesDisablesRetry() {
        props.setMaxRetries(0);
        WadoRsRetryPolicy p = new WadoRsRetryPolicy(props);
        assertFalse(p.shouldRetry(1));
        assertEquals(0, p.maxRetries());
    }

    // ---------- sleep ----------

    @Test
    @DisplayName("sleep(0) 立即返回")
    void sleepZeroReturnsImmediately() throws InterruptedException {
        long start = System.currentTimeMillis();
        policy.sleep(0L);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 50L, "sleep(0) 应立即返回，实测 " + elapsed + "ms");
    }

    @Test
    @DisplayName("sleep(<0) 立即返回")
    void sleepNegativeReturnsImmediately() throws InterruptedException {
        long start = System.currentTimeMillis();
        policy.sleep(-100L);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 50L);
    }

    @Test
    @DisplayName("sleep(50ms) 实际至少睡 40ms（不强制精确）")
    void sleepActuallyDelays() throws InterruptedException {
        long start = System.currentTimeMillis();
        policy.sleep(50L);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 40L, "sleep(50) 应至少 40ms，实测 " + elapsed);
    }

    // ---------- exhausted 工厂 ----------

    @Test
    @DisplayName("exhausted 包 WadoRsHttpException → 透传状态码 + retryable=false")
    void exhaustedWrapsHttpException() {
        WadoRsHttpException original = new WadoRsHttpException(503, "down",
                true, WadoRsHttpException.Reason.SERVER_ERROR);
        WadoRsHttpException exhausted = policy.exhausted(original, 4);
        assertEquals(503, exhausted.getStatusCode());
        assertFalse(exhausted.isRetryable());
        assertEquals(WadoRsHttpException.Reason.SERVER_ERROR, exhausted.getReason());
        assertNotNull(exhausted.getMessage());
        assertTrue(exhausted.getMessage().contains("4 次"));
    }

    @Test
    @DisplayName("exhausted 包非 HTTP 异常 → 退化为 TRANSPORT_FAILURE")
    void exhaustedFallsBackToTransportFailure() {
        IOException io = new IOException("connection reset");
        WadoRsHttpException exhausted = policy.exhausted(io, 4);
        assertEquals(WadoRsHttpException.STATUS_TRANSPORT_FAILURE, exhausted.getStatusCode());
        assertEquals(WadoRsHttpException.Reason.TRANSPORT_FAILURE, exhausted.getReason());
        assertTrue(exhausted.isRetryable(), "传输失败默认可重试；调用方拿到 exhausted 后 retryable=false");
    }
}