package com.crosshospital.imaging.service.task;

import java.util.Optional;

import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.repository.PeerRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 逐节点的重试预算解析 —— 「这个外院节点允许重试几次、基数退避多少毫秒」。
 *
 * <p><b>预算为什么是逐节点的</b>：全市三甲 PACS 与县医院 PACS 的承受能力不一样。
 * 三甲那台 PACS 前面挂着四台影像设备，一条调阅请求排到 30 秒很正常；
 * 县医院那台是单机，挤一挤就拒接。给所有外院用同一个重试次数，
 * 要么把县医院那台刷到拒接，要么让三甲那台白白放弃。
 * 所以预算写在 {@code imaging_peers} 表里，随准入表一起维护。
 *
 * <p><b>取值口径与迁移脚本一致</b>：
 * {@code max_retries} 默认 3、{@code backoff_ms} 默认 1000（V1 迁移的 DEFAULT）。
 * 节点查不到时也用这两个默认值 —— 不是「不重试」：
 * 查不到节点真正的失败来自「节点未登记」（不可重试），预算取什么都不影响结果；
 * 给默认值是为了让退避计算有确定的基数，不返回 0 触发忙等。
 *
 * <p>源主路径证据：dcm4che 用 LDAP 存 DICOM 节点配置（{@code ReconnectDirContext}），
 * 逐节点的连接参数天然挂在节点条目上；本产品把同一份「逐节点配置」
 * 换成 PostgreSQL 的 {@code imaging_peers} 表（属融合增量，见 spec.md → 开源对照）。
 */
@Service
@Profile("!test")
public class RetryBudgetResolver {

    private static final Logger LOG = LoggerFactory.getLogger(RetryBudgetResolver.class);

    /** 准入表缺 {@code max_retries} 时的兜底预算（与 V1 migration 的 DEFAULT 3 一致）。 */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /** 准入表缺 {@code backoff_ms} 时的兜底基数（与 V1 migration 的 DEFAULT 1000 一致）。 */
    public static final int DEFAULT_BACKOFF_MS = 1000;

    private final PeerRepository peerRepository;

    public RetryBudgetResolver(PeerRepository peerRepository) {
        if (peerRepository == null) {
            throw new IllegalArgumentException("RetryBudgetResolver 依赖不能为 null");
        }
        this.peerRepository = peerRepository;
    }

    /**
     * 某个节点的逐节点重试预算。
     *
     * @param maxRetries    重试上限（不含首次尝试）
     * @param baseBackoffMs 退避基数（毫秒）
     */
    public record RetryBudget(int maxRetries, long baseBackoffMs) {

        public static RetryBudget defaults() {
            return new RetryBudget(DEFAULT_MAX_RETRIES, DEFAULT_BACKOFF_MS);
        }
    }

    /**
     * 取节点的重试预算；节点未登记 / 配置缺失时回落默认值。
     *
     * <p>读库失败也不抛 —— 预算读不到不该让整条调阅失败，
     * 回落默认值后重试照常，运维从日志里看到「预算回落默认」即可。
     *
     * @param peerAet 外院 AE Title
     */
    public RetryBudget budgetFor(String peerAet) {
        if (peerAet == null || peerAet.isBlank()) {
            return RetryBudget.defaults();
        }
        Optional<Peer> peer;
        try {
            peer = peerRepository.findByPeerAet(peerAet);
        } catch (RuntimeException e) {
            LOG.warn("读节点重试配置失败，回落到默认预算: peer={} {}", peerAet, e.getMessage());
            return RetryBudget.defaults();
        }
        if (peer.isEmpty()) {
            LOG.debug("节点未登记，重试预算按默认值: peer={}", peerAet);
            return RetryBudget.defaults();
        }
        Integer maxRetries = peer.get().getMaxRetries();
        Integer backoffMs = peer.get().getBackoffMs();
        return new RetryBudget(
                maxRetries == null || maxRetries < 0 ? DEFAULT_MAX_RETRIES : maxRetries,
                backoffMs == null || backoffMs <= 0 ? DEFAULT_BACKOFF_MS : backoffMs);
    }
}
