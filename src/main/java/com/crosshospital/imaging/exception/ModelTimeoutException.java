package com.crosshospital.imaging.exception;

/**
 * 模型服务调用超时。
 *
 * <p>与 {@link ModelUnreachableException} 分开：超时有明确的重试价值（模型服务只是慢或网络抖），
 * 因此 {@link #isRetryable()} 恒为 {@code true}。
 *
 * <p><b>超时 ≠ 模型没话说</b>：超时发生后本产品**不会**把空结果当成「模型认为这份报告没有问题」，
 * 而是向上抛，由 task 27 回退规则模板并显式标注 {@code source=rule_fallback}。
 */
public class ModelTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long timeoutMs;
    private final String endpoint;

    public ModelTimeoutException(long timeoutMs, String endpoint, String message, Throwable cause) {
        super(message, cause);
        this.timeoutMs = timeoutMs;
        this.endpoint = endpoint;
    }

    /** 本次调用的超时预算（毫秒）。 */
    public long getTimeoutMs() { return timeoutMs; }

    /** 被调用的端点（不含凭据）。 */
    public String getEndpoint() { return endpoint; }

    /** 超时可重试。 */
    public boolean isRetryable() { return true; }
}
