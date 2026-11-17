package com.crosshospital.imaging.security;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.support.AuditTestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 24 单测：审计链校验器。
 *
 * <p>覆盖「没被动过 → 通过」与「被动了 → 必须失败并指出是哪一行」两侧。
 */
class AuditChecksumVerifierTest {

    private final AuditChecksumVerifier verifier = new AuditChecksumVerifier();

    private static final Instant T0 = Instant.parse("2026-11-06T02:00:00Z");

    /** 造一条连续链：每行的 prev_checksum 指向上一行的 row_checksum。 */
    private static List<AuditRow> buildChain(int size) {
        List<AuditRow> rows = new ArrayList<>();
        byte[] prev = AuditChecksum.emptyChecksum();
        for (int i = 0; i < size; i++) {
            AuditRow row = AuditTestFixtures.row("employee_" + String.format("%03d", i),
                    T0.plusSeconds(i), "local", "PEER_HZ", "<EXAMPLE_STUDY_UID>",
                    i % 5 == 0 ? "failed" : "stored", i % 5 == 0 ? "TIMEOUT" : null, i % 3);
            AuditChecksum.stamp(row, prev);
            prev = row.getRowChecksum();
            AuditTestFixtures.assignId(row, i + 1L);
            rows.add(row);
        }
        return rows;
    }

    @Test
    @DisplayName("空链视为一致")
    void emptyChainIsValid() {
        assertTrue(verifier.verifyChain(List.of()));
        assertTrue(verifier.verifyChain(null));
        ChainVerificationReport report = verifier.verifyChainDetailed(List.of());
        assertTrue(report.valid());
        assertEquals(0, report.rowsChecked());
        assertEquals(-1, report.mismatchIndex());
    }

    @Test
    @DisplayName("连续 100 行的链校验通过")
    void hundredRowChainVerifies() {
        List<AuditRow> rows = buildChain(100);
        assertTrue(verifier.verifyChain(rows));

        ChainVerificationReport report = verifier.verifyChainDetailed(rows);
        assertTrue(report.valid());
        assertEquals(100, report.rowsChecked());
        assertEquals(ChainVerificationReport.Reason.NONE, report.reason());
    }

    @Test
    @DisplayName("篡改任意一行的字段 → 校验失败并报告该行 id")
    void tamperedRowFailsAndReportsMismatchId() {
        List<AuditRow> rows = buildChain(100);
        AuditRow tampered = rows.get(49);
        long tamperedId = tampered.getId();

        // 模拟「有人事后把操作人改掉了」：改 who，不动校验和
        tampered.withWho("someone_else");

        assertFalse(verifier.verifyChain(rows));

        ChainVerificationReport report = verifier.verifyChainDetailed(rows);
        assertFalse(report.valid());
        assertNotNull(report.mismatchId());
        assertEquals(tamperedId, report.mismatchId());
        assertEquals(49, report.mismatchIndex());
        assertEquals(ChainVerificationReport.Reason.ROW_CHECKSUM_MISMATCH, report.reason());
        assertNotNull(report.expectedHex());
        assertNotNull(report.actualHex());
        assertFalse(report.expectedHex().equals(report.actualHex()));
        assertTrue(report.summary().contains("第 50 行"));
    }

    @Test
    @DisplayName("篡改首行的 prev_checksum → 断链被识别")
    void brokenPrevLinkIsDetected() {
        List<AuditRow> rows = buildChain(10);
        byte[] bogus = new byte[32];
        bogus[0] = 0x42;
        rows.get(0).withChecksums(bogus, rows.get(0).getRowChecksum());

        ChainVerificationReport report = verifier.verifyChainDetailed(rows);
        assertFalse(report.valid());
        assertEquals(ChainVerificationReport.Reason.PREV_LINK_MISMATCH, report.reason());
        assertEquals(0, report.mismatchIndex());
    }

    @Test
    @DisplayName("中途插入一行（未盖戳）→ 断链被识别")
    void insertedRowBreaksChain() {
        List<AuditRow> rows = new ArrayList<>(buildChain(10));
        AuditRow injected = AuditTestFixtures.row("intruder", T0, "local", "PEER_HZ",
                "<EXAMPLE_STUDY_UID>", "stored", null, 0);
        AuditTestFixtures.assignId(injected, 999L);
        rows.add(5, injected);

        ChainVerificationReport report = verifier.verifyChainDetailed(rows);
        assertFalse(report.valid());
        assertEquals(ChainVerificationReport.Reason.MISSING_ROW_CHECKSUM, report.reason());
        assertEquals(5, report.mismatchIndex());
        assertEquals(999L, report.mismatchId());
    }

    @Test
    @DisplayName("两行对调 → 链被识别为不一致")
    void reorderedRowsBreakChain() {
        List<AuditRow> rows = new ArrayList<>(buildChain(10));
        AuditRow tmp = rows.get(3);
        rows.set(3, rows.get(4));
        rows.set(4, tmp);

        assertFalse(verifier.verifyChain(rows));
        ChainVerificationReport report = verifier.verifyChainDetailed(rows);
        assertEquals(ChainVerificationReport.Reason.PREV_LINK_MISMATCH, report.reason());
    }

    @Test
    @DisplayName("列表中出现 null 行 → 报告而不是抛异常")
    void nullRowIsReported() {
        List<AuditRow> rows = new ArrayList<>(buildChain(5));
        rows.set(2, null);

        ChainVerificationReport report = verifier.verifyChainDetailed(rows);
        assertFalse(report.valid());
        assertEquals(ChainVerificationReport.Reason.NULL_ROW, report.reason());
        assertEquals(2, report.mismatchIndex());
    }
}
