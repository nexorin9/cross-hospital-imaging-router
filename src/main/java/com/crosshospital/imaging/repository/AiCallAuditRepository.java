package com.crosshospital.imaging.repository;

import com.crosshospital.imaging.domain.AiCallAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ModelGateway 调用审计 Repository。
 *
 * <p>每次模型调用落一行（成功 / 失败）；不引入 append-only 触发器，
 * 因为本表允许运维按 created_at 滚动归档。
 */
public interface AiCallAuditRepository extends JpaRepository<AiCallAudit, Long> {

    List<AiCallAudit> findAllByTaskId(UUID taskId);

    Page<AiCallAudit> findByStatus(String status, Pageable pageable);

    /** 失败率统计（按时间窗 + status）。 */
    @Query("select a.status, count(a) from AiCallAudit a "
            + "where a.createdAt >= :from and a.createdAt < :to "
            + "group by a.status")
    List<Object[]> aggregateByStatusBetween(@Param("from") Instant from,
                                            @Param("to") Instant to);

    /**
     * 平均延迟（按调用来源分组）。
     *
     * <p><b>为什么按 {@code kind} 而不是 {@code source}</b>：{@code ai_call_audit}
     * （V6）没有 {@code source} 列 —— 本表用 {@code kind} 标识"这次模型调用来自哪个介入点"
     * （{@code report_normalize} / {@code feasibility_screener}），用 {@code model_name} 标识模型，
     * 用 {@code status} 标识结局。原查询写的是实体上不存在的 {@code a.source}，
     * Spring Data 在<b>建仓阶段</b>就校验 JPQL 并抛 {@code QueryCreationException}，
     * 导致整个 Spring 容器起不来（连只读接口都打不开）。
     *
     * <p>本方法按 {@code kind} 聚合，即"哪个介入点慢"——这是本表现有列能支撑的最接近
     * "按来源"的切法；若日后要区分"规则兜底 vs 模型调用"，需在 V6 之后新增一列并同步实体。
     */
    @Query("select a.kind, avg(a.latencyMs) from AiCallAudit a "
            + "where a.createdAt >= :from and a.createdAt < :to "
            + "and a.latencyMs is not null "
            + "group by a.kind")
    List<Object[]> averageLatencyBySource(@Param("from") Instant from,
                                        @Param("to") Instant to);
}