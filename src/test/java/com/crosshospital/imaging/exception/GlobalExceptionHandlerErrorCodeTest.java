package com.crosshospital.imaging.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 38 — 把 6 种异常真的打到 {@link GlobalExceptionHandler} 上，
 * 校验 HTTP 状态码 + 统一响应体（{@code code / message / traceId / retryable / suggestedAction}）。
 *
 * <p>用 standalone MockMvc + 一个只负责抛异常的 controller：不启动整个 Spring 容器，
 * 但异常分派、@RestControllerAdvice、Jackson 序列化走的都是真实链路。
 */
@DisplayName("Task 38 — 异常 → HTTP 状态码映射")
class GlobalExceptionHandlerErrorCodeTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("connection_refused → 502 + retryable=true")
    void connectionRefused() throws Exception {
        assertError("connection_refused", 502, true);
    }

    @Test
    @DisplayName("model_unreachable → 502 + retryable=true")
    void modelUnreachable() throws Exception {
        assertError("model_unreachable", 502, true);
    }

    @Test
    @DisplayName("idem_conflict → 409 + retryable=false")
    void idemConflict() throws Exception {
        assertError("idem_conflict", 409, false);
    }

    @Test
    @DisplayName("dlq_full → 507 + retryable=true")
    void dlqFull() throws Exception {
        assertError("dlq_full", 507, true);
    }

    @Test
    @DisplayName("unauthorized → 401 + retryable=false")
    void unauthorized() throws Exception {
        assertError("unauthorized", 401, false);
    }

    @Test
    @DisplayName("invalid_dicom_preamble → 422 + retryable=false")
    void invalidDicomPreamble() throws Exception {
        assertError("invalid_dicom_preamble", 422, false);
    }

    @Test
    @DisplayName("旧异常（validation_error）也带 retryable 字段，响应契约统一")
    void legacyValidationErrorAlsoCarriesRetryable() throws Exception {
        mvc.perform(get("/test/errors/legacy-validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private void assertError(String code, int httpStatus, boolean retryable) throws Exception {
        mvc.perform(get("/test/errors/" + code))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.retryable").value(retryable))
                .andExpect(jsonPath("$.suggestedAction").isNotEmpty());
    }

    /** 只负责按路径抛异常，方便把每一层异常都送进真实 @RestControllerAdvice。 */
    @RestController
    static class ThrowingController {

        @GetMapping("/test/errors/{code}")
        String throwError(@PathVariable String code) {
            if ("legacy-validation".equals(code)) {
                throw new ValidationException("参数写错了");
            }
            ErrorCode errorCode = ErrorCode.of(code);
            if (errorCode == null) {
                throw new IllegalArgumentException("未知错误码: " + code);
            }
            throw ErrorCodeRegistry.newException(errorCode, errorCode.message());
        }
    }
}
