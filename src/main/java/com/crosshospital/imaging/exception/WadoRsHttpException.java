package com.crosshospital.imaging.exception;

/**
 * WADO-RS 拉取的 HTTP 层失败。
 *
 * <p>区分「有没有拿到 HTTP 响应」：{@link #statusCode} &lt; 0 表示传输层失败（连接拒绝 / 读超时），
 * 此时 {@link #responseBody} 为空串。拿到响应但状态码不可接受时，{@code statusCode} 为真实状态码，
 * {@code responseBody} 保留截断后的响应体片段，便于信息科在工单里比对网关报错。
 *
 * <p>{@link #isRetryable()} 的语义（task 12 重试策略消费）：
 * <ul>
 *   <li>传输失败 / 5xx → {@code true}（可重试）</li>
 *   <li>4xx → {@code false}（重试没有意义，配置或请求本身有问题）</li>
 * </ul>
 */
public class WadoRsHttpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 传输层失败时的哨兵状态码（无 HTTP 响应）。 */
    public static final int STATUS_TRANSPORT_FAILURE = -1;

    /** 响应体片段的最大保留长度（字节）；超出截断，防止把大 body 带进日志与工单。 */
    public static final int MAX_BODY_SNIPPET = 2048;

    public enum Reason {
        /** 连接拒绝 / 读超时 / 连接重置：没拿到 HTTP 响应。 */
        TRANSPORT_FAILURE,
        /** 404：网关认为该 StudyInstanceUID 不存在（或患者标识对不上）。 */
        STUDY_NOT_FOUND,
        /** 416：网关无法为该请求范围提供对象；按「不可重试」处理。 */
        RANGE_NOT_SATISFIABLE,
        /** 5xx：网关侧故障，可重试。 */
        SERVER_ERROR,
        /** 其余 4xx：请求本身不合规或无权限，不重试。 */
        CLIENT_ERROR
    }

    private final int statusCode;
    private final String responseBody;
    private final boolean retryable;
    private final Reason reason;

    public WadoRsHttpException(int statusCode, String responseBody, boolean retryable, Reason reason) {
        this(statusCode, responseBody, retryable, reason, null);
    }

    public WadoRsHttpException(int statusCode, String responseBody, boolean retryable, Reason reason,
                               Throwable cause) {
        super(buildMessage(statusCode, reason, responseBody), cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
        this.retryable = retryable;
        this.reason = reason;
    }

    /** 传输层失败工厂（无 HTTP 响应）。 */
    public static WadoRsHttpException transportFailure(String detail, Throwable cause) {
        return new WadoRsHttpException(STATUS_TRANSPORT_FAILURE, detail, true, Reason.TRANSPORT_FAILURE, cause);
    }

    public int getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }
    public boolean isRetryable() { return retryable; }
    public Reason getReason() { return reason; }

    /** 按 {@link #MAX_BODY_SNIPPET} 截断响应体，避免大 body 撑爆日志与工单文本框。 */
    public static String snippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        int len = Math.min(body.length, MAX_BODY_SNIPPET);
        String s = new String(body, 0, len, java.nio.charset.StandardCharsets.ISO_8859_1);
        return body.length > len ? s + "...[truncated " + (body.length - len) + " bytes]" : s;
    }

    private static String buildMessage(int statusCode, Reason reason, String responseBody) {
        StringBuilder sb = new StringBuilder("WADO-RS 拉取失败: ")
                .append(statusCode < 0 ? "TRANSPORT_FAILURE" : "HTTP " + statusCode)
                .append(" / ").append(reason);
        if (responseBody != null && !responseBody.isBlank()) {
            sb.append(" / body=").append(responseBody);
        }
        return sb.toString();
    }
}