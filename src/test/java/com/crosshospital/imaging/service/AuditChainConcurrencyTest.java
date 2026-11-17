package com.crosshospital.imaging.service;

import com.crosshospital.imaging.api.dto.PeerCreateRequest;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.repository.AiCallAuditRepository;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.security.AuditChecksumVerifier;
import com.crosshospital.imaging.service.wado.WadoRsAuditWriter;
import com.crosshospital.imaging.support.AuditTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 24 修复的回归测试：审计链在<b>多条写入路径并发</b>下不得分叉。
 *
 * <p><b>为什么要有这个测试</b>：修复前 {@code AuditService} /
 * {@code PeerRegistry} / {@code WadoRsAuditWriter} 三条写入路径各自
 * 「读最新 prev → 盖戳 → INSERT」，但只有 {@code AuditService} 加了锁。
 * 并发压一次实测 947 行里分叉 557 点 —— 而分叉在校验器眼里与
 * 「有人篡改了记录」完全同形（{@code PREV_LINK_MISMATCH}），
 * 于是飞检现场会看到一条<b>并不存在的</b>篡改告警。
 *
 * <p>这类缺陷单靠「审计链自身算得对不对」的用例测不出来：
 * {@code AuditChecksumTest} 全绿，链照样会分叉。
 * 必须是<b>并发 + 多路径</b>的用例才拦得住。
 */
class AuditChainConcurrencyTest {

    private AuditTestFixtures.InMemoryAuditRepository repository;
    private AuditService auditService;
    private PeerRegistry peerRegistry;
    private WadoRsAuditWriter wadoRsAuditWriter;
    private AuditChecksumVerifier verifier;

    @BeforeEach
    void setUp() {
        repository = new AuditTestFixtures.InMemoryAuditRepository();
        verifier = new AuditChecksumVerifier();
        auditService = new AuditService(repository);

        PeerRepository peerRepository = mock(PeerRepository.class);
        when(peerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(peerRepository.existsByPeerAet(any())).thenReturn(false);
        peerRegistry = new PeerRegistry(peerRepository, repository, new InputValidator());

        wadoRsAuditWriter = new WadoRsAuditWriter(repository, mock(AiCallAuditRepository.class));
    }

    @Test
    @DisplayName("三条写入路径并发写：审计链仍然是一条直线（不分叉、不误报篡改）")
    void mixedWritePathsDoNotForkTheChain() throws Exception {
        int threads = 9;
        int perThread = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        switch (idx % 3) {
                            case 0 -> auditService.record(new AuditRow()
                                    .withWho("employee_" + idx + "_" + i)
                                    .withFromAet("local").withToAet("PEER_HZ")
                                    .withStudyUid("<EXAMPLE_STUDY_UID>")
                                    .withRetryCount(0).withStatusTerminal("handshake_established"));
                            case 1 -> peerRegistry.createPeer(sampleCreate("PEER_" + idx + "_" + i),
                                    "employee_" + idx);
                            default -> wadoRsAuditWriter.recordFailure("PEER_HZ",
                                    "<EXAMPLE_STUDY_UID>", new RuntimeException("boom"), 1,
                                    "system", null, 5L);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发写入应在 60 秒内完成");
        pool.shutdownNow();

        List<AuditRow> rows = new ArrayList<>(repository.all());
        assertTrue(rows.size() >= threads * perThread,
                "三条路径都应留下审计行，实际 " + rows.size());
        rows.sort((a, b) -> Long.compare(a.getId(), b.getId()));

        // 每一行的 prev_checksum 必须接上上一行的 row_checksum
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(java.util.Arrays.equals(rows.get(i - 1).getRowChecksum(),
                            rows.get(i).getPrevChecksum()),
                    "第 " + (i + 1) + " 行的 prev_checksum 未接上上一行 —— 链分叉了");
        }

        // 整条链校验必须通过：分叉会表现为「像被篡改」的误报
        assertTrue(verifier.verifyChain(rows),
                "并发多路径写入后审计链必须仍然可验证通过");
    }

    @Test
    @DisplayName("事务未提交时锁不释放：JPA 的 INSERT 提交前对别的连接不可见")
    void lockIsHeldUntilTransactionCompletes() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        try {
            auditService.record(new AuditRow()
                    .withWho("employee_tx").withFromAet("local").withToAet("PEER_HZ")
                    .withRetryCount(0).withStatusTerminal("handshake_established"));

            AtomicBoolean secondWriteFinished = new AtomicBoolean(false);
            Thread other = new Thread(() -> {
                AuditChain.append(repository, new AuditRow()
                        .withWho("employee_other").withFromAet("local").withToAet("PEER_HZ")
                        .withRetryCount(0).withStatusTerminal("handshake_established"));
                secondWriteFinished.set(true);
            });
            other.start();

            // 事务还没结束 → 第二个写必须被挡在锁外
            other.join(500);
            assertFalse(secondWriteFinished.get(),
                    "事务未提交时不应放锁：否则对方读不到本行的 row_checksum，链会分叉");

            // 模拟事务提交
            for (TransactionSynchronization sync :
                    TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
            other.join(5000);
            assertTrue(secondWriteFinished.get(), "事务结束后应放锁");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        List<AuditRow> rows = new ArrayList<>(repository.all());
        rows.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        assertTrue(verifier.verifyChain(rows));
    }

    @Test
    @DisplayName("写入失败不泄漏锁：后续写入仍能正常接链")
    void failedWriteDoesNotLeakTheLock() {
        AuditRepository failing = mock(AuditRepository.class);
        when(failing.findLatestRowChecksum()).thenReturn(java.util.Optional.empty());
        when(failing.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> AuditChain.append(failing, new AuditRow()
                .withWho("employee_x").withFromAet("local").withToAet("PEER_HZ")
                .withRetryCount(0).withStatusTerminal("handshake_established")));

        // 锁必须已经放掉：正常写入仍能接上
        AuditRow row = AuditChain.append(repository, new AuditRow()
                .withWho("employee_y").withFromAet("local").withToAet("PEER_HZ")
                .withRetryCount(0).withStatusTerminal("handshake_established"));
        assertTrue(java.util.Arrays.equals(
                com.crosshospital.imaging.security.AuditChecksum.emptyChecksum(),
                row.getPrevChecksum()));
    }

    @Test
    @DisplayName("null 入参被拒（不吞异常、不半写一行）")
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AuditChain.append(null, new AuditRow()));
        assertThrows(IllegalArgumentException.class, () -> AuditChain.append(repository, null));
    }

    private PeerCreateRequest sampleCreate(String aet) {
        PeerCreateRequest req = new PeerCreateRequest();
        req.setPeerAet(aet);
        req.setHost("pacs.example-hospital.cn");
        req.setPort(11112);
        req.setTlsMode("none");
        req.setPresentContexts(List.of("1.2.840.10008.5.1.4.1.1.2"));
        req.setTransferSyntaxes(List.of("1.2.840.10008.1.2.1"));
        req.setBlacklistAets(List.of());
        return req;
    }
}
