package com.crosshospital.imaging.event;

/**
 * DICOM 连接被黑名单拒绝事件。
 *
 * <p>对应源仓库 dcm4che-net {@code ConnectionMonitor.onConnectionRejectedBlacklisted}
 * （{@code ConnectionMonitor.java:55}）。DICOM upper-layer 标准不强制 AET
 * 黑名单语义，但本项目在准入表 {@code peer.blacklist_aets} JSONB 字段扩展
 * 了对端 AET 的应用层屏蔽；命中黑名单时 {@code BlacklistEnforcer.enforce}
 * 抛 {@code HandshakeException(BLACKLISTED, retryable=false)}。
 *
 * <p>本事件由 {@code ImagingConnectionMonitor} 发布；监听方：
 * <ul>
 *   <li>告警 webhook（钉钉 / 企业微信 / HTTP）</li>
 *   <li>信息科安全审计仪表盘</li>
 * </ul>
 *
 * <p>黑名单命中是<b>配置层</b>拒绝，与网络瞬态不同 ——
 * 必须人工介入修改准入表，**不**触发自动重试。
 */
import org.springframework.context.ApplicationEvent;

public class ConnectionRejectedBlacklistedEvent extends ApplicationEvent {

    private final String peerAet;
    private final String remoteAet;
    private final String blacklistEntry;
    private final String reason;

    public ConnectionRejectedBlacklistedEvent(Object source,
                                                String peerAet,
                                                String remoteAet,
                                                String blacklistEntry,
                                                String reason) {
        super(source);
        this.peerAet = peerAet;
        this.remoteAet = remoteAet;
        this.blacklistEntry = blacklistEntry;
        this.reason = reason;
    }

    public String getPeerAet() { return peerAet; }
    public String getRemoteAet() { return remoteAet; }
    public String getBlacklistEntry() { return blacklistEntry; }
    public String getReason() { return reason; }
}
