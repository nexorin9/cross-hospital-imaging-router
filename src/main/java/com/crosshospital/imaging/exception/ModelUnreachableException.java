package com.crosshospital.imaging.exception;

/**
 * 模型服务不可用（连接不上 / 端点未配置 / 返回 4xx-5xx）。
 *
 * <p>与 {@link ModelTimeoutException} 分开的原因：<b>超时有明确的重试价值</b>（模型服务只是慢），
 * 而鉴权失败、参数错误重试多少次都一样 —— 两条路径的处置策略不同，异常类型就不能混。
 *
 * <p>{@link #isRetryable()} 给出「这条失败值不值得重试」：
 * <ul>
 *   <li>{@link Reason#TRANSPORT_FAILURE} / {@link Reason#SERVER_ERROR} / {@link Reason#RATE_LIMITED}
 *       —— {@code true}（可重试）</li>
 *   <li>{@link Reason#UNAUTHORIZED} / {@link Reason#CLIENT_ERROR} —— {@code false}（重试没有意义，
 *       应直接走规则模板兜底并把问题暴露给信息科）</li>
 *   <li>{@link Reason#NOT_CONFIGURED} —— {@code false}（没配模型服务，属配置问题）</li>
 * </ul>
 *
 * <p>无论哪一种，<b>都不允许据此推进 PACS 写入或调阅任务状态</b> ——
 * 那是 task 27 规则模板兜底的职责边界。
 */
public class ModelUnreachableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败原因分类。 */
    public enum Reason {
        /** 未配置模型服务端点（{@code model.base-url} 为空）。 */
        NOT_CONFIGURED,
        /** 传输层失败：连接被拒 / DNS 失败 / 连接重置。 */
        TRANSPORT_FAILURE,
        /** 鉴权失败：HTTP 401 / 403。 */
        UNAUTHORIZED,
        /** 限流：HTTP 429。 */
        RATE_LIMITED,
        /** 服务端错误：HTTP 5xx。 */
        SERVER_ERROR,
        /** 其它客户端错误：HTTP 4xx。 */
        CLIENT_ERROR
    }

    private final Reason reason;
    private final int httpStatus;
    private final boolean retryable;
    private final String responseSnippet;

    public ModelUnreachableException(Reason reason, int httpStatus, boolean retryable,
                                     String responseSnippet, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.responseSnippet = responseSnippet == null ? "" : responseSnippet;
    }

    /** 未配置端点。 */
    public static ModelUnreachableException notConfigured(String message) {
        return new ModelUnreachableException(Reason.NOT_CONFIGURED, -1, false, "", message, null);
    }

    /** 传输层失败（没有拿到 HTTP 响应）。 */
    public static ModelUnreachableException transportFailure(String message, Throwable cause) {
        return new ModelUnreachableException(Reason.TRANSPORT_FAILURE, -1, true, "", message, cause);
    }

    /** 拿到了 HTTP 响应但状态码不可接受。 */
    public static ModelUnreachableException httpStatus(int status, String responseSnippet, String message) {
        Reason reason;
        boolean retryable;
        if (status == 401 || status == 403) {
            reason = Reason.UNAUTHORIZED;
            retryable = false;
        } else if (status == 429) {
            reason = Reason.RATE_LIMITED;
            retryable = true;
        } else if (status >= 500) {
            reason = Reason.SERVER_ERROR;
            retryable = true;
        } else {
            reason = Reason.CLIENT_ERROR;
            retryable = false;
        }
        return new ModelUnreachableException(reason, status, retryable, responseSnippet, message, null);
    }

    public Reason getReason() { return reason; }

    /** HTTP 状态码；未拿到响应时为 {@code -1}。 */
    public int getHttpStatus() { return httpStatus; }

    /** 这条失败是否值得重试。 */
    public boolean isRetryable() { return retryable; }

    /** 错误响应体片段（截断后，便于信息科比对网关报错）。 */
    public String getResponseSnippet() { return responseSnippet; }
}
