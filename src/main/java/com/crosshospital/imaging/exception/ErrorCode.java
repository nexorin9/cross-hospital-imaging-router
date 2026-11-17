package com.crosshospital.imaging.exception;

/**
 * 统一错误码总表（task 38）。
 *
 * <p>一个错误码把四件事一次说清楚：<b>叫什么</b>（code）、<b>给调用方看什么</b>
 * （message）、<b>该不该重试</b>（retryable）、<b>HTTP 该回几</b>（httpStatus），
 * 再补一条给值班人的处置建议（suggestedAction）与所属异常层（layer）。
 *
 * <p>与 {@code service/routing/FailureCodeRegistry} 的分工：后者是 DICOM 协议栈
 * 的「失败叫什么 / 要不要重放队列」，面向运维排障；本表是<b>对 HTTP 调用方的
 * 统一错误契约</b>，两边通过 code 字符串对齐但语义不混用 —— 同一个
 * {@code connection_refused} 在队列侧可能触发退避重试，在 API 侧就是 502 + retryable。
 *
 * <p><b>retryable 的判据</b>：只有「同样的请求晚点再发一次可能成功」才为 true。
 * 配置错、鉴权失败、报文格式错一律 false —— 让调用方无脑重试只会把对方 PACS
 * 或告警通道再打一遍。
 */
public enum ErrorCode {

    CONNECTION_REFUSED("connection_refused", "连不上外院 PACS", true, 502,
            Layer.EXTERNAL_SYSTEM, "核对准入表 host/port 与网络策略，确认对方节点已起"),
    ASSOCIATION_REJECTED("association_rejected", "外院 PACS 拒绝本院 AE Title", false, 502,
            Layer.EXTERNAL_SYSTEM, "请对方在 AE 白名单登记本节点后重新发起"),
    QUERY_TIMEOUT("query_timeout", "C-FIND 查询超时", true, 504,
            Layer.EXTERNAL_SYSTEM, "队列会退避重放；连续出现查对方 PACS 负载"),
    RETRIEVE_FAILED("retrieve_failed", "C-MOVE 拉回失败", true, 502,
            Layer.EXTERNAL_SYSTEM, "核对 MoveDestination 登记与 104 端口放通"),
    STORE_FAILED("store_failed", "C-STORE 写入本院 PACS 失败", true, 502,
            Layer.INFRASTRUCTURE, "查本院 PACS 存储容量与 C-STORE 日志"),
    PARTIAL_FAILURE("partial_failure", "部分影像对象拉回失败", true, 502,
            Layer.EXTERNAL_SYSTEM, "按 sub-op 失败计数定位缺失序列，重放该 study"),
    TLS_MISMATCH("tls_mismatch", "TLS 协议或密码套件不匹配", false, 502,
            Layer.EXTERNAL_SYSTEM, "核对准入表 tls_mode 与对方证书/套件配置"),
    TRANSFER_SYNTAX_EMPTY("transfer_syntax_empty", "双方传输语法无交集", false, 502,
            Layer.EXTERNAL_SYSTEM, "请对方开启 Explicit VR Little Endian"),
    SOP_CLASS_UNSUPPORTED("sop_class_unsupported", "双方无共同 SOP Class", false, 502,
            Layer.EXTERNAL_SYSTEM, "核对准入表 present_contexts 与对方开放的 Q/R 服务"),
    INVALID_DICOM_PREAMBLE("invalid_dicom_preamble", "DICOM 对象缺 Part-10 前导", false, 422,
            Layer.DOMAIN, "拒绝写入并留证；让上游重新导出该对象"),
    INVALID_HL7_FRAME("invalid_hl7_frame", "HL7 MLLP 帧格式非法", false, 400,
            Layer.DOMAIN, "回 NAK 并落死信，核对 HIS 侧 MLLP 围栏字节"),
    MODEL_UNREACHABLE("model_unreachable", "模型服务不可达", true, 502,
            Layer.EXTERNAL_SYSTEM, "主路径走规则模板兜底，不阻断调阅"),
    MODEL_TIMEOUT("model_timeout", "模型服务调用超时", true, 504,
            Layer.EXTERNAL_SYSTEM, "主路径走规则模板兜底，不阻断调阅"),
    MODEL_PARSE_ERROR("model_parse_error", "模型响应读不懂或结构不符", false, 502,
            Layer.EXTERNAL_SYSTEM, "查 response_format=json_schema 与模型版本"),
    IDEM_CONFLICT("idem_conflict", "同一检查已有未终结任务", false, 409,
            Layer.DOMAIN, "复用响应里的 taskId 查询状态，不要重复受理"),
    DLQ_FULL("dlq_full", "死信队列已满", true, 507,
            Layer.INFRASTRUCTURE, "清理已处置死信或扩容后重放失败任务"),
    WEBHOOK_FAILED("webhook_failed", "HIS 通知回调未送达", true, 502,
            Layer.EXTERNAL_SYSTEM, "任务保持 notified，失败落 dlq_notifications 补推"),
    UNAUTHORIZED("unauthorized", "操作人未授权", false, 401,
            Layer.DOMAIN, "核对 X-Employee-Id 请求头与院内 API 网关策略");

    /** 异常分层：决定 GlobalExceptionHandler 走哪一类处理器。 */
    public enum Layer {
        DOMAIN, INFRASTRUCTURE, EXTERNAL_SYSTEM
    }

    private final String code;
    private final String message;
    private final boolean retryable;
    private final int httpStatus;
    private final Layer layer;
    private final String suggestedAction;

    ErrorCode(String code, String message, boolean retryable, int httpStatus,
              Layer layer, String suggestedAction) {
        this.code = code;
        this.message = message;
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.layer = layer;
        this.suggestedAction = suggestedAction;
    }

    public String code() { return code; }

    public String message() { return message; }

    public boolean retryable() { return retryable; }

    public int httpStatus() { return httpStatus; }

    public Layer layer() { return layer; }

    public String suggestedAction() { return suggestedAction; }

    /** 按字符串找错误码；找不到返回 null（调用方决定降级，不默默编一个）。 */
    public static ErrorCode of(String code) {
        if (code == null) {
            return null;
        }
        for (ErrorCode value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
