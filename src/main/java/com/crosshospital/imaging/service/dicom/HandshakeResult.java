package com.crosshospital.imaging.service.dicom;

import com.crosshospital.imaging.exception.HandshakeException.FailureCode;
import org.dcm4che3.net.Connection;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 握手结果值对象（最终落到 {@code PeersHealthController} 返回 JSON）。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code compatible} = true → 全部 6 步预校验通过 + TCP 探活成功</li>
 *   <li>{@code compatible} = false → 任一阶段失败；{@code lastErrorCode} 与 {@code lastErrorMessage} 说明原因</li>
 * </ul>
 */
public class HandshakeResult {

    public final boolean compatible;
    public final String peerAet;
    public final List<String> commonSopClasses;
    public final List<String> agreedTransferSyntaxes;
    public final List<String> agreedTlsProtocols;
    public final List<String> agreedCipherSuites;
    public final FailureCode lastErrorCode;
    public final String lastErrorMessage;
    public final String localAet;
    public final String remoteAet;
    public final String remoteHost;
    public final int remotePort;

    private HandshakeResult(boolean compatible,
                            String peerAet,
                            List<String> commonSopClasses,
                            List<String> agreedTransferSyntaxes,
                            List<String> agreedTlsProtocols,
                            List<String> agreedCipherSuites,
                            FailureCode lastErrorCode,
                            String lastErrorMessage,
                            Connection local,
                            Connection remote) {
        this.compatible = compatible;
        this.peerAet = peerAet;
        this.commonSopClasses = commonSopClasses == null ? Collections.emptyList() : commonSopClasses;
        this.agreedTransferSyntaxes = agreedTransferSyntaxes == null ? Collections.emptyList() : agreedTransferSyntaxes;
        this.agreedTlsProtocols = agreedTlsProtocols == null ? Collections.emptyList() : agreedTlsProtocols;
        this.agreedCipherSuites = agreedCipherSuites == null ? Collections.emptyList() : agreedCipherSuites;
        this.lastErrorCode = lastErrorCode;
        this.lastErrorMessage = lastErrorMessage;
        this.localAet = local != null ? dicomAet(local) : null;
        this.remoteAet = remote != null ? dicomAet(remote) : null;
        this.remoteHost = remote != null ? remote.getHostname() : null;
        this.remotePort = remote != null ? remote.getPort() : 0;
    }

    /** 工厂：成功结果。 */
    public static HandshakeResult success(String peerAet,
                                          Connection local,
                                          Connection remote,
                                          List<String> commonSopClasses,
                                          List<String> agreedTransferSyntaxes,
                                          List<String> agreedTlsProtocols,
                                          List<String> agreedCipherSuites) {
        return new HandshakeResult(true, peerAet,
                commonSopClasses, agreedTransferSyntaxes, agreedTlsProtocols, agreedCipherSuites,
                null, null, local, remote);
    }

    /** 工厂：失败结果。 */
    public static HandshakeResult failure(FailureCode code,
                                          String message,
                                          Connection local,
                                          Connection remote) {
        return new HandshakeResult(false, null,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                code, message, local, remote);
    }

    /** 从 Connection 推导 AE Title — 源 Connection 字段里没有 aet，由 Device/ApplicationEntity 持有；这里用 hostname 占位即可。 */
    private static String dicomAet(Connection c) {
        if (c == null) return null;
        return c.getCommonName() != null ? c.getCommonName() : c.getHostname();
    }

    @Override
    public String toString() {
        return "HandshakeResult{compatible=" + compatible
                + ", peerAet=" + peerAet
                + ", commonSopClasses=" + commonSopClasses
                + ", agreedTransferSyntaxes=" + agreedTransferSyntaxes
                + ", agreedTlsProtocols=" + agreedTlsProtocols
                + ", agreedCipherSuites=" + agreedCipherSuites
                + ", lastErrorCode=" + lastErrorCode
                + ", lastErrorMessage=" + lastErrorMessage
                + ", localAet=" + localAet
                + ", remoteAet=" + remoteAet
                + ", remoteHost=" + remoteHost
                + ", remotePort=" + remotePort
                + '}';
    }

    /** 字符串数组 → 列表（暴露给 controller 序列化）。 */
    public static List<String> asList(String[] arr) {
        if (arr == null) return Collections.emptyList();
        return Arrays.asList(arr);
    }
}
