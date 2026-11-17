package com.crosshospital.imaging.service.task;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 进程内调阅任务队列（task 19 的默认实现）。
 *
 * <p>有界阻塞队列（默认 1024 格）：<b>队列满必须明确失败</b>，不能悄悄丢任务 ——
 * 丢一条任务意味着一次跨院调阅永远不会发生，而 HIS 侧已经收到 ACK，不会重投。
 * 满了就抛 {@link IllegalStateException}，由调用方写死信 + 回 NAK，
 * 让 HIS 重投或让信息科从死信里看到缺口。
 *
 * <p>本实现是<b>单进程</b>队列：进程重启后队列里的任务会丢，但任务行已落
 * {@code imaging_fetch_tasks}（status=queued），可由 task 21 的重放/卡死扫描重新拉起。
 * 状态在库里、队列在内存里 —— 这是刻意的分工：库是事实，队列只是调度提示。
 */
@Component
public class InMemoryFetchTaskQueue implements FetchTaskQueuePort {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryFetchTaskQueue.class);

    /** 默认队列容量。 */
    public static final int DEFAULT_CAPACITY = 1024;

    /** 队列满时等待入队的最长毫秒数（给执行侧一点喘息，不无限等）。 */
    private static final long OFFER_TIMEOUT_MS = 200L;

    private final BlockingQueue<UUID> queue;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    public InMemoryFetchTaskQueue() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryFetchTaskQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("调阅队列容量必须为正数，收到 " + capacity);
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public void submit(UUID taskId) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不能为 null");
        }
        try {
            if (!queue.offer(taskId, OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                rejected.incrementAndGet();
                throw new IllegalStateException("调阅任务队列已满（容量 " + queue.size()
                        + "），任务 " + taskId + " 未入队；请检查执行侧是否卡死");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            rejected.incrementAndGet();
            throw new IllegalStateException("调阅任务入队被中断，任务 " + taskId + " 未入队", ex);
        }
        submitted.incrementAndGet();
        LOG.debug("调阅任务已入队：taskId={} 队列深度={}", taskId, queue.size());
    }

    /** 取下一个待执行任务；队列空返回 {@code null}（不阻塞）。 */
    public UUID poll() {
        return queue.poll();
    }

    @Override
    public java.util.Optional<UUID> pollNext(long timeoutMs) throws InterruptedException {
        if (timeoutMs <= 0) {
            return java.util.Optional.ofNullable(queue.poll());
        }
        return java.util.Optional.ofNullable(queue.poll(timeoutMs, TimeUnit.MILLISECONDS));
    }

    /** 取下一个待执行任务，最多等 {@code timeoutMs} 毫秒。 */
    public UUID poll(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** 队列当前深度。 */
    public int size() {
        return queue.size();
    }

    /** 累计入队数。 */
    public long submittedCount() {
        return submitted.get();
    }

    /** 累计因队列满被拒数（应与死信里的 enqueue_failed 对得上）。 */
    public long rejectedCount() {
        return rejected.get();
    }

    /** 队列容量。 */
    public int capacity() {
        return queue.size() + queue.remainingCapacity();
    }

    /** 排空队列（测试与运维复位用）。 */
    public List<UUID> drain() {
        List<UUID> out = new ArrayList<>();
        queue.drainTo(out);
        return out;
    }
}
