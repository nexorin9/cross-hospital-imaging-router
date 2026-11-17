package com.crosshospital.imaging.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 更新外院节点请求 DTO。
 *
 * <p>路径参数 {@code aet}（被更新的节点）不可变；DTO 内字段均可选（null = 不替换）。
 * 与 {@link PeerCreateRequest} 的差异：没有 {@code peerAet}（自身不可改名），
 * 所有数值字段放宽为可空，便于信息科单独调整某一项。
 */
@Schema(name = "PeerUpdateRequest", description = "外院 DICOM 节点准入表更新请求（字段均可选）")
public class PeerUpdateRequest {

    @Size(min = 1, max = 16)
    @Schema(description = "主机名或 IP", example = "pacs.example-hospital.cn")
    private String host;

    @Min(1)
    @Max(65535)
    @Schema(description = "DICOM 监听端口（1..65535）", example = "11112")
    private Integer port;

    @Schema(description = "TLS 模式：none / start_tls / tls", example = "tls")
    private String tlsMode;

    @Schema(description = "本端支持的 SOP Class UID 列表")
    private List<String> presentContexts;

    @Schema(description = "本端支持的 Transfer Syntax UID 列表")
    private List<String> transferSyntaxes;

    @Schema(description = "黑名单对端 AET 列表")
    private List<String> blacklistAets;

    @Min(0)
    @Max(10)
    @Schema(description = "最大重试次数（0..10）", example = "5")
    private Integer maxRetries;

    @Min(100)
    @Max(60000)
    @Schema(description = "重试初始退避毫秒", example = "2000")
    private Integer backoffMs;

    @Min(1)
    @Max(720)
    @Schema(description = "缓存 TTL 小时", example = "48")
    private Integer cacheTtlHours;

    @Min(64)
    @Max(1048576)
    @Schema(description = "缓存容量上限 MB", example = "8192")
    private Long maxCacheMb;

    @Schema(description = "启用 / 禁用", example = "true")
    private Boolean enabled;

    @Size(max = 256)
    @Schema(description = "备注", example = "对方已升级到 v2.5")
    private String notes;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getTlsMode() { return tlsMode; }
    public void setTlsMode(String tlsMode) { this.tlsMode = tlsMode; }
    public List<String> getPresentContexts() { return presentContexts; }
    public void setPresentContexts(List<String> presentContexts) { this.presentContexts = presentContexts; }
    public List<String> getTransferSyntaxes() { return transferSyntaxes; }
    public void setTransferSyntaxes(List<String> transferSyntaxes) { this.transferSyntaxes = transferSyntaxes; }
    public List<String> getBlacklistAets() { return blacklistAets; }
    public void setBlacklistAets(List<String> blacklistAets) { this.blacklistAets = blacklistAets; }
    public Integer getMaxRetries() { return maxRetries; }
    public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }
    public Integer getBackoffMs() { return backoffMs; }
    public void setBackoffMs(Integer backoffMs) { this.backoffMs = backoffMs; }
    public Integer getCacheTtlHours() { return cacheTtlHours; }
    public void setCacheTtlHours(Integer cacheTtlHours) { this.cacheTtlHours = cacheTtlHours; }
    public Long getMaxCacheMb() { return maxCacheMb; }
    public void setMaxCacheMb(Long maxCacheMb) { this.maxCacheMb = maxCacheMb; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}