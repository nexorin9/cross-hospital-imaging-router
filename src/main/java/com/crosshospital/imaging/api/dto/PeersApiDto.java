package com.crosshospital.imaging.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Peers 端点共用 OpenAPI 文档模型（仅文档化用途）。
 *
 * <p>{@link Page} 的 springdoc 渲染依赖
 * {@code springdoc-openapi-starter-common} 的
 * {@code org.springdoc.core.converters.models.PageableOpenAPIConverter}；
 * 本类提供扁平列表与分页摘要两条等价表达，便于在 swagger-ui 直接读懂。
 *
 * <p>实际业务响应仍由 {@link PeerResponse} 与 Spring Data 的
 * {@link Page} 共同承担，本类不参与运行时数据流。
 */
@Schema(name = "PeersApiDto", description = "外院节点 API 文档容器")
public class PeersApiDto {

    @Schema(
            description = "扁平响应（用于单条 GET / POST 响应）",
            example = "{\"peerAet\":\"PEER_HZ\",\"host\":\"pacs.hz.cn\",\"port\":11112}")
    private PeerResponse peerResponse;

    @Schema(description = "分页响应（用于 GET /api/imaging/peers）")
    private PagedPeers pagedPeers;

    public PeerResponse getPeerResponse() { return peerResponse; }
    public void setPeerResponse(PeerResponse peerResponse) { this.peerResponse = peerResponse; }
    public PagedPeers getPagedPeers() { return pagedPeers; }
    public void setPagedPeers(PagedPeers pagedPeers) { this.pagedPeers = pagedPeers; }

    /**
     * springdoc 看到的分页结构（Spring Data 默认 Page 序列化形态）。
     */
    @Schema(
            name = "PagedPeers",
            description = "外院节点分页响应（Page<PeerResponse> 序列化的标准形态）")
    public static class PagedPeers {

        @Schema(description = "当页内容")
        private List<PeerResponse> content;

        @Schema(description = "页码（0 起）", example = "0")
        private int number;

        @Schema(description = "页大小", example = "20")
        private int size;

        @Schema(description = "总元素数", example = "42")
        private long totalElements;

        @Schema(description = "总页数", example = "3")
        private int totalPages;

        public List<PeerResponse> getContent() { return content; }
        public void setContent(List<PeerResponse> content) { this.content = content; }
        public int getNumber() { return number; }
        public void setNumber(int number) { this.number = number; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        public long getTotalElements() { return totalElements; }
        public void setTotalElements(long totalElements) { this.totalElements = totalElements; }
        public int getTotalPages() { return totalPages; }
        public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
    }
}