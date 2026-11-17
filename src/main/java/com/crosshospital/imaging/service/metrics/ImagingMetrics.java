package com.crosshospital.imaging.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.function.Supplier;

/**
 * task 37 运行指标门面 —— 「这条链路跑了多少次、多慢、坏了多少」的唯一出入口。
 *
 * <p>指标名用下划线风格（Prometheus 暴露时即 {@code imaging_fetch_total} 等），
 * 与 {@code wado.*} / {@code hl7_*_total} 这些早期指标并存不冲突。
 *
 * <table border="1">
 *   <tr><th>指标</th><th>类型</th><th>含义</th></tr>
 *   <tr><td>imaging_fetch_total</td><td>Counter</td><td>受理入队的一次跨院调阅</td></tr>
 *   <tr><td>imaging_fetch_failed_total</td><td>Counter</td><td>推进到 failed 终态的任务</td></tr>
 *   <tr><td>imaging_fetch_duration_seconds</td><td>Timer</td><td>从受理到 notified 的耗时</td></tr>
 *   <tr><td>ai_call_total</td><td>Counter</td><td>模型网关真实发出的调用次数</td></tr>
 *   <tr><td>ai_call_latency_seconds</td><td>Timer</td><td>模型调用耗时</td></tr>
 *   <tr><td>dcm_handshake_total</td><td>Counter</td><td>DICOM 建联尝试次数</td></tr>
 *   <tr><td>imaging_peers_count</td><td>Gauge</td><td>准入表里启用的外院节点数</td></tr>
 *   <tr><td>dlq_unresolved_count</td><td>Gauge</td><td>未处置死信条数</td></tr>
 * </table>
 *
 * <p>为什么不直接在各 service 里 {@code Counter.builder(...).register(...)}：
 * 同一名字重复注册会拿到不同实例，指标会被切成几份；集中在这里注册一次，
 * 各 service 只调语义方法，也不会把「该不该算一次」的判断散落到各层。
 */
public class ImagingMetrics {

    public static final String FETCH_TOTAL = "imaging_fetch_total";
    public static final String FETCH_FAILED_TOTAL = "imaging_fetch_failed_total";
    public static final String FETCH_DURATION = "imaging_fetch_duration_seconds";
    public static final String AI_CALL_TOTAL = "ai_call_total";
    public static final String AI_CALL_LATENCY = "ai_call_latency_seconds";
    public static final String DCM_HANDSHAKE_TOTAL = "dcm_handshake_total";
    public static final String PEERS_COUNT = "imaging_peers_count";
    public static final String DLQ_UNRESOLVED = "dlq_unresolved_count";

    private final MeterRegistry registry;
    private final Counter fetchTotal;
    private final Counter fetchFailedTotal;
    private final Counter aiCallTotal;
    private final Counter dcmHandshakeTotal;
    private final Timer fetchDuration;
    private final Timer aiCallLatency;

    /** 单测 / 无数据库环境用：两个 gauge 恒为 0。 */
    public ImagingMetrics(MeterRegistry registry) {
        this(registry, () -> 0, () -> 0);
    }

    public ImagingMetrics(MeterRegistry registry,
                          Supplier<Number> enabledPeers,
                          Supplier<Number> unresolvedDlq) {
        this.registry = registry;
        this.fetchTotal = Counter.builder(FETCH_TOTAL)
                .description("跨院调阅任务受理总数").register(registry);
        this.fetchFailedTotal = Counter.builder(FETCH_FAILED_TOTAL)
                .description("推进到 failed 终态的调阅任务数").register(registry);
        this.aiCallTotal = Counter.builder(AI_CALL_TOTAL)
                .description("模型网关调用总数").register(registry);
        this.dcmHandshakeTotal = Counter.builder(DCM_HANDSHAKE_TOTAL)
                .description("DICOM 建联尝试总数").register(registry);
        this.fetchDuration = Timer.builder(FETCH_DURATION)
                .description("跨院调阅受理到通知的耗时").register(registry);
        this.aiCallLatency = Timer.builder(AI_CALL_LATENCY)
                .description("模型调用耗时").register(registry);

        Supplier<Number> peers = enabledPeers == null ? () -> 0 : enabledPeers;
        Supplier<Number> dlq = unresolvedDlq == null ? () -> 0 : unresolvedDlq;
        Gauge.builder(PEERS_COUNT, peers)
                .description("准入表中启用的外院节点数").register(registry);
        Gauge.builder(DLQ_UNRESOLVED, dlq)
                .description("未处置死信条数").register(registry);
    }

    /** 受理一条新任务（幂等复用的旧任务不算新受理）。 */
    public void fetchAccepted() {
        fetchTotal.increment();
    }

    /** 任务转 failed 终态。 */
    public void fetchFailed() {
        fetchFailedTotal.increment();
    }

    /** 开始计时；返回 null 表示未启用指标。 */
    public Timer.Sample startFetch() {
        return Timer.start(registry);
    }

    /** 推进到 notified 时结束计时。 */
    public void fetchCompleted(Timer.Sample sample) {
        if (sample != null) {
            sample.stop(fetchDuration);
        }
    }

    /** 模型网关发起一次真实调用。 */
    public void aiCallStarted() {
        aiCallTotal.increment();
    }

    public Timer.Sample startAiCall() {
        return Timer.start(registry);
    }

    public void aiCallCompleted(Timer.Sample sample) {
        if (sample != null) {
            sample.stop(aiCallLatency);
        }
    }

    /** 一次 DICOM 建联尝试。 */
    public void dicomHandshake() {
        dcmHandshakeTotal.increment();
    }

    /** 供测试断言：当前累计值。 */
    public double fetchTotalCount() {
        return fetchTotal.count();
    }

    public double fetchFailedCount() {
        return fetchFailedTotal.count();
    }

    public double aiCallCount() {
        return aiCallTotal.count();
    }

    public double dcmHandshakeCount() {
        return dcmHandshakeTotal.count();
    }
}
