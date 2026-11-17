package com.crosshospital.imaging.service.task;

/**
 * 调阅任务入队请求 —— {@link FetchTaskService#enqueue} 的入参契约。
 *
 * <p>用 record 而不是直接收 {@code FetchTask} 实体，是为了让入队方（HL7 触发路由器 /
 * 后续的 REST 触发接口）只声明"我知道的字段"，不必先造一个实体再改。
 *
 * @param patientMpi   患者主索引（PID-3）—— 跨院调阅的主锚，必填
 * @param studyUid     影像引用 —— 外院那次检查的唯一标识，必填
 * @param peerAet      承接这次调阅的外院节点 AET，必填
 * @param initiatedBy  发起人（API 调用方的工号 / HL7 发送应用名），必填
 * @param initiatedSrc 发起来源（{@code api} / {@code hl7} / {@code retry} / {@code stuck_recovery}），必填
 * @param accessionNo  检查申请号（外院可能以申请号代替 StudyInstanceUID 暴露影像），可空
 * @param sopClassUid  SOP Class UID（协议层识别用；触发时刻通常尚不可得），可空
 */
public record FetchTaskEnqueueRequest(String patientMpi,
                                      String studyUid,
                                      String peerAet,
                                      String initiatedBy,
                                      String initiatedSrc,
                                      String accessionNo,
                                      String sopClassUid) {

    /** HL7 触发来源的 {@code initiated_src} 取值（与 V2 迁移的 CHECK 一致）。 */
    public static final String SRC_HL7 = "hl7";
    /** REST / 内部接口触发来源。 */
    public static final String SRC_API = "api";

    /**
     * 由 HL7 触发组装：发起人取 HL7 发送应用名（MSH-3），缺失时归到 {@code system}。
     */
    public static FetchTaskEnqueueRequest fromHl7(String patientMpi,
                                                   String studyUid,
                                                   String peerAet,
                                                   String sendingApplication,
                                                   String accessionNo) {
        return new FetchTaskEnqueueRequest(
                patientMpi,
                studyUid,
                peerAet,
                sendingApplication == null || sendingApplication.isBlank() ? "system" : sendingApplication,
                SRC_HL7,
                accessionNo,
                null);
    }
}
