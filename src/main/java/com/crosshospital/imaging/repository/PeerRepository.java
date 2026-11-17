package com.crosshospital.imaging.repository;

import com.crosshospital.imaging.domain.Peer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 外院 DICOM 节点准入表 CRUD Repository。
 *
 * <p>Spring Data JPA 自动派生 {@code findByPeerAet} / {@code findAllByEnabled} /
 * {@code findAll(Pageable)} / {@code deleteByPeerAet}（不推荐使用——准入表
 * 节点删除应走 {@code PeerRegistry.deletePeer} 业务层并落审计）。
 */
public interface PeerRepository extends JpaRepository<Peer, Long> {

    Optional<Peer> findByPeerAet(String peerAet);

    boolean existsByPeerAet(String peerAet);

    List<Peer> findAllByEnabledTrue();

    Page<Peer> findAllByEnabledTrue(Pageable pageable);

    /** 按 host 反查所有节点（信息科排障用）。 */
    @Query("select p from Peer p where p.host = ?1")
    List<Peer> findAllByHost(String host);
}