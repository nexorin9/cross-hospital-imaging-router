package com.crosshospital.imaging.service.task;

import java.util.concurrent.ThreadLocalRandom;

import com.crosshospital.imaging.service.routing.FailureCodeRegistry;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 任务级重试策略（task 21）—— 指数退避 + 抖动 + 重试预算判定。
 *
 * <p><b>它解决的是哪一次卡住</b>：外院 PACS 正在重启、链路抖了一下、
 * 磁盘写满恢复了一会儿 —— 这类「过一会儿会自己好」的故障，
 * 一次失败不代表这次调阅做不成。可要是每种失败都重试，
 * AE 白名单没登记这类「改配置才能好」的故障会被重试到天荒地老，
 * 还把对方 PACS 的日志刷满、把这次调阅拖长到预算上限。
 *
 * <p>所以本类只回答两个问题：
 * <ol>
 *   <li><b>该不该再试</b> —— 失败码可重试（{@link FailureCodeRegistry#retryable()}）
 *       且重试预算没花完；</li>
 *   <li><b>隔多久再试</b> —— 指数退避 {@code base × 2^attempt}，封顶 30 秒，
 *       叠加 ±25% 抖动。</li>
 * </ol>
 *
 * <h2>两层重试的分工（别搞混）</h2>
 * <pre>
 *   协议步内重试（task 16 DicomRoutingRetryDecorator）
 *       └─ 重试「一次 C-FIND / C-MOVE / C-STORE 调用」，预算取自节点配置，无抖动
 *   任务级重试（本类 + RetryScheduler）
 *       └─ 重试「整条调阅任务」，预算同样取自节点配置，本层负责抖动
 * </pre>
 * <p>task 16 的装饰器明确写了「抖动留给 task 21」—— 两层各管一件事，
 * 同一批任务同刻失败时只有任务层被打散，协议层保持确定的节奏，便于排障对时。
 *
 * <h2>为什么抖动是 ±25% 而不是随机 0~2×</h2>
 * <p>纯随机（0~2×）在基数为 1 秒时可以退到 0 秒 —— 一批任务会在同一毫秒醒来再打一次；
 * 而固定 ±25% 保证「至少等了基数的 3/4」，既打散又不至于退化成忙等。
 *
 * <h2>封顶 30 秒</h2>
 * <p>跨院调阅一次最多几分钟，封到 30 秒后即使再失败也按 30 秒等 ——
 * 外院 PACS 恢复通常在分钟级，等 5 分钟没有意义，等 30 秒足够错开重启窗口。
 * 封顶同时规避了 {@code base × 2^attempt} 在 attempt 很大时的整型溢出
 * （{@code 1L << 31} 会翻成负数，负的 sleep 在某些平台直接抛异常）。
 *
 * <p>源主路径证据：通用分布式系统重试模式；参数与本仓库
 * {@code WadoRsRetryPolicy}（WADO-RS 侧）保持同一组基准值，两侧节奏不打架。
 */
@Component
@Profile("!test")
public class RetryPolicy {

    /** 退避基数（毫秒）—— 与 {@code imaging_peers.backoff_ms} 的迁移默认值一致。 */
    public static final long DEFAULT_BASE_BACKOFF_MS = 1000L;

    /** 退避封顶（毫秒）：实际 sleep 永不超过这个值（含抖动后）。 */
    public static final long MAX_BACKOFF_MS = 30_000L;

    /** 抖动比例：±25%。 */
    public static final double JITTER_RATIO = 0.25;

    /** 默认重试上限（不含首次尝试）—— 与 {@code imaging_peers.max_retries} 的迁移默认值一致。 */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /**
     * 抖动来源 —— 抽成接口是为了让单测能钉死抖动边界。
     *
     * <p>返回值域为 {@code [-1, 1]}，实际抖动幅度由 {@link #JITTER_RATIO} 决定。
     */
    @FunctionalInterface
    public interface JitterSource {
        /** @return {@code [-1, 1]} 区间的抖动因子 */
        double nextFactor();
    }

    private static final JitterSource RANDOM_JITTER =
            () -> ThreadLocalRandom.current().nextDouble(-1.0d, 1.0d);

    private volatile JitterSource jitterSource = RANDOM_JITTER;

    /** 替换抖动来源（单测用；传 {@code null} 恢复随机抖动）。 */
    public void setJitterSource(JitterSource source) {
        this.jitterSource = source == null ? RANDOM_JITTER : source;
    }

    /**
     * 纯指数退避（<b>不含抖动</b>）—— 基数 × 2^attempt，封顶 {@value #MAX_BACKOFF_MS} ms。
     *
     * <p>序列（基数 1000ms）：attempt 0→1s、1→2s、2→4s、3→8s、4→16s、5→32s→封顶 30s。
     *
     * @param attempt     0-indexed 的已重试次数（attempt=0 表示第一次失败后就要等）
     * @param baseBackoffMs 退避基数（毫秒）；非正数时用 {@link #DEFAULT_BASE_BACKOFF_MS}
     * @return 退避基线（毫秒）
     */
    public long baselineBackoffMs(int attempt, long baseBackoffMs) {
        if (attempt < 0) {
            return 0L;
        }
        long base = baseBackoffMs <= 0 ? DEFAULT_BASE_BACKOFF_MS : baseBackoffMs;
        if (base >= MAX_BACKOFF_MS) {
            return MAX_BACKOFF_MS;
        }
        if (attempt >= 31) {
            // base << 31 在 long 下已是 2^31 倍，直接判封顶，连中间态都不碰
            return MAX_BACKOFF_MS;
        }
        long scaled = base << attempt;
        return Math.min(scaled, MAX_BACKOFF_MS);
    }

    /**
     * 指数退避 + ±25% 抖动 —— 实际要睡的时长。
     *
     * <p>结果落在 {@code [基线 × 0.75, 基线 × 1.25]}，并且**永远不超过**
     * {@link #MAX_BACKOFF_MS}：封顶作用在抖动之后的值上，
     * 保证「最多等 30 秒」这条承诺对调用方是真的。
     *
     * @param attempt       0-indexed 的已重试次数
     * @param baseBackoffMs 退避基数（毫秒）
     * @return 实际退避时长（毫秒）
     */
    public long computeBackoffMs(int attempt, long baseBackoffMs) {
        long baseline = baselineBackoffMs(attempt, baseBackoffMs);
        if (baseline <= 0L) {
            return 0L;
        }
        double factor = jitterSource.nextFactor();
        double jittered = baseline * (1.0d + factor * JITTER_RATIO);
        if (jittered < 0.0d) {
            jittered = 0.0d;
        }
        return Math.min((long) jittered, MAX_BACKOFF_MS);
    }

    /**
     * 该失败码是否值得重试。
     *
     * <p>口径唯一出自 {@link FailureCodeRegistry} —— 失败码词表只有那一张，
     * 「该不该重试」由它说了算，本类不另立一套。
     *
     * @param failureCode 失败码（{@code imaging_fetch_tasks.failure_code} 的值）
     */
    public boolean isRetryable(String failureCode) {
        return FailureCodeRegistry.fromDbValue(failureCode).retryable();
    }

    /**
     * 一次失败之后要不要再试、隔多久再试 —— 本类的**唯一决策入口**。
     *
     * @param attempt        已重试次数（0 = 第一次就失败了）
     * @param maxRetries     重试预算上限（不含首次尝试）
     * @param baseBackoffMs  退避基数（毫秒）
     * @param failureCode    失败码
     * @return 决策结果（{@link RetryDecision}）
     */
    public RetryDecision decide(int attempt, int maxRetries, long baseBackoffMs, String failureCode) {
        FailureCodeRegistry code = FailureCodeRegistry.fromDbValue(failureCode);
        return decide(attempt, maxRetries, baseBackoffMs, code);
    }

    /**
     * 一次失败之后要不要再试、隔多久再试。
     *
     * @param attempt       已重试次数（0 = 第一次就失败了）
     * @param maxRetries    重试预算上限（不含首次尝试）
     * @param baseBackoffMs 退避基数（毫秒）
     * @param code          失败码
     */
    public RetryDecision decide(int attempt, int maxRetries, long baseBackoffMs, FailureCodeRegistry code) {
        if (code == null) {
            return RetryDecision.giveUp("失败码缺失，按不可重试处理");
        }
        if (!code.retryable()) {
            return RetryDecision.giveUp("失败码 " + code.code() + " 不可重试：" + code.suggestedAction());
        }
        int budget = Math.max(0, maxRetries);
        if (attempt >= budget) {
            return RetryDecision.giveUp("重试预算耗尽（已重试 " + attempt + " 次，上限 " + budget + " 次）");
        }
        long backoff = computeBackoffMs(attempt, baseBackoffMs);
        return RetryDecision.retry(backoff,
                "第 " + (attempt + 1) + "/" + budget + " 次重试，退避 " + backoff + " ms");
    }

    /**
     * 重试决策结果。
     *
     * @param shouldRetry 是否还应继续重试
     * @param backoffMs   退避时长（毫秒）；{@code shouldRetry=false} 时为 0
     * @param reason      判定理由（直接写进日志与审计，信息科看得懂）
     */
    public record RetryDecision(boolean shouldRetry, long backoffMs, String reason) {

        public static RetryDecision retry(long backoffMs, String reason) {
            return new RetryDecision(true, Math.max(0L, backoffMs), reason);
        }

        public static RetryDecision giveUp(String reason) {
            return new RetryDecision(false, 0L, reason);
        }
    }
}
