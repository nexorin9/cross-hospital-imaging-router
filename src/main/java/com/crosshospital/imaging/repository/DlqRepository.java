package com.crosshospital.imaging.repository;

import com.crosshospital.imaging.domain.DlqEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 调阅任务死信队列 Repository。
 *
 * <p>未处置行（{@code resolved_at IS NULL}）走 {@code findUnresolved*}，
 * 由 V5 局部索引 {@code idx_dlq_unresolved} 兜底。
 */
public interface DlqRepository extends JpaRepository<DlqEntry, Long> {

    /** 未处置列表（按 created_at 倒序，V5 idx_dlq_unresolved 局部索引生效）。 */
    @Query("select d from DlqEntry d where d.resolvedAt is null order by d.createdAt desc")
    Page<DlqEntry> findUnresolved(Pageable pageable);

    @Query("select d from DlqEntry d where d.resolvedAt is null and d.alertWebhookSent = false")
    List<DlqEntry> findUnresolvedAlertPending(Pageable pageable);

    /**
     * 未处置列表 + 失败码过滤（信息科按失败码分组排障：外院全断 vs 某一个 AET 被挡）。
     */
    @Query("select d from DlqEntry d where d.resolvedAt is null and d.failureCode = :failureCode "
            + "order by d.createdAt desc")
    Page<DlqEntry> findUnresolvedByFailureCode(@Param("failureCode") String failureCode,
                                                Pageable pageable);

    /**
     * 告警冷却窗口判定：最近这段时间内，该失败码有没有成功告警过。
     *
     * <p>用于「同码告警风暴抑制」—— 一次外院断网会同时产生几十条同码死信，
     * 只推第一条告警，其余只落库。跨实例 / 重启后依然生效（不依赖进程内状态）。
     */
    @Query("select count(d) from DlqEntry d where d.failureCode = :failureCode "
            + "and d.alertWebhookSent = true and d.createdAt > :threshold")
    long countAlertedSince(@Param("failureCode") String failureCode,
                            @Param("threshold") Instant threshold);

    /** 信息科手动 mark resolved 时用：返回当前 dlq 详情。 */
    @Query("select d from DlqEntry d where d.id = :id and d.resolvedAt is null")
    java.util.Optional<DlqEntry> findUnresolvedById(@Param("id") Long id);

    List<DlqEntry> findAllByTaskId(UUID taskId);

    /**
     * 保留策略：resolved_at < now() - retentionDays 的行可清理。
     */
    @Query("select d from DlqEntry d where d.resolvedAt is not null and d.resolvedAt < :before")
    List<DlqEntry> findResolvedBefore(@Param("before") Instant before);

    long countByResolvedAtIsNull();
}