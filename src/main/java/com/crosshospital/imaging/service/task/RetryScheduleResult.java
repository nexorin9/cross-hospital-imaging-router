package com.crosshospital.imaging.service.task;

/**
 * 重试安排结果 —— 「这次失败到底重不重试」的回执。
 *
 * <p>这个回执要同时被两个地方消费：
 * <ul>
 *   <li>{@link com.crosshospital.imaging.service.task.FetchTaskService} ——
 *       看到 {@link #retried()} 为 true 就<b>不</b>把任务推失败终态；</li>
 *   <li>失败处置链（task 16 的 {@code RoutingFailureHandler}）——
 *       看到 {@link #retried()} 为 true 就<b>不</b>把这次失败当终局失败记账。</li>
 * </ul>
 * 两边看到的是同一份事实，不会出现「一边说要重试、一边把任务判死」的分裂。
 *
 * @param retried    是否已安排重试（true = 任务会被退回 queued 并在退避后重新入队）
 * @param retryCount 安排后的重试次数（{@code retry_count} 列的值）
 * @param backoffMs  退避时长（毫秒）；已安排时有效
 * @param failureCode 触发本次判定的失败码
 * @param reason     判定理由（写进日志与审计）
 */
public record RetryScheduleResult(boolean retried, int retryCount, long backoffMs,
                                  String failureCode, String reason) {

    /** 不重试（不可重试的失败码 / 预算耗尽 / 任务不存在）。 */
    public static RetryScheduleResult notRetried(String reason) {
        return new RetryScheduleResult(false, 0, 0L, null, reason);
    }

    /** 不重试，但带失败码（便于调用方日志对齐）。 */
    public static RetryScheduleResult notRetried(String failureCode, String reason) {
        return new RetryScheduleResult(false, 0, 0L, failureCode, reason);
    }

    /** 已安排一次重试。 */
    public static RetryScheduleResult scheduled(int retryCount, long backoffMs,
                                                String failureCode, String reason) {
        return new RetryScheduleResult(true, retryCount, Math.max(0L, backoffMs), failureCode, reason);
    }

    /**
     * 这次失败<b>此前已经</b>安排过重试 —— 幂等回执。
     *
     * <p>存在的原因：同一次失败会被两处问到（路由失败出口问一次，
     * 任务状态机问一次）。第二处必须拿到「已经安排过了」的答复，
     * 而不是又排一次退避 —— 否则同一次失败会退避两次、任务被退两次。
     */
    public static RetryScheduleResult alreadyScheduled(int retryCount, long backoffMs,
                                                      String failureCode, String reason) {
        return new RetryScheduleResult(true, retryCount, Math.max(0L, backoffMs), failureCode, reason);
    }
}
