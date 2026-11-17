package com.crosshospital.imaging.scheduled;

import com.crosshospital.imaging.config.CacheConfigProps;
import com.crosshospital.imaging.service.cache.PacsCacheManager;
import com.crosshospital.imaging.service.cache.PacsEvictionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 32 — 过期巡检逐项对接 PACS 存储侧淘汰端口。 */
@DisplayName("Task 32 — PACS 缓存过期巡检")
class CacheEvictionSchedulerTest {

    private PacsCacheManager cache;
    private CacheConfigProps props;
    private final List<String> deleted = new ArrayList<>();
    private CacheEvictionScheduler scheduler;

    @BeforeEach
    void setUp() {
        cache = new PacsCacheManager(10000L);
        props = new CacheConfigProps();
        props.setTtlHours(1);
        scheduler = new CacheEvictionScheduler(cache, deleted::add, props);
    }

    @Test
    @DisplayName("过期项逐项交给 PACS 淘汰端口，账本清零")
    void expiredStudiesAreDeletedThroughPort() {
        cache.put("OLD-1", 100);
        cache.put("OLD-2", 200);

        int evicted = scheduler.evictExpiredAt(Instant.now().plus(java.time.Duration.ofHours(2)));

        assertEquals(2, evicted);
        assertEquals(2, deleted.size());
        assertTrue(deleted.containsAll(List.of("OLD-1", "OLD-2")));
        assertEquals(0, cache.stats().entries());
    }

    @Test
    @DisplayName("未过期项不淘汰")
    void freshStudiesAreKept() {
        cache.put("FRESH", 100);

        assertEquals(0, scheduler.evictExpiredAt(Instant.now()));
        assertTrue(deleted.isEmpty());
        assertTrue(cache.contains("FRESH"));
    }

    @Test
    @DisplayName("单个端口实现抛异常不打断其余项")
    void portFailureDoesNotAbortTheRound() {
        cache.put("OLD-1", 100);
        cache.put("OLD-2", 100);
        PacsEvictionPort flaky = studyUid -> {
            deleted.add(studyUid);
            if ("OLD-1".equals(studyUid)) {
                throw new IllegalStateException("厂商接口暂时不可用");
            }
        };
        CacheEvictionScheduler flakyScheduler =
                new CacheEvictionScheduler(cache, flaky, props);

        int evicted = flakyScheduler.evictExpiredAt(
                Instant.now().plus(java.time.Duration.ofHours(2)));

        assertEquals(2, evicted);
        assertEquals(2, deleted.size(), "第一项失败也要继续处理第二项");
    }
}
