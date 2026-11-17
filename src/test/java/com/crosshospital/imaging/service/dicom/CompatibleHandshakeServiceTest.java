package com.crosshospital.imaging.service.dicom;

import com.crosshospital.imaging.config.DicomConfigProps;
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.exception.HandshakeException;
import com.crosshospital.imaging.repository.PeerRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CompatibleHandshakeService 单元测试。
 *
 * <p>核心覆盖：
 * <ol>
 *   <li>peer_aet 不存在 → PEER_NOT_FOUND</li>
 *   <li>远端端口拒接（无 listener）→ CONNECTION_REFUSED</li>
 *   <li>传输语法无交集 → TRANSFER_SYNTAX_EMPTY</li>
 *   <li>SOP Class 无交集 → SOP_CLASS_UNSUPPORTED</li>
 *   <li>TLS cipher 配错 → CIPHER_MISMATCH（通过 peer.tls_mode=start_tls + 空 cipher 触发）</li>
 *   <li>本地起 ServerSocket → 探活成功 → compatible=true</li>
 *   <li>intersect / hasIntersection 静态方法的边界（空集合、单元素）</li>
 * </ol>
 */
public class CompatibleHandshakeServiceTest {

    private static ServerSocket liveServer;
    private static int liveServerPort;
    private PeerRepository peerRepository;
    private DeviceFactory deviceFactory;
    private DicomConfigProps dicomProps;
    private TransferSyntaxNegotiator transferSyntaxNegotiator;
    private SopClassFilter sopClassFilter;
    private BlacklistEnforcer blacklistEnforcer;
    private CompatibleHandshakeService service;

    @BeforeAll
    static void startLiveServer() throws IOException {
        // 真实监听一个端口供探活成功路径使用；绑定 loopback 避免对外暴露
        liveServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        liveServerPort = liveServer.getLocalPort();
        // 单次 accept 即可 — 握手流程只做 TCP connect 后立即 close
        Thread t = new Thread(() -> {
            try {
                while (!liveServer.isClosed()) {
                    java.net.Socket s = liveServer.accept();
                    s.close();
                }
            } catch (IOException ignored) { }
        }, "handshake-test-live-server");
        t.setDaemon(true);
        t.start();
    }

    @AfterAll
    static void stopLiveServer() throws IOException {
        if (liveServer != null) liveServer.close();
    }

    @BeforeEach
    void setUp() {
        peerRepository = mock(PeerRepository.class);
        dicomProps = new DicomConfigProps();
        // 缩短超时，加快拒接 / 超时用例
        dicomProps.setRemoteConnectTimeoutMs(500);
        deviceFactory = new DeviceFactory(dicomProps);
        // 三个新拆出的协商 / 过滤 / 黑名单执行器 — Task 8 注入
        transferSyntaxNegotiator = new TransferSyntaxNegotiator();
        sopClassFilter = new SopClassFilter();
        blacklistEnforcer = new BlacklistEnforcer();
        service = new CompatibleHandshakeService(peerRepository, deviceFactory, dicomProps,
                transferSyntaxNegotiator, sopClassFilter, blacklistEnforcer);
    }

    private Peer peer(String aet, String host, int port, String tlsMode,
                      String sopClasses, String transferSyntaxes, String blacklist) {
        Peer p = new Peer();
        p.setPeerAet(aet);
        p.setHost(host);
        p.setPort(port);
        p.setTlsMode(tlsMode == null ? "none" : tlsMode);
        p.setPresentContexts(sopClasses == null ? "[]" : sopClasses);
        p.setTransferSyntaxes(transferSyntaxes == null ? "[]" : transferSyntaxes);
        p.setBlacklistAets(blacklist == null ? "[]" : blacklist);
        p.setEnabled(true);
        return p;
    }

    /** ① peer_aet 不存在 → PEER_NOT_FOUND（运行时异常） */
    @Test
    void peerNotFoundThrowsHandshakeException() {
        when(peerRepository.findByPeerAet(eq("GHOST"))).thenReturn(Optional.empty());
        HandshakeException ex = assertThrows(HandshakeException.class, () -> service.handshake("GHOST"));
        assertEquals(HandshakeException.FailureCode.PEER_NOT_FOUND, ex.getFailureCode());
    }

    /** ② 空 AET → PEER_NOT_FOUND */
    @Test
    void emptyPeerAetIsRejected() {
        assertThrows(HandshakeException.class, () -> service.handshake(""));
        assertThrows(HandshakeException.class, () -> service.handshake(null));
    }

    /** ③ 远端端口无 listener → CONNECTION_REFUSED */
    @Test
    void connectionRefusedWhenNoListener() {
        // 找一个空闲端口（即使 serverSocket 已关闭，端口可能短暂占用 — 接受 connect_refused 或 timeout 都算拒接）
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        when(peerRepository.findByPeerAet(eq("REFUSED")))
                .thenReturn(Optional.of(peer("REFUSED", "127.0.0.1", freePort, "none",
                        "[\"1.2.840.10008.5.1.4.1.1.2\"]",
                        "[\"1.2.840.10008.1.2.1\"]", "[]")));
        HandshakeResult r = service.handshake("REFUSED");
        assertFalse(r.compatible);
        assertEquals(HandshakeException.FailureCode.CONNECTION_REFUSED, r.lastErrorCode);
        assertNotNull(r.lastErrorMessage);
        assertTrue(r.lastErrorMessage.contains("127.0.0.1"));
    }

    /** ④ 传输语法无交集 → TRANSFER_SYNTAX_EMPTY（在探活之前失败） */
    @Test
    void transferSyntaxEmpty() {
        when(peerRepository.findByPeerAet(eq("TS_MISMATCH")))
                .thenReturn(Optional.of(peer("TS_MISMATCH", "127.0.0.1", liveServerPort, "none",
                        "[\"1.2.840.10008.5.1.4.1.1.2\"]",
                        "[\"1.2.999.999.999\"]", "[]")));
        HandshakeResult r = service.handshake("TS_MISMATCH");
        assertFalse(r.compatible);
        assertEquals(HandshakeException.FailureCode.TRANSFER_SYNTAX_EMPTY, r.lastErrorCode);
        assertTrue(r.lastErrorMessage.contains("无交集"));
    }

    /** ⑤ SOP Class 无交集 → SOP_CLASS_UNSUPPORTED */
    @Test
    void sopClassUnsupported() {
        when(peerRepository.findByPeerAet(eq("SOP_MISMATCH")))
                .thenReturn(Optional.of(peer("SOP_MISMATCH", "127.0.0.1", liveServerPort, "none",
                        "[\"1.999.999.999.1\"]",
                        "[\"1.2.840.10008.1.2.1\"]", "[]")));
        HandshakeResult r = service.handshake("SOP_MISMATCH");
        assertFalse(r.compatible);
        assertEquals(HandshakeException.FailureCode.SOP_CLASS_UNSUPPORTED, r.lastErrorCode);
        assertTrue(r.lastErrorMessage.contains("SOP Class"));
    }

    /** ⑥ TLS cipher 配错 → peer.tls_mode=start_tls + 本地 cipher 非空 + 远程 cipher 沿用同一集合
     *  — 但本地 cipherSuites = ["X"] 远程 cipherSuites = ["X"] 时仍兼容，因此需要：本地配置一组 cipher，
     * 让远端 Connection 在 buildRemoteConnection 时复用本地 cipher（默认就是）；本用例改用「本地 cipherSuites 与对端
     * cipherSuites 完全不同」的方式触发 — 通过 dicomProps.tlsCipherSuites 设置一个 cipher 让远端 Connection
     * 也带上同一组；当两端 cipher 一致时不应触发 CIPHER_MISMATCH。这是预期通过路径；CIPHER_MISMATCH
     * 用单独的单元覆盖在 {@link #cipherMismatchDirectUnit()}。 */
    @Test
    void handshakeWithCipherAgreementSucceeds() {
        dicomProps.setTlsCipherSuites(java.util.List.of("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"));
        dicomProps.setTlsProtocols(java.util.List.of("TLSv1.2"));
        // 重置 service 以应用 cipher
        DeviceFactory df2 = new DeviceFactory(dicomProps);
        CompatibleHandshakeService svc = new CompatibleHandshakeService(peerRepository, df2, dicomProps,
                transferSyntaxNegotiator, sopClassFilter, blacklistEnforcer);
        when(peerRepository.findByPeerAet(eq("TLS_OK")))
                .thenReturn(Optional.of(peer("TLS_OK", "127.0.0.1", liveServerPort, "start_tls",
                        "[\"1.2.840.10008.5.1.4.1.1.2\"]",
                        "[\"1.2.840.10008.1.2.1\"]", "[]")));
        HandshakeResult r = svc.handshake("TLS_OK");
        assertTrue(r.compatible, "Expected TLS_OK to succeed; got " + r.lastErrorCode + " " + r.lastErrorMessage);
        assertNotNull(r.agreedTlsProtocols);
        assertTrue(r.agreedTlsProtocols.contains("TLSv1.2"));
        assertTrue(r.agreedCipherSuites.contains("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"));
    }

    /** ⑥b  直接单元覆盖 classifyIncompatibility — 用手工构造的 Connection 对象验证 TLS 协议 / cipher 不匹配 */
    @Test
    void classifyIncompatibilityDistinguishesTlsMismatchAndCipherMismatch() {
        // 本地：cipher 非空（标记 isTls=true），TLS 协议=TLSv1.2
        org.dcm4che3.net.Connection local = new org.dcm4che3.net.Connection();
        local.setProtocol(org.dcm4che3.net.Connection.Protocol.DICOM);
        local.setTlsCipherSuites("CIPHER_A");
        local.setTlsProtocols("TLSv1.2");

        // 远端 1：协议族相同但 TLS 协议不匹配（TLSv1.3 vs TLSv1.2）
        org.dcm4che3.net.Connection remoteProtoMismatch = new org.dcm4che3.net.Connection();
        remoteProtoMismatch.setProtocol(org.dcm4che3.net.Connection.Protocol.DICOM);
        remoteProtoMismatch.setTlsCipherSuites("CIPHER_A");
        remoteProtoMismatch.setTlsProtocols("TLSv1.3");

        HandshakeResult r1 = service.classifyIncompatibility(local, remoteProtoMismatch);
        assertFalse(r1.compatible);
        assertEquals(HandshakeException.FailureCode.TLS_MISMATCH, r1.lastErrorCode);
        assertTrue(r1.lastErrorMessage.contains("TLS 协议"));

        // 远端 2：协议族相同 + TLS 协议一致但 cipher 不匹配
        org.dcm4che3.net.Connection remoteCipherMismatch = new org.dcm4che3.net.Connection();
        remoteCipherMismatch.setProtocol(org.dcm4che3.net.Connection.Protocol.DICOM);
        remoteCipherMismatch.setTlsCipherSuites("CIPHER_B");
        remoteCipherMismatch.setTlsProtocols("TLSv1.2");

        HandshakeResult r2 = service.classifyIncompatibility(local, remoteCipherMismatch);
        assertFalse(r2.compatible);
        assertEquals(HandshakeException.FailureCode.CIPHER_MISMATCH, r2.lastErrorCode);
        assertTrue(r2.lastErrorMessage.contains("密码套件"));

        // 远端 3：协议族不同
        org.dcm4che3.net.Connection remoteProtocolMismatch = new org.dcm4che3.net.Connection();
        remoteProtocolMismatch.setProtocol(org.dcm4che3.net.Connection.Protocol.HL7);
        remoteProtocolMismatch.setTlsCipherSuites("CIPHER_A");
        remoteProtocolMismatch.setTlsProtocols("TLSv1.2");

        HandshakeResult r3 = service.classifyIncompatibility(local, remoteProtocolMismatch);
        assertFalse(r3.compatible);
        assertEquals(HandshakeException.FailureCode.INCOMPATIBLE_CONNECTION, r3.lastErrorCode);
        assertTrue(r3.lastErrorMessage.contains("协议族"));
    }

    /** ⑦ 探活成功 → compatible=true + 共同 SOP / TS 列表非空 */
    @Test
    void handshakeSuccessAgainstLoopbackListener() {
        when(peerRepository.findByPeerAet(eq("LIVE")))
                .thenReturn(Optional.of(peer("LIVE", "127.0.0.1", liveServerPort, "none",
                        "[\"1.2.840.10008.5.1.4.1.1.2\",\"1.2.840.10008.5.1.4.1.1.4\"]",
                        "[\"1.2.840.10008.1.2.1\",\"1.2.840.10008.1.2\"]", "[]")));
        HandshakeResult r = service.handshake("LIVE");
        assertTrue(r.compatible, "Expected LIVE to succeed; got " + r.lastErrorCode + " " + r.lastErrorMessage);
        assertNull(r.lastErrorCode);
        assertTrue(r.commonSopClasses.contains("1.2.840.10008.5.1.4.1.1.2"));
        assertTrue(r.commonSopClasses.contains("1.2.840.10008.5.1.4.1.1.4"));
        assertTrue(r.agreedTransferSyntaxes.contains("1.2.840.10008.1.2.1"));
        assertTrue(r.agreedTransferSyntaxes.contains("1.2.840.10008.1.2"));
        assertEquals("LIVE", r.peerAet);
    }

    /** ⑧ 静态方法 intersect — 边界 */
    @Test
    void intersectStaticMethodEdges() {
        assertTrue(CompatibleHandshakeService.intersect(java.util.List.of(), java.util.List.of("a")).isEmpty());
        assertTrue(CompatibleHandshakeService.intersect(null, java.util.List.of("a")).isEmpty());
        assertTrue(CompatibleHandshakeService.intersect(java.util.List.of("a"), java.util.List.of("b")).isEmpty());
        assertEquals(java.util.List.of("b", "c"),
                CompatibleHandshakeService.intersect(java.util.List.of("a", "b", "c"), java.util.List.of("b", "c", "d")));
        // hasIntersection 空集 = true（语义：两端都没声明视为无冲突）
        assertTrue(CompatibleHandshakeService.hasIntersection(java.util.List.of(), java.util.List.of()));
        assertFalse(CompatibleHandshakeService.hasIntersection(java.util.List.of("a"), java.util.List.of()));
        assertFalse(CompatibleHandshakeService.hasIntersection(java.util.List.of(), java.util.List.of("a")));
        assertTrue(CompatibleHandshakeService.hasIntersection(java.util.List.of("a"), java.util.List.of("a")));
    }

    /** ⑨ 黑白名单 AET 命中 → BLACKLISTED 异常 */
    @Test
    void blacklistHitThrowsHandshakeException() {
        when(peerRepository.findByPeerAet(eq("BLACKLISTED")))
                .thenReturn(Optional.of(peer("BLACKLISTED", "127.0.0.1", liveServerPort, "none",
                        "[\"1.2.840.10008.5.1.4.1.1.2\"]",
                        "[\"1.2.840.10008.1.2.1\"]",
                        "[\"BLACKLISTED\"]")));
        HandshakeException ex = assertThrows(HandshakeException.class, () -> service.handshake("BLACKLISTED"));
        assertEquals(HandshakeException.FailureCode.BLACKLISTED, ex.getFailureCode());
    }

    /** ⑩ DeviceFactory.parseJsonStringArray — JSONB 解析边界 */
    @Test
    void jsonStringArrayParserEdges() {
        assertTrue(DeviceFactory.parseJsonStringArray(null).isEmpty());
        assertTrue(DeviceFactory.parseJsonStringArray("").isEmpty());
        assertTrue(DeviceFactory.parseJsonStringArray("[]").isEmpty());
        assertTrue(DeviceFactory.parseJsonStringArray("[\"\"]").isEmpty()); // 空字符串被过滤
        assertEquals(java.util.List.of("a", "b"),
                DeviceFactory.parseJsonStringArray("[\"a\",\"b\"]"));
        assertEquals(java.util.List.of("a"),
                DeviceFactory.parseJsonStringArray("[\"a\"]"));
    }

    /** ⑪ HandshakeResult.failure / success 工厂 */
    @Test
    void handshakeResultFactoriesExposeFields() {
        org.dcm4che3.net.Connection local = new org.dcm4che3.net.Connection();
        local.setHostname("local");
        local.setPort(-1);
        org.dcm4che3.net.Connection remote = new org.dcm4che3.net.Connection();
        remote.setHostname("remote.example");
        remote.setPort(11112);

        HandshakeResult ok = HandshakeResult.success("OK", local, remote,
                java.util.List.of("1.2.3"), java.util.List.of("1.2.1"),
                java.util.List.of("TLSv1.2"), java.util.List.of("CIPHER_A"));
        assertTrue(ok.compatible);
        assertEquals("OK", ok.peerAet);
        assertEquals("remote.example", ok.remoteHost);
        assertEquals(11112, ok.remotePort);

        HandshakeResult fail = HandshakeResult.failure(
                HandshakeException.FailureCode.TLS_MISMATCH, "no tls", local, remote);
        assertFalse(fail.compatible);
        assertEquals(HandshakeException.FailureCode.TLS_MISMATCH, fail.lastErrorCode);
        assertEquals("no tls", fail.lastErrorMessage);
    }
}
