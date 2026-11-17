package com.crosshospital.imaging.service.dicom;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * SOP Class（Service-Object Pair Class）白名单过滤。
 *
 * <p>外院 PACS 在关联建立时会通过 {@code A-ASSOCIATE-RQ} 的 presentContext 列表
 * 声明它支持的存储 / 查询 / 检索 SOP Class UID。本服务需要在握手阶段确认两端至少
 * 有 1 个共同 SOP Class，否则即使 TCP / TLS / 传输语法全通过，后续 C-FIND / C-MOVE /
 * C-STORE 也无从发起。
 *
 * <p>对应源仓库 dcm4che：
 * <ul>
 *   <li>{@code Connection.java} — DICOM upper-layer presentContext 协商字段</li>
 *   <li>{@code CompatibleConnection.java} — 兼容性容器把「共同 SOP Class」作为关联
 *       建立的硬约束之一</li>
 * </ul>
 *
 * <p><b>设计要点</b>：
 * <ul>
 *   <li>本类只做白名单过滤（求交集），不拒绝任何合法 UID——拒绝逻辑（握手协议校验）
 *       由 {@link CompatibleHandshakeService} 在拿到空结果时按
 *       {@code SOP_CLASS_UNSUPPORTED} 失败码返回。</li>
 *   <li>保序去重：按远端声明顺序保留，方便审计 + 日志可读。</li>
 *   <li>空列表视为"无声明"——与 {@link TransferSyntaxNegotiator} 语义对齐：
 *       显式声明是 PACS 协议规范，未声明等同于不兼容。</li>
 * </ul>
 */
@Service
@Profile("!test")
public class SopClassFilter {

    /** CT Image Storage。 */
    public static final String CT_IMAGE_STORAGE = "1.2.840.10008.5.1.4.1.1.2";

    /** MR Image Storage。 */
    public static final String MR_IMAGE_STORAGE = "1.2.840.10008.5.1.4.1.1.4";

    /** CR Image Storage。 */
    public static final String CR_IMAGE_STORAGE = "1.2.840.10008.5.1.4.1.1.1";

    /** US Image Storage。 */
    public static final String US_IMAGE_STORAGE = "1.2.840.10008.5.1.4.1.1.6.1";

    /** Secondary Capture（关键互认场景：外院扫描的胶片扫描件）。 */
    public static final String SECONDARY_CAPTURE = "1.2.840.10008.5.1.4.1.1.7";

    /** Study Root Query/Retrieve - FIND（用于 C-FIND 询 StudyInstanceUID）。 */
    public static final String STUDY_ROOT_QUERY_FIND = "1.2.840.10008.5.1.4.1.2.2.1";

    /** Study Root Query/Retrieve - MOVE（用于 C-MOVE 拉回影像）。 */
    public static final String STUDY_ROOT_QUERY_MOVE = "1.2.840.10008.5.1.4.1.2.2.2";

    /**
     * 按 presentContexts 过滤 SOP Class——即求远端声明集合与本服务支持集合的交集。
     *
     * @param peerSopClasses 远端 PACS 声明的 SOP Class UID 列表（来自 {@code peer.present_contexts} JSONB）
     * @param ourSopClasses  本服务支持的 SOP Class UID 列表（来自 {@code dicom.supportedSopClasses}）
     * @return 双方共同支持的 SOP Class UID 列表（保序、去重）；空列表表示无交集，
     *         调用方应视为
     *         {@link com.crosshospital.imaging.exception.HandshakeException.FailureCode#SOP_CLASS_UNSUPPORTED}
     */
    public List<String> filterByPresentContexts(List<String> peerSopClasses, List<String> ourSopClasses) {
        if (peerSopClasses == null || peerSopClasses.isEmpty()
                || ourSopClasses == null || ourSopClasses.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> ourSet = new LinkedHashSet<>(ourSopClasses);
        List<String> common = new ArrayList<>();
        for (String peerSop : peerSopClasses) {
            if (peerSop != null && ourSet.contains(peerSop) && !common.contains(peerSop)) {
                common.add(peerSop);
            }
        }
        return common;
    }

    /**
     * 判断过滤结果是否非空（语法糖）。
     *
     * @return true 表示至少有一个共同 SOP Class，握手可继续
     */
    public boolean hasOverlap(List<String> commonSopClasses) {
        return commonSopClasses != null && !commonSopClasses.isEmpty();
    }

    /**
     * 判断给定的 SOP Class UID 是否格式合法（点分数字段）。
     *
     * <p>仅做语法校验。语义合法性（是否在 DICOM 标准注册表内）由 DICOM upper-layer
     * 关联建立时校验；本类只拦截明显错误（缺失 / 非数字段），降低入库脏数据风险。
     */
    public boolean isValidSopClass(String uid) {
        if (uid == null || uid.isBlank()) return false;
        String[] parts = uid.split("\\.");
        // DICOM UID 至少 3 段（1.<国家>.<组织>.<...>），2 段如 "1.2" 是根 OID 弧而非合法 SOP Class UID
        if (parts.length < 3) return false;
        for (String p : parts) {
            if (p.isEmpty()) return false;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') return false;
            }
        }
        return true;
    }
}