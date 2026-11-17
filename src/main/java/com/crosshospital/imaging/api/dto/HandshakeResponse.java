package com.crosshospital.imaging.api.dto;

import com.crosshospital.imaging.exception.HandshakeException.FailureCode;
import com.crosshospital.imaging.service.dicom.HandshakeResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 握手健康检查响应 DTO。
 *
 * <p>由 {@link com.crosshospital.imaging.api.PeersHealthController} 在
 * {@code POST /api/imaging/peers/{aet}/health} 端点返回。{@code compatible=true}
 * 表示本端与对端完成全部 6 步预校验（isCompatible / 黑名单 / 传输语法 / SOP Class /
 * TLS 协议 / cipher）+ TCP 探活成功；{@code compatible=false} 表示握手失败，
 * 但 HTTP 仍然返回 200 OK，调用方读 {@code lastErrorCode} 与
 * {@code lastErrorMessage} 判定失败码（用于运维诊断 + 准入表校验）。
 *
 * <p>字段命名沿用 dcm4che 既有语义（{@code agreedTransferSyntaxes} 等），
 * 便于读过源仓库代码的工程师直接定位。
 */
@Schema(name = "HandshakeResponse", description = "外院 DICOM 节点握手健康检查结果")
public class HandshakeResponse {

    @Schema(description = "是否握手成功（true=全部 6 步预校验通过 + TCP 探活成功）", example = "true")
    private boolean compatible;

    @Schema(description = "Peer AE Title（来自请求路径）", example = "PEER_HZ_CITY_HOSP")
    private String peerAet;

    @Schema(description = "本端与对端共同支持的 SOP Class UID 列表")
    private List<String> commonSopClasses;

    @Schema(description = "本端与对端共同支持的 Transfer Syntax UID 列表")
    private List<String> agreedTransferSyntaxes;

    @Schema(description = "本端与对端共同支持的 TLS 协议列表")
    private List<String> agreedTlsProtocols;

    @Schema(description = "本端与对端共同支持的 TLS 密码套件列表")
    private List<String> agreedCipherSuites;

    @Schema(description = "失败时错误码（compatible=false 时）；成功时为 null",
            example = "TRANSFER_SYNTAX_EMPTY")
    private FailureCode lastErrorCode;

    @Schema(description = "失败时错误消息；成功时为 null",
            example = "本地与对端传输语法无交集")
    private String lastErrorMessage;

    @Schema(description = "失败是否可重试（网络瞬态=true；配置型失败/黑名单=false）", example = "false")
    private boolean retryable;

    @Schema(description = "本端 AE Title（local connection common name）")
    private String localAet;

    @Schema(description = "对端 AE Title（remote connection common name）")
    private String remoteAet;

    @Schema(description = "对端主机", example = "pacs.example-hospital.cn")
    private String remoteHost;

    @Schema(description = "对端端口", example = "11112")
    private int remotePort;

    @Schema(description = "执行操作员工号 / system", example = "employee_007")
    private String who;

    @Schema(description = "握手检查执行时间 (ISO-8601)", example = "2026-10-20T10:00:00Z")
    private Instant checkedAt;

    /** 工厂：握手成功。 */
    public static HandshakeResponse fromResult(String peerAet, String who, HandshakeResult result) {
        HandshakeResponse r = new HandshakeResponse();
        r.compatible = result.compatible;
        r.peerAet = peerAet;
        r.commonSopClasses = result.commonSopClasses == null ? Collections.emptyList() : result.commonSopClasses;
        r.agreedTransferSyntaxes = result.agreedTransferSyntaxes == null ? Collections.emptyList() : result.agreedTransferSyntaxes;
        r.agreedTlsProtocols = result.agreedTlsProtocols == null ? Collections.emptyList() : result.agreedTlsProtocols;
        r.agreedCipherSuites = result.agreedCipherSuites == null ? Collections.emptyList() : result.agreedCipherSuites;
        r.lastErrorCode = result.lastErrorCode;
        r.lastErrorMessage = result.lastErrorMessage;
        r.retryable = !result.compatible && result.lastErrorCode != null
                && (result.lastErrorCode == FailureCode.CONNECTION_REFUSED
                || result.lastErrorCode == FailureCode.CONNECT_TIMEOUT);
        r.localAet = result.localAet;
        r.remoteAet = result.remoteAet;
        r.remoteHost = result.remoteHost;
        r.remotePort = result.remotePort;
        r.who = who;
        r.checkedAt = Instant.now();
        return r;
    }

    /** 工厂：握手抛 {@code HandshakeException}（典型 PEER_NOT_FOUND / BLACKLISTED）。 */
    public static HandshakeResponse fromException(String peerAet, String who,
                                                  FailureCode code, String message, boolean retryable) {
        HandshakeResponse r = new HandshakeResponse();
        r.compatible = false;
        r.peerAet = peerAet;
        r.commonSopClasses = Collections.emptyList();
        r.agreedTransferSyntaxes = Collections.emptyList();
        r.agreedTlsProtocols = Collections.emptyList();
        r.agreedCipherSuites = Collections.emptyList();
        r.lastErrorCode = code;
        r.lastErrorMessage = message;
        r.retryable = retryable;
        r.who = who;
        r.checkedAt = Instant.now();
        return r;
    }

    // -- getters（springdoc + Jackson 序列化用；不需要 setter，DTO 一经装配即返回） --
    public boolean isCompatible() { return compatible; }
    public String getPeerAet() { return peerAet; }
    public List<String> getCommonSopClasses() { return commonSopClasses; }
    public List<String> getAgreedTransferSyntaxes() { return agreedTransferSyntaxes; }
    public List<String> getAgreedTlsProtocols() { return agreedTlsProtocols; }
    public List<String> getAgreedCipherSuites() { return agreedCipherSuites; }
    public FailureCode getLastErrorCode() { return lastErrorCode; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public boolean isRetryable() { return retryable; }
    public String getLocalAet() { return localAet; }
    public String getRemoteAet() { return remoteAet; }
    public String getRemoteHost() { return remoteHost; }
    public int getRemotePort() { return remotePort; }
    public String getWho() { return who; }
    public Instant getCheckedAt() { return checkedAt; }
}
