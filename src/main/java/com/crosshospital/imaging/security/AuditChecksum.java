package com.crosshospital.imaging.security;

import com.crosshospital.imaging.domain.AuditRow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * append-only 审计链的 SHA-256 行校验和（task 24）。
 *
 * <p><b>链式定义</b>：每一行的 {@code row_checksum} 覆盖「前一行 {@code row_checksum}」+
 * 本行全部业务字段；空表的第一行以 32 字节全 0 作为 {@code prev_checksum}。
 * 任何一行被改写（哪怕只动一个字符）都会让它自己和它之后的所有行校验失败，
 * 因此可以回答飞检/内审时最常问的那句：「这批调阅记录中途有没有被人动过」。
 *
 * <p><b>参与哈希的字段（顺序固定，不可调整）</b>：
 * <ol>
 *   <li>{@code prev_checksum} —— 前一行的 {@code row_checksum}（32 字节原始字节）</li>
 *   <li>{@code who} —— 操作人工号 / {@code system}</li>
 *   <li>{@code when_ts} —— 事件时间戳（截断到<b>微秒</b>的 ISO-8601）</li>
 *   <li>{@code from_aet}</li>
 *   <li>{@code to_aet}</li>
 *   <li>{@code study_uid}</li>
 *   <li>{@code sop_class_uid}</li>
 *   <li>{@code transfer_syntax}</li>
 *   <li>{@code retry_count}</li>
 *   <li>{@code status_terminal}</li>
 *   <li>{@code failure_code}</li>
 * </ol>
 *
 * <p><b>编码约定</b>：
 * <ul>
 *   <li>每个字段用 UTF-8 编码，字段之间插入 {@code 0x00} 分隔符 —— AET / UID / 状态标签
 *       都不含 NUL，用它可以避免「{@code from_aet="AB", to_aet=null}」与
 *       「{@code from_aet="A", to_aet="B"}」拼出同一串字节这种跨字段歧义。</li>
 *   <li>{@code null} 字段按空串参与哈希；{@code retry_count} 为 {@code null} 时按 {@code 0}。</li>
 *   <li>时间戳截断到微秒，与 PostgreSQL {@code TIMESTAMPTZ} 的存储精度一致 ——
 *       否则「写入时算出的校验和」与「从库里读回来重算的校验和」会因纳秒被截断而不一致。</li>
 * </ul>
 *
 * <p><b>写库约定</b>：本类只负责算，不负责存。唯一合法写入路径是
 * {@code AuditService.record}（或任何拿到 {@code prev_checksum} 的写入方）调用
 * {@link #stamp(AuditRow, byte[])}，由它一次性补齐时间戳与两个校验和字段，
 * 再交给 {@code AuditRepository.save}（INSERT only）。
 *
 * <p>源主路径证据：dcm4che-net-audit 应用层审计模块 + 链式校验和模式
 * （{@code audit_imaging_fetch} 的 {@code row_checksum}/{@code prev_checksum} 两列由
 * V3 迁移脚本建立，append-only 触发器拒任何 UPDATE/DELETE）。
 */
public final class AuditChecksum {

    /** SHA-256 输出长度（字节）。 */
    public static final int CHECKSUM_LENGTH = 32;

    /** 字段分隔符：DICOM AET / UID / 状态标签均不含 NUL，用它消除跨字段拼接歧义。 */
    private static final byte FIELD_SEPARATOR = 0x00;

    private AuditChecksum() {
    }

    /**
     * 空表首行使用的 {@code prev_checksum}：32 字节全 0。
     *
     * <p>每次调用返回<b>新数组</b>，避免调用方误改共享常量导致整条链失真。
     */
    public static byte[] emptyChecksum() {
        return new byte[CHECKSUM_LENGTH];
    }

    /**
     * 计算一行的 {@code row_checksum}。
     *
     * @param previousChecksum 前一行的 {@code row_checksum}；{@code null} 视为空表首行（32 字节 0）
     * @param row             待计算的行（字段按类注释里的固定顺序参与哈希）
     * @return 32 字节 SHA-256 摘要
     * @throws IllegalArgumentException row 为 {@code null}，或 previousChecksum 长度不是 32
     */
    public static byte[] computeRowChecksum(byte[] previousChecksum, AuditRow row) {
        if (row == null) {
            throw new IllegalArgumentException("AuditRow 不能为空");
        }
        byte[] prev = previousChecksum == null ? emptyChecksum() : previousChecksum;
        if (prev.length != CHECKSUM_LENGTH) {
            throw new IllegalArgumentException(
                    "prev_checksum 长度必须为 " + CHECKSUM_LENGTH + " 字节，实际 " + prev.length);
        }
        MessageDigest md = newDigest();
        md.update(prev);
        for (String field : canonicalFields(row)) {
            md.update(field.getBytes(StandardCharsets.UTF_8));
            md.update(FIELD_SEPARATOR);
        }
        return md.digest();
    }

    /**
     * 给一行盖戳：补齐缺失的时间戳 / 重试次数，并写入 {@code prev_checksum} 与
     * {@code row_checksum}。
     *
     * <p><b>必须在 INSERT 之前调用</b>：{@code row_checksum} 与 {@code prev_checksum}
     * 两列在数据库里都是 {@code NOT NULL}，不盖戳直接 INSERT 会被数据库拒绝。
     *
     * @param row              待盖戳的行（就地修改）
     * @param previousChecksum 前一行校验和；{@code null} 视为空表首行
     * @return 本行 {@code row_checksum}
     */
    public static byte[] stamp(AuditRow row, byte[] previousChecksum) {
        if (row == null) {
            throw new IllegalArgumentException("AuditRow 不能为空");
        }
        if (row.getWhenTs() == null) {
            row.withWhenTs(Instant.now().truncatedTo(ChronoUnit.MICROS));
        }
        if (row.getRetryCount() == null) {
            row.withRetryCount(0);
        }
        byte[] prev = previousChecksum == null ? emptyChecksum() : previousChecksum;
        byte[] rowChecksum = computeRowChecksum(prev, row);
        row.withChecksums(prev, rowChecksum);
        return rowChecksum;
    }

    /**
     * 参与哈希的字段序列（顺序固定）。公开出来是为了让校验器与测试能用同一份定义，
     * 也方便运维在排障时打印原始载荷。
     */
    public static List<String> canonicalFields(AuditRow row) {
        if (row == null) {
            throw new IllegalArgumentException("AuditRow 不能为空");
        }
        return List.of(
                nullToEmpty(row.getWho()),
                canonicalTimestamp(row.getWhenTs()),
                nullToEmpty(row.getFromAet()),
                nullToEmpty(row.getToAet()),
                nullToEmpty(row.getStudyUid()),
                nullToEmpty(row.getSopClassUid()),
                nullToEmpty(row.getTransferSyntax()),
                row.getRetryCount() == null ? "0" : row.getRetryCount().toString(),
                nullToEmpty(row.getStatusTerminal()),
                nullToEmpty(row.getFailureCode()));
    }

    /**
     * 可读的规范化载荷（字段用 {@code |} 分隔），仅用于日志与排障；
     * 真正的摘要按 {@code 0x00} 分隔的字节流计算。
     */
    public static String canonicalPayload(AuditRow row) {
        return String.join("|", canonicalFields(row));
    }

    /**
     * 时间戳规范化：截断到微秒后转 ISO-8601。
     *
     * <p>PostgreSQL {@code TIMESTAMPTZ} 默认精度即微秒；写入前截断可以保证
     * 「插入时算出的校验和」与「从库里读回后重算的校验和」完全一致。
     */
    public static String canonicalTimestamp(Instant instant) {
        if (instant == null) {
            return "";
        }
        return instant.truncatedTo(ChronoUnit.MICROS).toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 规范强制要求支持的算法，理论上不可能走到这里
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
