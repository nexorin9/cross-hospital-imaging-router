package com.crosshospital.imaging.service.wado;

import com.crosshospital.imaging.config.WadoConfigProps;
import com.crosshospital.imaging.exception.WadoRsHttpException;
import com.crosshospital.imaging.exception.WadoRsParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * WADO-RS 拉取的重试策略（task 12）。
 *
 * <p>判定语义：
 * <ul>
 *   <li><b>可重试</b>：5xx、传输层失败（{@code TRANSPORT_FAILURE}）、超时；
 *       重试上限来自 {@code WadoConfigProps.maxRetries}（默认 3）。</li>
 *   <li><b>不可重试</b>：4xx（含 404 / 416 / 401 / 403）、multipart 解析失败、
 *       DICOM Part-10 前导缺失、响应体超大 —— 重试没有意义，立刻抛出。</li>
 * </ul>
 *
 * <p>退避策略：指数退避 1s/2s/4s/8s/16s 封顶 30s，叠加 ±25% 抖动（防雷击）。
 * task 21 的 {@code RetryPolicy} 借鉴同一组参数；本类专注于 WADO-RS 的
 * 协议层失败谱，与路由主路径的 {@code peer.max_retries/backoff_ms} 区分。
 *
 * <p>源主路径证据：HTTP 客户端最佳实践 + dcm4che {@code MultipartParser} 容错模式。
 */
@Component
@Profile("!test")
public class WadoRsRetryPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(WadoRsRetryPolicy.class);

    /** 退避基数（毫秒）；第 N 次重试的 baseline = BASE * 2^(attempt-1)。 */
    static final long BASE_BACKOFF_MS = 1000L;

    /** 退避上限（毫秒），避免长尾退避拖死 worker。 */
    static final long MAX_BACKOFF_MS = 30_000L;

    /** 抖动比例：±25%（防雷击）。 */
    static final double JITTER_RATIO = 0.25;

    private final WadoConfigProps props;

    public WadoRsRetryPolicy(WadoConfigProps props) {
        this.props = props;
    }

    /** 最大重试次数（不含首次尝试）。 */
    public int maxRetries() {
        return Math.max(0, props.getMaxRetries());
    }

    /**
     * 判断给定异常是否可重试。
     *
     * @param throwable 上层抛出的异常（WadoRsHttpException / WadoRsParseException / 其他）
     * @return true 表示可重试
     */
    public boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WadoRsHttpException http) {
            return http.isRetryable();
        }
        if (throwable instanceof WadoRsParseException) {
            // 解析失败一般是网关格式/容量问题，重试意义不大
            return false;
        }
        // 其他异常（如 WadoRsHttpException.transportFailure 包裹的 IOException）
        // 默认按可重试处理（任务级策略偏保守）
        return true;
    }

    /**
     * 计算下一次重试前的 sleep 时长（毫秒），含 ±25% 抖动。
     *
     * @param attempt 1-indexed 的重试次数（attempt=1 是首次重试前）
     * @return sleep 时长（毫秒），范围 [base * (1 - JITTER_RATIO), base * (1 + JITTER_RATIO)]
     */
    public long computeBackoffMs(int attempt) {
        if (attempt < 1) {
            return 0L;
        }
        long baseline = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L << Math.min(attempt - 1, 30)));
        double jitter = ThreadLocalRandom.current().nextDouble(-JITTER_RATIO, JITTER_RATIO);
        long withJitter = (long) (baseline * (1.0 + jitter));
        return Math.max(0L, withJitter);
    }

    /**
     * 判定是否还应继续重试（attempt 已用过 attempt 次且未超 maxRetries）。
     *
     * @param attempt 已尝试次数（含首次失败 = 1）
     * @return true 表示可继续重试
     */
    public boolean shouldRetry(int attempt) {
        return attempt <= maxRetries();
    }

    /**
     * Sleep 当前线程指定时长（便于在 task 12 调用方阻塞等待）。
     *
     * <p>单元测试可注入 {@code Sleeper} 桩以跳过真实睡眠。
     */
    public void sleep(long millis) throws InterruptedException {
        if (millis <= 0) return;
        Thread.sleep(millis);
        if (Thread.interrupted()) {
            throw new InterruptedException("WADO-RS 重试退避被中断");
        }
    }

    /**
     * 重试耗尽时的统一异常工厂：把最后一次失败的 cause 包成
     * {@code retriesExhausted=true} 的新异常。
     *
     * <p>对 {@link WadoRsHttpException} 透传状态码 / body / reason；对外层（RuntimeException）
     * 走 {@link WadoRsHttpException#transportFailure} 退化路径，并在消息里带上
     * {@code "重试 N 次后仍失败"} 供运营 / 审计查询定位。
     */
    public WadoRsHttpException exhausted(Throwable lastCause, int attempt) {
        if (lastCause instanceof WadoRsHttpException http) {
            String original = http.getMessage();
            String annotated = (original == null ? "" : original)
                    + "（已重试 " + attempt + " 次后耗尽）";
            return new WadoRsHttpException(
                    http.getStatusCode(),
                    http.getResponseBody(),
                    false, // 重试耗尽后不再 retryable
                    http.getReason(),
                    lastCause) {
                @Override public String getMessage() { return annotated; }
            };
        }
        return WadoRsHttpException.transportFailure(
                "WADO-RS 重试 " + attempt + " 次后仍失败: " + lastCause.getMessage(), lastCause);
    }

    /** 仅供 task 12 调用方在每次重试前打印 INFO 级别日志。 */
    public void logRetry(int attempt, long backoffMs, Throwable cause) {
        LOG.info("WADO-RS 第 {} 次重试前 sleep {} ms（maxRetries={}, cause={}）",
                attempt, backoffMs, maxRetries(),
                cause == null ? "n/a" : cause.getClass().getSimpleName()
                        + ": " + cause.getMessage());
    }
}