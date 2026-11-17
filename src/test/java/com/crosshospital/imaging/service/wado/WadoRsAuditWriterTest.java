package com.crosshospital.imaging.service.wado;

import com.crosshospital.imaging.domain.AiCallAudit;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.exception.WadoRsHttpException;
import com.crosshospital.imaging.exception.WadoRsParseException;
import com.crosshospital.imaging.repository.AiCallAuditRepository;
import com.crosshospital.imaging.repository.AuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 12 单测：WadoRsAuditWriter 失败分类 + 双表落库。
 */
class WadoRsAuditWriterTest {

    private AuditRepository auditRepository;
    private AiCallAuditRepository aiCallAuditRepository;
    private WadoRsAuditWriter writer;

    @BeforeEach
    void setUp() {
        auditRepository = mock(AuditRepository.class);
        aiCallAuditRepository = mock(AiCallAuditRepository.class);
        // AuditRepository.save 返回模拟递增 id
        when(auditRepository.save(any(AuditRow.class))).thenAnswer(inv -> {
            AuditRow row = inv.getArgument(0);
            try {
                java.lang.reflect.Field f = AuditRow.class.getDeclaredField("id");
                f.setAccessible(true);
                f.set(row, 1L);
            } catch (Exception e) {
                // ignore
            }
            return row;
        });
        when(aiCallAuditRepository.save(any(AiCallAudit.class))).thenAnswer(inv -> inv.getArgument(0));
        writer = new WadoRsAuditWriter(auditRepository, aiCallAuditRepository);
    }

    @Test
    @DisplayName("HTTP 5xx → 落 audit + ai_call_audit(status=http_error)")
    void http5xxMapsToHttpError() {
        WadoRsHttpException e = new WadoRsHttpException(503, "down",
                true, WadoRsHttpException.Reason.SERVER_ERROR);
        writer.recordFailure("PEER_HZ", "<EXAMPLE_STUDY_UID>", e, 4, "employee_007",
                UUID.randomUUID(), 1234L);

        ArgumentCaptor<AuditRow> rowCap = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository).save(rowCap.capture());
        AuditRow saved = rowCap.getValue();
        assertEquals("wado_fetch_failed", saved.getStatusTerminal());
        assertEquals("http_error", saved.getFailureCode());
        assertEquals("PEER_HZ", saved.getToAet());
        assertEquals("employee_007", saved.getWho());

        ArgumentCaptor<AiCallAudit> aiCap = ArgumentCaptor.forClass(AiCallAudit.class);
        verify(aiCallAuditRepository).save(aiCap.capture());
        AiCallAudit ai = aiCap.getValue();
        assertEquals("http_error", ai.getStatus());
        assertEquals("wado_fetch", ai.getKind());
        assertEquals("wado-rs", ai.getModelName());
        assertEquals(1234, ai.getLatencyMs());
        assertNotNull(ai.getErrorMessage());
    }

    @Test
    @DisplayName("传输失败 → 落 audit + ai_call_audit(status=unreachable)")
    void transportFailureMapsToUnreachable() {
        WadoRsHttpException e = WadoRsHttpException.transportFailure("connect refused",
                new IOException("connect refused"));
        assertEquals("unreachable", writer.classifyFailure(e));
        writer.recordFailure("PEER_HZ", "<EXAMPLE_STUDY_UID>", e, 4, "system",
                UUID.randomUUID(), 8000L);

        ArgumentCaptor<AiCallAudit> aiCap = ArgumentCaptor.forClass(AiCallAudit.class);
        verify(aiCallAuditRepository).save(aiCap.capture());
        assertEquals("unreachable", aiCap.getValue().getStatus());
    }

    @Test
    @DisplayName("HTTP 408/504 → 归类为 timeout")
    void requestTimeoutMapsToTimeout() {
        WadoRsHttpException e408 = new WadoRsHttpException(408, "request timeout",
                true, WadoRsHttpException.Reason.SERVER_ERROR);
        assertEquals("timeout", writer.classifyFailure(e408));

        WadoRsHttpException e504 = new WadoRsHttpException(504, "gateway timeout",
                true, WadoRsHttpException.Reason.SERVER_ERROR);
        assertEquals("timeout", writer.classifyFailure(e504));
    }

    @Test
    @DisplayName("multipart 解析失败 → 落 audit + ai_call_audit(status=parse_error)")
    void parseErrorMapsToParseError() {
        WadoRsParseException e = new WadoRsParseException(
                WadoRsParseException.ParseError.MISSING_BOUNDARY,
                "no boundary");
        assertEquals("parse_error", writer.classifyFailure(e));
        writer.recordFailure("PEER_HZ", "<EXAMPLE_STUDY_UID>", e, 1, "system",
                UUID.randomUUID(), 200L);

        verify(auditRepository, times(1)).save(any(AuditRow.class));
        ArgumentCaptor<AiCallAudit> aiCap = ArgumentCaptor.forClass(AiCallAudit.class);
        verify(aiCallAuditRepository).save(aiCap.capture());
        assertEquals("parse_error", aiCap.getValue().getStatus());
        assertTrue(aiCap.getValue().getErrorMessage().contains("WadoRsParseException"));
    }

    @Test
    @DisplayName("其他未分类异常 → 兜底为 unreachable")
    void unknownExceptionFallsBackToUnreachable() {
        assertEquals("unreachable", writer.classifyFailure(new RuntimeException("???")));
    }

    @Test
    @DisplayName("who=null → 默认 system；retry<0 → 安全为 0")
    void nullWhoDefaultsToSystemAndNegativeRetryClampedToZero() {
        WadoRsHttpException e = new WadoRsHttpException(503, "down",
                true, WadoRsHttpException.Reason.SERVER_ERROR);
        writer.recordFailure("PEER_HZ", "<EXAMPLE_STUDY_UID>", e, -5, null, null, 100L);

        ArgumentCaptor<AuditRow> rowCap = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository).save(rowCap.capture());
        AuditRow saved = rowCap.getValue();
        assertEquals("system", saved.getWho());
        assertEquals(0, saved.getRetryCount());
    }

    @Test
    @DisplayName("auditRepository 抛异常 → 不冒泡（不让审计故障阻碍业务流）")
    void auditRepositoryFailureDoesNotPropagate() {
        when(auditRepository.save(any(AuditRow.class)))
                .thenThrow(new RuntimeException("DB down"));
        WadoRsHttpException e = new WadoRsHttpException(503, "down",
                true, WadoRsHttpException.Reason.SERVER_ERROR);
        // 不应抛
        writer.recordFailure("PEER_HZ", "<EXAMPLE_STUDY_UID>", e, 4, "system",
                UUID.randomUUID(), 100L);
        // ai_call_audit 仍写入
        verify(aiCallAuditRepository, atLeastOnce()).save(any(AiCallAudit.class));
    }

    @Test
    @DisplayName("summarizeFailure 截断超长消息到 1000 字符")
    void summarizeFailureTruncates() {
        String huge = "x".repeat(2000);
        String s = writer.summarizeFailure(new RuntimeException(huge));
        assertEquals(1000, s.length());
        assertTrue(s.endsWith("..."));
    }
}