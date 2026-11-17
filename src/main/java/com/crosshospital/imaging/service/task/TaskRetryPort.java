package com.crosshospital.imaging.service.task;

import java.util.UUID;

/**
 * 任务级重试端口 —— 「这次失败要不要再来一次」这件事的契约。
 *
 * <p><b>为什么要抽成端口</b>：{@link FetchTaskService} 需要在把任务推失败终态之前
 * 先问一句「该不该再试」，而真正的判定要读节点的重试预算、要退避、要重新入队 ——
 * 这些都是 {@link RetryScheduler} 的活。两边直接互相构造注入会绕成环
 * （{@code RetryScheduler} 需要 {@code FetchTaskService} 来回退任务状态，
 * {@code FetchTaskService} 又需要重试判定），所以这里用接口 + setter 注入解开。
 *
 * <p><b>实现约定</b>：
 * <ul>
 *   <li>返回 {@code null} 或 {@link RetryScheduleResult#notRetried(String)} ——
 *       调用方按「终局失败」处理（推 failed + 落审计 + 进死信）；</li>
 *   <li>返回 {@link RetryScheduleResult#retried()} 为 true ——
 *       调用方<b>不要</b>再把任务推失败终态，任务已被退回 queued 并会在退避后重新入队；</li>
 *   <li>实现必须<b>幂等</b>：同一次失败被问两次时，第二次只能返回
 *       {@link RetryScheduleResult#alreadyScheduled(int, long, String, String)}，
 *       不得再排一次退避、不得再退一次任务状态。</li>
 *   <li>实现不得抛异常 —— 重试判定失败不该把调阅流程炸掉，
 *       调用方会兜住异常并按终局失败处理。</li>
 * </ul>
 */
public interface TaskRetryPort {

    /**
     * 判定一次失败是否安排重试；安排时由实现方负责把任务退回 queued 并重新入队。
     *
     * @param taskId      调阅任务 UUID
     * @param failureCode 失败码（{@link com.crosshospital.imaging.service.routing.FailureCodeRegistry} 词表）
     * @param who         操作人（工号 / 系统标识）
     * @return 重试安排结果；不可重试时返回 {@link RetryScheduleResult#notRetried(String)}
     */
    RetryScheduleResult scheduleRetry(UUID taskId, String failureCode, String who);
}
