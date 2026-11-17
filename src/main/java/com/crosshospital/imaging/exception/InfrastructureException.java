package com.crosshospital.imaging.exception;

/**
 * 本服务基础设施层异常基类（task 38 异常分层第二层）。
 *
 * <p>「我方自己的资源不够 / 组件坏了」：本地 PACS 写不进、死信队列满、
 * 任务状态没法持久化。与外部系统异常的差别是<b>找谁修</b>：前者找本院信息科，
 * 后者找对方医院。HTTP 落在 5xx，且通常可重试（资源释放后再来一次有意义）。
 */
public class InfrastructureException extends RuntimeException {

    private final ErrorCode errorCode;

    public InfrastructureException(ErrorCode errorCode) {
        this(errorCode, errorCode == null ? "本服务基础设施不可用" : errorCode.message(), null);
    }

    public InfrastructureException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public InfrastructureException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
