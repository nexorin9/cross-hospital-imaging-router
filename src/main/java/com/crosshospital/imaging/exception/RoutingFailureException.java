package com.crosshospital.imaging.exception;

/**
 * 路由主路径（DICOM C-FIND / C-MOVE / C-STORE）失败异常。
 *
 * <p>与 {@link HandshakeException} 的分工：
 * <ul>
 *   <li>{@code HandshakeException} —— 握手/端口可达/协议族预校验阶段（task 7-10），
 *       握手失败本身是**可观察的结果**（compatible=false），HTTP 仍返回 200；</li>
 *   <li>{@code RoutingFailureException} —— 关联建起来之后，主路径三步真正执行失败，
 *       会让 {@code imaging_fetch_tasks} 进入 {@code failed}，必须中断本次调阅。</li>
 * </ul>
 *
 * <p><b>不静默原则</b>：query 超时 / 关联被拒 / 查询出错一律抛本异常，
 * <b>绝不</b>退化成「返回空列表」。空列表在外院 PACS 语境里只有一个含义——
 * 这个病人在该院确实没有影像；把网络故障伪装成空结果，会让 HIS 工单被误判为
 * 「已调阅但外院无片」，属于错误的业务状态推进。
 *
 * <p>失败码与 {@code service.routing.FailureCodeRegistry}（task 16）一一对应；
 * DLQ 落库与 FetchTask 状态推进由 {@code FetchTaskService}（task 20）消费本异常完成，
 * 本类只负责**把失败原因带足**。
 */
public class RoutingFailureException extends RuntimeException {

    /**
     * 路由失败码（DB 侧 {@code imaging_fetch_tasks.failure_code} 的取值）。
     *
     * <p>retryable 语义：网络瞬态为 true（task 21 的 RetryPolicy 会重试）；
     * 配置型错误为 false（重试没有意义，必须信息科改配置）。
     */
    public enum FailureCode {
        /** A-ASSOCIATE-RQ 被对端拒绝（AE Title 未授权 / 无可接受的 presentation context）。 */
        ASSOCIATION_REJECTED(false),
        /** 查询响应超时：到点仍未收到终态 C-FIND-RSP。 */
        QUERY_TIMEOUT(true),
        /** 查询执行期出错（链路中断 / 响应无法解码 / 关联中途被对端释放）。 */
        QUERY_FAILED(true),
        /** 关联与查询均正常，但该病人在外院确无影像 —— 空列表而非异常。 */
        NO_STUDY_FOUND(false),
        /** C-MOVE 发送或拉回期出错（sub-operation 无法完成 / 关联在拉回时中断）。 */
        RETRIEVE_FAILED(true),
        /** C-MOVE 响应超 DIMSE 预算仍未到终态，被 dcm4che abort。 */
        MOVE_TIMEOUT(true),
        /** C-MOVE-RSP 终态失败态之一：单条 sub-operation 失败的数据语义错误。 */
        SUB_OP_FAILED(true),
        /** C-MOVE-RSP 终态为 Warning/OneOrMoreFailures（部分 instance 拉回失败）。 */
        PARTIAL_FAILURE(true),
        /** C-STORE 写入本院 PACS 失败：建关联被拒 / C-STORE-RSP 非 Success / 无可写 instance。 */
        STORE_FAILED(true),
        /** C-STORE 响应超 DIMSE 预算仍未到终态，被 dcm4che abort。 */
        STORE_TIMEOUT(true);

        private final boolean retryable;

        FailureCode(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }

        /** 与 DB 列 {@code failure_code} 之间的字符串形式。 */
        public String dbValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static FailureCode fromDbValue(String v) {
            if (v == null || v.isBlank()) {
                throw new IllegalArgumentException("failure_code 不能为空");
            }
            return valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    private final FailureCode failureCode;
    private final String peerAet;
    private final String patientMpi;

    public RoutingFailureException(FailureCode failureCode, String message,
                                   String peerAet, String patientMpi, Throwable cause) {
        super(message, cause);
        this.failureCode = failureCode;
        this.peerAet = peerAet;
        this.patientMpi = patientMpi;
    }

    public RoutingFailureException(FailureCode failureCode, String message,
                                   String peerAet, String patientMpi) {
        this(failureCode, message, peerAet, patientMpi, null);
    }

    public FailureCode getFailureCode() { return failureCode; }

    /** 直接给出「该不该重试」，供 task 21 RetryPolicy 与 task 16 失败分类复用。 */
    public boolean isRetryable() { return failureCode.isRetryable(); }

    public String getPeerAet() { return peerAet; }

    public String getPatientMpi() { return patientMpi; }

    /** 写 {@code imaging_fetch_tasks.failure_code} 的值（小写下划线）。 */
    public String getDbFailureCode() { return failureCode.dbValue(); }
}