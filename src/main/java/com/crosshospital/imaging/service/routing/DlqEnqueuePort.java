package com.crosshospital.imaging.service.routing;

/**
 * 死信入队端口 —— task 16 的 {@link RoutingFailureHandler} 依赖本接口，
 * 由 task 22 的 {@code DlqService} 实现。
 *
 * <p>拆成接口而不是直接依赖 {@code DlqService}，是因为本轮（task 16）要先交付
 * 「失败 → 落审计 → 进死信」这条链路，而 DLQ 的落库、告警 webhook、30 天 TTL
 * 清理是下一个任务的范围。端口让两段可以分别验收、分别失败重放。
 */
@FunctionalInterface
public interface DlqEnqueuePort {

    /**
     * 把一条已判定「不可恢复或重试耗尽」的失败入队。
     *
     * <p>实现方<b>不得</b>吞掉入队失败 —— 但调用方（{@code RoutingFailureHandler}）
     * 会兜住异常并降级为日志，保证「排障失败」不会反过来变成「调阅流程崩掉」。
     *
     * @param request 入队内容（含失败码、原始消息、已重试次数、定位上下文）
     */
    void enqueue(DlqEnqueueRequest request);
}