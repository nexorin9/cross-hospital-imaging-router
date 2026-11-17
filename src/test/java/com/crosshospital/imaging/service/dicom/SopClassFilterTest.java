package com.crosshospital.imaging.service.dicom;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SopClassFilter} 单元测试。
 *
 * <p>核心覆盖：
 * <ol>
 *   <li>全交集：远端声明 3 个 SOP Class，本地均支持</li>
 *   <li>部分交集：远端 4 个，本地仅支持 2 个 → 仅返回公共 2 个</li>
 *   <li>无交集：远端 & 本地 UID 错开 → 空列表（调用方视为 SOP_CLASS_UNSUPPORTED）</li>
 *   <li>边界：null / 空 / 去重</li>
 * </ol>
 */
public class SopClassFilterTest {

    private final SopClassFilter filter = new SopClassFilter();

    /** ① 全交集 — 两端列出相同 SOP Class → 完整返回。 */
    @Test
    void filterReturnsFullOverlapWhenAllPeerClassesAreLocallySupported() {
        List<String> peer = Arrays.asList(
                SopClassFilter.CT_IMAGE_STORAGE,
                SopClassFilter.MR_IMAGE_STORAGE,
                SopClassFilter.STUDY_ROOT_QUERY_FIND);
        List<String> our = Arrays.asList(
                SopClassFilter.CT_IMAGE_STORAGE,
                SopClassFilter.MR_IMAGE_STORAGE,
                SopClassFilter.STUDY_ROOT_QUERY_FIND,
                SopClassFilter.STUDY_ROOT_QUERY_MOVE);

        List<String> common = filter.filterByPresentContexts(peer, our);

        assertEquals(3, common.size());
        assertTrue(common.contains(SopClassFilter.CT_IMAGE_STORAGE));
        assertTrue(common.contains(SopClassFilter.MR_IMAGE_STORAGE));
        assertTrue(common.contains(SopClassFilter.STUDY_ROOT_QUERY_FIND));
        assertTrue(filter.hasOverlap(common));
    }

    /** ② 部分交集 — 远端声明 4 个，本地仅支持 CT + MR → 仅返回 2 个（保序按远端声明）。 */
    @Test
    void filterReturnsPartialOverlapPreservingPeerOrder() {
        List<String> peer = Arrays.asList(
                SopClassFilter.STUDY_ROOT_QUERY_FIND,  // 本地支持
                "1.2.840.10008.5.1.4.1.1.66",          // 本地不支持（Query/Retrieve 实为引用类）
                SopClassFilter.CT_IMAGE_STORAGE,       // 本地支持
                "1.2.999.999.999.999"                  // 本地不支持（编造 UID）
        );
        List<String> our = Arrays.asList(
                SopClassFilter.CT_IMAGE_STORAGE,
                SopClassFilter.MR_IMAGE_STORAGE,
                SopClassFilter.STUDY_ROOT_QUERY_FIND,
                SopClassFilter.STUDY_ROOT_QUERY_MOVE);

        List<String> common = filter.filterByPresentContexts(peer, our);

        // 远端顺序：QUERY_FIND → 1.66 → CT_IMAGE_STORAGE → 999.999
        // 公共（按远端顺序）：QUERY_FIND → CT_IMAGE_STORAGE
        assertEquals(2, common.size());
        assertEquals(SopClassFilter.STUDY_ROOT_QUERY_FIND, common.get(0));
        assertEquals(SopClassFilter.CT_IMAGE_STORAGE, common.get(1));
        assertTrue(filter.hasOverlap(common));
    }

    /** ③ 无交集 — 两端 SOP Class 完全不同 → 空列表（调用方视为 SOP_CLASS_UNSUPPORTED）。 */
    @Test
    void filterReturnsEmptyWhenNoOverlap() {
        List<String> peer = Arrays.asList("1.2.999.001", "1.2.999.002");
        List<String> our = Arrays.asList(
                SopClassFilter.CT_IMAGE_STORAGE,
                SopClassFilter.MR_IMAGE_STORAGE);

        List<String> common = filter.filterByPresentContexts(peer, our);

        assertTrue(common.isEmpty());
        assertFalse(filter.hasOverlap(common));
    }

    /** ④ 边界 — null / 空 / 去重。 */
    @Test
    void filterEdgeCasesOnNullEmptyAndDedup() {
        // null 入参
        assertTrue(filter.filterByPresentContexts(null, Arrays.asList(SopClassFilter.CT_IMAGE_STORAGE)).isEmpty());
        assertTrue(filter.filterByPresentContexts(Arrays.asList(SopClassFilter.CT_IMAGE_STORAGE), null).isEmpty());
        assertTrue(filter.filterByPresentContexts(null, null).isEmpty());

        // 空列表
        assertTrue(filter.filterByPresentContexts(Collections.emptyList(),
                Arrays.asList(SopClassFilter.CT_IMAGE_STORAGE)).isEmpty());
        assertTrue(filter.filterByPresentContexts(Arrays.asList(SopClassFilter.CT_IMAGE_STORAGE),
                Collections.emptyList()).isEmpty());

        // 去重：远端重复声明
        List<String> dedup = filter.filterByPresentContexts(
                Arrays.asList(
                        SopClassFilter.CT_IMAGE_STORAGE,
                        SopClassFilter.CT_IMAGE_STORAGE,  // 重复
                        SopClassFilter.MR_IMAGE_STORAGE),
                Arrays.asList(
                        SopClassFilter.CT_IMAGE_STORAGE,
                        SopClassFilter.MR_IMAGE_STORAGE));
        assertEquals(2, dedup.size());
        assertEquals(SopClassFilter.CT_IMAGE_STORAGE, dedup.get(0));
        assertEquals(SopClassFilter.MR_IMAGE_STORAGE, dedup.get(1));
    }

    /** ⑤ isValidSopClass — SOP Class UID 格式校验。 */
    @Test
    void isValidSopClassAcceptsStandardUidsAndRejectsGarbage() {
        assertTrue(filter.isValidSopClass(SopClassFilter.CT_IMAGE_STORAGE));
        assertTrue(filter.isValidSopClass(SopClassFilter.STUDY_ROOT_QUERY_MOVE));
        assertFalse(filter.isValidSopClass(null));
        assertFalse(filter.isValidSopClass(""));
        assertFalse(filter.isValidSopClass("not-a-uid"));
        assertFalse(filter.isValidSopClass("1.2.840.abc"));
    }
}