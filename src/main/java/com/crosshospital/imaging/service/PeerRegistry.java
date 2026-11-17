package com.crosshospital.imaging.service;

import com.crosshospital.imaging.api.dto.PeerCreateRequest;
import com.crosshospital.imaging.api.dto.PeerResponse;
import com.crosshospital.imaging.api.dto.PeerUpdateRequest;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.exception.ConflictException;
import com.crosshospital.imaging.exception.NotFoundException;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.security.AuditChecksum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 外院 DICOM 节点准入表 CRUD 业务层。
 *
 * <p>承担三件事：
 * <ol>
 *   <li>字段语义校验（与 {@link InputValidator} 配合）</li>
 *   <li>唯一性 / 存在性检查（peer_aet）</li>
 *   <li>每次写操作落审计行（{@code audit_imaging_fetch}）</li>
 * </ol>
 *
 * <p>源主路径证据：dcm4che ReconnectDirContext（LDAP 自动重连）→
 * 改写为 PostgreSQL + HikariCP，应用层维护 AET 准入表。
 * 业务上对应信息科每接入一家外院节点触发一次的
 * "白名单录入"流程：失败码分类、落审计、待人工复核。
 */
@Service
@Profile("!test")
public class PeerRegistry {

    private static final Logger log = LoggerFactory.getLogger(PeerRegistry.class);

    private final PeerRepository peerRepository;
    private final AuditRepository auditRepository;
    private final InputValidator inputValidator;

    public PeerRegistry(PeerRepository peerRepository,
                        AuditRepository auditRepository,
                        InputValidator inputValidator) {
        this.peerRepository = peerRepository;
        this.auditRepository = auditRepository;
        this.inputValidator = inputValidator;
    }

    @Transactional
    public PeerResponse createPeer(PeerCreateRequest req, String who) {
        inputValidator.isValidAet(req.getPeerAet());
        inputValidator.isValidHost(req.getHost());
        inputValidator.isValidPort(req.getPort());
        inputValidator.isValidTlsMode(req.getTlsMode() == null ? "none" : req.getTlsMode());
        inputValidator.validateSopClasses(req.getPresentContexts());
        inputValidator.validateTransferSyntaxes(req.getTransferSyntaxes());
        inputValidator.validateBlacklistAets(req.getBlacklistAets(), req.getPeerAet());

        if (peerRepository.existsByPeerAet(req.getPeerAet())) {
            throw new ConflictException("peer_aet already exists: " + req.getPeerAet());
        }

        Peer p = new Peer();
        p.setPeerAet(req.getPeerAet());
        p.setHost(req.getHost());
        p.setPort(req.getPort());
        p.setTlsMode(req.getTlsMode() == null ? "none" : req.getTlsMode());
        p.setPresentContexts(toJsonArray(req.getPresentContexts()));
        p.setTransferSyntaxes(toJsonArray(req.getTransferSyntaxes()));
        p.setBlacklistAets(toJsonArray(req.getBlacklistAets()));
        p.setMaxRetries(req.getMaxRetries() == null ? 3 : req.getMaxRetries());
        p.setBackoffMs(req.getBackoffMs() == null ? 1000 : req.getBackoffMs());
        p.setCacheTtlHours(req.getCacheTtlHours() == null ? 24 : req.getCacheTtlHours());
        p.setMaxCacheMb(req.getMaxCacheMb() == null ? 5120L : req.getMaxCacheMb());
        p.setEnabled(Boolean.TRUE);
        p.setNotes(req.getNotes());

        Peer saved = peerRepository.save(p);
        recordPeerAudit(saved, "create_peer", who);
        log.info("peer created aet={} by={}", saved.getPeerAet(), who);
        return PeerResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public Page<PeerResponse> listPeers(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<Peer> peers = peerRepository.findAllByEnabledTrue(pageable);
        return peers.map(PeerResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public PeerResponse getPeer(String aet) {
        Peer p = peerRepository.findByPeerAet(aet)
                .orElseThrow(() -> new NotFoundException("peer", aet));
        return PeerResponse.fromEntity(p);
    }

    @Transactional
    public PeerResponse updatePeer(String aet, PeerUpdateRequest req, String who) {
        Peer p = peerRepository.findByPeerAet(aet)
                .orElseThrow(() -> new NotFoundException("peer", aet));

        if (req.getHost() != null) {
            inputValidator.isValidHost(req.getHost());
            p.setHost(req.getHost());
        }
        if (req.getPort() != null) {
            inputValidator.isValidPort(req.getPort());
            p.setPort(req.getPort());
        }
        if (req.getTlsMode() != null) {
            inputValidator.isValidTlsMode(req.getTlsMode());
            p.setTlsMode(req.getTlsMode());
        }
        if (req.getPresentContexts() != null) {
            inputValidator.validateSopClasses(req.getPresentContexts());
            p.setPresentContexts(toJsonArray(req.getPresentContexts()));
        }
        if (req.getTransferSyntaxes() != null) {
            inputValidator.validateTransferSyntaxes(req.getTransferSyntaxes());
            p.setTransferSyntaxes(toJsonArray(req.getTransferSyntaxes()));
        }
        if (req.getBlacklistAets() != null) {
            inputValidator.validateBlacklistAets(req.getBlacklistAets(), aet);
            p.setBlacklistAets(toJsonArray(req.getBlacklistAets()));
        }
        if (req.getMaxRetries() != null) {
            p.setMaxRetries(req.getMaxRetries());
        }
        if (req.getBackoffMs() != null) {
            p.setBackoffMs(req.getBackoffMs());
        }
        if (req.getCacheTtlHours() != null) {
            p.setCacheTtlHours(req.getCacheTtlHours());
        }
        if (req.getMaxCacheMb() != null) {
            p.setMaxCacheMb(req.getMaxCacheMb());
        }
        if (req.getEnabled() != null) {
            p.setEnabled(req.getEnabled());
        }
        if (req.getNotes() != null) {
            p.setNotes(req.getNotes());
        }

        Peer saved = peerRepository.save(p);
        recordPeerAudit(saved, "update_peer", who);
        log.info("peer updated aet={} by={}", saved.getPeerAet(), who);
        return PeerResponse.fromEntity(saved);
    }

    @Transactional
    public void deletePeer(String aet, String who) {
        Peer p = peerRepository.findByPeerAet(aet)
                .orElseThrow(() -> new NotFoundException("peer", aet));
        recordPeerAudit(p, "delete_peer", who);
        peerRepository.delete(p);
        log.info("peer deleted aet={} by={}", aet, who);
    }

    // ──── audit helper ────

    /**
     * 把节点变更写到 {@code audit_imaging_fetch}：who + when + from_aet + to_aet +
     * status_terminal。
     *
     * <p>task 24 起链式 {@code row_checksum} 由 {@link AuditChecksum} 统一计算：
     * 本方法取当前表最后一行的 {@code row_checksum} 作为 {@code prev_checksum}，
     * 再按固定字段顺序算出本行摘要。准入审计与握手/调阅审计写在同一张表里，
     * 因此必须走同一条链，否则 {@code AuditChecksumVerifier} 会在跨来源的那一行报断链。
     *
     * <p><b>写入走 {@link AuditChain#append} 而不是直接 {@code auditRepository.save}</b>：
     * 本类与握手/调阅审计共用同一条链，并发写入必须共用同一把锁 ——
     * 此前本类裸写（不盖戳也不加锁），既会被数据库的 {@code NOT NULL} 拒掉，
     * 又会把审计链分叉成「看起来像被人篡改」的断链误报。
     */
    private void recordPeerAudit(Peer p, String statusTerminal, String who) {
        AuditRow row = new AuditRow()
                .withWho(who == null ? "system" : who)
                .withFromAet("local")
                .withToAet(p.getPeerAet())
                .withStudyUid(null)
                .withSopClassUid(null)
                .withTransferSyntax(null)
                .withRetryCount(0)
                .withStatusTerminal(statusTerminal)
                .withFailureCode(null);

        AuditChain.append(auditRepository, row);
    }

    private static String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        return list.stream()
                .map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }
}