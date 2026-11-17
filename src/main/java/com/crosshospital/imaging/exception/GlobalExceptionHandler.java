package com.crosshospital.imaging.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 全局异常处理（{@code @RestControllerAdvice}）。
 *
 * <p>统一错误响应契约：
 * <pre>
 * {
 *   "code": "validation_error" | "not_found" | "conflict" | "internal_error" | 统一错误码,
 *   "message": "可读错误描述",
 *   "traceId": "uuid-for-correlation",
 *   "retryable": false,
 *   "timestamp": "2026-10-20T10:00:00Z",
 *   "fieldErrors": [{ "field": "port", "rejectedValue": "0", "message": "must be ≥ 1" }, ...]
 * }
 * </pre>
 *
 * <p>task 38 起，所有响应都带 {@code retryable}：调用方不用查文档猜「这个错能不能重发」。
 * 带统一 {@link ErrorCode} 的异常还会带 {@code suggestedAction}，把值班人该做什么
 * 一并写进响应体。
 *
 * <p>异常→HTTP 状态码映射：
 * <ul>
 *   <li>{@link ValidationException} / {@link MethodArgumentNotValidException}
 *       / {@link ConstraintViolationException} —— 400</li>
 *   <li>{@link NotFoundException} —— 404</li>
 *   <li>{@link ConflictException} —— 409</li>
 *   <li>{@link HttpMessageNotReadableException} —— 400（JSON 缺字段）</li>
 *   <li>{@link MethodArgumentTypeMismatchException} —— 400（路径参数类型错）</li>
 *   <li>{@link HandlerMethodValidationException} —— 400（@PathVariable/@RequestParam 校验）</li>
 *   <li>{@link HttpRequestMethodNotSupportedException} —— 405（方法用错，不是服务故障）</li>
 *   <li>{@link NoResourceFoundException} —— 404（路径 / 静态资源不存在）</li>
 *   <li>{@link ModelUnreachableException} —— 502（模型服务不可达 / 4xx / 5xx）</li>
 *   <li>{@link ModelTimeoutException} —— 504（模型服务超时）</li>
 *   <li>{@link ModelResponseParseException} —— 502（模型响应读不懂或结构不符）</li>
 *   <li>其它 —— 500，message 不暴露内部异常细节</li>
 * </ul>
 *
 * <p>源主路径：spring-boot-starter-web + springdoc 标准错误响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ─────── 业务层自定义 ───────

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ValidationException ex) {
        log.warn("validation rejected: {}", ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, "validation_error", ex.getMessage(), null);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex) {
        log.info("resource not found: {}", ex.getMessage());
        return body(HttpStatus.NOT_FOUND, "not_found", ex.getMessage(), null);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException ex) {
        log.info("conflict: {}", ex.getMessage());
        return body(HttpStatus.CONFLICT, "conflict", ex.getMessage(), null);
    }

    /**
     * 非法状态跃迁 → 409。
     *
     * <p>不是 400：请求本身语法没问题，是它要做的动作与当前业务状态冲突
     * （比如影像已经写进本院 PACS 了，又有人要求把它拉回查询阶段）。
     * 409 让调用方知道「重试没用，得先查状态」。
     */
    @ExceptionHandler(IllegalTaskTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalTransition(
            IllegalTaskTransitionException ex) {
        log.warn("illegal task transition: {}", ex.getMessage());
        return body(HttpStatus.CONFLICT, "illegal_state_transition", ex.getMessage(), null);
    }

    // ─────── spring 内置校验 ───────

    /** {@code @Valid @RequestBody} 失败 → 400，附 fieldErrors。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleBodyValidation(MethodArgumentNotValidException ex) {
        List<Map<String, Object>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .collect(Collectors.toList());
        log.warn("body validation failed: {}", fieldErrors);
        return body(HttpStatus.BAD_REQUEST, "validation_error", "Request body validation failed",
                fieldErrors);
    }

    /** {@code @PathVariable / @RequestParam} 校验失败 → 400。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(
            ConstraintViolationException ex) {
        List<Map<String, Object>> fieldErrors = ex.getConstraintViolations().stream()
                .map(this::violation)
                .collect(Collectors.toList());
        log.warn("constraint violation: {}", fieldErrors);
        return body(HttpStatus.BAD_REQUEST, "validation_error", "Constraint violation", fieldErrors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleHandlerMethodValidation(
            HandlerMethodValidationException ex) {
        log.warn("handler method validation: {}", ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, "validation_error",
                "Handler method validation failed: " + ex.getMessage(), null);
    }

    // ─────── 协议层 ───────

    /** JSON 缺失 / 类型不匹配 → 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("http message not readable: {}", ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, "validation_error",
                "Request body is missing or malformed JSON", null);
    }

    /** 路径参数类型错 → 400。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        String msg = String.format("parameter '%s' expects %s but got '%s'",
                ex.getName(),
                ex.getRequiredType() == null ? "?" : ex.getRequiredType().getSimpleName(),
                ex.getValue());
        log.warn("method argument type mismatch: {}", msg);
        return body(HttpStatus.BAD_REQUEST, "validation_error", msg, null);
    }

    /**
     * 不支持的 HTTP 方法 → 405。
     *
     * <p>不是 500：只读端点收到 POST 是调用方方法用错，不是本服务故障。
     * 兜底的 {@code @ExceptionHandler(Exception.class)} 会抢在 Spring 默认的
     * {@code DefaultHandlerExceptionResolver} 之前把这类异常吞成 500，必须显式接住。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        log.info("method not supported: {}", ex.getMessage());
        return body(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed",
                "HTTP method not allowed on this endpoint", null);
    }

    /** 路径 / 静态资源找不到 → 404，而不是让兜底把它报成 500。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
        log.info("resource not found: {}", ex.getMessage());
        return body(HttpStatus.NOT_FOUND, "not_found", "Resource not found", null);
    }

    // ─────── 模型网关 ───────

    /**
     * 模型服务不可达 → 502。
     *
     * <p>不是 500：本服务本身没坏，是它依赖的模型服务没响应。
     * 502 让调用方一眼分清「我这边的错」与「上游 AI 的错」——
     * 后者应由规则模板兜底，不该拖着调阅主路径一起失败。
     */
    @ExceptionHandler(ModelUnreachableException.class)
    public ResponseEntity<Map<String, Object>> handleModelUnreachable(ModelUnreachableException ex) {
        log.warn("model unreachable: reason={} status={} retryable={} msg={}",
                ex.getReason(), ex.getHttpStatus(), ex.isRetryable(), ex.getMessage());
        return body(HttpStatus.BAD_GATEWAY, "model_unreachable", ex.getMessage(), null);
    }

    /** 模型服务超时 → 504。 */
    @ExceptionHandler(ModelTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleModelTimeout(ModelTimeoutException ex) {
        log.warn("model timeout: timeoutMs={} endpoint={}", ex.getTimeoutMs(), ex.getEndpoint());
        return body(HttpStatus.GATEWAY_TIMEOUT, "model_timeout", ex.getMessage(), null);
    }

    /** 模型响应读不懂 / 结构不符 → 502。 */
    @ExceptionHandler(ModelResponseParseException.class)
    public ResponseEntity<Map<String, Object>> handleModelParse(ModelResponseParseException ex) {
        log.warn("model response parse failed: error={} violations={}", ex.getParseError(),
                ex.getViolations());
        return body(HttpStatus.BAD_GATEWAY, "model_response_invalid", ex.getMessage(), null);
    }

    // ─────── task 38：三层异常 → 统一错误码 ───────

    /**
     * 把带统一错误码的异常转成 HTTP 响应。
     *
     * <p>三种基类共用一段逻辑，但保留三个 handler 方法：Spring 按最具体类型选 handler，
     * 显式列出来才能让人一眼看出「这三层都接住了」；同时保证以后给某一层加特殊处理
     * 时不必改公共方法。
     */
    private ResponseEntity<Map<String, Object>> fromErrorCode(RuntimeException ex,
                                                              ErrorCode code,
                                                              HttpStatus fallback) {
        if (code == null) {
            return body(fallback, "internal_error", ex.getMessage(), null, false, null);
        }
        return body(HttpStatus.valueOf(code.httpStatus()), code.code(), ex.getMessage(),
                null, code.retryable(), code.suggestedAction());
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, Object>> handleDomain(DomainException ex) {
        log.warn("domain rule rejected: {}", ex.getMessage());
        return fromErrorCode(ex, ex.errorCode(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(InfrastructureException.class)
    public ResponseEntity<Map<String, Object>> handleInfrastructure(InfrastructureException ex) {
        log.error("infrastructure failure: {}", ex.getMessage(), ex);
        return fromErrorCode(ex, ex.errorCode(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(ExternalSystemException.class)
    public ResponseEntity<Map<String, Object>> handleExternalSystem(ExternalSystemException ex) {
        log.warn("external system failure: {}", ex.getMessage());
        return fromErrorCode(ex, ex.errorCode(), HttpStatus.BAD_GATEWAY);
    }

    // ─────── 兜底 ───────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception ex) {
        log.error("unhandled exception", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred. Please contact the administrator with the traceId.",
                null);
    }

    // ─────── helpers ───────

    private ResponseEntity<Map<String, Object>> body(
            HttpStatus status, String code, String message, List<Map<String, Object>> fieldErrors) {
        return body(status, code, message, fieldErrors, false, null);
    }

    private ResponseEntity<Map<String, Object>> body(
            HttpStatus status, String code, String message, List<Map<String, Object>> fieldErrors,
            boolean retryable, String suggestedAction) {
        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("code", code);
        body2.put("message", message);
        body2.put("traceId", UUID.randomUUID().toString());
        body2.put("retryable", retryable);
        body2.put("timestamp", Instant.now().toString());
        if (suggestedAction != null && !suggestedAction.isBlank()) {
            body2.put("suggestedAction", suggestedAction);
        }
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            body2.put("fieldErrors", fieldErrors);
        }
        return ResponseEntity.status(status).body(body2);
    }

    private Map<String, Object> fieldError(FieldError fe) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", fe.getField());
        m.put("rejectedValue", fe.getRejectedValue());
        m.put("message", fe.getDefaultMessage());
        return m;
    }

    private Map<String, Object> violation(ConstraintViolation<?> v) {
        Map<String, Object> m = new LinkedHashMap<>();
        // 路径形如 "create.aet" 或 "get.aet" —— 截最后一段作为 field 名
        String path = v.getPropertyPath().toString();
        int dot = path.lastIndexOf('.');
        m.put("field", dot >= 0 ? path.substring(dot + 1) : path);
        m.put("rejectedValue", v.getInvalidValue());
        m.put("message", v.getMessage());
        return m;
    }
}