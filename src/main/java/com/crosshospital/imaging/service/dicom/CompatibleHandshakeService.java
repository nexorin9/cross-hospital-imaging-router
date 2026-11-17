package com.crosshospital.imaging.service.dicom;

import com.crosshospital.imaging.config.DicomConfigProps;
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.exception.HandshakeException;
import com.crosshospital.imaging.exception.HandshakeException.FailureCode;
import com.crosshospital.imaging.repository.PeerRepository;
import org.dcm4che3.net.CompatibleConnection;
import org.dcm4che3.net.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 兼容的 DICOM 握手服务。
 *
 * <p>封装 dcm4che-net {@code Connection} + {@code CompatibleConnection}，执行：
 * <ol>
 *   <li>读 Peer 准入表（AET / host / port / tls_mode / 协商参数）</li>
 *   <li>建本端 + 远端 Connection 对象（{@link DeviceFactory}）</li>
 *   <li>{@code isCompatible()} 预校验（protocol 一致 / TLS 协议交集 / cipher 交集）</li>
 *   <li>{@link TransferSyntaxNegotiator} 取传输语法交集</li>
 *   <li>{@link BlacklistEnforcer} 检查 peer AET 是否在 blacklist_aets 中</li>
 *   <li>{@link SopClassFilter} 求共同 SOP Class 列表</li>
 *   <li>TCP Socket 探活（带超时）→ 拿 {@link HandshakeResult}</li>
 *   <li>由 {@link ImagingConnectionMonitor} 把每次握手结果落审计 + 发 Spring 事件</li>
 * </ol>
 *
 * <p>失败分类统一进 {@link FailureCode}：
 * <ul>
 *   <li>{@code TLS_MISMATCH} / {@code CIPHER_MISMATCH} — 配置不匹配，**不**重试（修配置）</li>
 *   <li>{@code TRANSFER_SYNTAX_EMPTY} — 两端无公共传输语法，**不**重试（修 transfer_syntaxes）</li>
 *   <li>{@code CONNECTION_REFUSED} / {@code CONNECT_TIMEOUT} — 网络瞬态，可重试</li>
 *   <li>{@code BLACKLISTED} — 命中黑名单，**不**重试（人工介入）</li>
 *   <li>{@code PEER_NOT_FOUND} / {@code SOP_CLASS_UNSUPPORTED} / {@code INCOMPATIBLE_CONNECTION} — 配置错误，**不**重试</li>
 * </ul>
 *
 * <p>源主路径证据：
 * <ul>
 *   <li>{@code Connection.java:1034-1091} — {@code connect(remoteConn)} → checkCompatible → Socket</li>
 *   <li>{@code Connection.java:1197-1209} — {@code isCompatible()} — protocol/TLS/cipher 校验</li>
 *   <li>{@code CompatibleConnection.java:44-62} — 本地+远端 Connection 配对容器</li>
 *   <li>{@code Connection.setBlacklistedCallingAETitles} — 黑名单由 {@link BlacklistEnforcer} 复用</li>
 *   <li>{@code ConnectionMonitor.java:47-60} — 五态回调 → 本服务由
 *       {@link ImagingConnectionMonitor} 落审计 + 业务事件</li>
 * </ul>
 */
@Service
@Profile("!test")
public class CompatibleHandshakeService {

    private static final Logger LOG = LoggerFactory.getLogger(CompatibleHandshakeService.class);

    private final PeerRepository peerRepository;
    private final DeviceFactory deviceFactory;
    private final DicomConfigProps dicomProps;
    private final TransferSyntaxNegotiator transferSyntaxNegotiator;
    private final SopClassFilter sopClassFilter;
    private final BlacklistEnforcer blacklistEnforcer;
    /** task 9 注入；nullable 以兼容 task 7/8 的 6 参测试构造。 */
    private final ImagingConnectionMonitor connectionMonitor;

    /** 6 参构造函数（保留以兼容既有单元测试，{@code connectionMonitor=null} 时旁路回调）。 */
    public CompatibleHandshakeService(PeerRepository peerRepository,
                                      DeviceFactory deviceFactory,
                                      DicomConfigProps dicomProps,
                                      TransferSyntaxNegotiator transferSyntaxNegotiator,
                                      SopClassFilter sopClassFilter,
                                      BlacklistEnforcer blacklistEnforcer) {
        this(peerRepository, deviceFactory, dicomProps, transferSyntaxNegotiator,
                sopClassFilter, blacklistEnforcer, null);
    }

    /** 7 参构造函数（生产使用，{@code @Autowired} 显式标记以让 Spring 走此重载）。 */
    @Autowired
    public CompatibleHandshakeService(PeerRepository peerRepository,
                                      DeviceFactory deviceFactory,
                                      DicomConfigProps dicomProps,
                                      TransferSyntaxNegotiator transferSyntaxNegotiator,
                                      SopClassFilter sopClassFilter,
                                      BlacklistEnforcer blacklistEnforcer,
                                      ImagingConnectionMonitor connectionMonitor) {
        this.peerRepository = peerRepository;
        this.deviceFactory = deviceFactory;
        this.dicomProps = dicomProps;
        this.transferSyntaxNegotiator = transferSyntaxNegotiator;
        this.sopClassFilter = sopClassFilter;
        this.blacklistEnforcer = blacklistEnforcer;
        this.connectionMonitor = connectionMonitor;
    }

    /**
     * 对外握手健康检查入口。
     *
     * @param peerAet 外院节点 AE Title
     * @return 握手结果（含共同支持的 SOP Class / 传输语法 / TLS / cipher）
     * @throws HandshakeException 配置型错误（peer 不存在 / 黑白名单命中 / 协议族不兼容）
     */
    public HandshakeResult handshake(String peerAet) {
        if (peerAet == null || peerAet.isBlank()) {
            // 空 AET 不属于任何一个 handshake 状态分支；只走 monitor.onConnectionRejectedBlacklisted
            // 略嫌过度，因此直接抛 HandshakeException 让 monitor 不被叫醒
            throw new HandshakeException(FailureCode.PEER_NOT_FOUND, "peer_aet 不能为空");
        }
        Optional<Peer> peerOpt = peerRepository.findByPeerAet(peerAet);
        if (peerOpt.isEmpty()) {
            throw new HandshakeException(FailureCode.PEER_NOT_FOUND, "Peer 不存在: " + peerAet);
        }
        Peer peer = peerOpt.get();
        return handshakeInternal(peer);
    }

    /** 内部握手（接受 Peer 对象，便于测试与复用）。 */
    public HandshakeResult handshakeInternal(Peer peer) {
        Connection local = deviceFactory.buildLocalConnection();
        Connection remote = deviceFactory.buildRemoteConnection(peer);

        // ===== 1. 协议族 + TLS 兼容性预校验 =====
        if (!local.isCompatible(remote)) {
            // isCompatible 内部已检查 protocol / TLS protocol 交集 / cipher 交集
            HandshakeResult r = classifyIncompatibility(local, remote);
            notifyRejected(local, null, exceptionFor(r.lastErrorCode, r.lastErrorMessage));
            return r;
        }

        // ===== 2. 黑名单 AET 命中校验（应用层，由 BlacklistEnforcer 接管） =====
        List<String> blacklist = DeviceFactory.parseJsonBlacklist(peer.getBlacklistAets());
        try {
            // 注意：黑名单检查在 TS / SOP 之前——命中黑名单时跳过协议协商，避免无效计算
            // 异常 retryable=false，由 BlacklistEnforcer 抛 HandshakeException(BLACKLISTED)
            blacklistEnforcer.enforce(peer.getPeerAet(), blacklist);
        } catch (HandshakeException e) {
            // 由 ImagingConnectionMonitor 落审计 + 发黑名单拒绝事件（不重试）
            notifyBlacklisted(local, peer.getPeerAet());
            throw e;
        }

        // ===== 3. 传输语法协商（由 TransferSyntaxNegotiator 接管） =====
        List<String> peerTsList = DeviceFactory.parseJsonStringArray(peer.getTransferSyntaxes());
        List<String> localTs = new ArrayList<>(dicomProps.getSupportedTransferSyntaxes());
        List<String> agreedTransferSyntaxes = transferSyntaxNegotiator.negotiate(peerTsList, localTs);
        if (!transferSyntaxNegotiator.isNegotiated(agreedTransferSyntaxes)) {
            HandshakeResult r = HandshakeResult.failure(FailureCode.TRANSFER_SYNTAX_EMPTY,
                    "本地与对端传输语法无交集: peer=" + peerTsList + " local=" + localTs,
                    local, remote);
            notifyRejected(local, null, exceptionFor(r.lastErrorCode, r.lastErrorMessage));
            return r;
        }

        // ===== 4. SOP Class 白名单过滤（由 SopClassFilter 接管） =====
        List<String> peerSopList = DeviceFactory.parseJsonSopClasses(peer.getPresentContexts());
        List<String> localSop = new ArrayList<>(dicomProps.getSupportedSopClasses());
        List<String> commonSopClasses = sopClassFilter.filterByPresentContexts(peerSopList, localSop);
        if (!sopClassFilter.hasOverlap(commonSopClasses)) {
            HandshakeResult r = HandshakeResult.failure(FailureCode.SOP_CLASS_UNSUPPORTED,
                    "本地与对端 SOP Class 无交集: peer=" + peerSopList + " local=" + localSop,
                    local, remote);
            notifyRejected(local, null, exceptionFor(r.lastErrorCode, r.lastErrorMessage));
            return r;
        }

        // ===== 5. TLS 协议 / cipher 交集 =====
        List<String> agreedTls = intersect(toList(local.getTlsProtocols()), toList(remote.getTlsProtocols()));
        List<String> agreedCiphers = intersect(toList(local.getTlsCipherSuites()), toList(remote.getTlsCipherSuites()));

        // ===== 6. 实际 TCP Socket 探活（带超时） =====
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(peer.getHost(),
                    peer.getPort() == null ? 11112 : peer.getPort()),
                    dicomProps.getRemoteConnectTimeoutMs());
            // 探活成功 → 立即关闭（仅验证端口可达 + 不阻塞）
            HandshakeResult result = HandshakeResult.success(peer.getPeerAet(), local, remote,
                    commonSopClasses, agreedTransferSyntaxes, agreedTls, agreedCiphers);
            // CompatibleConnection 容器 — 验证通过后释放
            CompatibleConnection compat = new CompatibleConnection(local, remote);
            LOG.debug("Compatible handshake OK for peer={} via {}",
                    peer.getPeerAet(), compat);
            // task 9：握手成功后由 ImagingConnectionMonitor 落审计 + 发 ConnectionEstablishedEvent
            notifyEstablished(local, remote, socket);
            return result;
        } catch (ConnectException e) {
            // 端口关闭或拒接
            HandshakeResult r = HandshakeResult.failure(FailureCode.CONNECTION_REFUSED,
                    "对端拒接: " + peer.getHost() + ":" + peer.getPort(),
                    local, remote);
            notifyRejected(local, null, e);
            return r;
        } catch (SocketTimeoutException e) {
            HandshakeResult r = HandshakeResult.failure(FailureCode.CONNECT_TIMEOUT,
                    "连接超时 (" + dicomProps.getRemoteConnectTimeoutMs() + "ms): "
                            + peer.getHost() + ":" + peer.getPort(),
                    local, remote);
            notifyRejected(local, null, e);
            return r;
        } catch (IOException e) {
            HandshakeResult r = HandshakeResult.failure(FailureCode.CONNECTION_REFUSED,
                    "网络错误: " + e.getMessage(), local, remote);
            notifyRejected(local, null, e);
            return r;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** 把 isCompatible() 失败进一步细分为 TLS / cipher / protocol 错误。 */
    HandshakeResult classifyIncompatibility(Connection local, Connection remote) {
        // protocol 不一致
        if (local.getProtocol() != remote.getProtocol()) {
            return HandshakeResult.failure(FailureCode.INCOMPATIBLE_CONNECTION,
                    "协议族不一致: local=" + local.getProtocol() + " remote=" + remote.getProtocol(),
                    local, remote);
        }
        // TLS 协议不匹配
        if (!hasIntersection(toList(local.getTlsProtocols()), toList(remote.getTlsProtocols()))) {
            return HandshakeResult.failure(FailureCode.TLS_MISMATCH,
                    "TLS 协议无交集: local=" + Arrays.toString(local.getTlsProtocols())
                            + " remote=" + Arrays.toString(remote.getTlsProtocols()),
                    local, remote);
        }
        // cipher 不匹配
        if (!hasIntersection(toList(local.getTlsCipherSuites()), toList(remote.getTlsCipherSuites()))) {
            return HandshakeResult.failure(FailureCode.CIPHER_MISMATCH,
                    "TLS 密码套件无交集: local=" + Arrays.toString(local.getTlsCipherSuites())
                            + " remote=" + Arrays.toString(remote.getTlsCipherSuites()),
                    local, remote);
        }
        // 兜底
        return HandshakeResult.failure(FailureCode.INCOMPATIBLE_CONNECTION,
                "isCompatible() 返回 false 但未识别具体原因", local, remote);
    }

    /** 把分类好的失败码包装成一个异常（仅用于传给 monitor — 不抛回）。 */
    private static HandshakeException exceptionFor(FailureCode code, String message) {
        boolean retryable = code == FailureCode.CONNECTION_REFUSED || code == FailureCode.CONNECT_TIMEOUT;
        return new HandshakeException(code, message, retryable);
    }

    /** task 9 旁路：monitor 为 null 或抛异常时容错。 */
    private void notifyEstablished(Connection local, Connection remote, Socket socket) {
        if (connectionMonitor == null) return;
        try {
            connectionMonitor.onConnectionEstablished(local, remote, socket);
        } catch (Exception ex) {
            LOG.warn("ImagingConnectionMonitor.onConnectionEstablished 失败: {}", ex.getMessage());
        }
    }

    private void notifyRejected(Connection local, Socket socket, Throwable cause) {
        if (connectionMonitor == null) return;
        try {
            connectionMonitor.onConnectionRejected(local, socket, cause);
        } catch (Exception ex) {
            LOG.warn("ImagingConnectionMonitor.onConnectionRejected 失败: {}", ex.getMessage());
        }
    }

    private void notifyBlacklisted(Connection local, String peerAet) {
        if (connectionMonitor == null) return;
        try {
            connectionMonitor.onConnectionRejectedBlacklisted(local, null);
        } catch (Exception ex) {
            LOG.warn("ImagingConnectionMonitor.onConnectionRejectedBlacklisted 失败: {}", ex.getMessage());
        }
    }

    /** 列表交集（保序，去重）。 */
    static List<String> intersect(List<String> a, List<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>(a);
        List<String> out = new ArrayList<>();
        for (String s : b) {
            if (set.contains(s)) out.add(s);
        }
        return out;
    }

    /** 是否存在至少一个公共元素。 */
    static boolean hasIntersection(List<String> a, List<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return a != null && b != null && a.isEmpty() && b.isEmpty();
        }
        for (String s : a) if (b.contains(s)) return true;
        return false;
    }

    /** String[] → List<String>（用于 getTlsProtocols / getTlsCipherSuites）。 */
    private static List<String> toList(String[] arr) {
        if (arr == null || arr.length == 0) return Collections.emptyList();
        List<String> out = new ArrayList<>(arr.length);
        for (String s : arr) out.add(s);
        return out;
    }
}
