package com.crosshospital.imaging.service.dicom;

import com.crosshospital.imaging.exception.HandshakeException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 黑名单 AET 执行器。
 *
 * <p>对端 PACS 在准入表登记时可能声明 {@code blacklist_aets} 列表，列入其中的 AET 不允许
 * 接入本服务（DICOM standard 不强制 AET 黑名单语义；该字段是本服务在 dcm4che
 * {@code Connection.setBlacklistedCallingAETitles} 之上的扩展）。
 *
 * <p>对应源仓库 dcm4che：
 * <ul>
 *   <li>{@code Connection.java} — {@code setBlacklistedCallingAETitles} / {@code setBlacklistedCalledAETitles}
 *       把黑名单注入协议层握手拒绝路径</li>
 *   <li>{@code ConnectionMonitor.java} — {@code onConnectionRejectedBlacklisted} 回调
 *       （本项目 {@code ImagingConnectionMonitor} 复用此回调落审计 + 告警）</li>
 * </ul>
 *
 * <p><b>行为</b>：黑名单命中一律抛 {@link HandshakeException}，且 {@code retryable=false}。
 * 黑名单是安全策略（不是网络瞬态），重试只会反复触发同一条拒绝链路，必须人工介入
 * 修改准入表。
 *
 * <p><b>大小写不敏感</b>：AET 在 DICOM upper-layer 走 ASCII 字符串匹配，本服务
 * 同样按 {@link String#equalsIgnoreCase(String)} 比较——这是与 dcm4che
 * {@code Connection.isBlacklisted()} 一致的语义。
 */
@Service
@Profile("!test")
public class BlacklistEnforcer {

    /** 黑名单命中是配置异常，**不**可重试；重试只会反复触发同一条拒绝链路。 */
    public static final boolean BLACKLISTED_NOT_RETRYABLE = false;

    /**
     * 检查 peerAet 是否在黑名单中。
     *
     * @param peerAet   远端 AET
     * @param blacklist 黑名单 AET 列表（来自 {@code peer.blacklist_aets} JSONB）
     * @return true 表示命中黑名单
     */
    public boolean isBlacklisted(String peerAet, List<String> blacklist) {
        if (blacklist == null || blacklist.isEmpty() || peerAet == null) {
            return false;
        }
        for (String blocked : blacklist) {
            if (blocked != null && blocked.equalsIgnoreCase(peerAet)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 强制执行黑名单检查；命中即抛 {@link HandshakeException}（{@code retryable=false}）。
     *
     * @param peerAet   远端 AET
     * @param blacklist 黑名单 AET 列表
     * @throws HandshakeException 当 peerAet 在黑名单中时抛出
     */
    public void enforce(String peerAet, List<String> blacklist) {
        if (isBlacklisted(peerAet, blacklist)) {
            throw new HandshakeException(
                    HandshakeException.FailureCode.BLACKLISTED,
                    "Peer AET 命中黑名单: " + peerAet,
                    BLACKLISTED_NOT_RETRYABLE);
        }
    }
}