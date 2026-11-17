package com.crosshospital.imaging.exception;

/**
 * 业务层输入校验失败异常（HTTP 400 语义）。
 *
 * <p>与 {@code jakarta.validation.ValidationException} 区分：
 * 本类是业务层校验（字段语义/域约束），spring 注解校验
 * 仍由 {@code @Valid} 触发并由 {@code GlobalExceptionHandler} 映射。
 */
public class ValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }
}