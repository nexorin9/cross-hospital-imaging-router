package com.crosshospital.imaging.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.crosshospital.imaging.domain.Peer;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 外院节点响应 DTO。
 *
 * <p>由 {@link com.crosshospital.imaging.service.PeerRegistry} 在
 * {@code createPeer / listPeers / getPeer / updatePeer} 四个方法返回。
 * JSONB 列（present_contexts / transfer_syntaxes / blacklist_aets）
 * 以字符串数组形式暴露，便于信息科排障时直接复制 UID。
 */
@Schema(name = "PeerResponse", description = "外院 DICOM 节点响应")
public class PeerResponse {

    @Schema(description = "节点主键 ID", example = "1")
    private Long id;

    @Schema(description = "AE Title", example = "PEER_HZ_CITY_HOSP")
    private String peerAet;

    @Schema(description = "主机名或 IP", example = "pacs.example-hospital.cn")
    private String host;

    @Schema(description = "端口", example = "11112")
    private Integer port;

    @Schema(description = "TLS 模式", example = "none")
    private String tlsMode;

    @Schema(description = "本端支持的 SOP Class UID 列表")
    private List<String> presentContexts;

    @Schema(description = "本端支持的 Transfer Syntax UID 列表")
    private List<String> transferSyntaxes;

    @Schema(description = "黑名单对端 AET 列表")
    private List<String> blacklistAets;

    @Schema(description = "最大重试次数", example = "3")
    private Integer maxRetries;

    @Schema(description = "退避初始毫秒", example = "1000")
    private Integer backoffMs;

    @Schema(description = "缓存 TTL 小时", example = "24")
    private Integer cacheTtlHours;

    @Schema(description = "缓存容量上限 MB", example = "5120")
    private Long maxCacheMb;

    @Schema(description = "是否启用", example = "true")
    private Boolean enabled;

    @Schema(description = "备注", example = "接入杭州某三甲医院 PACS")
    private String notes;

    @Schema(description = "创建时间（ISO-8601）")
    private Instant createdAt;

    @Schema(description = "更新时间（ISO-8601）")
    private Instant updatedAt;

    public static PeerResponse fromEntity(Peer p) {
        PeerResponse r = new PeerResponse();
        r.id = p.getId();
        r.peerAet = p.getPeerAet();
        r.host = p.getHost();
        r.port = p.getPort();
        r.tlsMode = p.getTlsMode();
        r.presentContexts = parseJsonArray(p.getPresentContexts());
        r.transferSyntaxes = parseJsonArray(p.getTransferSyntaxes());
        r.blacklistAets = parseJsonArray(p.getBlacklistAets());
        r.maxRetries = p.getMaxRetries();
        r.backoffMs = p.getBackoffMs();
        r.cacheTtlHours = p.getCacheTtlHours();
        r.maxCacheMb = p.getMaxCacheMb();
        r.enabled = p.getEnabled();
        r.notes = p.getNotes();
        r.createdAt = p.getCreatedAt();
        r.updatedAt = p.getUpdatedAt();
        return r;
    }

    /**
     * 最小 JSON 数组解析：去除方括号与多余空白后按逗号切分。
     * 不引入 Jackson 是为了避免对简单字符串解析的依赖爆炸；严格 JSON 解析在 task 11 WADO-RS 引入。
     */
    private static List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        String trimmed = json.trim();
        if (trimmed.equals("[]") || trimmed.isEmpty()) {
            return Collections.emptyList();
        }
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return Collections.emptyList();
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(body.split(","))
                .map(String::trim)
                .map(s -> s.startsWith("\"") && s.endsWith("\"")
                        ? s.substring(1, s.length() - 1) : s)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toList());
    }

    // -- getters --
    public Long getId() { return id; }
    public String getPeerAet() { return peerAet; }
    public String getHost() { return host; }
    public Integer getPort() { return port; }
    public String getTlsMode() { return tlsMode; }
    public List<String> getPresentContexts() { return presentContexts; }
    public List<String> getTransferSyntaxes() { return transferSyntaxes; }
    public List<String> getBlacklistAets() { return blacklistAets; }
    public Integer getMaxRetries() { return maxRetries; }
    public Integer getBackoffMs() { return backoffMs; }
    public Integer getCacheTtlHours() { return cacheTtlHours; }
    public Long getMaxCacheMb() { return maxCacheMb; }
    public Boolean getEnabled() { return enabled; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}