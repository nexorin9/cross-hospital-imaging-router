package com.crosshospital.imaging.service.routing;

/**
 * 调阅任务失败推进端口 —— task 16 的 {@link RoutingFailureHandler} 依赖本接口，
 * 由 task 20 的 {@code FetchTaskService} 实现（其 {@code markFailed} 会走五态机
 * 跃迁校验，把任务从 queued/querying/pulling 推到 {@code failed}）。
 *
 * <p>为什么不直接调 {@code FetchTaskService}：五态机是 task 20 的交付物，
 * 本轮先到失败分类与审计/死信这一段。端口既让本轮可测（用记录型替身断言
 * 「确实调用了 markFailed 且传对了失败码」），又保证 task 20 落地时
 * 跃迁校验不会被绕过 —— 实现方必须自己接端口，不能各写各的。
 */
@FunctionalInterface
public interface FetchTaskFailurePort {

    /**
     * 把任务推进到失败终态。
     *
     * <p><b>返回值告诉调用方这次失败是不是终局失败</b>（task 21）：
     * 失败码可重试时，实现方会把任务退回 {@code queued} 等退避后重跑，
     * 并返回 {@link TaskFailureOutcome#RETRY_SCHEDULED} —— 此时
     * 调用方<b>不</b>该把这次失败当终局失败记账（不进死信）。
     *
     * @param taskId      调阅任务 UUID（字符串形式）
     * @param failureCode 失败码（{@link FailureCodeRegistry#code()}）
     * @return 这次失败的收场方式；实现方无法判断时按
     *         {@link TaskFailureOutcome#TERMINAL_FAILURE} 处理（保守：宁可多留一条待办，
     *         也不可让一次真实失败从信息科的视野里消失）
     */
    TaskFailureOutcome markFailed(String taskId, String failureCode);
}
