package com.crosshospital.imaging.service.task;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.service.routing.RoutingFailureContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 卡死任务探测器 —— 每 5 分钟扫一次表，把「停在 querying 却不再动」的任务捞回来。
 *
 * <p><b>它接住的是哪一次卡住</b>：C-FIND 发出去了，对端 PACS 半死不活地不回，
 * 协议层等到超时也不一定抛得干净；又或者本院 PACS 的 C-STORE 把连接挂在那儿，
 * 双方都不吭声 —— 这时任务永远停在 {@code querying}，
 * HIS 侧看到的是「系统收下了」，医生在工作站上等一天也等不到片子。
 *
 * <p>本探测器每 5 分钟扫一次：{@code status=querying} 且
 * {@code updated_at} 早于 5 分钟的任务，判定为卡死，
 * <b>重置回 {@code queued} + {@code retry_count + 1} + 落一行审计</b>，
 * 重新投回执行队列 —— 由重试策略决定这一次值不值得再跑一遍。
 *
 * <p><b>为什么只盯 querying</b>：{@code queued} 的任务本来就该在队列里排着
 * （排队超过 5 分钟是正常业务高峰，不是卡死）；
 * {@code pulling / stored} 卡住是协议子步内部的事，由 task 13/14/15 的超时兜底
 * （{@code routing.query-timeout-ms / retrieve-timeout-ms / store-timeout-ms}）。
 * 只有 {@code querying} 这一态是「查得到、但没人回」的典型卡死形态。
 *
 * <p><b>卡死重排也受重试预算约束</b>：一条任务被反复捞起又反复卡死时，
 * 预算用完就转失败终态 + 进死信，而不是永远在 queued/querying 之间来回。
 * 没有这条约束，一次网络分区就能把任务表变成永动机。
 *
 * <p>源主路径证据：dcm4che 的 {@code Connection} 有 {@code setRequestTimeout} /
 * {@code setResponseTimeout} 与 {@code ConnectionMonitor} 的失败回调，
 * 但源产品<b>没有</b>「把僵在中间的会话捞回来重做」的机制 ——
 * {@code findscu} 是人工反复敲的命令，人就是重试器。
 * 本类是把那份「人工反复敲」内化成常驻巡检，属融合增量。
 */
@Component
@Profile("!test")
public class StuckTaskDetector {

    private static final Logger LOG = LoggerFactory.getLogger(StuckTaskDetector.class);

    /** 卡死判定的失败码（写进 {@code failure_code} 与审计行）。 */
    public static final String FAILURE_STUCK_TIMEOUT = "stuck_task_timeout";

    private final FetchTaskRepository repository;
    private final FetchTaskService fetchTaskService;
    private final TaskRequeueService requeueService;
    private final RetryBudgetResolver budgetResolver;

    /** 卡死阈值（毫秒）：更新时间早于「现在 - 阈值」才算卡死。 */
    private final long stuckThresholdMs;

    public StuckTaskDetector(FetchTaskRepository repository,
                             FetchTaskService fetchTaskService,
                             TaskRequeueService requeueService,
                             RetryBudgetResolver budgetResolver,
                             @Value("${task.retry.stuck-threshold-ms:300000}") long stuckThresholdMs) {
        if (repository == null || fetchTaskService == null || requeueService == null
                || budgetResolver == null) {
            throw new IllegalArgumentException("StuckTaskDetector 依赖不能为 null");
        }
        this.repository = repository;
        this.fetchTaskService = fetchTaskService;
        this.requeueService = requeueService;
        this.budgetResolver = budgetResolver;
        this.stuckThresholdMs = stuckThresholdMs <= 0 ? 300_000L : stuckThresholdMs;
    }

    /**
     * 巡检入口 —— 由 Spring 的 {@code @Scheduled} 每 5 分钟触发一次。
     *
     * <p>方法不抛异常：巡检本身出问题不该把整个调度器带崩。
     */
    @Scheduled(fixedDelayString = "${task.retry.stuck-scan-interval-ms:300000}",
               initialDelayString = "${task.retry.stuck-initial-delay-ms:60000}")
    public void scan() {
        try {
            ScanReport report = scanOnce();
            if (report.requeued() > 0 || report.failed() > 0) {
                LOG.warn("卡死任务巡检完成：捞回 {} 条、转失败 {} 条、跳过 {} 条",
                        report.requeued(), report.failed(), report.skipped());
            } else {
                LOG.debug("卡死任务巡检完成：未发现卡死任务");
            }
        } catch (RuntimeException ex) {
            LOG.error("卡死任务巡检异常（已吞掉，不影响下一次巡检）: {}", ex.getMessage());
        }
    }

    /**
     * 巡检一次 —— 可被单测直接调用。
     *
     * @return 本次巡检的处理结果
     */
    public ScanReport scanOnce() {
        Instant cutoff = Instant.now().minusMillis(stuckThresholdMs);
        List<FetchTask> stuck = repository.findStuckTasks(TaskStatus.QUERYING, cutoff);
        int requeued = 0;
        int failed = 0;
        int skipped = 0;

        for (FetchTask task : stuck) {
            UUID taskId = task.getTaskId();
            if (taskId == null) {
                skipped++;
                continue;
            }
            int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
            RetryBudgetResolver.RetryBudget budget = budgetResolver.budgetFor(task.getPeerAet());

            if (retryCount >= budget.maxRetries()) {
                // 重试预算已用尽 → 转失败终态 + 进死信，不再无休止地捞回
                fetchTaskService.markFailedAndDeadLetter(taskId, FAILURE_STUCK_TIMEOUT,
                        new RoutingFailureContext(RoutingFailureContext.STEP_QUERY,
                                task.getPeerAet(), task.getPatientMpi(), task.getStudyUid(),
                                taskId.toString(), "system"));
                failed++;
                LOG.warn("卡死任务重试预算已耗尽，转失败终态: taskId={} peer={} retry_count={}",
                        taskId, task.getPeerAet(), retryCount);
                continue;
            }

            FetchTask requeuedTask = requeueService.requeue(
                    taskId, FAILURE_STUCK_TIMEOUT, retryCount + 1,
                    TaskRequeueService.AUDIT_TASK_STUCK_REQUEUED);
            if (requeuedTask == null) {
                skipped++;
                continue;
            }
            requeueService.resubmit(taskId);
            requeued++;
            LOG.info("卡死任务已捞回重新排队: taskId={} peer={} retry_count={} 停在 querying 超 {} ms",
                    taskId, task.getPeerAet(), retryCount + 1, stuckThresholdMs);
        }
        return new ScanReport(requeued, failed, skipped);
    }

    /**
     * 一次巡检的处理结果。
     *
     * @param requeued 捞回并重新排队的任务数
     * @param failed   转失败终态的任务数
     * @param skipped  跳过的任务数（任务不存在 / 已终结）
     */
    public record ScanReport(int requeued, int failed, int skipped) {
    }
}
