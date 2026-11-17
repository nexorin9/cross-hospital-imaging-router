package com.crosshospital.imaging.service.wado;

import java.nio.ByteBuffer;

/**
 * multipart/related 中的一个 part：一个 DICOM 实例对象。
 *
 * @param partNumber  part 序号（从 1 开始，与源 MultipartParser 的 handler 计数一致）
 * @param contentType part 头里的 Content-Type（通常 {@code application/dicom}）
 * @param bytes       part 体（已校验 DICOM Part-10 前导；位置 0 起）
 */
public record DicomPart(int partNumber, String contentType, ByteBuffer bytes) {

    /** part 体字节数。 */
    public int size() {
        return bytes.remaining();
    }
}