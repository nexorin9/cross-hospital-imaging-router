package com.crosshospital.imaging.exception;

/**
 * 业务规则层异常基类（task 38 异常分层第一层）。
 *
 * <p>「请求本身不符合业务规则」：幂等冲突、报文格式、鉴权、对象不合规。
 * 共同特征是<b>重试没用</b>，必须改请求或改数据；HTTP 落在 4xx。
 * 这类异常不携带 cause 链上的外部系统故障，日志不需要打堆栈全文。
 */
public class DomainException extends RuntimeException {

    private final ErrorCode errorCode;

    public DomainException(ErrorCode errorCode) {
        this(errorCode, errorCode == null ? "业务规则校验失败" : errorCode.message(), null);
    }

    public DomainException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public DomainException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
