package com.crosshospital.imaging.service;

import com.crosshospital.imaging.api.dto.PeerCreateRequest;
import com.crosshospital.imaging.api.dto.PeerResponse;
import com.crosshospital.imaging.api.dto.PeerUpdateRequest;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.exception.ConflictException;
import com.crosshospital.imaging.exception.NotFoundException;
import com.crosshospital.imaging.exception.ValidationException;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 5 — PeerRegistry 准入表 CRUD service 单元测试。
 *
 * <p>使用 Mockito 模拟 {@link PeerRepository} 与 {@link AuditRepository}，
 * 不依赖 Spring 上下文 / 数据库 / Flyway 即可验证业务逻辑与审计联动。
 *
 * <p>覆盖：
 * <ul>
 *   <li>4 个 happy path：createPeer / listPeers / getPeer / updatePeer / deletePeer</li>
 *   <li>5 个异常路径：peer_aet 重复 / host 缺失 / tls_mode 非法 / present_contexts
 *       含非法 SOP Class / blacklist_aets 含自身</li>
 *   <li>审计落库：每次写操作调用一次 {@code auditRepository.save}，
 *       status_terminal 与动作匹配，who 字段透传</li>
 * </ul>
 */
@DisplayName("Task 5 — PeerRegistry CRUD service (4 happy + 5 exception)")
class PeerRegistryServiceTest {

    private PeerRepository peerRepository;
    private AuditRepository auditRepository;
    private InputValidator inputValidator;
    private PeerRegistry peerRegistry;

    @BeforeEach
    void setUp() {
        peerRepository = mock(PeerRepository.class);
        auditRepository = mock(AuditRepository.class);
        inputValidator = new InputValidator();
        peerRegistry = new PeerRegistry(peerRepository, auditRepository, inputValidator);

        // save 默认回显：返回入参对象（生产环境是 JPA 写入返回带 id 的持久化对象）
        when(peerRepository.save(any(Peer.class))).thenAnswer(inv -> {
            Peer p = inv.getArgument(0);
            if (p.getId() == null) p.setId(System.nanoTime());
            return p;
        });
        when(peerRepository.existsByPeerAet(anyString())).thenReturn(false);
    }

    // ──────── happy path ────────

    @Test
    @DisplayName("happy: createPeer 写入 + 落 audit + 字段透传")
    void createPeerHappyPath() {
        PeerCreateRequest req = sampleCreate();

        when(peerRepository.existsByPeerAet("PEER_A")).thenReturn(false);

        PeerResponse resp = peerRegistry.createPeer(req, "employee_001");

        assertNotNull(resp);
        assertEquals("PEER_A", resp.getPeerAet());
        assertEquals("pacs.peer.cn", resp.getHost());
        assertEquals(11112, resp.getPort());
        assertEquals("none", resp.getTlsMode());

        verify(peerRepository, times(1)).save(any(Peer.class));
        verify(auditRepository, times(1)).save(any(AuditRow.class));

        AuditRow audit = captureAudit();
        assertEquals("create_peer", audit.getStatusTerminal());
        assertEquals("employee_001", audit.getWho());
        assertEquals("PEER_A", audit.getToAet());
    }

    @Test
    @DisplayName("happy: listPeers 返回分页 + 按 enabled=true 过滤")
    void listPeersHappyPath() {
        Peer p = samplePeer();
        when(peerRepository.findAllByEnabledTrue(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(p), PageRequest.of(0, 20), 1));

        Page<PeerResponse> page = peerRegistry.listPeers(0, 20);

        assertEquals(1, page.getTotalElements());
        assertEquals("PEER_A", page.getContent().get(0).getPeerAet());
        verify(peerRepository, times(1)).findAllByEnabledTrue(any(Pageable.class));
        // 列表不落审计
        verify(auditRepository, never()).save(any(AuditRow.class));
    }

    @Test
    @DisplayName("happy: getPeer 按 AET 查找返回 DTO")
    void getPeerHappyPath() {
        Peer p = samplePeer();
        when(peerRepository.findByPeerAet("PEER_A")).thenReturn(Optional.of(p));

        PeerResponse resp = peerRegistry.getPeer("PEER_A");

        assertEquals("PEER_A", resp.getPeerAet());
        assertEquals(11112, resp.getPort());
        // 只读不落审计
        verify(auditRepository, never()).save(any(AuditRow.class));
    }

    @Test
    @DisplayName("happy: updatePeer 部分字段 + 落 audit")
    void updatePeerHappyPath() {
        Peer p = samplePeer();
        when(peerRepository.findByPeerAet("PEER_A")).thenReturn(Optional.of(p));

        PeerUpdateRequest req = new PeerUpdateRequest();
        req.setPort(11113);
        req.setTlsMode("tls");
        req.setMaxRetries(5);

        PeerResponse resp = peerRegistry.updatePeer("PEER_A", req, "employee_002");

        assertEquals(11113, resp.getPort());
        assertEquals("tls", resp.getTlsMode());
        assertEquals(5, resp.getMaxRetries());
        verify(peerRepository, times(1)).save(any(Peer.class));
        verify(auditRepository, times(1)).save(any(AuditRow.class));

        AuditRow audit = captureAudit();
        assertEquals("update_peer", audit.getStatusTerminal());
        assertEquals("employee_002", audit.getWho());
    }

    @Test
    @DisplayName("happy: deletePeer 删除 + 落 audit")
    void deletePeerHappyPath() {
        Peer p = samplePeer();
        when(peerRepository.findByPeerAet("PEER_A")).thenReturn(Optional.of(p));

        peerRegistry.deletePeer("PEER_A", "employee_003");

        verify(peerRepository, times(1)).delete(p);
        verify(auditRepository, times(1)).save(any(AuditRow.class));

        AuditRow audit = captureAudit();
        assertEquals("delete_peer", audit.getStatusTerminal());
        assertEquals("employee_003", audit.getWho());
        assertEquals("PEER_A", audit.getToAet());
    }

    // ──────── exception path ────────

    @Test
    @DisplayName("exception: peer_aet 重复 → ConflictException，不写库不落审计")
    void duplicateAetRejected() {
        PeerCreateRequest req = sampleCreate();
        when(peerRepository.existsByPeerAet("PEER_A")).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> peerRegistry.createPeer(req, "employee_001"));

        assertTrue(ex.getMessage().contains("PEER_A"));
        verify(peerRepository, never()).save(any(Peer.class));
        verify(auditRepository, never()).save(any(AuditRow.class));
    }

    @Test
    @DisplayName("exception: host 缺失 → ValidationException")
    void missingHostRejected() {
        PeerCreateRequest req = sampleCreate();
        req.setHost("");

        assertThrows(ValidationException.class,
                () -> peerRegistry.createPeer(req, "employee_001"));
        verify(peerRepository, never()).save(any(Peer.class));
    }

    @Test
    @DisplayName("exception: tls_mode 非法 → ValidationException")
    void invalidTlsModeRejected() {
        PeerCreateRequest req = sampleCreate();
        req.setTlsMode("blowfish");

        assertThrows(ValidationException.class,
                () -> peerRegistry.createPeer(req, "employee_001"));
        verify(peerRepository, never()).save(any(Peer.class));
    }

    @Test
    @DisplayName("exception: present_contexts 含非法 SOP Class → ValidationException")
    void invalidSopClassRejected() {
        PeerCreateRequest req = sampleCreate();
        req.setPresentContexts(List.of("not-a-uid", "1.2.840.10008.5.1.4.1.1.2"));

        assertThrows(ValidationException.class,
                () -> peerRegistry.createPeer(req, "employee_001"));
        verify(peerRepository, never()).save(any(Peer.class));
    }

    @Test
    @DisplayName("exception: blacklist_aets 含自身 → ValidationException")
    void blacklistContainsSelfRejected() {
        PeerCreateRequest req = sampleCreate();
        req.setBlacklistAets(List.of("PEER_A"));

        assertThrows(ValidationException.class,
                () -> peerRegistry.createPeer(req, "employee_001"));
        verify(peerRepository, never()).save(any(Peer.class));
    }

    // ──────── 辅助 ────────

    private PeerCreateRequest sampleCreate() {
        PeerCreateRequest req = new PeerCreateRequest();
        req.setPeerAet("PEER_A");
        req.setHost("pacs.peer.cn");
        req.setPort(11112);
        req.setTlsMode("none");
        req.setPresentContexts(List.of(
                "1.2.840.10008.5.1.4.1.1.2",
                "1.2.840.10008.5.1.4.1.1.4"));
        req.setTransferSyntaxes(List.of(
                "1.2.840.10008.1.2.1",
                "1.2.840.10008.1.2.4.70"));
        req.setBlacklistAets(List.of());
        return req;
    }

    private Peer samplePeer() {
        Peer p = new Peer();
        p.setId(1L);
        p.setPeerAet("PEER_A");
        p.setHost("pacs.peer.cn");
        p.setPort(11112);
        p.setTlsMode("none");
        p.setPresentContexts("[]");
        p.setTransferSyntaxes("[]");
        p.setBlacklistAets("[]");
        p.setMaxRetries(3);
        p.setBackoffMs(1000);
        p.setCacheTtlHours(24);
        p.setMaxCacheMb(5120L);
        p.setEnabled(true);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }

    private AuditRow captureAudit() {
        ArgumentCaptor<AuditRow> cap = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository, atLeastOnce()).save(cap.capture());
        return cap.getValue();
    }
}