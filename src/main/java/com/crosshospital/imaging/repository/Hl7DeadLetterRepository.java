package com.crosshospital.imaging.repository;

import com.crosshospital.imaging.domain.Hl7DeadLetter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * HL7 触发报文死信 Repository（{@code dlq_hl7_messages}）。
 *
 * <p>与 {@link DlqRepository}（调阅任务死信）同构：未处置列表用局部索引
 * {@code idx_dlq_hl7_unresolved}（{@code resolved_at IS NULL}）支撑。
 */
public interface Hl7DeadLetterRepository extends JpaRepository<Hl7DeadLetter, Long> {

    /** 按消息控制 ID 回查（信息科拿 HIS 给的控制 ID 找死信原文位置）。 */
    List<Hl7DeadLetter> findByMessageControlId(String messageControlId);

    /** 按失败分类统计（运营看板：哪一类死信最多）。 */
    @Query("select d.failureCode, count(d) from Hl7DeadLetter d group by d.failureCode")
    List<Object[]> aggregateByFailureCode();

    /** 未处置死信（{@code resolved_at IS NULL}）。 */
    @Query("select d from Hl7DeadLetter d where d.resolvedAt is null "
            + "order by d.createdAt desc")
    Page<Hl7DeadLetter> findUnresolved(Pageable pageable);

    /** 未处置且尚未推过告警 webhook 的死信（补推告警用）。 */
    @Query("select d from Hl7DeadLetter d where d.resolvedAt is null "
            + "and d.alertWebhookSent = false order by d.createdAt asc")
    List<Hl7DeadLetter> findUnresolvedWithoutAlert();

    /** 按时间窗统计死信量（按天出「触发报文失败趋势」）。 */
    @Query("select count(d) from Hl7DeadLetter d "
            + "where d.createdAt >= :from and d.createdAt < :to")
    long countBetween(@Param("from") Instant from, @Param("to") Instant to);
}
