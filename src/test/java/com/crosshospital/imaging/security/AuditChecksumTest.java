package com.crosshospital.imaging.security;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.support.AuditTestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 24 单测：SHA-256 链式 row_checksum 的计算规则。
 *
 * <p>核心手法：期望值由 {@link AuditTestFixtures#referenceChecksum} <b>独立</b>重算，
 * 不复用被测实现，避免「实现等于自己」的假通过。
 */
class AuditChecksumTest {

    private static final Instant T0 = Instant.parse("2026-11-06T02:00:00.123456Z");

    @Test
    @DisplayName("空表首行的 prev_checksum = 32 字节全 0")
    void emptyChecksumIsThirtyTwoZeroBytes() {
        byte[] empty = AuditChecksum.emptyChecksum();
        assertEquals(32, empty.length);
        assertEquals(32, AuditChecksum.CHECKSUM_LENGTH);
        for (byte b : empty) {
            assertEquals(0, b, "空表首行 prev_checksum 每个字节都应为 0");
        }
        // 每次返回新数组，调用方误改不会污染全局
        empty[0] = 0x7F;
        assertEquals(0, AuditChecksum.emptyChecksum()[0]);
    }

    @Test
    @DisplayName("计算结果与规格的独立参考实现一致")
    void computeMatchesIndependentReferenceImplementation() {
        AuditRow row = AuditTestFixtures.row("employee_007", T0, "local", "PEER_HZ",
                "<EXAMPLE_STUDY_UID>", "stored", null, 2);
        byte[] prev = AuditChecksum.emptyChecksum();

        assertArrayEquals(AuditTestFixtures.referenceChecksum(prev, row),
                AuditChecksum.computeRowChecksum(prev, row));
    }

    @Test
    @DisplayName("相同输入 → 相同摘要；不同 prev → 不同摘要")
    void checksumIsDeterministicAndPrevSensitive() {
        AuditRow row = AuditTestFixtures.row("system", T0, "local", "PEER_A", null, "failed", "TIMEOUT", 1);
        byte[] prev = AuditChecksum.emptyChecksum();

        assertArrayEquals(AuditChecksum.computeRowChecksum(prev, row),
                AuditChecksum.computeRowChecksum(prev, row));

        byte[] otherPrev = new byte[32];
        otherPrev[31] = 0x01;
        assertFalse(Arrays.equals(AuditChecksum.computeRowChecksum(prev, row),
                AuditChecksum.computeRowChecksum(otherPrev, row)));
    }

    @Test
    @DisplayName("null 字段与空串字段等价（都按空串参与哈希）")
    void nullFieldsAreTreatedAsEmptyString() {
        AuditRow nulls = new AuditRow()
                .withWho(null).withFromAet(null).withToAet(null).withStudyUid(null)
                .withSopClassUid(null).withTransferSyntax(null).withRetryCount(null)
                .withStatusTerminal(null).withFailureCode(null)
                .withWhenTs(T0);
        AuditRow empties = new AuditRow()
                .withWho("").withFromAet("").withToAet("").withStudyUid("")
                .withSopClassUid("").withTransferSyntax("").withRetryCount(0)
                .withStatusTerminal("").withFailureCode("")
                .withWhenTs(T0);

        byte[] prev = AuditChecksum.emptyChecksum();
        assertArrayEquals(AuditChecksum.computeRowChecksum(prev, nulls),
                AuditChecksum.computeRowChecksum(prev, empties));
    }

    @Test
    @DisplayName("时间戳截断到微秒：纳秒差异不改变摘要（对齐 PostgreSQL TIMESTAMPTZ 精度）")
    void timestampIsTruncatedToMicroseconds() {
        Instant withNanos = T0.plusNanos(789);
        assertEquals("2026-11-06T02:00:00.123456Z", AuditChecksum.canonicalTimestamp(withNanos));

        AuditRow a = AuditTestFixtures.row("system", withNanos, "local", "PEER_A", null, "stored", null, 0);
        AuditRow b = AuditTestFixtures.row("system", T0, "local", "PEER_A", null, "stored", null, 0);
        byte[] prev = AuditChecksum.emptyChecksum();

        assertArrayEquals(AuditChecksum.computeRowChecksum(prev, a),
                AuditChecksum.computeRowChecksum(prev, b));
    }

    @Test
    @DisplayName("10 个字段逐一改动都会改变摘要（无字段漏进哈希）")
    void everyFieldParticipatesInTheChecksum() {
        AuditRow base = AuditTestFixtures.row("employee_001", T0, "local", "PEER_HZ",
                "<EXAMPLE_STUDY_UID>", "stored", null, 0)
                .withSopClassUid("1.2.840.10008.5.1.4.1.1.2")
                .withTransferSyntax("1.2.840.10008.1.2.1");
        byte[] prev = AuditChecksum.emptyChecksum();
        byte[] baseCs = AuditChecksum.computeRowChecksum(prev, base);

        List<AuditRow> variants = List.of(
                AuditTestFixtures.row("employee_002", T0, "local", "PEER_HZ",
                        "<EXAMPLE_STUDY_UID>", "stored", null, 0)
                        .withSopClassUid("1.2.840.10008.5.1.4.1.1.2")
                        .withTransferSyntax("1.2.840.10008.1.2.1"),
                AuditTestFixtures.row("employee_001", T0.plusSeconds(1), "local", "PEER_HZ",
                        "<EXAMPLE_STUDY_UID>", "stored", null, 0)
                        .withSopClassUid("1.2.840.10008.5.1.4.1.1.2")
                        .withTransferSyntax("1.2.840.10008.1.2.1"),
                AuditTestFixtures.row("employee_001", T0, "remote", "PEER_HZ",
                        "<EXAMPLE_STUDY_UID>", "stored", null, 0)
                        .withSopClassUid("1.2.840.10008.5.1.4.1.1.2")
                        .withTransferSyntax("1.2.840.10008.1.2.1"),
                AuditTestFixtures.row("employee_001", T0, "local", "PEER_SH",
                        "<EXAMPLE_STUDY_UID>", "stored", null, 0)
                        .withSopClassUid("1.2.840.10008.5.1.4.1.1.2")
                        .withTransferSyntax("1.2.840.10008.1.2.1"),
                AuditTestFixtures.row("employee_001", T0, "local", "PEER_HZ",
                        "<EXAMPLE_STUDY_UID_2>", "stored", null, 0)
                        .withSopClassUid("1.2.840.10008.5.1.4.1.1.2")
                        .withTransferSyntax("1.2.840.10008.1.2.1"),
                AuditTestFixtures.row("employee_001", T0, "local", "PEER_HZ",
                        "<EXAMPLE_STUDY_UID>", "stored", null, 0)
                        .withSopClassUid("1.2.840.10008.5.1.4.1.1.3")
                        .withTransferSyntax("1.2.840.10008.1.2.1"),
                AuditTestFixtures.row("employee_001", T0, "local", "PEER_HZ",
                        "<EXAMPLE_STUDY_UID>", "stored", null, 0)
                        .withSopClassUid("1.2.840.10008.5.1.4.1.1.2")
                        .withTransferSyntax("1.2.840.10008.1.2.2"),
                AuditTestFixtures.row("employee_001", T0, "local", "PEER_HZ",
                        "<EXAMPLE_STUDY_UID>", "failed", null, 0)
                        .withSopClassUid("1.2.840.10008.5.1.4.1.1.2")
                        .withTransferSyntax("1.2.840.10008.1.2.1"),
                AuditTestFixtures.row("employee_001", T0, "local", "PEER_HZ",
                        "<EXAMPLE_STUDY_UID>", "stored", "TIMEOUT", 0)
                        .withSopClassUid("1.2.840.10008.5.1.4.1.1.2")
                        .withTransferSyntax("1.2.840.10008.1.2.1"),
                AuditTestFixtures.row("employee_001", T0, "local", "PEER_HZ",
                        "<EXAMPLE_STUDY_UID>", "stored", null, 1)
                        .withSopClassUid("1.2.840.10008.5.1.4.1.1.2")
                        .withTransferSyntax("1.2.840.10008.1.2.1"));

        for (AuditRow variant : variants) {
            assertFalse(Arrays.equals(baseCs, AuditChecksum.computeRowChecksum(prev, variant)),
                    "改字段后摘要必须变化，否则该字段没进哈希");
        }
    }

    @Test
    @DisplayName("stamp：补齐时间戳与重试次数，并写入 prev/row 两个校验和")
    void stampFillsTimestampAndChecksums() {
        AuditRow row = new AuditRow()
                .withWho("system")
                .withFromAet("local")
                .withToAet("PEER_HZ")
                .withStatusTerminal("handshake_established");

        byte[] prev = AuditChecksum.emptyChecksum();
        byte[] stamped = AuditChecksum.stamp(row, prev);

        assertNotNull(row.getWhenTs());
        assertTrue(row.getWhenTs().equals(row.getWhenTs().truncatedTo(ChronoUnit.MICROS)),
                "时间戳应截断到微秒");
        assertEquals(0, row.getRetryCount());
        assertArrayEquals(prev, row.getPrevChecksum());
        assertArrayEquals(stamped, row.getRowChecksum());
        assertArrayEquals(AuditTestFixtures.referenceChecksum(prev, row), stamped);
    }

    @Test
    @DisplayName("规范化载荷按固定顺序列出字段")
    void canonicalPayloadKeepsFieldOrder() {
        AuditRow row = AuditTestFixtures.row("employee_007", T0, "local", "PEER_HZ",
                "<EXAMPLE_STUDY_UID>", "stored", null, 2);
        String payload = AuditChecksum.canonicalPayload(row);

        assertTrue(payload.indexOf("employee_007") < payload.indexOf("2026-11-06"),
                "who 应排在 when 之前");
        assertTrue(payload.indexOf("local") < payload.indexOf("PEER_HZ"));
        assertTrue(payload.indexOf("PEER_HZ") < payload.indexOf("<EXAMPLE_STUDY_UID>"));
        assertTrue(payload.indexOf("<EXAMPLE_STUDY_UID>") < payload.indexOf("stored"));
        assertTrue(payload.endsWith("|stored|"), "末尾应为 status_terminal 与空的 failure_code");
    }

    @Test
    @DisplayName("非法输入被拒：null 行 / prev_checksum 长度不是 32")
    void invalidInputIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AuditChecksum.computeRowChecksum(AuditChecksum.emptyChecksum(), null));
        assertThrows(IllegalArgumentException.class,
                () -> AuditChecksum.computeRowChecksum(new byte[16],
                        AuditTestFixtures.row("system", T0, "local", "PEER_A", null, "stored", null, 0)));
        assertThrows(IllegalArgumentException.class, () -> AuditChecksum.stamp(null, null));
    }
}
