package com.crosshospital.imaging.repository;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 调阅任务 Repository（按五态机持久化推进）。
 *
 * <p>幂等去重的核心方法 {@code findActiveByPatientMpiAndStudyUid}（按
 * 未终结状态）配合 V2 数据库 UNIQUE INDEX 双重防 race；高并发场景下
 * race condition 由数据库约束兜底，应用层 {@code IdempotencyGuard} 优先
 * 走 SELECT 再 INSERT。
 */
public interface FetchTaskRepository extends JpaRepository<FetchTask, UUID> {

    Optional<FetchTask> findByTaskId(UUID taskId);

    /**
     * 批量取任务（死信列表补上下文用，避免逐行查库的 N+1）。
     */
    List<FetchTask> findByTaskIdIn(Collection<UUID> taskIds);

    /**
     * 在「未终结」期内查找同 (patient_mpi, study_uid) 的现有 task。
     *
     * <p>对应 V2 的 UNIQUE INDEX
     * {@code idx_fetch_tasks_idem}（WHERE status IN 4 个未终结态）。
     */
    @Query("select t from FetchTask t where t.patientMpi = :patientMpi "
            + "and t.studyUid = :studyUid "
            + "and t.status in (com.crosshospital.imaging.domain.TaskStatus.QUEUED, "
            + "com.crosshospital.imaging.domain.TaskStatus.QUERYING, "
            + "com.crosshospital.imaging.domain.TaskStatus.PULLING, "
            + "com.crosshospital.imaging.domain.TaskStatus.STORED)")
    Optional<FetchTask> findActiveByPatientMpiAndStudyUid(
            @Param("patientMpi") String patientMpi,
            @Param("studyUid") String studyUid);

    List<FetchTask> findByStatus(TaskStatus status);

    Page<FetchTask> findByStatus(TaskStatus status, Pageable pageable);

    /**
     * 按幂等键查<b>全部</b>历史任务（含已终结，按创建时间倒序）。
     *
     * <p>与 {@link #findActiveByPatientMpiAndStudyUid} 的区别：后者只查未终结的
     * 「在办」任务；本方法给出的是「这个病人的这次检查，本院历史上调阅过几回」，
     * 是信息科核对互认记录时问的问题。
     */
    List<FetchTask> findByPatientMpiAndStudyUidOrderByCreatedAtDesc(String patientMpi,
                                                                    String studyUid);

    /**
     * 按状态集合查任务（运营看板 / 批量重放）。
     */
    List<FetchTask> findByStatusIn(Collection<TaskStatus> statuses);

    /**
     * 卡死任务（非终态且长时间没动）。
     *
     * <p>{@link #findStuckTasks(TaskStatus, Instant)} 是单状态版本；
     * 本方法覆盖全部非终态（queued/querying/pulling/stored），
     * 因为「卡死」不只发生在查询阶段 —— 拉回阶段被外院挂住同样算卡死。
     */
    @Query("select t from FetchTask t where t.status in ("
            + "com.crosshospital.imaging.domain.TaskStatus.QUEUED, "
            + "com.crosshospital.imaging.domain.TaskStatus.QUERYING, "
            + "com.crosshospital.imaging.domain.TaskStatus.PULLING, "
            + "com.crosshospital.imaging.domain.TaskStatus.STORED) "
            + "and t.updatedAt < :before")
    List<FetchTask> findStuckNonTerminalTasks(@Param("before") Instant before);

    /**
     * 卡死任务检测（{@code StuckTaskDetector} 用）：status=querying 且
     * updated_at 早于阈值 → 重置为 queued 重新调度。
     */
    @Query("select t from FetchTask t where t.status = :status and t.updatedAt < :before")
    List<FetchTask> findStuckTasks(@Param("status") TaskStatus status,
                                   @Param("before") Instant before);

    @Query("select count(t) from FetchTask t where t.status = :status")
    long countByStatus(@Param("status") TaskStatus status);

    /**
     * 按状态 + 时间窗统计（运营报表 / 容量监控）。
     */
    @Query("select t.status, count(t) from FetchTask t "
            + "where t.createdAt >= :from and t.createdAt < :to group by t.status")
    List<Object[]> aggregateByStatusBetween(@Param("from") Instant from,
                                            @Param("to") Instant to);
}