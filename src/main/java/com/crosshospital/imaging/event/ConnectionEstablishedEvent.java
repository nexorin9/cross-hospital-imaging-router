package com.crosshospital.imaging.event;

/**
 * DICOM 连接已建立事件。
 *
 * <p>对应源仓库 dcm4che-net {@code ConnectionMonitor.onConnectionEstablished}
 * 和 {@code ConnectionMonitor.onConnectionAccepted} 的业务侧通知。
 * 发布方：{@code ImagingConnectionMonitor}；监听方：路由服务相关 component
 * （缓存预热、互认初筛预取、UI 看板等）。
 */
import org.springframework.context.ApplicationEvent;

public class ConnectionEstablishedEvent extends ApplicationEvent {

    private final String peerAet;
    private final String localAet;
    private final String remoteHost;
    private final Integer remotePort;
    private final String statusTerminal;

    public ConnectionEstablishedEvent(Object source,
                                       String peerAet,
                                       String localAet,
                                       String remoteHost,
                                       Integer remotePort,
                                       String statusTerminal) {
        super(source);
        this.peerAet = peerAet;
        this.localAet = localAet;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.statusTerminal = statusTerminal;
    }

    public String getPeerAet() { return peerAet; }
    public String getLocalAet() { return localAet; }
    public String getRemoteHost() { return remoteHost; }
    public Integer getRemotePort() { return remotePort; }
    public String getStatusTerminal() { return statusTerminal; }
}
