package com.crosshospital.imaging.service.task;

import java.util.Optional;
import java.util.UUID;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.service.AuditService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 任务重排服务 —— 「把一条没做完的调阅任务放回队列」这一个动作的归属地。
 *
 * <p><b>为什么要单独抽一个类</b>（而不是挂在 {@link FetchTaskService} 上）：
 * 任务级重试有<b>两个</b>发起方 ——
 * {@link RetryScheduler}（子步失败后决定再试）与 {@link StuckTaskDetector}（巡检捞回卡死任务）；
 * 而 {@code RetryScheduler} 又必须被 {@code FetchTaskService} 反向调用
 * （失败处置前先问「要不要再试」）。如果重排逻辑住在 {@code FetchTaskService} 上，
 * 依赖就绕成 {@code FetchTaskService → RetryScheduler → FetchTaskService} 的环，
 * Spring 容器启动时直接报「Requested bean is currently in creation」。
 * 把这一个动作抽出来之后，依赖图是干净的：
 *
 * <pre>
 *   FetchTaskService ──(setter)──▶ RetryScheduler ──▶ TaskRequeueService ──▶ Repository / Audit / Queue
 *   StuckTaskDetector ────────────▶ TaskRequeueService
 * </pre>
 *
 * <p><b>「退回 queued」不是一次业务态跃迁</b>：五态机
 * （{@code queued→querying→pulling→stored→notified}）约束的是「影像到哪一步了」，
 * 而重排表达的是「这次还没成，再来一次」—— 影像一个字节都没进本院 PACS，
 * 业务状态本就该留在起点。所以这是一次<b>显式授权的回退</b>，并且每次都留痕：
 * <ul>
 *   <li>状态回 {@code queued}（仍在幂等块内，唯一约束继续兜并发）；</li>
 *   <li>{@code retry_count} 记为本次重试的序号；</li>
 *   <li>{@code failure_code} 保留本次失败原因（信息科一眼看出「为什么重试」）；</li>
 *   <li>落一行审计（{@code status_terminal} 由调用方给出：
 *       {@code task_retry_scheduled} 或 {@code task_stuck_requeued}）。</li>
 * </ul>
 *
 * <p><b>终态任务不许回退</b>：已通知 HIS 的任务翻回去重跑，等于对外说了两次
 * 互相矛盾的话；已判失败的任务回退会让死信与库状态对不上。
 */
@Service
@Profile("!test")
public class TaskRequeueService {

    private static final Logger LOG = LoggerFactory.getLogger(TaskRequeueService.class);

    /** 审计终态：失败后安排了任务级重试（task 21）。 */
    public static final String AUDIT_TASK_RETRY_SCHEDULED = "task_retry_scheduled";
    /** 审计终态：卡死任务被巡检捞回重新排队（task 21）。 */
    public static final String AUDIT_TASK_STUCK_REQUEUED = "task_stuck_requeued";

    private final FetchTaskRepository repository;
    private final AuditService auditService;
    private final FetchTaskQueuePort queue;

    public TaskRequeueService(FetchTaskRepository repository,
                               AuditService auditService,
                               FetchTaskQueuePort queue) {
        if (repository == null || auditService == null || queue == null) {
            throw new IllegalArgumentException("TaskRequeueService 依赖不能为 null");
        }
        this.repository = repository;
        this.auditService = auditService;
        this.queue = queue;
    }

    /** 读任务行（重试判定要用 peer_aet 与 retry_count）。 */
    @Transactional(readOnly = true)
    public Optional<FetchTask> load(UUID taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return repository.findByTaskId(taskId);
    }

    /**
     * 把任务退回 {@code queued} 等待重跑。
     *
     * @param taskId         任务 UUID
     * @param failureCode    本次失败原因（写进 {@code failure_code} 与审计行）
     * @param retryCount     新的重试次数
     * @param statusTerminal 审计行的终态标记
     * @return 回退后的任务行；<b>回退被拒（任务不存在或已是终态）返回 {@code null}</b>
     */
    @Transactional
    public FetchTask requeue(UUID taskId, String failureCode, int retryCount, String statusTerminal) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不能为 null");
        }
        Optional<FetchTask> found = repository.findByTaskId(taskId);
        if (found.isEmpty()) {
            LOG.warn("任务回退重排时任务不存在：taskId={}", taskId);
            return null;
        }
        FetchTask task = found.get();
        TaskStatus from = task.getStatus();
        if (from != null && from.isTerminal()) {
            LOG.warn("任务已是终态，不回退重排：taskId={} status={}", taskId, from);
            return null;
        }

        task.setStatus(TaskStatus.QUEUED);
        task.setRetryCount(Math.max(0, retryCount));
        if (failureCode != null && !failureCode.isBlank()) {
            task.setFailureCode(failureCode);
        }
        FetchTask saved = repository.save(task);
        recordAudit(saved, statusTerminal, failureCode);
        LOG.info("调阅任务已退回 queued 等待重跑：taskId={} 原状态={} retry_count={} failure_code={}",
                taskId, from, saved.getRetryCount(), failureCode);
        return saved;
    }

    /**
     * 把任务立即重新投回执行队列（事务提交后投）。
     *
     * <p><b>必须等事务提交后再投队列</b>：任务行被退回 queued 是在当前事务里写的，
     * 事务没提交时执行器取到任务会读到旧状态。先投队列再提交，
     * 会出现「队列里排着、库里还是 querying」的幽灵任务。
     */
    public void resubmit(UUID taskId) {
        if (taskId == null) {
            return;
        }
        submitAfterCommit(taskId, 0L, null);
    }

    /**
     * 退避 {@code delayMs} 毫秒后把任务重新投回执行队列。
     *
     * <p>退避用调度器的<b>延迟</b>语义实现（到点唤醒一次），不是让一个线程
     * {@code sleep} 占着 —— 退避最长 30 秒，一批任务同时失败时后者会瞬间吃光线程池。
     *
     * @param delayMs   退避时长（毫秒）
     * @param scheduler 退避调度器
     */
    public void resubmitAfter(UUID taskId, long delayMs, BackoffScheduler scheduler) {
        resubmitAfter(taskId, delayMs, scheduler, null);
    }

    /**
     * 退避 {@code delayMs} 毫秒后把任务重新投回执行队列，并在真正入队后回调一次。
     *
     * @param onSubmitted 入队后的回调（可空）—— 调用方用它销掉「待重试」标记
     */
    public void resubmitAfter(UUID taskId, long delayMs, BackoffScheduler scheduler,
                              Runnable onSubmitted) {
        if (taskId == null || scheduler == null) {
            return;
        }
        submitAfterCommit(taskId, Math.max(0L, delayMs), scheduler, onSubmitted);
    }

    private void submitAfterCommit(UUID taskId, long delayMs, BackoffScheduler scheduler) {
        submitAfterCommit(taskId, delayMs, scheduler, null);
    }

    private void submitAfterCommit(UUID taskId, long delayMs, BackoffScheduler scheduler,
                                   Runnable onSubmitted) {
        Runnable submit = () -> {
            try {
                queue.submit(taskId);
                LOG.info("调阅任务重新入队：taskId={} delay={} ms", taskId, delayMs);
            } catch (RuntimeException ex) {
                LOG.error("调阅任务重新入队失败：taskId={} {}", taskId, ex.getMessage());
            } finally {
                if (onSubmitted != null) {
                    try {
                        onSubmitted.run();
                    } catch (RuntimeException ex) {
                        LOG.warn("重试销账回调异常（已吞掉）: {}", ex.getMessage());
                    }
                }
            }
        };
        Runnable deferred = scheduler == null
                ? submit
                : () -> scheduler.schedule(submit, delayMs);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deferred.run();
                }
            });
            return;
        }
        deferred.run();
    }

    /** 落一行重排审计（审计库故障不阻断重排 —— 排障失败不该把调阅流程炸掉）。 */
    private void recordAudit(FetchTask task, String statusTerminal, String failureCode) {
        try {
            AuditRow row = new AuditRow()
                    .withWho("system")
                    .withFromAet("local")
                    .withToAet(task.getPeerAet())
                    .withStudyUid(task.getStudyUid())
                    .withSopClassUid(task.getSopClassUid())
                    .withRetryCount(task.getRetryCount() == null ? 0 : task.getRetryCount())
                    .withStatusTerminal(statusTerminal == null || statusTerminal.isBlank()
                            ? AUDIT_TASK_RETRY_SCHEDULED : statusTerminal);
            if (failureCode != null && !failureCode.isBlank()) {
                row.withFailureCode(failureCode);
            }
            auditService.record(row);
        } catch (RuntimeException ex) {
            LOG.error("重排审计写入失败（已吞掉，不影响重排）: {}", ex.getMessage());
        }
    }
}
