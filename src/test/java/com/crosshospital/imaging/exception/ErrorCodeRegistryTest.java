package com.crosshospital.imaging.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 38 — 18 个统一错误码的注册表自检。 */
@DisplayName("Task 38 — 错误码注册表")
class ErrorCodeRegistryTest {

    @Test
    @DisplayName("18 个错误码、名字唯一、每项都有 HTTP 状态与处置建议")
    void registryIsCompleteAndSelfConsistent() {
        Map<String, ErrorCode> all = ErrorCodeRegistry.all();
        assertAll(
                () -> assertEquals(18, all.size()),
                () -> assertTrue(ErrorCodeRegistry.selfCheck().isEmpty(),
                        "自检不通过：" + ErrorCodeRegistry.selfCheck()),
                () -> all.forEach((code, value) -> {
                    assertEquals(code, value.code());
                    assertNotNull(value.message());
                    assertFalse(value.message().isBlank());
                    assertNotNull(value.suggestedAction());
                    assertTrue(value.httpStatus() >= 400 && value.httpStatus() <= 599);
                })
        );
    }

    @Test
    @DisplayName("三层异常类型都至少有一个错误码，且映射互不混用")
    void everyLayerIsMapped() {
        Map<ErrorCode.Layer, Integer> counts = new EnumMap<>(ErrorCode.Layer.class);
        for (ErrorCode.Layer layer : ErrorCode.Layer.values()) {
            counts.put(layer, 0);
        }
        for (ErrorCode code : ErrorCode.values()) {
            counts.compute(code.layer(), (k, v) -> v + 1);
            Class<? extends RuntimeException> type = ErrorCodeRegistry.exceptionType(code);
            switch (code.layer()) {
                case DOMAIN -> assertEquals(DomainException.class, type);
                case INFRASTRUCTURE -> assertEquals(InfrastructureException.class, type);
                case EXTERNAL_SYSTEM -> assertEquals(ExternalSystemException.class, type);
            }
        }
        for (Map.Entry<ErrorCode.Layer, Integer> entry : counts.entrySet()) {
            assertTrue(entry.getValue() > 0, entry.getKey() + " 层没有任何错误码");
        }
    }

    @Test
    @DisplayName("retryable 判据：配置错/鉴权/格式错不可重试，链路瞬态可重试")
    void retryableFlagFollowsTheRule() {
        Set<String> notRetryable = Set.of("association_rejected", "tls_mismatch",
                "transfer_syntax_empty", "sop_class_unsupported", "invalid_dicom_preamble",
                "invalid_hl7_frame", "model_parse_error", "idem_conflict", "unauthorized");
        Set<String> retryable = Set.of("connection_refused", "query_timeout", "retrieve_failed",
                "store_failed", "partial_failure", "model_unreachable", "model_timeout",
                "dlq_full", "webhook_failed");

        assertAll(
                () -> notRetryable.forEach(code -> assertFalse(
                        ErrorCode.of(code).retryable(), code + " 不该可重试")),
                () -> retryable.forEach(code -> assertTrue(
                        ErrorCode.of(code).retryable(), code + " 应可重试")),
                () -> assertEquals(notRetryable.size() + retryable.size(),
                        ErrorCode.values().length)
        );
    }

    @Test
    @DisplayName("现存异常经 classify 桥接到统一错误码，认不出来返回 null")
    void legacyExceptionsAreBridged() {
        assertEquals(ErrorCode.CONNECTION_REFUSED,
                ErrorCodeRegistry.classify(new HandshakeException(
                        HandshakeException.FailureCode.CONNECTION_REFUSED, "连不上")));
        assertEquals(ErrorCode.MODEL_TIMEOUT,
                ErrorCodeRegistry.classify(new ModelTimeoutException(5000, "http://x", "超时", null)));
        assertEquals(ErrorCode.IDEM_CONFLICT,
                ErrorCodeRegistry.classify(new ConflictException("重复")));
        assertEquals(null, ErrorCodeRegistry.classify(new ValidationException("参数错")));
        assertEquals(null, ErrorCodeRegistry.classify(null));
    }
}
