package com.crosshospital.imaging.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建外院节点请求 DTO。
 *
 * <p>Controller 边界由 {@code @Valid} 触发 JSR-303 注解校验；
 * 业务层语义校验（UID 标准格式 / AET 黑名单不能含自身）由
 * {@link com.crosshospital.imaging.service.InputValidator} 执行。
 */
@Schema(name = "PeerCreateRequest", description = "外院 DICOM 节点准入表创建请求")
public class PeerCreateRequest {

    @NotBlank
    @Size(min = 1, max = 16)
    @Schema(description = "AE Title（1..16 字符，大写字母数字下划线短横）",
            example = "PEER_HZ_CITY_HOSP", requiredMode = Schema.RequiredMode.REQUIRED)
    private String peerAet;

    @NotBlank
    @Schema(description = "外院 PACS 主机名或 IP", example = "pacs.example-hospital.cn",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String host;

    @NotNull
    @Min(1)
    @Max(65535)
    @Schema(description = "DICOM 监听端口（1..65535）", example = "11112",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer port;

    @Schema(description = "TLS 模式：none / start_tls / tls", example = "none")
    private String tlsMode = "none";

    @Schema(description = "本端支持的 SOP Class UID 列表（DICOM 标准 UID 格式）",
            example = "[\"1.2.840.10008.5.1.4.1.1.2\",\"1.2.840.10008.5.1.4.1.1.4\"]")
    private List<String> presentContexts;

    @Schema(description = "本端支持的 Transfer Syntax UID 列表",
            example = "[\"1.2.840.10008.1.2.1\",\"1.2.840.10008.1.2.4.70\"]")
    private List<String> transferSyntaxes;

    @Schema(description = "需要拉黑的对端 AET 列表（不允许包含本节点自身的 AET）",
            example = "[\"BAD_AET_1\",\"BAD_AET_2\"]")
    private List<String> blacklistAets;

    @Min(0)
    @Max(10)
    @Schema(description = "握手 / 调阅最大重试次数（0..10）", example = "3")
    private Integer maxRetries = 3;

    @Min(100)
    @Max(60000)
    @Schema(description = "重试初始退避毫秒（100..60000）", example = "1000")
    private Integer backoffMs = 1000;

    @Min(1)
    @Max(720)
    @Schema(description = "本地 PACS 缓存 TTL 小时", example = "24")
    private Integer cacheTtlHours = 24;

    @Min(64)
    @Max(1048576)  // ≤ 1 TB
    @Schema(description = "本地 PACS 缓存容量上限 MB", example = "5120")
    private Long maxCacheMb = 5120L;

    @Size(max = 256)
    @Schema(description = "备注（信息科填写的接入备注）", example = "接入杭州某三甲医院 PACS")
    private String notes;

    public String getPeerAet() { return peerAet; }
    public void setPeerAet(String peerAet) { this.peerAet = peerAet; }
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
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}