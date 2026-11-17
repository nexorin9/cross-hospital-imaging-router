package com.crosshospital.imaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;

/**
 * DICOM 网络配置（{@code dicom.*}）。
 *
 * <p>对应源仓库 dcm4che 中 {@code Connection} 类的可配置字段：
 * <ul>
 *   <li>{@link #localAet} / {@link #localHost} — 本服务作 SCU 时对外呈现的 AE Title</li>
 *   <li>{@link #bindPort} — 本服务作 SCP 时监听端口（如 -1 = 仅作客户端，不监听）</li>
 *   <li>{@link #remoteConnectTimeoutMs} — 远端 TCP 连接超时</li>
 *   <li>{@link #tlsCipherSuites} / {@link #tlsProtocols} — 协议协商的白名单</li>
 *   <li>{@link #supportedTransferSyntaxes} / {@link #supportedSopClasses} — 本服务支持的传输语法 + SOP Class</li>
 *   <li>{@link #maxOpsInvoked} / {@link #maxOpsPerformed} — DIMSE 上限</li>
 * </ul>
 *
 * <p>源主路径证据：dcm4che-net {@code Connection.java:1034-1091}（connect + checkCompatible） +
 * {@code Connection.java:1197-1217}（isCompatible）；{@code CompatibleConnection} 把本地 + 远端
 * 两端 Connection 配对用于兼容性预校验。
 */
@ConfigurationProperties(prefix = "dicom")
@Profile("!test")
public class DicomConfigProps {

    /**
     * 本服务作 SCU 时对外呈现的 AE Title。
     *
     * <p>DICOM PS3.8 规定 AE Title 最长 16 字符；dcm4che 在
     * {@code AAssociateRQ.setCallingAET()} 会直接校验，超长抛
     * {@code IllegalArgumentException("callingAET: ...")}。默认值必须是合法值，
     * 不能靠 application.yml 兜底 —— 配置文件缺失时也要能建关联。
     */
    private String localAet = "IMAGER";

    /** 本服务绑定的 hostname（一般 0.0.0.0 或具体 IP）；默认 0.0.0.0 让 OS 选接口。 */
    private String localHost = "0.0.0.0";

    /** 本服务作 SCP 时监听的端口；-1 表示仅作客户端不监听（默认）。 */
    private int bindPort = -1;

    /** 远端 TCP 连接超时（毫秒）。 */
    private int remoteConnectTimeoutMs = 5000;

    /** TLS 协议白名单（默认 TLSv1.2；与 Connection.DEFAULT_TLS_PROTOCOLS 对齐）。 */
    private List<String> tlsProtocols = new ArrayList<>(List.of("TLSv1.2"));

    /** TLS 密码套件白名单（空 = 不启用 TLS）。 */
    private List<String> tlsCipherSuites = new ArrayList<>();

    /** 本服务作 SCU 时声明的传输语法（按偏好顺序）。 */
    private List<String> supportedTransferSyntaxes = new ArrayList<>(List.of(
            "1.2.840.10008.1.2.1",      // Explicit VR Little Endian
            "1.2.840.10008.1.2",        // Implicit VR Little Endian
            "1.2.840.10008.1.2.4.70",   // JPEG Lossless
            "1.2.840.10008.1.2.4.90"    // JPEG 2000 Lossless
    ));

    /** 本服务支持的 SOP Class UID 列表（握手健康检查时与对端交集）。 */
    private List<String> supportedSopClasses = new ArrayList<>(List.of(
            "1.2.840.10008.5.1.4.1.1.2", // CT Image Storage
            "1.2.840.10008.5.1.4.1.1.4", // MR Image Storage
            "1.2.840.10008.5.1.4.1.1.1", // CR Image Storage
            "1.2.840.10008.5.1.4.1.1.6.1", // US Image Storage
            "1.2.840.10008.5.1.4.1.1.7"  // Secondary Capture
    ));

    /** DIMSE 并发上界（调用方 / 被调用方）；默认 1 表示同步模式。 */
    private int maxOpsInvoked = 1;
    private int maxOpsPerformed = 1;

    public String getLocalAet() { return localAet; }
    public void setLocalAet(String localAet) { this.localAet = localAet; }

    public String getLocalHost() { return localHost; }
    public void setLocalHost(String localHost) { this.localHost = localHost; }

    public int getBindPort() { return bindPort; }
    public void setBindPort(int bindPort) { this.bindPort = bindPort; }

    public int getRemoteConnectTimeoutMs() { return remoteConnectTimeoutMs; }
    public void setRemoteConnectTimeoutMs(int remoteConnectTimeoutMs) {
        this.remoteConnectTimeoutMs = remoteConnectTimeoutMs;
    }

    public List<String> getTlsProtocols() { return tlsProtocols; }
    public void setTlsProtocols(List<String> tlsProtocols) { this.tlsProtocols = tlsProtocols; }

    public List<String> getTlsCipherSuites() { return tlsCipherSuites; }
    public void setTlsCipherSuites(List<String> tlsCipherSuites) { this.tlsCipherSuites = tlsCipherSuites; }

    public List<String> getSupportedTransferSyntaxes() { return supportedTransferSyntaxes; }
    public void setSupportedTransferSyntaxes(List<String> supportedTransferSyntaxes) {
        this.supportedTransferSyntaxes = supportedTransferSyntaxes;
    }

    public List<String> getSupportedSopClasses() { return supportedSopClasses; }
    public void setSupportedSopClasses(List<String> supportedSopClasses) {
        this.supportedSopClasses = supportedSopClasses;
    }

    public int getMaxOpsInvoked() { return maxOpsInvoked; }
    public void setMaxOpsInvoked(int maxOpsInvoked) { this.maxOpsInvoked = maxOpsInvoked; }

    public int getMaxOpsPerformed() { return maxOpsPerformed; }
    public void setMaxOpsPerformed(int maxOpsPerformed) { this.maxOpsPerformed = maxOpsPerformed; }
}
