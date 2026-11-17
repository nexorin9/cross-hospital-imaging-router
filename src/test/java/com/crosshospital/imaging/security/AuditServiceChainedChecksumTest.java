package com.crosshospital.imaging.security;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.support.AuditTestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 24 单测：{@code AuditService} 的链式写入。
 *
 * <p>这是本任务的主验收路径 ——「连续插入 100 行 → 最后一行 row_checksum 等于链式
 * SHA-256 期望值 → 篡改任一行 → 校验失败并指出是哪一行 → 空表首行 prev 为 32 字节 0」。
 */
class AuditServiceChainedChecksumTest {

    private final AuditTestFixtures.InMemoryAuditRepository repository =
            new AuditTestFixtures.InMemoryAuditRepository();
    private final AuditService auditService = new AuditService(repository);
    private final AuditChecksumVerifier verifier = new AuditChecksumVerifier();

    @Test
    @DisplayName("连续写入 100 行：首行 prev 为 32 字节 0，逐行接续，末行等于链式期望值")
    void hundredConsecutiveRecordsFormAValidChain() {
        for (int i = 0; i < 100; i++) {
            auditService.record(AuditTestFixtures.row(
                    "employee_" + String.format("%03d", i),
                    null,                       // 时间戳交给实现补齐
                    "local",
                    "PEER_HZ",
                    "<EXAMPLE_STUDY_UID>",
                    i % 5 == 0 ? "failed" : "stored",
                    i % 5 == 0 ? "TIMEOUT" : null,
                    i % 3));
        }

        List<AuditRow> rows = repository.all();
        assertEquals(100, rows.size());

        // ① 空表首行 prev_checksum = 32 字节 0
        assertArrayEquals(AuditChecksum.emptyChecksum(), rows.get(0).getPrevChecksum());

        // ② 每一行的 prev_checksum = 上一行的 row_checksum
        for (int i = 1; i < rows.size(); i++) {
            assertArrayEquals(rows.get(i - 1).getRowChecksum(), rows.get(i).getPrevChecksum(),
                    "第 " + (i + 1) + " 行的 prev_checksum 未接上上一行");
        }

        // ③ 末行 row_checksum = 独立参考实现算出的链式期望值
        byte[] expectedLast = AuditTestFixtures.referenceChecksum(
                rows.get(98).getRowChecksum(), rows.get(99));
        assertArrayEquals(expectedLast, rows.get(99).getRowChecksum());

        // ④ 整条链校验通过
        assertTrue(verifier.verifyChain(rows));
        ChainVerificationReport report = verifier.verifyChainDetailed(rows);
        assertTrue(report.valid());
        assertEquals(100, report.rowsChecked());
    }

    @Test
    @DisplayName("篡改任意一行 → 校验失败并报告该行 id")
    void tamperedRowBreaksVerificationAndIsLocated() {
        for (int i = 0; i < 100; i++) {
            auditService.record(AuditTestFixtures.row("employee_" + i, null, "local", "PEER_HZ",
                    "<EXAMPLE_STUDY_UID>", "stored", null, 0));
        }

        List<AuditRow> rows = repository.all();
        AuditRow tampered = rows.get(42);
        long tamperedId = tampered.getId();
        tampered.withStatusTerminal("stored_but_tampered");

        assertFalse(verifier.verifyChain(rows));

        ChainVerificationReport report = verifier.verifyChainDetailed(rows);
        assertFalse(report.valid());
        assertEquals(tamperedId, report.mismatchId());
        assertEquals(42, report.mismatchIndex());
        assertEquals(ChainVerificationReport.Reason.ROW_CHECKSUM_MISMATCH, report.reason());
    }

    @Test
    @DisplayName("recordHandshake 也接入同一条链")
    void handshakeAuditIsPartOfTheSameChain() {
        auditService.recordHandshake("PEER_HZ", "handshake_established", "employee_007", null);
        auditService.recordHandshake("PEER_HZ", "handshake_failed", "employee_007", "TIMEOUT");

        List<AuditRow> rows = repository.all();
        assertEquals(2, rows.size());
        assertArrayEquals(AuditChecksum.emptyChecksum(), rows.get(0).getPrevChecksum());
        assertArrayEquals(rows.get(0).getRowChecksum(), rows.get(1).getPrevChecksum());
        assertTrue(verifier.verifyChain(rows));
        assertEquals("handshake_established", rows.get(0).getStatusTerminal());
        assertEquals("TIMEOUT", rows.get(1).getFailureCode());
    }

    @Test
    @DisplayName("并发写入不分叉：10 线程 × 10 行，链仍然完整可验")
    void concurrentRecordsKeepTheChainLinear() throws Exception {
        int threads = 10;
        int perThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        auditService.record(AuditTestFixtures.row("employee_" + idx + "_" + i, null,
                                "local", "PEER_HZ", "<EXAMPLE_STUDY_UID>", "stored", null, i));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        List<AuditRow> rows = repository.all();
        assertEquals(threads * perThread, rows.size());

        // 按写入顺序（id 递增）重排后校验：链不能分叉
        List<AuditRow> ordered = new ArrayList<>(rows);
        ordered.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        assertTrue(verifier.verifyChain(ordered),
                "并发写入后审计链必须仍然是一条直线");
    }

    @Test
    @DisplayName("写入时补齐了时间戳与两个校验和字段（库里两列都是 NOT NULL）")
    void recordStampsBothChecksumColumns() {
        AuditRow saved = auditService.record(
                AuditTestFixtures.row("system", null, "local", "PEER_HZ", null,
                        "handshake_established", null, 0));

        assertNotNull(saved.getWhenTs());
        assertNotNull(saved.getRowChecksum());
        assertNotNull(saved.getPrevChecksum());
        assertEquals(32, saved.getRowChecksum().length);
        assertEquals(32, saved.getPrevChecksum().length);
    }
}
