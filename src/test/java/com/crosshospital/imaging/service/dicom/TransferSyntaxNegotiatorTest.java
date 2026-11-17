package com.crosshospital.imaging.service.dicom;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TransferSyntaxNegotiator} 单元测试。
 *
 * <p>核心覆盖：
 * <ol>
 *   <li>完全交集：两端列出全部相同 UID → 返回完整列表</li>
 *   <li>部分交集：远端多声明、本地仅部分支持 → 仅返回公共部分</li>
 *   <li>无交集：两端 UID 完全错开 → 返回空列表，调用方应视为 TRANSFER_SYNTAX_EMPTY</li>
 *   <li>边界：null / 空 / 单元素 / 去重保序</li>
 * </ol>
 */
public class TransferSyntaxNegotiatorTest {

    private final TransferSyntaxNegotiator negotiator = new TransferSyntaxNegotiator();

    /** ① 完全交集 — 两端列出相同 4 个 UID → 完整返回。 */
    @Test
    void negotiateReturnsFullIntersectionWhenBothListsIdentical() {
        List<String> peer = Arrays.asList(
                TransferSyntaxNegotiator.EXPLICIT_VR_LE,
                TransferSyntaxNegotiator.IMPLICIT_VR_LE,
                TransferSyntaxNegotiator.JPEG_LOSSLESS,
                TransferSyntaxNegotiator.JPEG_2000_LOSSLESS);
        List<String> local = Arrays.asList(
                TransferSyntaxNegotiator.EXPLICIT_VR_LE,
                TransferSyntaxNegotiator.IMPLICIT_VR_LE,
                TransferSyntaxNegotiator.JPEG_LOSSLESS,
                TransferSyntaxNegotiator.JPEG_2000_LOSSLESS);

        List<String> agreed = negotiator.negotiate(peer, local);

        assertEquals(4, agreed.size());
        assertEquals(TransferSyntaxNegotiator.EXPLICIT_VR_LE, agreed.get(0));
        assertEquals(TransferSyntaxNegotiator.IMPLICIT_VR_LE, agreed.get(1));
        assertEquals(TransferSyntaxNegotiator.JPEG_LOSSLESS, agreed.get(2));
        assertEquals(TransferSyntaxNegotiator.JPEG_2000_LOSSLESS, agreed.get(3));
        assertTrue(negotiator.isNegotiated(agreed));
    }

    /** ② 部分交集 — 远端 3 个，本地支持其中 2 个 → 仅返回 2 个（保序按远端声明顺序）。 */
    @Test
    void negotiateReturnsPartialIntersectionPreservingPeerOrder() {
        List<String> peer = Arrays.asList(
                TransferSyntaxNegotiator.JPEG_2000_LOSSLESS,  // 本地不支持
                TransferSyntaxNegotiator.EXPLICIT_VR_LE,      // 本地支持
                TransferSyntaxNegotiator.JPEG_LOSSLESS        // 本地支持
        );
        List<String> local = Arrays.asList(
                TransferSyntaxNegotiator.EXPLICIT_VR_LE,
                TransferSyntaxNegotiator.IMPLICIT_VR_LE,
                TransferSyntaxNegotiator.JPEG_LOSSLESS);

        List<String> agreed = negotiator.negotiate(peer, local);

        // 远端顺序：JPEG_2000_LOSSLESS → EXPLICIT_VR_LE → JPEG_LOSSLESS
        // 公共（按远端顺序保序）：EXPLICIT_VR_LE → JPEG_LOSSLESS
        assertEquals(2, agreed.size());
        assertEquals(TransferSyntaxNegotiator.EXPLICIT_VR_LE, agreed.get(0));
        assertEquals(TransferSyntaxNegotiator.JPEG_LOSSLESS, agreed.get(1));
        assertTrue(negotiator.isNegotiated(agreed));
    }

    /** ③ 无交集 — 两端 UID 完全不同 → 空列表（调用方视为 TRANSFER_SYNTAX_EMPTY）。 */
    @Test
    void negotiateReturnsEmptyWhenNoIntersection() {
        List<String> peer = Arrays.asList("1.2.999.999.1", "1.2.999.999.2");
        List<String> local = Arrays.asList(
                TransferSyntaxNegotiator.EXPLICIT_VR_LE,
                TransferSyntaxNegotiator.IMPLICIT_VR_LE);

        List<String> agreed = negotiator.negotiate(peer, local);

        assertTrue(agreed.isEmpty());
        assertFalse(negotiator.isNegotiated(agreed));
    }

    /** ④ 边界 — null / 空 / 单元素 / 去重保序。 */
    @Test
    void negotiateEdgeCasesOnNullEmptyAndDedup() {
        // null 入参
        assertTrue(negotiator.negotiate(null, Arrays.asList("1.2.1")).isEmpty());
        assertTrue(negotiator.negotiate(Arrays.asList("1.2.1"), null).isEmpty());
        assertTrue(negotiator.negotiate(null, null).isEmpty());

        // 空列表
        assertTrue(negotiator.negotiate(Collections.emptyList(), Arrays.asList("1.2.1")).isEmpty());
        assertTrue(negotiator.negotiate(Arrays.asList("1.2.1"), Collections.emptyList()).isEmpty());
        assertTrue(negotiator.negotiate(Collections.emptyList(), Collections.emptyList()).isEmpty());

        // 单元素
        List<String> single = negotiator.negotiate(
                Arrays.asList(TransferSyntaxNegotiator.EXPLICIT_VR_LE),
                Arrays.asList(TransferSyntaxNegotiator.EXPLICIT_VR_LE));
        assertEquals(1, single.size());
        assertEquals(TransferSyntaxNegotiator.EXPLICIT_VR_LE, single.get(0));

        // 去重：远端重复声明同一 UID → 仅保留一次（保远端首现位置）
        List<String> dedup = negotiator.negotiate(
                Arrays.asList(
                        TransferSyntaxNegotiator.EXPLICIT_VR_LE,
                        TransferSyntaxNegotiator.EXPLICIT_VR_LE,  // 重复
                        TransferSyntaxNegotiator.IMPLICIT_VR_LE),
                Arrays.asList(
                        TransferSyntaxNegotiator.EXPLICIT_VR_LE,
                        TransferSyntaxNegotiator.IMPLICIT_VR_LE));
        assertEquals(2, dedup.size());
        assertEquals(TransferSyntaxNegotiator.EXPLICIT_VR_LE, dedup.get(0));
        assertEquals(TransferSyntaxNegotiator.IMPLICIT_VR_LE, dedup.get(1));
    }

    /** ⑤ isValidTransformatSyntax — UID 格式校验（点分数字段）。 */
    @Test
    void isValidTransferSyntaxAcceptsStandardUidsAndRejectsGarbage() {
        // 合法 DICOM UID（点分十进制数字段）
        assertTrue(negotiator.isValidTransferSyntax(TransferSyntaxNegotiator.EXPLICIT_VR_LE));
        assertTrue(negotiator.isValidTransferSyntax("1.2.840.10008.1.2"));
        // 非法：null / 空 / 短
        assertFalse(negotiator.isValidTransferSyntax(null));
        assertFalse(negotiator.isValidTransferSyntax(""));
        assertFalse(negotiator.isValidTransferSyntax("1.2"));  // 单段不合法
        // 非法：含字母
        assertFalse(negotiator.isValidTransferSyntax("1.2.840.abc"));
        assertFalse(negotiator.isValidTransferSyntax("not-a-uid"));
        // 非法：连续点 / 末尾点
        assertFalse(negotiator.isValidTransferSyntax("1..2"));
        assertFalse(negotiator.isValidTransferSyntax("1.2."));
    }
}