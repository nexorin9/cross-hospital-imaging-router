package com.crosshospital.imaging.security;

/**
 * 审计链校验结果（task 24）。
 *
 * <p>除了「通过 / 不通过」，还要能回答「<b>哪一行</b>对不上」——
 * 飞检现场只被告知「审计链断了」是不够的，必须能指到具体那条记录。
 *
 * @param valid          整条链是否一致
 * @param rowsChecked    本次校验过的行数
 * @param mismatchIndex  首个不一致行在传入列表中的下标（一致时为 -1）
 * @param mismatchId     首个不一致行的 {@code audit_imaging_fetch.id}（无法确定时为 null）
 * @param reason         不一致的原因
 * @param expectedHex    期望校验和（十六进制；不一致时给出）
 * @param actualHex      实际校验和（十六进制；不一致时给出）
 * @param detail         人类可读的补充说明
 */
public record ChainVerificationReport(
        boolean valid,
        int rowsChecked,
        int mismatchIndex,
        Long mismatchId,
        Reason reason,
        String expectedHex,
        String actualHex,
        String detail) {

    /** 不一致原因。 */
    public enum Reason {
        /** 整条链一致。 */
        NONE,
        /** 列表中出现 {@code null} 行。 */
        NULL_ROW,
        /** 行缺少 {@code row_checksum}（未盖戳就入库）。 */
        MISSING_ROW_CHECKSUM,
        /** 行的 {@code prev_checksum} 与上一行的 {@code row_checksum} 对不上（链被断开或插入了行）。 */
        PREV_LINK_MISMATCH,
        /** 行的 {@code row_checksum} 与按字段重算的结果对不上（行内容被改写）。 */
        ROW_CHECKSUM_MISMATCH
    }

    public static ChainVerificationReport valid(int rowsChecked) {
        return new ChainVerificationReport(true, rowsChecked, -1, null, Reason.NONE, null, null,
                "审计链一致，共校验 " + rowsChecked + " 行");
    }

    public static ChainVerificationReport mismatch(int index, Long id, Reason reason,
                                                   String expectedHex, String actualHex, String detail) {
        return new ChainVerificationReport(false, index + 1, index, id, reason,
                expectedHex, actualHex, detail);
    }

    /** 便于日志输出的一行摘要。 */
    public String summary() {
        if (valid) {
            return detail;
        }
        return "审计链校验失败：第 " + (mismatchIndex + 1) + " 行"
                + (mismatchId == null ? "" : "（id=" + mismatchId + "）")
                + " 原因=" + reason + " " + detail;
    }
}
