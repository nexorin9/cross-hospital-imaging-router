package com.crosshospital.imaging.service.dicom;

import com.crosshospital.imaging.event.ConnectionEstablishedEvent;
import com.crosshospital.imaging.event.ConnectionFailedEvent;
import com.crosshospital.imaging.event.ConnectionRejectedBlacklistedEvent;
import com.crosshospital.imaging.service.AuditService;
import org.dcm4che3.net.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.Socket;

/**
 * 跨院影像路由的 {@link org.dcm4che3.net.ConnectionMonitor} 实现。
 *
 * <p><b>源主路径</b>：dcm4che-net 的 {@code ConnectionMonitor} 接口
 * （{@code ConnectionMonitor.java:47-60}）定义了 DICOM upper-layer 五态回调：
 * <ul>
 *   <li>{@code onConnectionEstablished} — TCP 建连 + 协议族/TLS 协商通过</li>
 *   <li>{@code onConnectionAccepted} — 本端作为 server 接受对端连接</li>
 *   <li>{@code onConnectionFailed} — TCP 失败 / 超时 / 协议层异常</li>
 *   <li>{@code onConnectionRejected} — 协议层 / TLS 拒绝（不是黑名单）</li>
 *   <li>{@code onConnectionRejectedBlacklisted} — 黑名单命中拒绝</li>
 * </ul>
 *
 * <p><b>融合后医院产品行为</b>：源项目 ConnectionMonitor 通常仅做日志；
 * 本服务把它升级到：
 * <ol>
 *   <li>每次回调落 {@link AuditRow}（append-only 审计行 + 链式 checksum）</li>
 *   <li>发布 Spring ApplicationEvent 给路由服务 / 告警 webhook / 仪表盘订阅</li>
 *   <li>每态写明 {@code status_terminal}，便于运营侧按状态聚合查询</li>
 * </ol>
 *
 * <p><b>5 态 → 3 事件 + 1 失败事件的映射</b>：
 * <ul>
 *   <li>onConnectionEstablished → ConnectionEstablishedEvent + audit(handshake_established)</li>
 *   <li>onConnectionAccepted → ConnectionEstablishedEvent(status=handshake_accepted)</li>
 *   <li>onConnectionFailed → ConnectionFailedEvent + audit(handshake_failed, e.class)</li>
 *   <li>onConnectionRejected → ConnectionFailedEvent(status=handshake_rejected)</li>
 *   <li>onConnectionRejectedBlacklisted → ConnectionRejectedBlacklistedEvent +
 *       audit(handshake_blacklisted_rejected)</li>
 * </ul>
 *
 * <p><b>责任边界</b>：本 monitor 只发审计 + 事件；<b>不</b>推进 FetchTask 状态机，
 * <b>不</b>推进路由主路径。这两件事由 {@code RoutingFailureHandler} (task 16) 与
 * {@code FetchTaskService} (task 20) 接管，避免回调里悄悄改业务状态。
 *
 * <p><b>异常隔离</b>：{@link AuditService#record} 与 {@code publishEvent} 任一
 * 抛异常都不应冒泡到 dcm4che 上层（否则会污染 Association lifecycle），所以本类
 * 的所有回调都包了 try/catch + 失败仅记日志。
 */
@Component
@Profile("!test")
public class ImagingConnectionMonitor implements org.dcm4che3.net.ConnectionMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(ImagingConnectionMonitor.class);

    public static final String STATUS_ESTABLISHED = "handshake_established";
    public static final String STATUS_ACCEPTED = "handshake_accepted";
    public static final String STATUS_FAILED = "handshake_failed";
    public static final String STATUS_REJECTED = "handshake_rejected";
    public static final String STATUS_BLACKLISTED = "handshake_blacklisted_rejected";

    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public ImagingConnectionMonitor(AuditService auditService,
                                     ApplicationEventPublisher eventPublisher) {
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void onConnectionEstablished(Connection local, Connection remoteConn, Socket s) {
        String peerAet = remoteConn != null ? safeGetCommonName(remoteConn) : safeGetCommonName(local);
        safeRecordAndPublish(() -> {
            LOG.info("DICOM connection established: peer={} socket={}",
                    peerAet, s == null ? "n/a" : s.getRemoteSocketAddress());
            auditService.recordHandshake(peerAet, STATUS_ESTABLISHED, "system", null);
            eventPublisher.publishEvent(new ConnectionEstablishedEvent(this,
                    peerAet, safeGetCommonName(local),
                    remoteConn != null ? safeGetHostname(remoteConn) : null,
                    remoteConn != null ? safeGetPort(remoteConn) : null,
                    STATUS_ESTABLISHED));
        }, "onConnectionEstablished");
    }

    @Override
    public void onConnectionAccepted(Connection conn, Socket s) {
        String aet = safeGetCommonName(conn);
        // 先把 socket 上的派生字段全部解析成 final 局部变量；避免在 publishEvent
        // 实参里对 unconnected Socket 调 .getHostAddress() 触发 NPE
        final String remoteHost = safeGetHostAddress(s);
        final Integer remotePort = safeGetPortFromSocket(s);
        safeRecordAndPublish(() -> {
            LOG.info("DICOM connection accepted: local={} socket={}",
                    aet, s == null ? "n/a" : s.getRemoteSocketAddress());
            auditService.recordHandshake(aet, STATUS_ACCEPTED, "system", null);
            eventPublisher.publishEvent(new ConnectionEstablishedEvent(this,
                    aet, aet, remoteHost, remotePort, STATUS_ACCEPTED));
        }, "onConnectionAccepted");
    }

    @Override
    public void onConnectionFailed(Connection local, Connection remoteConn, Socket s, Throwable e) {
        String peerAet = remoteConn != null ? safeGetCommonName(remoteConn) : safeGetCommonName(local);
        String errClass = e != null ? e.getClass().getSimpleName() : "UnknownException";
        String errMsg = e != null ? e.getMessage() : null;
        safeRecordAndPublish(() -> {
            LOG.warn("DICOM connection failed: peer={} class={} msg={}",
                    peerAet, errClass, errMsg);
            auditService.recordHandshake(peerAet, STATUS_FAILED, "system", errClass);
            eventPublisher.publishEvent(new ConnectionFailedEvent(this,
                    peerAet, errClass, errMsg, STATUS_FAILED));
        }, "onConnectionFailed");
    }

    @Override
    public void onConnectionRejected(Connection local, Socket s, Throwable e) {
        String localAet = safeGetCommonName(local);
        String errClass = e != null ? e.getClass().getSimpleName() : "RejectedException";
        String errMsg = e != null ? e.getMessage() : null;
        safeRecordAndPublish(() -> {
            LOG.warn("DICOM connection rejected: local={} class={} msg={}",
                    localAet, errClass, errMsg);
            auditService.recordHandshake(localAet, STATUS_REJECTED, "system", errClass);
            eventPublisher.publishEvent(new ConnectionFailedEvent(this,
                    localAet, errClass, errMsg, STATUS_REJECTED));
        }, "onConnectionRejected");
    }

    @Override
    public void onConnectionRejectedBlacklisted(Connection local, Socket s) {
        String localAet = safeGetCommonName(local);
        String socketHint;
        if (s != null && s.getRemoteSocketAddress() != null) {
            socketHint = s.getRemoteSocketAddress().toString();
        } else {
            socketHint = "n/a";
        }
        // lambda 捕获需要 effectively-final，赋值写一次而非多次
        final String capturedLocalAet = localAet;
        final String capturedSocketHint = socketHint;
        safeRecordAndPublish(() -> {
            LOG.warn("DICOM connection rejected (blacklisted): local={} remote={}",
                    capturedLocalAet, capturedSocketHint);
            auditService.recordHandshake(capturedLocalAet, STATUS_BLACKLISTED, "system", "BLACKLISTED");
            eventPublisher.publishEvent(new ConnectionRejectedBlacklistedEvent(this,
                    capturedLocalAet, capturedSocketHint, "blacklisted_calling_aet",
                    "Peer AET 命中黑名单"));
        }, "onConnectionRejectedBlacklisted");
    }

    /**
     * 审计 + 事件发布的 try/catch 包壳：异常仅记 WARN，不抛回 dcm4che
     * 上层，避免污染 Association lifecycle。
     */
    private void safeRecordAndPublish(Runnable body, String callbackName) {
        try {
            body.run();
        } catch (Exception ex) {
            LOG.warn("ImagingConnectionMonitor.{} side-effect failed (suppressed): {}",
                    callbackName, ex.getMessage(), ex);
        }
    }

    /** dcm4che Connection.getCommonName() 在某些边界下抛异常；包一层默认值。 */
    private static String safeGetCommonName(Connection c) {
        if (c == null) return "unknown";
        try {
            String n = c.getCommonName();
            return n == null ? "unknown" : n;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String safeGetHostname(Connection c) {
        if (c == null) return null;
        try { return c.getHostname(); } catch (Exception e) { return null; }
    }

    private static Integer safeGetPort(Connection c) {
        if (c == null) return null;
        try { return c.getPort(); } catch (Exception e) { return null; }
    }

    /** 兼容 unconnected Socket：getInetAddress().getHostAddress() 在底层为 null 时会 NPE。 */
    private static String safeGetHostAddress(Socket s) {
        if (s == null || s.getInetAddress() == null) return null;
        try { return s.getInetAddress().getHostAddress(); }
        catch (Exception e) { return null; }
    }

    /** 兼容 unconnected Socket：getPort() 在未连接时抛 IllegalArgumentException。 */
    private static Integer safeGetPortFromSocket(Socket s) {
        if (s == null) return null;
        try { return s.getPort(); }
        catch (Exception e) { return null; }
    }
}
