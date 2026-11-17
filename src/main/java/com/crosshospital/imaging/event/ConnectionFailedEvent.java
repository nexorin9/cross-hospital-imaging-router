package com.crosshospital.imaging.event;

/**
 * DICOM 连接失败事件。
 *
 * <p>对应源仓库 dcm4che-net {@code ConnectionMonitor.onConnectionFailed} 和
 * {@code ConnectionMonitor.onConnectionRejected}。失败既包含网络瞬态
 * （{@code ConnectException} / {@code SocketTimeoutException}），也包含协议层拒绝
 * （协议族 / TLS / cipher / 传输语法 / SOP Class 不兼容）。
 *
 * <p>本事件不直接推进路由任务的状态机（由 {@code FetchTaskService} 与
 * {@code RoutingFailureHandler} 在 task 16 之后接管），仅作为业务事件
 * 通知告警 / 看板。
 */
import org.springframework.context.ApplicationEvent;

public class ConnectionFailedEvent extends ApplicationEvent {

    private final String peerAet;
    private final String failureCode;
    private final String errorMessage;
    private final String statusTerminal;

    public ConnectionFailedEvent(Object source,
                                  String peerAet,
                                  String failureCode,
                                  String errorMessage,
                                  String statusTerminal) {
        super(source);
        this.peerAet = peerAet;
        this.failureCode = failureCode;
        this.errorMessage = errorMessage;
        this.statusTerminal = statusTerminal;
    }

    public String getPeerAet() { return peerAet; }
    public String getFailureCode() { return failureCode; }
    public String getErrorMessage() { return errorMessage; }
    public String getStatusTerminal() { return statusTerminal; }
}
