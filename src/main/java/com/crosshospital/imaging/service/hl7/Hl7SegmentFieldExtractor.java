package com.crosshospital.imaging.service.hl7;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.crosshospital.imaging.exception.FieldNotInWhitelistException;

import org.springframework.stereotype.Component;

/**
 * HL7 段位置白名单执行器：业务层只能读取白名单内的字段位置，
 * 命中白名单之外的字段立即抛 {@link FieldNotInWhitelistException}。
 *
 * <p>源主路径对照：源产品 {@code github_ref/dcm4che/dcm4che-hl7/.../HL7Parser.java:55-213}
 * 把 MSH/PID/PV1 全部字段以 SAX 回调交给业务方，不做白名单——这是源作为通用解析器的设计。
 * 本服务为「只读 HL7 触发」的最小权限模型（仅从 HIS ORM^O01 抽 patient_mpi + accession_number
 * 推 fetch 任务），必须在字段读取层就把不在白名单的字段挡住，避免业务方误读到 HIV 标记、
 * 医保结算字段等与待诊流程无关的内容，避免下游把这些字段误写进本院 PACS 报告。
 *
 * <p>白名单字段位置表（与 {@code src/main/resources/hl7/segment-mappings.yml} 的
 * {@code hl7.whitelist} 块对齐）：
 * <ul>
 *   <li>MSH: 1 / 2 / 3 / 4 / 5 / 6 / 7 / 9 / 10 / 12</li>
 *   <li>PID: 3 / 5 / 7</li>
 *   <li>PV1: 2 / 19</li>
 *   <li>OBR: 3 / 4</li>
 *   <li>ORC: 1 / 2</li>
 *   <li>OBX: 3 / 5</li>
 * </ul>
 *
 * <p><b>非白名单字段</b>直接抛异常——不静默置 null，调用方按异常类型决定
 * 是「这条字段不是我们的责任」还是「整条报文要进死信」。
 */
@Component
public class Hl7SegmentFieldExtractor {

    /**
     * 段位置白名单（segment → 允许读取的 field index 集合）。
     * 用 {@link LinkedHashMap} 保持声明顺序，便于排障对照 segment-mappings.yml。
     */
    private static final Map<String, Set<Integer>> WHITELIST = new LinkedHashMap<>();

    static {
        WHITELIST.put("MSH", Set.of(1, 2, 3, 4, 5, 6, 7, 9, 10, 12));
        WHITELIST.put("PID", Set.of(3, 5, 7));
        WHITELIST.put("PV1", Set.of(2, 19));
        WHITELIST.put("OBR", Set.of(3, 4));
        WHITELIST.put("ORC", Set.of(1, 2));
        WHITELIST.put("OBX", Set.of(3, 5));
    }

    /**
     * 从已分段的 HL7 报文中抽取一个白名单字段的值。
     *
     * @param segments  解析后的段列表（每个元素为按字段分隔符切分后的字段数组，
     *                  数组下标 0 为段 ID「MSH/PID/PV1…」）
     * @param segmentId 段 ID（不区分大小写）
     * @param field     字段位置（MSH 从 1 起算，PID/PV1/OBR 同理）
     * @return 该字段的值；该段缺失 / 该 field 越界 / 字段值为空字符串 → 返回 {@code null}
     * @throws FieldNotInWhitelistException 当 {@code (segmentId, field)} 不在白名单内
     */
    public String extract(List<String[]> segments, String segmentId, int field) {
        if (segmentId == null) {
            throw new IllegalArgumentException("segmentId 不能为 null");
        }
        String upper = segmentId.toUpperCase();
        if (field < 1) {
            throw new IllegalArgumentException("field 必须从 1 起算，收到 " + field);
        }
        Set<Integer> allowed = WHITELIST.get(upper);
        if (allowed == null || !allowed.contains(field)) {
            throw new FieldNotInWhitelistException(upper, field);
        }
        for (String[] seg : segments) {
            if (seg == null || seg.length == 0) {
                continue;
            }
            if (upper.equals(seg[0])) {
                if (seg.length <= field) {
                    return null;
                }
                String v = seg[field];
                if (v == null || v.isEmpty()) {
                    return null;
                }
                return v;
            }
        }
        return null;
    }

    /**
     * 测试 / 调试用：返回当前白名单的只读视图。
     * 业务层调用 {@link #extract} 即可，无需直接读白名单。
     */
    public Map<String, Set<Integer>> whitelistView() {
        return Map.copyOf(WHITELIST);
    }

    /**
     * 询问某段某字段是否在白名单内（不抛异常，仅返回 boolean）。
     */
    public boolean isWhitelisted(String segmentId, int field) {
        if (segmentId == null || field < 1) {
            return false;
        }
        Set<Integer> allowed = WHITELIST.get(segmentId.toUpperCase());
        return allowed != null && allowed.contains(field);
    }
}