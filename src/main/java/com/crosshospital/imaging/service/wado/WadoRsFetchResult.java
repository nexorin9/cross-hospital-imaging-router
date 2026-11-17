package com.crosshospital.imaging.service.wado;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * 一次 WADO-RS study 拉取的完整结果。
 *
 * <p>{@link #partial()} 为 {@code true} 表示网关回了 HTTP 206（部分内容）——对象列表是**不完整**的，
 * 上层推进业务状态时必须把这一点带出去，不得当成整 study 已拉全。
 *
 * @param peerAet  外院节点 AET
 * @param studyUid 被拉取的 StudyInstanceUID
 * @param httpStatus 实际 HTTP 状态码（200 / 206）
 * @param partial  是否为部分内容（206）
 * @param instances 按 part 顺序排列的 DICOM 实例
 * @param totalBytes 实例总字节数
 */
public record WadoRsFetchResult(
        String peerAet,
        String studyUid,
        int httpStatus,
        boolean partial,
        List<ByteBuffer> instances,
        long totalBytes) {

    public WadoRsFetchResult {
        instances = List.copyOf(instances);
    }

    public int instanceCount() {
        return instances.size();
    }
}