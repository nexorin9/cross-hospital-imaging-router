package com.crosshospital.imaging.repository;

import com.crosshospital.imaging.domain.AuditRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * append-only 审计表 Repository。
 *
 * <p><b>编译期防御</b>：本 Repository 继承 Spring Data {@link Repository}
 * 标记接口（而非 {@code JpaRepository}），因此 <b>不暴露</b> 自动派生的
 * {@code delete*}/{@code deleteAll}/{@code deleteById} 等变更方法。
 * 任何"修改审计"的尝试既会被数据库 {@code audit_no_update} 触发器拒绝，
 * 也会在编译期缺方法。
 *
 * <p>公开方法仅含 {@code save}（INSERT only）和多种读语义。
 *
 * <p>源主路径证据：dcm4che-net-audit 应用层审计模块。
 */
public interface AuditRepository extends Repository<AuditRow, Long> {

    /**
     * 显式声明的 INSERT：save 在持久化上下文外即纯 INSERT。
     * 由于 {@code AuditRow} 用 {@code @Immutable} 注解，Hibernate
     * 也不会发出 UPDATE 语句。
     */
    AuditRow save(AuditRow row);

    /**
     * 链式 checksum 计算所需：拿到最新一行的 row_checksum，作为新行的 prev_checksum。
     */
    @Query("select a.rowChecksum from AuditRow a order by a.id desc")
    List<byte[]> findLatestRowChecksums(Pageable pageable);

    default Optional<byte[]> findLatestRowChecksum() {
        List<byte[]> top = findLatestRowChecksums(Pageable.ofSize(1));
        return top.isEmpty() ? Optional.empty() : Optional.ofNullable(top.get(0));
    }

    @Query("select a from AuditRow a where a.id = :id")
    Optional<AuditRow> findById(@Param("id") Long id);

    /**
     * 取链尾（最新一行）—— 运维自检「链头到链尾是否接得上」时用。
     *
     * <p>与 {@link #findLatestRowChecksum()} 的区别：这里返回整行（含 id / 时间戳），
     * 便于把末行直接呈现给巡检人员；{@code findLatestRowChecksum} 只取校验和，
     * 供写入路径计算下一行的 {@code prev_checksum}。
     */
    @Query("select a from AuditRow a order by a.id desc")
    List<AuditRow> findLatestRows(Pageable pageable);

    /** 链尾行（{@code id} 最大的一行）；空表返回空 {@code Optional}。 */
    default Optional<AuditRow> findLatest() {
        List<AuditRow> top = findLatestRows(Pageable.ofSize(1));
        return top.isEmpty() ? Optional.empty() : Optional.ofNullable(top.get(0));
    }

    /**
     * 纯时间窗查询（不带其它过滤条件）。
     *
     * <p>与 {@link #queryByTimeWindow} 的分工：后者是多条件组合查询（飞检材料 / 运营报表），
     * 本方法是单条件快路径（一次调阅涉及的所有事件按时间取出来核对）。
     */
    @Query("select a from AuditRow a "
            + "where a.whenTs >= :fromTs and a.whenTs < :toTs "
            + "order by a.whenTs desc, a.id desc")
    Page<AuditRow> findByTimeWindow(@Param("fromTs") Instant fromTs,
                                    @Param("toTs") Instant toTs,
                                    Pageable pageable);

    /** 按源 AET 取行（我方向外院发起的那一侧）。 */
    @Query("select a from AuditRow a where a.fromAet = :fromAet order by a.id desc")
    List<AuditRow> findByFromAet(@Param("fromAet") String fromAet, Pageable pageable);

    /** 按目标 AET 取行（外院节点 / 本院 PACS 那一侧）。 */
    @Query("select a from AuditRow a where a.toAet = :toAet order by a.id desc")
    List<AuditRow> findByToAet(@Param("toAet") String toAet, Pageable pageable);

    /** 按终态标签取行（如 {@code stored} / {@code failed} / {@code handshake_established}）。 */
    @Query("select a from AuditRow a where a.statusTerminal = :statusTerminal order by a.id desc")
    List<AuditRow> findByStatusTerminal(@Param("statusTerminal") String statusTerminal,
                                        Pageable pageable);

    /**
     * 时间窗 + 多条件组合查询（运营报表 / 飞检材料）。
     */
    @Query("select a from AuditRow a "
            + "where a.whenTs >= :fromTs and a.whenTs < :toTs "
            + "and (:fromAet is null or a.fromAet = :fromAet) "
            + "and (:toAet is null or a.toAet = :toAet) "
            + "and (:statusTerminal is null or a.statusTerminal = :statusTerminal) "
            + "and (:who is null or a.who = :who) "
            + "order by a.whenTs desc, a.id desc")
    Page<AuditRow> queryByTimeWindow(
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs,
            @Param("fromAet") String fromAet,
            @Param("toAet") String toAet,
            @Param("statusTerminal") String statusTerminal,
            @Param("who") String who,
            Pageable pageable);

    @Query("select count(a) from AuditRow a where a.whenTs >= :fromTs and a.whenTs < :toTs")
    long countByTimeWindow(@Param("fromTs") Instant fromTs,
                           @Param("toTs") Instant toTs);
}