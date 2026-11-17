package com.crosshospital.imaging.service.task;

/**
 * 死信告警投递结果（回执）。
 *
 * <p>告警失败**不能**让死信流程崩掉，但也不能悄悄吞掉 —— 信息科需要知道
 * 「这条死信有没有真的推到值班群」。本 record 就是这条回执的载体：
 * 调用方（{@link DlqService}）把它写进日志，运维据此判断是「告警网关挂了」还是「没有告警」。
 *
 * @param status     投递状态
 * @param detail     可读说明（失败原因 / 抑制原因 / HTTP 状态码）
 * @param httpStatus 告警网关返回的 HTTP 状态码；未发出时为 {@code null}
 */
public record DlqAlertResult(Status status, String detail, Integer httpStatus) {

    /** 投递状态。 */
    public enum Status {
        /** 已送达告警网关（2xx）。 */
        SENT,
        /** 已受理并投递给告警线程，但尚未确认送达（异步路径）。 */
        SCHEDULED,
        /** 命中冷却窗口，本次不再发（同码告警风暴抑制）。 */
        SUPPRESSED_BY_COOLDOWN,
        /** 未配置告警地址：死信只落库不推告警。 */
        NOT_CONFIGURED,
        /** 投递失败（网络不通 / 超时 / 非 2xx）：降级为日志告警。 */
        FAILED
    }

    public static DlqAlertResult sent(int httpStatus) {
        return new DlqAlertResult(Status.SENT, "告警已送达", httpStatus);
    }

    public static DlqAlertResult scheduled(String detail) {
        return new DlqAlertResult(Status.SCHEDULED, detail, null);
    }

    public static DlqAlertResult suppressed(String detail) {
        return new DlqAlertResult(Status.SUPPRESSED_BY_COOLDOWN, detail, null);
    }

    public static DlqAlertResult notConfigured(String detail) {
        return new DlqAlertResult(Status.NOT_CONFIGURED, detail, null);
    }

    public static DlqAlertResult failed(String detail, Integer httpStatus) {
        return new DlqAlertResult(Status.FAILED, detail, httpStatus);
    }

    /** 是否真的送达了告警网关。 */
    public boolean delivered() {
        return status == Status.SENT;
    }
}
