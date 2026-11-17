package com.crosshospital.imaging.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.crosshospital.imaging.config.DlqConfigProps;
import com.crosshospital.imaging.domain.DlqEntry;
import com.crosshospital.imaging.repository.DlqRepository;
import com.crosshospital.imaging.scheduled.DlqRetentionCleaner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 22 — 死信保留期清理测试。
 *
 * <p>核心不变量：**未处置的死信永远不被自动清理**。清掉一条还没人管的失败，
 * 等于把「外院挂了」这件事从系统里悄悄抹掉 —— 那是排障链路上最不能接受的一种丢数据。
 */
@DisplayName("Task 22 — 死信保留期清理（只清已处置且过期）")
class DlqRetentionCleanerTest {

    private final DlqRepository repository = mock(DlqRepository.class);

    private static DlqConfigProps props(int retentionDays) {
        DlqConfigProps props = new DlqConfigProps();
        props.setRetentionDays(retentionDays);
        return props;
    }

    @Test
    @DisplayName("已处置且超过保留期的死信被删除")
    void expiredResolvedRowsAreDeleted() {
        DlqEntry a = new DlqEntry();
        a.setId(1L);
        a.setResolvedAt(Instant.parse("2026-09-01T00:00:00Z"));
        DlqEntry b = new DlqEntry();
        b.setId(2L);
        b.setResolvedAt(Instant.parse("2026-09-15T00:00:00Z"));
        when(repository.findResolvedBefore(any(Instant.class))).thenReturn(List.of(a, b));

        int removed = new DlqRetentionCleaner(repository, props(30)).cleanupExpired();

        assertThat(removed).isEqualTo(2);
        verify(repository, times(1)).deleteAll(List.of(a, b));
    }

    @Test
    @DisplayName("未处置的死信不进清理集合（findResolvedBefore 只捞已处置且过期的行）")
    void unresolvedRowsAreNeverCollected() {
        when(repository.findResolvedBefore(any(Instant.class))).thenReturn(List.of());

        int removed = new DlqRetentionCleaner(repository, props(30)).cleanupExpired();

        assertThat(removed).isZero();
        verify(repository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("没有过期行时不做任何删除")
    void nothingExpiredMeansNoDeletion() {
        when(repository.findResolvedBefore(any(Instant.class))).thenReturn(List.of());

        new DlqRetentionCleaner(repository, props(30)).cleanup();

        verify(repository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("保留天数配 0 → 关闭自动清理（一条都不删）")
    void zeroRetentionDisablesCleanup() {
        int removed = new DlqRetentionCleaner(repository, props(0)).cleanupExpired();

        assertThat(removed).isZero();
        verify(repository, never()).findResolvedBefore(any(Instant.class));
        verify(repository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("保留期内的已处置死信不动（清理阈值 = now - retentionDays）")
    void rowsWithinRetentionAreKept() {
        when(repository.findResolvedBefore(any(Instant.class))).thenReturn(List.of());

        new DlqRetentionCleaner(repository, props(30)).cleanupExpired();

        verify(repository, times(1)).findResolvedBefore(any(Instant.class));
        verify(repository, never()).deleteAll(any());
    }
}
