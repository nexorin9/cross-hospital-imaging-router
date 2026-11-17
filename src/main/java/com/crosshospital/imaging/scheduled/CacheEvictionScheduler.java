package com.crosshospital.imaging.scheduled;

import com.crosshospital.imaging.config.CacheConfigProps;
import com.crosshospital.imaging.service.cache.PacsCacheManager;
import com.crosshospital.imaging.service.cache.PacsEvictionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * PACS 缓存过期巡检（task 32）。
 *
 * <p>每小时扫一次账本：超过 {@code imaging.cache.ttl-hours} 的 study 按 LRU 淘汰，
 * 逐项调 {@link PacsEvictionPort} 走本院 PACS 的存储管理接口。
 * 单个端口实现抛异常只影响那一项，不打断整轮巡检。
 */
@Component
@Profile("!test")
public class CacheEvictionScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(CacheEvictionScheduler.class);

    private final PacsCacheManager cache;
    private final PacsEvictionPort eviction;
    private final CacheConfigProps props;

    public CacheEvictionScheduler(PacsCacheManager cache,
                                  PacsEvictionPort eviction,
                                  CacheConfigProps props) {
        this.cache = cache;
        this.eviction = eviction;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${imaging.cache.eviction-interval-ms:3600000}")
    public void evictExpiredOnSchedule() {
        evictExpiredNow();
    }

    /** 立即跑一轮（供运维触发与单元测试调用）。 */
    public int evictExpiredNow() {
        return evictExpiredAt(Instant.now());
    }

    /** 指定「现在」跑一轮 —— 单测不必真的等 TTL 到期。 */
    public int evictExpiredAt(Instant now) {
        List<String> expired = cache.evictExpired(Duration.ofHours(Math.max(1, props.getTtlHours())), now);
        for (String studyUid : expired) {
            try {
                eviction.delete(studyUid);
            } catch (RuntimeException ex) {
                LOG.error("PACS 缓存淘汰执行失败（继续处理其余项）：studyUid={} msg={}",
                        studyUid, ex.getMessage());
            }
        }
        if (!expired.isEmpty()) {
            LOG.info("PACS 缓存过期巡检完成：淘汰 {} 项，当前 {} 项 / {} 字节",
                    expired.size(), cache.stats().entries(), cache.stats().bytesUsed());
        }
        return expired.size();
    }
}
