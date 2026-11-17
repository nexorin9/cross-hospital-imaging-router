package com.crosshospital.imaging.service.task;

import java.util.Optional;
import java.util.UUID;

/**
 * 调阅任务队列端口 —— {@link FetchTaskService} 入队后把任务交给执行侧的契约。
 *
 * <p>拆成接口而不是直接依赖某个线程池，是因为 task 19 只交付「入队」这一段，
 * 而真正的执行调度（{@code RetryScheduler} / {@code StuckTaskDetector}）在后续任务里落地。
 * 端口让「入队」与「执行」可以分别验收：前者证明任务确实落库并进了队列，
 * 后者证明队列确实被消费。
 *
 * <p><b>实现约定</b>：
 * <ul>
 *   <li>{@link #submit(UUID)} 不得抛异常给调用方以外的语义 —— 队列满要<b>明确失败</b>，
 *       不能悄悄丢任务（悄悄丢 = 影像永远拉不回来，而 HIS 侧收到的是 ACK）；</li>
 *   <li>实现必须是<b>非阻塞</b>的：调用方跑在 Netty 事件循环线程上，
 *       一条慢报文不该拖垮整个 worker。</li>
 *   <li>{@link #pollNext(long)} 是<b>消费</b>方向：执行侧常驻线程从队列取任务推进状态机。
 *       取空不是失败 —— 队列空就是暂时没活干。</li>
 * </ul>
 */
public interface FetchTaskQueuePort {

    /**
     * 把一个已落库的调阅任务投进执行队列。
     *
     * @param taskId 任务 UUID（{@code imaging_fetch_tasks.task_id}）
     * @throws IllegalStateException 队列已满、无法接收（调用方据此进死信）
     */
    void submit(UUID taskId);

    /**
     * 取下一个待执行任务（执行侧消费）。
     *
     * <p>队列空时最多等待 {@code timeoutMs} 毫秒 —— 执行侧是常驻线程，
     * 空队列时应当歇一会儿再看，而不是空转烧 CPU。
     *
     * @param timeoutMs 等待毫秒数
     * @return 任务 UUID；等待超时仍为空返回 {@link Optional#empty()}
     * @throws InterruptedException 等待被中断
     */
    Optional<UUID> pollNext(long timeoutMs) throws InterruptedException;
}
