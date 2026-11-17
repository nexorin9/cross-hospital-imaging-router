package com.crosshospital.imaging.service.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 32 — PACS 缓存 LRU 淘汰与容量账本。 */
@DisplayName("Task 32 — PACS 缓存 LRU")
class PacsCacheManagerTest {

    @Test
    @DisplayName("超容量淘汰最老的一项，字节账目同步减少")
    void evictsOldestWhenOverCapacity() {
        PacsCacheManager cache = new PacsCacheManager(1000L);

        assertEquals(List.of(), cache.put("A", 400));
        assertEquals(List.of(), cache.put("B", 400));
        List<String> evicted = cache.put("C", 400);

        assertEquals(List.of("A"), evicted);
        assertEquals(800L, cache.stats().bytesUsed());
        assertEquals(2, cache.stats().entries());
    }

    @Test
    @DisplayName("访问过的 study 被保留，淘汰最近最少访问的那一项")
    void touchRefreshesLruOrder() {
        PacsCacheManager cache = new PacsCacheManager(1000L);
        cache.put("A", 300);
        cache.put("B", 300);
        cache.put("C", 300);
        assertTrue(cache.touch("A"));

        List<String> evicted = cache.put("D", 300);

        assertEquals(List.of("B"), evicted, "A 被访问过，应保留；B 最久未访问应淘汰");
        assertFalse(cache.contains("B"));
        assertTrue(cache.contains("A"));
    }

    @Test
    @DisplayName("单个 study 就超容量时不删自己，留日志让信息科调配置")
    void singleOversizedStudyIsKept() {
        PacsCacheManager cache = new PacsCacheManager(100L);

        assertEquals(List.of(), cache.put("HUGE", 500));

        assertEquals(1, cache.stats().entries());
        assertEquals(500L, cache.stats().bytesUsed());
    }

    @Test
    @DisplayName("touch 不存在的 study 返回 false，不改账目")
    void touchMissingStudyIsNoOp() {
        PacsCacheManager cache = new PacsCacheManager(1000L);
        cache.put("A", 100);

        assertFalse(cache.touch("MISSING"));
        assertEquals(100L, cache.stats().bytesUsed());
    }

    @Test
    @DisplayName("超过 TTL 的项被清理，账目归零")
    void expiredEntriesAreEvicted() {
        PacsCacheManager cache = new PacsCacheManager(10000L);
        cache.put("OLD", 100);

        List<String> expired = cache.evictExpired(Duration.ofHours(1),
                Instant.now().plus(Duration.ofHours(2)));

        assertEquals(List.of("OLD"), expired);
        assertEquals(0, cache.stats().entries());
        assertEquals(0L, cache.stats().bytesUsed());
    }

    @Test
    @DisplayName("容量上限按配置换算为字节")
    void maxBytesIsConfigured() {
        assertEquals(5120L * 1024 * 1024,
                new PacsCacheManager(5120L * 1024 * 1024).stats().maxBytes());
    }
}
