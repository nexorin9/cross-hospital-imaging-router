package com.crosshospital.imaging.service.routing;

/**
 * 死信入队请求 —— {@code RoutingFailureHandler} → {@code DlqService}（task 22）之间的契约。
 *
 * <p>用 record 而非直接传 {@code DlqEntry} 实体，是为了让 task 16 可以在
 * {@code DlqService} 落地之前就把入队链路跑通并测试：task 22 的
 * {@code DlqService} 实现 {@link DlqEnqueuePort} 后，只需把这个 record 映射成
 * {@code DlqEntry} 落库即可，不用回头改本类的调用点。
 *
 * @param taskId          调阅任务 UUID；无任务上下文时为 {@code null}
 * @param peerAet         对端 AE Title
 * @param patientMpi      病人主索引（可空）
 * @param studyUid        目标 StudyInstanceUID（可空）
 * @param step            失败步骤（query / retrieve / store）
 * @param failureCode     失败码（{@link FailureCodeRegistry#code()}）
 * @param failureMessage  失败消息原文（信息科排障的第一手材料）
 * @param retryAttempts   已重试次数
 */
public record DlqEnqueueRequest(String taskId,
                                String peerAet,
                                String patientMpi,
                                String studyUid,
                                String step,
                                String failureCode,
                                String failureMessage,
                                int retryAttempts) {

    /** 由上下文与分类结果组装（唯一构造入口，避免两处手工拼字段拼错）。 */
    public static DlqEnqueueRequest of(RoutingFailureContext ctx,
                                       FailureCodeRegistry code,
                                       String message,
                                       int retryAttempts) {
        return new DlqEnqueueRequest(
                ctx == null ? null : ctx.taskId(),
                ctx == null ? null : ctx.toAet(),
                ctx == null ? null : ctx.patientMpi(),
                ctx == null ? null : ctx.studyUid(),
                ctx == null ? null : ctx.step(),
                code == null ? FailureCodeRegistry.UNKNOWN.code() : code.code(),
                message,
                Math.max(0, retryAttempts));
    }
}