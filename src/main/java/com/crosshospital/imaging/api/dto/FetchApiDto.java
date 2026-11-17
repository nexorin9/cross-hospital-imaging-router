package com.crosshospital.imaging.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** fetch 端点的文档示例与请求/响应 schema 集合（task 30 / 33）。 */
@Schema(name = "FetchApiDto", description = "跨院调阅端点的示例报文（供 OpenAPI 文档与契约测试复用）")
public final class FetchApiDto {

    private FetchApiDto() {
    }

    /** 请求示例：省略 peerAet，由主索引命名空间自动定位外院节点。 */
    public static final String EXAMPLE_REQUEST = """
            {"patientMpi":"HZH^1234567","studyUid":"1.2.840.10008.5.1.4.1.1.2.EXAMPLE",
             "sopClassUid":"1.2.840.10008.5.1.4.1.1.2","accessionNumber":"<EXAMPLE_ACCESSION>"}
            """;

    /** 受理回执示例（202）。 */
    public static final String EXAMPLE_ACCEPTED = """
            {"taskId":"3f8a2f16-9a5e-4c11-9c53-2b9d1e0a7c44","status":"queued",
             "links":{"self":"/api/imaging/tasks/3f8a2f16-9a5e-4c11-9c53-2b9d1e0a7c44"}}
            """;

    /** 状态查询示例（notified）。 */
    public static final String EXAMPLE_STATUS = """
            {"taskId":"3f8a2f16-9a5e-4c11-9c53-2b9d1e0a7c44","status":"notified",
             "retryCount":0,"failureCode":null,
             "createdAt":"2026-10-20T08:31:00Z","updatedAt":"2026-10-20T08:34:12Z"}
            """;
}
