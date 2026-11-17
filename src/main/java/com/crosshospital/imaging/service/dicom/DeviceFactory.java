package com.crosshospital.imaging.service.dicom;

import com.crosshospital.imaging.config.DicomConfigProps;
import com.crosshospital.imaging.domain.Peer;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 工厂：构建 dcm4che {@link Device} + {@link Connection} 对象。
 *
 * <p>对应源仓库 dcm4che-net：
 * <ul>
 *   <li>{@code Connection.java} — TCP/协议/TLS/cipher 配置</li>
 *   <li>{@code Device.java} — 持有 Connection + ApplicationEntity 列表</li>
 *   <li>{@code CompatibleConnection} — 把本端 + 远端 Connection 配对用于 isCompatible 预校验</li>
 * </ul>
 *
 * <p>本工厂只做**配置层**装配；实际 {@code Connection.connect()} 由
 * {@link CompatibleHandshakeService} 调用。
 */
@Component
@Profile("!test")
public class DeviceFactory {

    private final DicomConfigProps props;

    public DeviceFactory(DicomConfigProps props) {
        this.props = props;
    }

    /** 构建本端 Connection（客户端视角，不监听）。 */
    public Connection buildLocalConnection() {
        Connection local = new Connection();
        local.setHostname(props.getLocalHost());
        local.setPort(props.getBindPort()); // -1 = 不监听
        local.setProtocol(Connection.Protocol.DICOM);
        local.setConnectTimeout(props.getRemoteConnectTimeoutMs());
        local.setMaxOpsInvoked(props.getMaxOpsInvoked());
        local.setMaxOpsPerformed(props.getMaxOpsPerformed());
        // TLS 配置：仅在 cipherSuites 非空时启用
        if (props.getTlsCipherSuites() != null && !props.getTlsCipherSuites().isEmpty()) {
            local.setTlsCipherSuites(props.getTlsCipherSuites().toArray(new String[0]));
            local.setTlsProtocols(props.getTlsProtocols().toArray(new String[0]));
        }
        return local;
    }

    /**
     * 构建远端 Connection：从 {@link Peer} 准入表读取。
     *
     * <p>注意：远端 Connection 的 {@code tlsCipherSuites} 字段在源仓库语义里既描述"本端接受的对端 cipher"
     * 也描述"对端声明的 cipher" — 调用方决定是否启用 TLS。本实现沿用源约定：
     * peer.tls_mode == "start_tls" 时设置 cipher；否则保持空数组（isTls()=false）。
     */
    public Connection buildRemoteConnection(Peer peer) {
        Connection remote = new Connection();
        remote.setHostname(peer.getHost());
        remote.setPort(peer.getPort() == null ? 11112 : peer.getPort());
        remote.setProtocol(Connection.Protocol.DICOM);
        remote.setConnectTimeout(props.getRemoteConnectTimeoutMs());
        if ("start_tls".equalsIgnoreCase(peer.getTlsMode())) {
            remote.setTlsCipherSuites(props.getTlsCipherSuites().toArray(new String[0]));
            remote.setTlsProtocols(props.getTlsProtocols().toArray(new String[0]));
        }
        return remote;
    }

    /**
     * 把 Device 与本端 Connection 绑定（用于 Connection.connect 时拿到 ConnectionMonitor）。
     *
     * <p>源约定：{@code Connection.connect(remoteConn)} 会调 {@code device.getConnectionMonitor()}；
     * 这里建一个匿名 Device 仅持有本端 Connection，不安装（不监听）。
     */
    public Device bindLocalDevice(Connection local) {
        Device device = new Device("cross-hospital-router");
        device.addConnection(local);
        return device;
    }

    /** 解析 peer.transfer_syntaxes（JSONB 字符串）为 List<String>。 */
    public static List<String> parseJsonStringArray(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return new ArrayList<>();
        }
        try {
            // 简单解析：去掉 [ ] 与 "，按 , 切
            String trimmed = json.trim();
            if (trimmed.startsWith("[")) trimmed = trimmed.substring(1);
            if (trimmed.endsWith("]")) trimmed = trimmed.substring(0, trimmed.length() - 1);
            List<String> out = new ArrayList<>();
            for (String s : trimmed.split(",")) {
                String t = s.trim();
                if (t.startsWith("\"")) t = t.substring(1);
                if (t.endsWith("\"")) t = t.substring(0, t.length() - 1);
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 解析 peer.present_contexts（JSONB 字符串）— SOP Class UID 列表。 */
    public static List<String> parseJsonSopClasses(String json) {
        return parseJsonStringArray(json);
    }

    /** 解析 peer.blacklist_aets。 */
    public static List<String> parseJsonBlacklist(String json) {
        return parseJsonStringArray(json);
    }
}
