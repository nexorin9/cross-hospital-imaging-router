package com.crosshospital.imaging.service.dicom;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.event.ConnectionEstablishedEvent;
import com.crosshospital.imaging.event.ConnectionFailedEvent;
import com.crosshospital.imaging.event.ConnectionRejectedBlacklistedEvent;
import com.crosshospital.imaging.service.AuditService;
import org.dcm4che3.net.Connection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.net.ConnectException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImagingConnectionMonitor 五态回调单元测试。
 *
 * <p>JDK 17+ 上 Mockito inline mockmaker 对 final / Object 子类有限制；
 * 本测试用 {@link StubAuditService} 与 {@link RecordingEventPublisher}
 * 替代 Mockito（{@code AuditService} 是具体类，{@code ApplicationEventPublisher}
 * 是接口，但为了对齐桩设计也改用自写桩，避免一处 mock 一处 stub 风格不一致）。
 *
 * <p><b>核心覆盖</b>：
 * <ol>
 *   <li>5 态回调各调用一次 {@code auditService.recordHandshake}（含 status_terminal + who + failure_code）</li>
 *   <li>5 态回调各发布一次 {@code ApplicationEvent}（事件类型与 status_terminal 一致）</li>
 *   <li>黑白名单回调的 status_terminal 硬编码为 {@code handshake_blacklisted_rejected}</li>
 *   <li>auditService 抛异常时 monitor 不冒泡（不污染 dcm4che 上层）</li>
 *   <li>ApplicationEventPublisher 抛异常时 monitor 不冒泡</li>
 *   <li>对端 Connection / Socket 为 null 时 monitor 不 NPE</li>
 * </ol>
 *
 * <p>不依赖 Spring 上下文；不依赖 PostgreSQL（DB 层验证在 task 35 / task 25）。
 */
public class ImagingConnectionMonitorTest {

    private StubAuditService auditService;
    private RecordingEventPublisher publisher;
    private ImagingConnectionMonitor monitor;

    @BeforeEach
    void setUp() {
        auditService = new StubAuditService();
        publisher = new RecordingEventPublisher();
        monitor = new ImagingConnectionMonitor(auditService, publisher);
    }

    private Connection conn(String commonName, String hostname, int port) {
        Connection c = new Connection();
        c.setCommonName(commonName);
        c.setHostname(hostname);
        c.setPort(port);
        return c;
    }

    private Socket socketStub() {
        return new java.net.Socket(); // dcm4che/monitor 对 socket 字段都做了 null-safe
    }

    /** ① onConnectionEstablished → record(handshake_established) + ConnectionEstablishedEvent */
    @Test
    void onConnectionEstablished_writesAuditAndPublishesEvent() {
        Connection local = conn("LOCAL_PACS", "192.168.1.1", 11112);
        Connection remote = conn("PEER_HZ", "10.0.0.5", 11112);
        Socket s = socketStub();

        monitor.onConnectionEstablished(local, remote, s);

        assertEquals(1, auditService.calls.size(), "expected one recordHandshake call");
        StubAuditService.Call c = auditService.calls.get(0);
        assertEquals("PEER_HZ", c.peerAet);
        assertEquals(ImagingConnectionMonitor.STATUS_ESTABLISHED, c.statusTerminal);
        assertEquals("system", c.who);
        assertEquals(null, c.failureCode);

        ConnectionEstablishedEvent ee = publisher.findFirst(ConnectionEstablishedEvent.class);
        assertNotNull(ee, "expected ConnectionEstablishedEvent");
        assertEquals("PEER_HZ", ee.getPeerAet());
        assertEquals("LOCAL_PACS", ee.getLocalAet());
        assertEquals("10.0.0.5", ee.getRemoteHost());
        assertEquals(Integer.valueOf(11112), ee.getRemotePort());
        assertEquals(ImagingConnectionMonitor.STATUS_ESTABLISHED, ee.getStatusTerminal());
    }

    /** ② onConnectionAccepted → record(handshake_accepted) + ConnectionEstablishedEvent(accepted) */
    @Test
    void onConnectionAccepted_writesAuditAndPublishesEstablishedEvent() {
        Connection local = conn("LOCAL_PACS", "192.168.1.1", 11112);
        Socket s = socketStub();

        monitor.onConnectionAccepted(local, s);

        assertEquals(1, auditService.calls.size());
        StubAuditService.Call c0 = auditService.calls.get(0);
        assertEquals("LOCAL_PACS", c0.peerAet);
        assertEquals(ImagingConnectionMonitor.STATUS_ACCEPTED, c0.statusTerminal);
        assertEquals("system", c0.who);
        assertEquals(null, c0.failureCode);

        ConnectionEstablishedEvent ee = publisher.findFirst(ConnectionEstablishedEvent.class);
        assertNotNull(ee);
        assertEquals(ImagingConnectionMonitor.STATUS_ACCEPTED, ee.getStatusTerminal());
    }

    /** ③ onConnectionFailed → record(handshake_failed, e.class) + ConnectionFailedEvent */
    @Test
    void onConnectionFailed_writesAuditWithExceptionClassAsFailureCode() {
        Connection local = conn("LOCAL_PACS", "192.168.1.1", 11112);
        Connection remote = conn("PEER_HZ", "10.0.0.5", 11112);
        Throwable cause = new ConnectException("Connection refused: /10.0.0.5:11112");

        monitor.onConnectionFailed(local, remote, null, cause);

        assertEquals(1, auditService.calls.size());
        StubAuditService.Call c0 = auditService.calls.get(0);
        assertEquals("PEER_HZ", c0.peerAet);
        assertEquals(ImagingConnectionMonitor.STATUS_FAILED, c0.statusTerminal);
        assertEquals("system", c0.who);
        assertEquals("ConnectException", c0.failureCode);

        ConnectionFailedEvent fe = publisher.findFirst(ConnectionFailedEvent.class);
        assertNotNull(fe);
        assertEquals("PEER_HZ", fe.getPeerAet());
        assertEquals("ConnectException", fe.getFailureCode());
        assertEquals("Connection refused: /10.0.0.5:11112", fe.getErrorMessage());
        assertEquals(ImagingConnectionMonitor.STATUS_FAILED, fe.getStatusTerminal());
    }

    /** ④ onConnectionRejected → record(handshake_rejected, e.class) + ConnectionFailedEvent */
    @Test
    void onConnectionRejected_writesAuditAndPublishesFailedEvent() {
        Connection local = conn("LOCAL_PACS", "192.168.1.1", 11112);
        Throwable cause = new RuntimeException("TLS negotiation failed");

        monitor.onConnectionRejected(local, null, cause);

        assertEquals(1, auditService.calls.size());
        StubAuditService.Call c0 = auditService.calls.get(0);
        assertEquals("LOCAL_PACS", c0.peerAet);
        assertEquals(ImagingConnectionMonitor.STATUS_REJECTED, c0.statusTerminal);
        assertEquals("system", c0.who);
        assertEquals("RuntimeException", c0.failureCode);

        ConnectionFailedEvent fe = publisher.findFirst(ConnectionFailedEvent.class);
        assertNotNull(fe);
        assertEquals(ImagingConnectionMonitor.STATUS_REJECTED, fe.getStatusTerminal());
    }

    /** ⑤ onConnectionRejectedBlacklisted → record(handshake_blacklisted_rejected, BLACKLISTED) + 专属事件 */
    @Test
    void onConnectionRejectedBlacklisted_hardcodesBlacklistAuditTerminal() {
        Connection local = conn("LOCAL_PACS", "192.168.1.1", 11112);
        Socket s = socketStub();

        monitor.onConnectionRejectedBlacklisted(local, s);

        assertEquals(1, auditService.calls.size());
        StubAuditService.Call c0 = auditService.calls.get(0);
        assertEquals("LOCAL_PACS", c0.peerAet);
        assertEquals(ImagingConnectionMonitor.STATUS_BLACKLISTED, c0.statusTerminal);
        assertEquals("system", c0.who);
        assertEquals("BLACKLISTED", c0.failureCode);

        ConnectionRejectedBlacklistedEvent be = publisher.findFirst(ConnectionRejectedBlacklistedEvent.class);
        assertNotNull(be);
        assertEquals("LOCAL_PACS", be.getPeerAet());
        assertNotNull(be.getReason());
        assertTrue(be.getReason().contains("黑名单"));
    }

    /** 边界：auditService 抛异常时 monitor 不冒泡（避免污染 dcm4che 上层） */
    @Test
    void monitorSwallowsExceptionsFromAuditServiceAndPublisher() {
        auditService.throwOnRecord = new RuntimeException("audit db down");
        publisher.throwOnPublish = new RuntimeException("publisher broken");

        Connection local = conn("LOCAL_PACS", "192.168.1.1", 11112);
        Connection remote = conn("PEER_HZ", "10.0.0.5", 11112);
        monitor.onConnectionEstablished(local, remote, null); // 期望不抛
        monitor.onConnectionFailed(local, remote, null, new ConnectException("x"));
        monitor.onConnectionRejected(local, null, new RuntimeException("y"));
        monitor.onConnectionRejectedBlacklisted(local, null);
        monitor.onConnectionAccepted(local, null);
        // 5 次 audit 都"尝试"调过（即便异常）
        assertEquals(5, auditService.calls.size());
    }

    /** 边界：null Connection / null Socket 不 NPE */
    @Test
    void monitorDoesNotNpeOnNullConnectionOrSocket() {
        monitor.onConnectionEstablished(null, null, null);
        monitor.onConnectionFailed(null, null, null, null);
        monitor.onConnectionRejected(null, null, null);
        monitor.onConnectionAccepted(null, null);
        assertEquals(4, auditService.calls.size());
        assertEquals(4, publisher.events.size());
    }

    /** 边界：onConnectionRejectedBlacklisted 对端 null 时不 NPE */
    @Test
    void onConnectionRejectedBlacklistedDoesNotNpeOnNullArgs() {
        monitor.onConnectionRejectedBlacklisted(null, null);
        assertEquals(1, auditService.calls.size());
        StubAuditService.Call c0 = auditService.calls.get(0);
        assertEquals("unknown", c0.peerAet); // safeGetCommonName fallback
        assertEquals(ImagingConnectionMonitor.STATUS_BLACKLISTED, c0.statusTerminal);
    }

    /** 边界：onConnectionEstablished 的远端 Connection 为 null 时只取 local 名 + 不 NPE */
    @Test
    void onConnectionEstablishedWithNullRemoteFallsBackToLocalName() {
        Connection local = conn("LOCAL_PACS", "192.168.1.1", 11112);
        monitor.onConnectionEstablished(local, null, null);
        assertEquals(1, auditService.calls.size());
        StubAuditService.Call c0 = auditService.calls.get(0);
        assertEquals("LOCAL_PACS", c0.peerAet);
        assertEquals(ImagingConnectionMonitor.STATUS_ESTABLISHED, c0.statusTerminal);
    }

    /** ---- 内部桩 ---- */

    /** AuditService 的非 Mockito 测试桩：覆盖 record 与 recordHandshake，记录每次调用。 */
    static class StubAuditService extends AuditService {
        final List<Call> calls = new ArrayList<>();
        RuntimeException throwOnRecord;

        StubAuditService() {
            super(null);
        }

        @Override
        public AuditRow record(AuditRow row) {
            if (throwOnRecord != null) {
                throw throwOnRecord;
            }
            return row;
        }

        @Override
        public AuditRow recordHandshake(String peerAet, String statusTerminal,
                                         String who, String failureCode) {
            calls.add(new Call(peerAet, statusTerminal, who, failureCode));
            if (throwOnRecord != null) {
                throw throwOnRecord;
            }
            // 跳过 super.recordHandshake 避免 NPE on null repo；返回一个空 AuditRow
            return new AuditRow();
        }

        static class Call {
            final String peerAet;
            final String statusTerminal;
            final String who;
            final String failureCode;

            Call(String peerAet, String statusTerminal, String who, String failureCode) {
                this.peerAet = peerAet;
                this.statusTerminal = statusTerminal;
                this.who = who;
                this.failureCode = failureCode;
            }
        }
    }

    /** ApplicationEventPublisher 测试桩：记录所有发布事件。 */
    static class RecordingEventPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();
        RuntimeException throwOnPublish;

        @Override
        public void publishEvent(ApplicationEvent event) {
            if (throwOnPublish != null) {
                throw throwOnPublish;
            }
            events.add(event);
        }

        @Override
        public void publishEvent(Object event) {
            if (throwOnPublish != null) {
                throw throwOnPublish;
            }
            events.add(event);
        }

        @SuppressWarnings("unchecked")
        <T> T findFirst(Class<T> type) {
            for (Object e : events) {
                if (type.isInstance(e)) return (T) e;
            }
            return null;
        }
    }
}
