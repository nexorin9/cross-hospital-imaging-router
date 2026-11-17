package com.crosshospital.imaging.exception;

import java.util.List;

/**
 * 模型响应无法解析 / 结构不符合预期。
 *
 * <p>与「模型不可达」严格分开：<b>拿到响应但读不懂</b>是另一类故障，
 * 而且处置策略不同 —— 解析失败<b>不重试</b>（同样的提示 + 同样的模型大概率还是不符合结构），
 * 直接回退规则模板（task 27）。
 *
 * <p>{@link ParseError} 六态：
 * <ul>
 *   <li>{@link ParseError#NOT_JSON} —— 响应体根本不是 JSON</li>
 *   <li>{@link ParseError#MISSING_CHOICE} —— 没有 {@code choices} 或为空数组</li>
 *   <li>{@link ParseError#MISSING_CONTENT} —— {@code choices[0].message.content} 缺失或空白</li>
 *   <li>{@link ParseError#INVALID_JSON_CONTENT} —— 要求 JSON 但模型回话不是合法 JSON</li>
 *   <li>{@link ParseError#SCHEMA_VIOLATION} —— 回话是合法 JSON 但不符合 schema</li>
 *   <li>{@link ParseError#INCOMPLETE_OUTPUT} —— {@code finish_reason} 说明模型<b>没有完整生成</b>
 *       （被长度上限截断 {@code length} / 被内容安全拦截 {@code content_filter}）</li>
 * </ul>
 */
public class ModelResponseParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 响应体片段保留上限（字符）；超出截断，防止把大 body 带进日志与工单。 */
    public static final int MAX_SNIPPET = 512;

    /** 解析失败分类。 */
    public enum ParseError {
        NOT_JSON,
        MISSING_CHOICE,
        MISSING_CONTENT,
        INVALID_JSON_CONTENT,
        SCHEMA_VIOLATION,
        /** 模型没有完整生成（finish_reason=length / content_filter）。 */
        INCOMPLETE_OUTPUT
    }

    private final ParseError parseError;
    private final String responseSnippet;
    private final List<String> violations;

    public ModelResponseParseException(ParseError parseError, String responseSnippet,
                                       List<String> violations, String message, Throwable cause) {
        super(message, cause);
        this.parseError = parseError;
        this.responseSnippet = snippet(responseSnippet);
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public ParseError getParseError() { return parseError; }

    /** 响应体片段（截断后）。 */
    public String getResponseSnippet() { return responseSnippet; }

    /** schema 校验的具体违规条目（非 SCHEMA_VIOLATION 时为空）。 */
    public List<String> getViolations() { return violations; }

    /** 解析失败<b>不</b>值得重试。 */
    public boolean isRetryable() { return false; }

    /** 按 {@link #MAX_SNIPPET} 截断响应体。 */
    public static String snippet(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        return body.length() > MAX_SNIPPET ? body.substring(0, MAX_SNIPPET) + "...[truncated]" : body;
    }
}
