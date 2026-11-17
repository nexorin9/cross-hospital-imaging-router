package com.crosshospital.imaging.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 测试用 DICOM Part-10 对象与 multipart/related 响应体构造器。
 *
 * <p>这里生成的是**结构合法**的最小 DICOM 文件（128 字节前导 + {@code "DICM"} + Explicit VR
 * Little Endian 文件元信息组），供协议层单测断言「前导在 offset 128、UID 可读」用。
 * 它**不是**像素数据，也不冒充真实患者影像：所有 UID 都在示例根
 * {@code 1.2.826.0.1.3680043.10.9999} 下自造，无任何可回溯到真实患者的标识。
 *
 * <p>需要真实影像数据集的路径由 {@code scripts/generate-fixtures.sh} 负责下载公开数据集到
 * {@code src/test/fixtures/samples-dicom/}；本类只服务于「边界与协议解析」类断言。
 */
public final class TestDicomFixtures {

    /** 示例 UID 根（自造，非真实机构分配）。 */
    public static final String EXAMPLE_UID_ROOT = "1.2.826.0.1.3680043.10.9999";

    public static final String EXAMPLE_STUDY_UID = EXAMPLE_UID_ROOT + ".1.20260101";
    public static final String EXAMPLE_SERIES_UID = EXAMPLE_UID_ROOT + ".2.20260101";
    public static final String EXAMPLE_SOP_CLASS_UID = "1.2.840.10008.5.1.4.1.1.2"; // CT Image Storage
    public static final String EXAMPLE_EXPLICIT_VR_LE = "1.2.840.10008.1.2.1";

    private static final int PREAMBLE_LENGTH = 128;

    private TestDicomFixtures() {
    }

    /** 构造一个 DICOM Part-10 对象（含 128 字节前导 + "DICM" + 文件元信息）。 */
    public static byte[] dicomInstance(String studyUid, String seriesUid, String sopInstanceUid,
                                       String sopClassUid) {
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        // 文件元信息组内元素（Explicit VR Little Endian）
        writeUl(meta, 0x0002, 0x0002, 0x00000000);   // 占位，稍后回填 group length
        writeOb(meta, 0x0002, 0x0001, new byte[]{0x00, 0x01}); // FileMetaInformationVersion
        writeUi(meta, 0x0002, 0x0002, sopClassUid);   // MediaStorageSOPClassUID
        writeUi(meta, 0x0002, 0x0003, sopInstanceUid);// MediaStorageSOPInstanceUID
        writeUi(meta, 0x0002, 0x0010, EXAMPLE_EXPLICIT_VR_LE); // TransferSyntaxUID
        writeUi(meta, 0x0002, 0x0012, EXAMPLE_UID_ROOT + ".0.1"); // ImplementationClassUID
        byte[] metaBytes = meta.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[PREAMBLE_LENGTH]);   // 128 字节 0 前导
        out.writeBytes("DICM".getBytes(StandardCharsets.US_ASCII));
        // 文件元信息组长度 = 除本元素（12 字节）以外的所有元素字节数
        int groupLength = metaBytes.length - 12;
        out.write(metaBytes, 0, 8);
        out.writeBytes(le32(groupLength));
        out.write(metaBytes, 12, metaBytes.length - 12);
        // 数据集片段：显式 VR 的 SOPInstanceUID + SeriesInstanceUID + StudyInstanceUID
        writeUi(out, 0x0008, 0x0018, sopInstanceUid);
        writeUi(out, 0x0020, 0x000E, seriesUid);
        writeUi(out, 0x0020, 0x000D, studyUid);
        return out.toByteArray();
    }

    /** 固定 SOP UID 的便捷重载。 */
    public static byte[] dicomInstance(String studyUid, int sopOrdinal) {
        return dicomInstance(studyUid, EXAMPLE_SERIES_UID,
                EXAMPLE_UID_ROOT + ".3." + sopOrdinal, EXAMPLE_SOP_CLASS_UID);
    }

    /** 不带 DICOM 前导的裸字节（用于「前导缺失应被拒」的负向用例）。 */
    public static byte[] notDicom(int length) {
        byte[] bytes = new byte[Math.max(length, 8)];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ('A' + (i % 26));
        }
        return bytes;
    }

    /** 构造 multipart/related 响应体（RFC 2387：boundary 行 + 头 + 空行 + 体 + CRLF）。 */
    public static byte[] multipartRelated(String boundary, String contentType, byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(("Content-Type: " + contentType + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(part);
            out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static void writeUl(ByteArrayOutputStream out, int group, int element, int value) {
        out.writeBytes(le16(group));
        out.writeBytes(le16(element));
        out.writeBytes("UL".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(le16(2));
        out.writeBytes(le32(value));
    }

    private static void writeOb(ByteArrayOutputStream out, int group, int element, byte[] value) {
        out.writeBytes(le16(group));
        out.writeBytes(le16(element));
        out.writeBytes("OB".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{0, 0});            // reserved
        out.writeBytes(le32(value.length));
        out.writeBytes(value);
    }

    private static void writeUi(ByteArrayOutputStream out, int group, int element, String value) {
        byte[] padded = new byte[value.length() + (value.length() % 2)];
        byte[] raw = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw, 0, padded, 0, raw.length);
        out.writeBytes(le16(group));
        out.writeBytes(le16(element));
        out.writeBytes("UI".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(le16(padded.length));
        out.writeBytes(padded);
    }

    private static byte[] le16(int v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF)};
    }

    private static byte[] le32(int v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF),
                (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF)};
    }
}