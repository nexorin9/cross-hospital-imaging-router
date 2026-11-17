package com.crosshospital.imaging.exception;

/**
 * 外部系统异常基类（task 38 异常分层第三层）。
 *
 * <p>「对面没给到东西」：外院 PACS 拒接、TLS 不兼容、WADO-RS 5xx、
 * 模型服务不可达、HIS webhook 没送达。HTTP 落在 502 / 504，
 * 可重试与否由 {@link ErrorCode#retryable()} 决定，不由异常类名猜。
 *
 * <p>最关键的一条业务约束：这类异常<b>不得回退调阅任务状态</b>。
 * 影像已经进了本院 PACS 之后回调失败，应落死信补推，而不是把 notified 翻成 failed。
 */
public class ExternalSystemException extends RuntimeException {

    private final ErrorCode errorCode;

    public ExternalSystemException(ErrorCode errorCode) {
        this(errorCode, errorCode == null ? "外部系统不可用" : errorCode.message(), null);
    }

    public ExternalSystemException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public ExternalSystemException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
