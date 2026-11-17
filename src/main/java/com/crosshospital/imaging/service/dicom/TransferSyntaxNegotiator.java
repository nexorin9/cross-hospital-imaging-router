package com.crosshospital.imaging.service.dicom;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 传输语法（Transfer Syntax）协商器。
 *
 * <p>从外院 PACS 声明的传输语法集合与本服务配置的 {@code dicom.supportedTransferSyntaxes}
 * 取交集，得出握手时双方实际可用的传输语法列表。空交集对应
 * {@code HandshakeException.FailureCode.TRANSFER_SYNTAX_EMPTY}（由调用方决定短路）。
 *
 * <p>对应源仓库 dcm4che：
 * <ul>
 *   <li>{@code Connection.java} — DICOM upper-layer 关联建立时会按 presentContext 协商
 *       传输语法；本类提前在协议层做一次预校验，避免 TCP 探活通过却后续 C-STORE / C-FIND
 *       因传输语法不兼容而失败。</li>
 *   <li>{@code CompatibleConnection.java} — 协议族兼容性容器的核心语义之一就是
 *       「本端 + 远端传输语法有交集」。本类把这一约束显式化、可单元测试。</li>
 * </ul>
 *
 * <p><b>算法</b>：保序去重遍历远端列表，命中本地列表即加入结果。空列表视为"无声明"，
 * 应当被解释为"未声明传输语法（协议层不兼容）"而非"全部支持"——这是与源仓库语义一致的约定：
 * 显式列出传输语法是 PACS 配置规范（如 DICOM Conformance Statement）。
 *
 * <p>本类是纯函数式（无副作用），便于在 {@link CompatibleHandshakeService} 中无状态复用，
 * 并在单元测试中独立验证边界。
 */
@Service
@Profile("!test")
public class TransferSyntaxNegotiator {

    /** DICOM Explicit VR Little Endian（几乎所有 PACS 都支持，作 fallback）。 */
    public static final String EXPLICIT_VR_LE = "1.2.840.10008.1.2.1";

    /** DICOM Implicit VR Little Endian（兼容性最广）。 */
    public static final String IMPLICIT_VR_LE = "1.2.840.10008.1.2";

    /** JPEG Lossless。 */
    public static final String JPEG_LOSSLESS = "1.2.840.10008.1.2.4.70";

    /** JPEG 2000 Lossless。 */
    public static final String JPEG_2000_LOSSLESS = "1.2.840.10008.1.2.4.90";

    /**
     * 取两端传输语法列表的交集（保序、去重）。
     *
     * @param peerTransferSyntaxes  远端 PACS 声明的传输语法 UID（来自 {@code peer.transfer_syntaxes} JSONB）
     * @param localTransferSyntaxes 本服务支持的传输语法 UID（来自 {@code dicom.supportedTransferSyntaxes}）
     * @return 双方共同支持的传输语法 UID 列表；空列表表示无交集，调用方应视为
     *         {@link com.crosshospital.imaging.exception.HandshakeException.FailureCode#TRANSFER_SYNTAX_EMPTY}
     */
    public List<String> negotiate(List<String> peerTransferSyntaxes, List<String> localTransferSyntaxes) {
        if (peerTransferSyntaxes == null || peerTransferSyntaxes.isEmpty()
                || localTransferSyntaxes == null || localTransferSyntaxes.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> localSet = new LinkedHashSet<>(localTransferSyntaxes);
        List<String> agreed = new ArrayList<>();
        for (String peerTs : peerTransferSyntaxes) {
            if (peerTs != null && localSet.contains(peerTs) && !agreed.contains(peerTs)) {
                agreed.add(peerTs);
            }
        }
        return agreed;
    }

    /**
     * 判断协商结果是否非空（语法糖；调用方喜欢写 {@code isNegotiated(result)} 比 {@code!result.isEmpty()} 更好读）。
     *
     * @return true 表示协商成功
     */
    public boolean isNegotiated(List<String> agreedTransferSyntaxes) {
        return agreedTransferSyntaxes != null && !agreedTransferSyntaxes.isEmpty();
    }

    /**
     * 判断给定的传输语法 UID 是否在白名单内（DICOM 标准 UID 都是点分十进制数字串）。
     *
     * <p>本方法用于 {@link com.crosshospital.imaging.service.InputValidator} 入库校验；
     * 它是纯语法检查（不查注册表）——只要符合 UID 格式（点分十进制，每个数字段 ≥0 且 ≤2^32-1）
     * 即视为合法。语义合法性（如是否在 DICOM 标准注册表内）由 DICOM upper-layer 关联建立时确认。
     */
    public boolean isValidTransferSyntax(String uid) {
        if (uid == null || uid.isBlank()) return false;
        String[] parts = uid.split("\\.");
        // DICOM UID 至少 3 段（1.<国家>.<组织>.<...>），2 段如 "1.2" 是根 OID 弧而非合法 DICOM UID
        if (parts.length < 3) return false;
        for (String p : parts) {
            if (p.isEmpty()) return false;
            // 段必须是十进制数字串
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') return false;
            }
        }
        return true;
    }
}