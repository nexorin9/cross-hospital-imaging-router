package com.crosshospital.imaging.security;

import com.crosshospital.imaging.domain.AuditRow;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.List;

/**
 * 审计链校验器（task 24）。
 *
 * <p>逐行重算 {@code row_checksum} 并与库中存的值比对，同时检查每行的
 * {@code prev_checksum} 是否等于上一行的 {@code row_checksum}（链是否被断开）。
 * 任何一处对不上即判定整条链不可信，并报告<b>具体是哪一行</b>。
 *
 * <p>典型用法（飞检 / 内审材料自检）：
 * <pre>{@code
 * List<AuditRow> rows = auditRepository.queryByTimeWindow(from, to, null, null, null, null, pageable).getContent();
 * ChainVerificationReport report = verifier.verifyChainDetailed(rows);
 * if (!report.valid()) {
 *     log.warn("审计链断裂：{}", report.summary());
 * }
 * }</pre>
 *
 * <p>源主路径证据：dcm4che-net-audit 应用层审计模块 + 链式校验和模式。
 */
@Component
public class AuditChecksumVerifier {

    /**
     * 校验整条链是否一致。
     *
     * @param rows 按写入顺序排列的审计行
     * @return 一致返回 {@code true}；任何一行对不上返回 {@code false}
     */
    public boolean verifyChain(List<AuditRow> rows) {
        return verifyChainDetailed(rows).valid();
    }

    /**
     * 校验一段链（task 25）：本段第一行的 {@code prev_checksum} 须等于给定的上一行校验和。
     *
     * @param rows             按写入顺序排列的审计行
     * @param expectedFirstPrev 本段之前那一行的 {@code row_checksum}；{@code null} 表示本段从链首开始
     * @return 一致返回 {@code true}
     */
    public boolean verifyChain(List<AuditRow> rows, byte[] expectedFirstPrev) {
        return verifyChainDetailed(rows, expectedFirstPrev).valid();
    }

    /**
     * 校验整条链并给出首个不一致行的定位信息。
     *
     * @param rows 按写入顺序排列的审计行
     * @return 校验报告（{@link ChainVerificationReport#valid()} 表示整条链一致）
     */
    public ChainVerificationReport verifyChainDetailed(List<AuditRow> rows) {
        return verifyChainDetailed(rows, null);
    }

    /**
     * 校验<b>一段</b>链（task 25）。
     *
     * <p>审计表只增不减，飞检取材料时查出来的往往只是<b>一个时间窗</b>里的行，
     * 而不是从链首开始的全部行。这时窗内第一行的 {@code prev_checksum} 指向的是
     * 窗外的上一行 —— 拿「空表首行 = 32 字节 0」去校验它，必然报
     * {@code PREV_LINK_MISMATCH}，而链其实是好的。
     *
     * <p>所以这里允许调用方给出「本段之前那一行的 {@code row_checksum}」作为起点：
     * <ul>
     *   <li>{@code expectedFirstPrev == null}：视作链首（32 字节全 0）；</li>
     *   <li>非 {@code null}：本段第一行的 {@code prev_checksum} 必须等于它。</li>
     * </ul>
     *
     * @param rows             按写入顺序排列的审计行
     * @param expectedFirstPrev 本段之前那一行的 {@code row_checksum}；{@code null} 表示本段从链首开始
     * @return 校验报告
     */
    public ChainVerificationReport verifyChainDetailed(List<AuditRow> rows, byte[] expectedFirstPrev) {
        if (rows == null || rows.isEmpty()) {
            return ChainVerificationReport.valid(0);
        }

        byte[] expectedPrev = expectedFirstPrev == null
                ? AuditChecksum.emptyChecksum()
                : expectedFirstPrev;
        for (int i = 0; i < rows.size(); i++) {
            AuditRow row = rows.get(i);
            if (row == null) {
                return ChainVerificationReport.mismatch(i, null,
                        ChainVerificationReport.Reason.NULL_ROW, null, null,
                        "第 " + (i + 1) + " 行为空，无法校验");
            }

            byte[] actualRowChecksum = row.getRowChecksum();
            if (actualRowChecksum == null || actualRowChecksum.length != AuditChecksum.CHECKSUM_LENGTH) {
                return ChainVerificationReport.mismatch(i, row.getId(),
                        ChainVerificationReport.Reason.MISSING_ROW_CHECKSUM, null, null,
                        "第 " + (i + 1) + " 行缺少合法的 row_checksum（未盖戳入库？）");
            }

            byte[] actualPrevChecksum = row.getPrevChecksum();
            if (actualPrevChecksum == null
                    || !MessageDigest.isEqual(expectedPrev, actualPrevChecksum)) {
                return ChainVerificationReport.mismatch(i, row.getId(),
                        ChainVerificationReport.Reason.PREV_LINK_MISMATCH,
                        hex(expectedPrev), hex(actualPrevChecksum),
                        "第 " + (i + 1) + " 行的 prev_checksum 与上一行的 row_checksum 不接续"
                                + "（链被断开或中间插入了行）");
            }

            byte[] expectedRowChecksum = AuditChecksum.computeRowChecksum(expectedPrev, row);
            if (!MessageDigest.isEqual(expectedRowChecksum, actualRowChecksum)) {
                return ChainVerificationReport.mismatch(i, row.getId(),
                        ChainVerificationReport.Reason.ROW_CHECKSUM_MISMATCH,
                        hex(expectedRowChecksum), hex(actualRowChecksum),
                        "第 " + (i + 1) + " 行的 row_checksum 与按字段重算的结果不一致（行内容被改写）");
            }

            expectedPrev = actualRowChecksum;
        }
        return ChainVerificationReport.valid(rows.size());
    }

    private static String hex(byte[] value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length * 2);
        for (byte b : value) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
