package com.crosshospital.imaging.service.task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 调阅任务执行器 —— 常驻消费队列，把一次调阅按五态机一步步推到底。
 *
 * <p><b>为什么要有这么一个常驻角色</b>：入队（task 19）只让任务「被安排上了」，
 * 没有人消费队列的话，任务会一直停在 {@code queued} —— HIS 侧看到的是
 * 「系统收下了」，影像却永远不会回来。本类把队列消费掉，
 * 让 {@code queued → querying → pulling → stored} 这条链真的跑起来。
 *
 * <p><b>推到哪里停</b>：推到 {@code stored}（影像已写进本院 PACS）为止。
 * 再往前一步是 {@code notified} —— 那一步要推 HIS 的工单回调，
 * 属于通知侧的职责（后续任务），本类不越界。
 *
 * <p><b>单线程串行</b>：一次只处理一个任务。理由不是性能，是正确性 ——
 * C-MOVE 的落点是本端那一个 C-STORE-SCP，两个拉回并发会互相清空对方的字节。
 * 跨院调阅的并发需求由「多节点 + 多实例部署」解决，不由单进程内并发解决。
 *
 * <p>失败处理不在本类：子步失败由 {@link FetchTaskService#advance} 统一处置 ——
 * 失败码不可重试（或预算耗尽）就转 failed + 落死信；失败码可重试就退回 queued
 * 等退避后重跑（task 21）。本类只负责「推一把」，推不动就停手，把原因留在
 * 审计与死信里；<b>「已安排重试」也是一种推不动</b>，同样停手，但不记成失败。
 */
@Component
@Profile("!test")
public class FetchTaskRunner {

    private static final Logger LOG = LoggerFactory.getLogger(FetchTaskRunner.class);

    /** 队列空时的等待间隔（毫秒）—— 空转烧 CPU 不划算。 */
    private static final long IDLE_WAIT_MS = 500L;

    /**
     * 本执行器负责推进的状态序列（完整五态机）。
     *
     * <p><b>必须包含 {@code NOTIFIED}</b>：{@link FetchTaskService#advance} 只在
     * 推进到 notified 这一步做 AI 增强、写终态审计并调 {@code NotificationPort}
     * 回推 HIS。早期这里只列到 STORED、日志写「等待通知」，但全工程再没有
     * 第二个调用方去推第五态 —— 真实运行时任务永远停在 stored，
     * HIS 收不到完成通知，两个 AI 介入点也一次都不会被调用。
     * NOTIFIED 没有协议子步（{@code StepExecutor.supports} 返回 false），
     * 因此这一跃迁只做状态 + 审计 + 通知，不会产生额外 DICOM 调用。
     */
    private static final List<TaskStatus> PIPELINE =
            List.of(TaskStatus.QUERYING, TaskStatus.PULLING,
                    TaskStatus.STORED, TaskStatus.NOTIFIED);

    private final FetchTaskService fetchTaskService;
    private final FetchTaskQueuePort queue;

    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    /** 已退回 queued 等待重试的任务数（task 21 引入的第三种结局）。 */
    private final AtomicLong requeued = new AtomicLong();
    private volatile boolean running;
    private Thread worker;

    public FetchTaskRunner(FetchTaskService fetchTaskService, FetchTaskQueuePort queue) {
        if (fetchTaskService == null || queue == null) {
            throw new IllegalArgumentException("FetchTaskRunner 依赖不能为 null");
        }
        this.fetchTaskService = fetchTaskService;
        this.queue = queue;
    }

    @PostConstruct
    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::loop, "fetch-task-runner");
        worker.setDaemon(true);
        worker.start();
        LOG.info("调阅任务执行器已启动（消费队列，按五态机推进）");
    }

    @PreDestroy
    public void stop() {
        running = false;
        Thread current = worker;
        if (current != null) {
            current.interrupt();
        }
        LOG.info("调阅任务执行器已停止");
    }

    /** 执行器是否在跑。 */
    public boolean isRunning() {
        return running;
    }

    /** 已处理的任务数。 */
    public long processedCount() {
        return processed.get();
    }

    /** 处理失败（任务转 failed）的任务数。 */
    public long failedCount() {
        return failed.get();
    }

    /**
     * 已退回 queued 等待重试的任务数（task 21）。
     *
     * <p>与 {@link #failedCount()} 分开计数，因为两者的运维含义完全不同：
     * 失败的任务要人管（进死信），等待重试的任务自己会好（退避到点自动重跑）。
     * 混在一个计数里，看板上「失败数」会随网络抖动忽高忽低，信息科分不清
     * 「外院真的挂了」与「链路抖了一下正在自己恢复」。
     */
    public long requeuedCount() {
        return requeued.get();
    }

    private void loop() {
        while (running) {
            UUID taskId = null;
            try {
                Optional<UUID> polled = queue.pollNext(IDLE_WAIT_MS);
                taskId = polled.orElse(null);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (taskId == null) {
                continue;
            }
            try {
                process(taskId);
            } catch (RuntimeException ex) {
                // 执行器自己不能死：一条坏任务不该让后面所有任务都卡住
                LOG.error("调阅任务处理异常（任务 {}）：{}", taskId, ex.getMessage(), ex);
            }
        }
    }

    /**
     * 把一个任务按状态机推到底。
     *
     * @param taskId 任务 UUID
     */
    void process(UUID taskId) {
        Optional<FetchTask> found = fetchTaskService.findByTaskId(taskId);
        if (found.isEmpty()) {
            LOG.warn("队列里的任务在库里不存在（可能被清理过），跳过：{}", taskId);
            return;
        }
        FetchTask task = found.get();
        if (task.getStatus() != TaskStatus.QUEUED) {
            // 已经被别的路径推进过（或已终结）—— 不重复推
            LOG.debug("任务不在 queued 状态，跳过执行：taskId={} status={}", taskId, task.getStatus());
            return;
        }

        for (TaskStatus next : PIPELINE) {
            FetchTask current;
            try {
                current = fetchTaskService.advance(taskId, next, "system");
            } catch (RuntimeException ex) {
                // 非法跃迁等：任务已经不在该推的位置上，停手
                LOG.warn("任务推进失败（已停止该任务）：taskId={} 目标={} msg={}",
                        taskId, next, ex.getMessage());
                failed.incrementAndGet();
                return;
            }
            if (current == null || current.getStatus() == TaskStatus.FAILED) {
                // 子步失败：状态机已把它转成 failed（失败码 + 死信已落）
                failed.incrementAndGet();
                LOG.warn("调阅任务失败终止：taskId={} 停在 {}", taskId, next);
                return;
            }
            if (current.getStatus() == TaskStatus.QUEUED) {
                // task 21：子步失败但失败码可重试 → 任务已退回 queued 等退避后重跑。
                // 这既不是「推到了 stored」也不是「判死」，本轮到此为止：
                // 继续往下推 pulling 会撞上 queued → pulling 的非法跃迁，
                // 把一次「等着自己恢复」记成失败并刷出误导性的告警。
                requeued.incrementAndGet();
                LOG.info("调阅子步失败已安排重试，退回 queued 等待重跑：taskId={} 停在 {}",
                        taskId, next);
                return;
            }
        }
        processed.incrementAndGet();
        LOG.info("调阅任务已推到终态（影像已写入本院 PACS，HIS 通知已触发）：taskId={}", taskId);
    }
}
