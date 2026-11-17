package com.crosshospital.imaging.service.routing;

/**
 * 失败处置报告 —— 一次 {@link RoutingFailureHandler#handle} 的**可核对回执**。
 *
 * <p>三个 {@code *Done} 布尔位分别对应三条副作用（审计 / 死信 / 任务终态）。
 * 它们的价值在于<b>可观测</b>：三者中任意一条落库失败都会被降级为日志，
 * 报告里对应位保持 {@code false}，运维与测试都能一眼看出「这次失败只记了一半」，
 * 而不是以为三条都记上了。
 *
 * @param step            失败步骤
 * @param peerAet         对端 AE Title
 * @param patientMpi      病人主索引（可空）
 * @param studyUid        目标 StudyInstanceUID（可空）
 * @param code            分类后的失败码
 * @param message         原始失败消息
 * @param retryCount      已重试次数（0 = 首次即失败）
 * @param auditRecorded   审计行是否写入成功
 * @param dlqEnqueued     死信是否入队成功
 * @param taskMarkedFailed 任务是否已推进到 failed 终态（无 taskId 时为 false）
 */
public record RoutingFailureReport(String step,
                                   String peerAet,
                                   String patientMpi,
                                   String studyUid,
                                   FailureCodeRegistry code,
                                   String message,
                                   int retryCount,
                                   boolean auditRecorded,
                                   boolean dlqEnqueued,
                                   boolean taskMarkedFailed) {

    /** 该失败是否可重试（由失败码决定，不看本次已重试几次）。 */
    public boolean retryable() {
        return code != null && code.retryable();
    }

    /** 给信息科的一句话处置建议。 */
    public String suggestedAction() {
        return code == null ? FailureCodeRegistry.UNKNOWN.suggestedAction() : code.suggestedAction();
    }

    /** 写进 DLQ 的 {@code failure_code} 列值。 */
    public String failureCode() {
        return code == null ? FailureCodeRegistry.UNKNOWN.code() : code.code();
    }

    @Override
    public String toString() {
        return String.format(
                "RoutingFailureReport{step=%s peer=%s study=%s code=%s retryCount=%d "
                        + "audit=%s dlq=%s task=%s}",
                step, peerAet, studyUid, failureCode(), retryCount,
                auditRecorded, dlqEnqueued, taskMarkedFailed);
    }
}