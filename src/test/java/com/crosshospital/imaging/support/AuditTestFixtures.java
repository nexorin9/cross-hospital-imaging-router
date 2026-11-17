package com.crosshospital.imaging.support;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.repository.AuditRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 审计链测试夹具（task 24）。
 *
 * <p>提供三样东西：
 * <ol>
 *   <li>{@link #referenceChecksum(byte[], AuditRow)} —— <b>独立于被测实现</b>的
 *       参考实现：直接按「prev_checksum + 10 个字段（0x00 分隔）」的公式用
 *       {@code MessageDigest} 拼一遍。用它做期望值，测的才是「实现是否等于规格」，
 *       而不是「实现是否等于自己」。</li>
 *   <li>{@link InMemoryAuditRepository} —— 内存版 append-only 审计表，
 *       按写入顺序累积，{@code findLatestRowChecksums} 返回最后一行的校验和。</li>
 *   <li>{@link #assignId(AuditRow, long)} —— 反射写入自增 id（实体不暴露 setter）。</li>
 * </ol>
 */
public final class AuditTestFixtures {

    private AuditTestFixtures() {
    }

    /**
     * 参考实现：按规格独立重算一行校验和。
     *
     * @param prev 前一行校验和（32 字节）
     * @param row  审计行
     * @return 期望的 SHA-256 摘要
     */
    public static byte[] referenceChecksum(byte[] prev, AuditRow row) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prev);
            for (String field : referenceFields(row)) {
                md.update(field.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /**
     * 参考实现使用的字段序列（顺序固定，与规格一一对应）。
     */
    public static List<String> referenceFields(AuditRow row) {
        return List.of(
                nz(row.getWho()),
                row.getWhenTs() == null ? "" : row.getWhenTs().truncatedTo(ChronoUnit.MICROS).toString(),
                nz(row.getFromAet()),
                nz(row.getToAet()),
                nz(row.getStudyUid()),
                nz(row.getSopClassUid()),
                nz(row.getTransferSyntax()),
                row.getRetryCount() == null ? "0" : row.getRetryCount().toString(),
                nz(row.getStatusTerminal()),
                nz(row.getFailureCode()));
    }

    /** 构造一行审计（时间戳可指定；null 表示由实现补齐）。 */
    public static AuditRow row(String who, Instant whenTs, String fromAet, String toAet,
                               String studyUid, String statusTerminal, String failureCode,
                               int retryCount) {
        AuditRow row = new AuditRow()
                .withWho(who)
                .withFromAet(fromAet)
                .withToAet(toAet)
                .withStudyUid(studyUid)
                .withSopClassUid(null)
                .withTransferSyntax(null)
                .withRetryCount(retryCount)
                .withStatusTerminal(statusTerminal)
                .withFailureCode(failureCode);
        if (whenTs != null) {
            row.withWhenTs(whenTs);
        }
        return row;
    }

    /** 反射写入自增 id（{@code AuditRow} 不暴露 setter）。 */
    public static void assignId(AuditRow row, long id) {
        try {
            Field f = AuditRow.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(row, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法给 AuditRow 赋 id", e);
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /**
     * 内存版 append-only 审计表：只支持 INSERT（{@code save}）+ 读，
     * 与 {@code AuditRepository} 的真实语义一致。
     */
    public static class InMemoryAuditRepository implements AuditRepository {

        private final List<AuditRow> rows = new ArrayList<>();
        private long nextId = 1L;

        @Override
        public AuditRow save(AuditRow row) {
            if (row.getId() == null) {
                assignId(row, nextId++);
            }
            rows.add(row);
            return row;
        }

        @Override
        public List<byte[]> findLatestRowChecksums(Pageable pageable) {
            if (rows.isEmpty()) {
                return List.of();
            }
            byte[] last = rows.get(rows.size() - 1).getRowChecksum();
            return last == null ? List.of() : List.of(last);
        }

        @Override
        public Optional<AuditRow> findById(Long id) {
            return rows.stream().filter(r -> r.getId().equals(id)).findFirst();
        }

        @Override
        public List<AuditRow> findLatestRows(Pageable pageable) {
            if (rows.isEmpty()) {
                return List.of();
            }
            List<AuditRow> sorted = new ArrayList<>(rows);
            sorted.sort((a, b) -> Long.compare(b.getId(), a.getId()));
            return sorted.subList(0, Math.min(pageable.getPageSize(), sorted.size()));
        }

        @Override
        public Page<AuditRow> findByTimeWindow(Instant fromTs, Instant toTs, Pageable pageable) {
            List<AuditRow> filtered = new ArrayList<>();
            for (AuditRow r : rows) {
                if (!inWindow(r, fromTs, toTs)) continue;
                filtered.add(r);
            }
            return paginate(filtered, pageable);
        }

        @Override
        public List<AuditRow> findByFromAet(String fromAet, Pageable pageable) {
            return rows.stream()
                    .filter(r -> fromAet.equals(r.getFromAet()))
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                    .limit(pageable.getPageSize())
                    .toList();
        }

        @Override
        public List<AuditRow> findByToAet(String toAet, Pageable pageable) {
            return rows.stream()
                    .filter(r -> toAet.equals(r.getToAet()))
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                    .limit(pageable.getPageSize())
                    .toList();
        }

        @Override
        public List<AuditRow> findByStatusTerminal(String statusTerminal, Pageable pageable) {
            return rows.stream()
                    .filter(r -> statusTerminal.equals(r.getStatusTerminal()))
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                    .limit(pageable.getPageSize())
                    .toList();
        }

        @Override
        public Page<AuditRow> queryByTimeWindow(Instant fromTs, Instant toTs, String fromAet,
                                                String toAet, String statusTerminal, String who,
                                                Pageable pageable) {
            List<AuditRow> filtered = new ArrayList<>();
            for (AuditRow r : rows) {
                if (!inWindow(r, fromTs, toTs)) continue;
                if (fromAet != null && !fromAet.equals(r.getFromAet())) continue;
                if (toAet != null && !toAet.equals(r.getToAet())) continue;
                if (statusTerminal != null && !statusTerminal.equals(r.getStatusTerminal())) continue;
                if (who != null && !who.equals(r.getWho())) continue;
                filtered.add(r);
            }
            return paginate(filtered, pageable);
        }

        private boolean inWindow(AuditRow r, Instant fromTs, Instant toTs) {
            if (fromTs != null && r.getWhenTs().isBefore(fromTs)) return false;
            return toTs == null || r.getWhenTs().isBefore(toTs);
        }

        /** 按时间倒序切页（与真实库 {@code order by when_ts desc, id desc} 一致）。 */
        private Page<AuditRow> paginate(List<AuditRow> filtered, Pageable pageable) {
            List<AuditRow> sorted = new ArrayList<>(filtered);
            sorted.sort((a, b) -> {
                int byTime = b.getWhenTs().compareTo(a.getWhenTs());
                return byTime != 0 ? byTime : Long.compare(b.getId(), a.getId());
            });
            int from = (int) Math.min(pageable.getOffset(), sorted.size());
            int to = (int) Math.min(from + pageable.getPageSize(), sorted.size());
            return new PageImpl<>(sorted.subList(from, to), pageable, sorted.size());
        }

        @Override
        public long countByTimeWindow(Instant fromTs, Instant toTs) {
            long count = 0;
            for (AuditRow r : rows) {
                if (fromTs != null && r.getWhenTs().isBefore(fromTs)) continue;
                if (toTs != null && !r.getWhenTs().isBefore(toTs)) continue;
                count++;
            }
            return count;
        }

        /** 已写入的全部行（按写入顺序）。 */
        public List<AuditRow> all() {
            return List.copyOf(rows);
        }
    }
}
