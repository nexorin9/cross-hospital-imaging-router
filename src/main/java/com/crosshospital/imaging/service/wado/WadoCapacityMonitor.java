package com.crosshospital.imaging.service.wado;

import com.crosshospital.imaging.exception.WadoRsHttpException;
import com.crosshospital.imaging.exception.WadoRsParseException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * WADO-RS 容量与运行监控（task 12）。
 *
 * <p>本类把每次拉取都打到 Micrometer：
 * <ul>
 *   <li><b>Counter</b>：{@code wado.bytes_fetched}（成功字节数）、
 *       {@code wado.objects_fetched}（成功 DICOM 对象数）、
 *       {@code wado.errors_total{reason=...}}（失败计数，按 Reason / ParseError 分桶）</li>
 *   <li><b>Timer</b>：{@code wado.fetch_duration_seconds}（含重试的总耗时）</li>
 *   <li><b>Gauge</b>：{@code wado.cache_mb_used}（PACS 缓存占用，由 task 32 维护）</li>
 * </ul>
 *
 * <p>指标名遵循 Micrometer 推荐的小写下划线风格；
 * Prometheus 端点（{@code /actuator/prometheus}）可直接消费。
 *
 * <p>源主路径证据：Micrometer + Prometheus 标准用法。
 */
@Component
@Profile("!test")
public class WadoCapacityMonitor {

    /** 缓存占用字节数（task 32 {@code PACS 缓存 LRU} 写入；本类只暴露 Gauge 入口）。 */
    private final AtomicLong cacheBytes = new AtomicLong(0L);

    private final Counter bytesFetched;
    private final Counter objectsFetched;
    private final Counter errorsHttp;
    private final Counter errorsParse;
    private final Counter errorsUnknown;
    private final Counter retriesConsumed;
    private final Timer fetchTimer;
    private final MeterRegistry registry;

    public WadoCapacityMonitor(MeterRegistry registry) {
        this.registry = registry;

        this.bytesFetched = Counter.builder("wado.bytes_fetched")
                .description("WADO-RS 成功拉取的字节数（multipart 解析后 DICOM 二进制总长）")
                .baseUnit("bytes")
                .register(registry);

        this.objectsFetched = Counter.builder("wado.objects_fetched")
                .description("WADO-RS 成功解析出的 DICOM 对象数")
                .baseUnit("instances")
                .register(registry);

        this.errorsHttp = Counter.builder("wado.errors")
                .description("WADO-RS HTTP 层失败计数")
                .tag("layer", "http")
                .register(registry);

        this.errorsParse = Counter.builder("wado.errors")
                .description("WADO-RS multipart 解析失败计数")
                .tag("layer", "parse")
                .register(registry);

        this.errorsUnknown = Counter.builder("wado.errors")
                .description("WADO-RS 未分类失败计数（兜底）")
                .tag("layer", "unknown")
                .register(registry);

        this.retriesConsumed = Counter.builder("wado.retries")
                .description("WADO-RS 重试次数累计（每次进入退避 sleep 累加 1）")
                .register(registry);

        this.fetchTimer = Timer.builder("wado.fetch_duration_seconds")
                .description("WADO-RS 一次 fetchStudyDetailed 调用总耗时（含重试）")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        Gauge.builder("wado.cache_mb_used", cacheBytes, this::mbUsed)
                .description("PACS 缓存当前占用 MB；由 task 32 PACS cache LRU 写入")
                .baseUnit("MB")
                .register(registry);
    }

    /**
     * 记录一次成功拉取：累加字节 + 对象数；启动定时器采样。
     */
    public void recordSuccess(long bytes, int objectCount, long elapsedMs) {
        bytesFetched.increment(bytes);
        objectsFetched.increment(objectCount);
        fetchTimer.record(java.time.Duration.ofMillis(Math.max(0, elapsedMs)));
    }

    /**
     * 记录一次 HTTP 层失败（带 Reason 标签）。
     */
    public void recordHttpFailure(WadoRsHttpException.Reason reason) {
        errorsHttp.increment();
        Counter.builder("wado.errors_by_reason")
                .description("WADO-RS 失败按 Reason / ParseError 分桶")
                .tag("reason", reason == null ? "unknown" : reason.name())
                .register(registry)
                .increment();
    }

    /**
     * 记录一次 multipart 解析失败（带 ParseError 标签）。
     */
    public void recordParseFailure(WadoRsParseException.ParseError parseError) {
        errorsParse.increment();
        Counter.builder("wado.errors_by_reason")
                .description("WADO-RS 失败按 Reason / ParseError 分桶")
                .tag("reason", parseError == null ? "unknown" : parseError.name())
                .register(registry)
                .increment();
    }

    /**
     * 记录一次未分类失败（兜底计数器）。
     */
    public void recordUnknownFailure(Throwable cause) {
        errorsUnknown.increment();
        Counter.builder("wado.errors_by_reason")
                .description("WADO-RS 失败按 Reason / ParseError 分桶")
                .tag("reason", "unknown:" + (cause == null ? "null" : cause.getClass().getSimpleName()))
                .register(registry)
                .increment();
    }

    /**
     * 记录一次重试退避（每次进入 sleep 前累加）。
     */
    public void recordRetry() {
        retriesConsumed.increment();
    }

    /**
     * PACS 缓存写入回调（task 32 接管）；单位字节。
     */
    public void updateCacheBytes(long bytes) {
        cacheBytes.set(Math.max(0L, bytes));
    }

    private double mbUsed(AtomicLong holder) {
        return holder.get() / (1024.0 * 1024.0);
    }

    /** 仅供单元测试：当前累计缓存字节数。 */
    public long currentCacheBytes() {
        return cacheBytes.get();
    }

    /** 仅供单元测试：当前 bytesFetched 累计计数。 */
    public double currentBytesFetched() {
        return bytesFetched.count();
    }

    /** 仅供单元测试：当前 objectsFetched 累计计数。 */
    public double currentObjectsFetched() {
        return objectsFetched.count();
    }

    /** 仅供单元测试：当前 errors 总数（http + parse + unknown）。 */
    public double currentErrorsTotal() {
        return errorsHttp.count() + errorsParse.count() + errorsUnknown.count();
    }

    /** 仅供单元测试：当前 retries 累计计数。 */
    public double currentRetries() {
        return retriesConsumed.count();
    }
}