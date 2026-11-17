package com.crosshospital.imaging.exception;

/**
 * DICOM 握手失败异常。
 *
 * <p>本异常**不**承载 HTTP 状态码 — 握手失败本身是可观察的结果（compatible=false），
 * {@code failureCode} 仅用于审计 + 日志 + 黑白名单命中告警；HTTP 仍返回 200 OK 携带
 * 详细失败原因（参见 {@code PeersHealthController}）。
 *
 * <p>对应源仓库 dcm4che-net {@code IncompatibleConnectionException} 与
 * {@code ConnectFailedException} 在应用层的统一包装。
 */
public class HandshakeException extends RuntimeException {

    public enum FailureCode {
        PEER_NOT_FOUND,
        CONNECTION_REFUSED,
        CONNECT_TIMEOUT,
        TLS_MISMATCH,
        CIPHER_MISMATCH,
        TRANSFER_SYNTAX_EMPTY,
        SOP_CLASS_UNSUPPORTED,
        BLACKLISTED,
        INCOMPATIBLE_CONNECTION,
        UNKNOWN
    }

    private final FailureCode failureCode;
    private final boolean retryable;

    public HandshakeException(FailureCode code, String message) {
        this(code, message, false, null);
    }

    public HandshakeException(FailureCode code, String message, boolean retryable) {
        this(code, message, retryable, null);
    }

    public HandshakeException(FailureCode code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.failureCode = code;
        this.retryable = retryable;
    }

    public FailureCode getFailureCode() { return failureCode; }
    public boolean isRetryable() { return retryable; }
}
