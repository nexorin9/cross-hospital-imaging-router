package com.crosshospital.imaging.service.cache;

import com.crosshospital.imaging.config.CacheConfigProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本院 PACS 影像缓存账本（task 32）。
 *
 * <p><b>它管的是「账」不是「字节」</b>：DICOM 对象实际存在本院 PACS 里，
 * 本类维护「哪个 study 占了多大、最后一次被访问是什么时候」，
 * 超容量时按 LRU 给出淘汰名单，由 {@code PacsEvictionPort} 去执行存储侧删除。
 *
 * <p><b>为什么用 ConcurrentHashMap + synchronized 的 LinkedHashMap</b>：
 * LRU 顺序需要在「读」时也改动（accessOrder=true），单靠并发 Map 保不住一致性；
 * 这里把「字节数 + LRU 顺序 + 访问时间」三样状态放在同一把锁下更新，
 * 避免并发 put/touch 时账目对不上（账目错了就会误删还在用的 study）。
 */
@Service
@Profile("!test")
public class PacsCacheManager {

    private static final Logger LOG = LoggerFactory.getLogger(PacsCacheManager.class);

    private final long maxBytes;
    private final Object lock = new Object();
    private final Map<String, CacheEntry> entries = new HashMap<>();
    private final LinkedHashMap<String, Boolean> lru = new LinkedHashMap<>(16, 0.75f, true);
    private long bytesUsed;

    /**
     * 生产装配入口。两个构造器并存时必须显式标注 {@link Autowired}，
     * 否则 Spring 在多构造器且无注解时会回退去找无参构造器，
     * 在真实上下文里直接抛 {@code No default constructor found}。
     * （此前的单测都用 {@code new PacsCacheManager(long)} 手工构造，没走到这条装配路径。）
     */
    @Autowired
    public PacsCacheManager(CacheConfigProps props) {
        this(Math.max(1, props.getMaxCacheMb()) * 1024L * 1024L);
    }

    /** 显式给定容量上限（字节）—— 单测用 MB 以下的小容量验证淘汰。 */
    public PacsCacheManager(long maxBytes) {
        this.maxBytes = Math.max(1, maxBytes);
    }

    /**
     * 登记一次拉回；超容量时按 LRU 淘汰到低于上限。
     *
     * @return 被淘汰的 studyUid 列表（按淘汰顺序）
     */
    public List<String> put(String studyUid, long bytes) {
        if (studyUid == null || studyUid.isBlank()) {
            throw new IllegalArgumentException("缓存 studyUid 不能为空");
        }
        long size = Math.max(0, bytes);
        List<String> evicted = new ArrayList<>();
        synchronized (lock) {
            CacheEntry old = entries.remove(studyUid);
            if (old != null) {
                bytesUsed -= old.bytes();
            }
            lru.remove(studyUid);
            entries.put(studyUid, new CacheEntry(size, Instant.now()));
            lru.put(studyUid, Boolean.TRUE);
            bytesUsed += size;

            while (bytesUsed > maxBytes && !lru.isEmpty()) {
                String victim = lru.keySet().iterator().next();
                // 刚放进来、且它是唯一一项时不删自己：单项超容量属于配置问题，
                // 记日志让信息科调 max_cache_mb，而不是把刚拉回的影像立刻删掉。
                if (victim.equals(studyUid) && entries.size() == 1) {
                    LOG.warn("单个 study 超过缓存上限，保留该 study 并告警：studyUid={} bytes={} max={}",
                            studyUid, size, maxBytes);
                    break;
                }
                evict(victim);
                evicted.add(victim);
            }
        }
        if (!evicted.isEmpty()) {
            LOG.info("PACS 缓存超容量，LRU 淘汰 {} 项：{}", evicted.size(), evicted);
        }
        return List.copyOf(evicted);
    }

    /** 记录一次访问（刷新 LRU 顺序与「最后访问时间」）。 */
    public boolean touch(String studyUid) {
        synchronized (lock) {
            if (!entries.containsKey(studyUid)) {
                return false;
            }
            lru.get(studyUid);
            CacheEntry entry = entries.get(studyUid);
            entries.put(studyUid, new CacheEntry(entry.bytes(), Instant.now()));
            return true;
        }
    }

    /** 淘汰超过 TTL 的项（按最后访问时间算）。 */
    public List<String> evictExpired(Duration ttl, Instant now) {
        Instant cutoff = now.minus(ttl);
        List<String> expired = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, CacheEntry> entry : new ArrayList<>(entries.entrySet())) {
                if (entry.getValue().lastAccessedAt().isBefore(cutoff)) {
                    evict(entry.getKey());
                    expired.add(entry.getKey());
                }
            }
        }
        return List.copyOf(expired);
    }

    /** 账本里是否还登记着该 study（运维巡检 / 容量核对用）。 */
    public boolean contains(String studyUid) {
        synchronized (lock) {
            return entries.containsKey(studyUid);
        }
    }

    /** 缓存账本快照。 */
    public CacheStats stats() {
        synchronized (lock) {
            return new CacheStats(entries.size(), bytesUsed, maxBytes);
        }
    }

    private void evict(String studyUid) {
        CacheEntry entry = entries.remove(studyUid);
        lru.remove(studyUid);
        if (entry != null) {
            bytesUsed -= entry.bytes();
        }
    }

    private record CacheEntry(long bytes, Instant lastAccessedAt) {
    }

    /** 条目数 / 已用字节 / 容量上限字节。 */
    public record CacheStats(int entries, long bytesUsed, long maxBytes) {
    }
}
