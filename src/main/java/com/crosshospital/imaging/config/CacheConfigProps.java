package com.crosshospital.imaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

/**
 * 本院 PACS 影像缓存配置（{@code imaging.cache.*}，task 32）。
 *
 * <p>跨院拉回的序列会先落本院 PACS 缓存；容量与存活时间都按节点配置，
 * 超限时按「最近最少访问」淘汰，避免一次 CT 200+ 张把缓存打满。
 */
@ConfigurationProperties(prefix = "imaging.cache")
@Profile("!test")
public class CacheConfigProps {

    /** 缓存容量上限（MB）。 */
    private long maxCacheMb = 5120;

    /** 缓存项存活时间（小时）。 */
    private int ttlHours = 24;

    /** 过期巡检周期（毫秒，默认每小时）。 */
    private long evictionIntervalMs = 3_600_000L;

    public long getMaxCacheMb() { return maxCacheMb; }
    public void setMaxCacheMb(long maxCacheMb) { this.maxCacheMb = maxCacheMb; }

    public int getTtlHours() { return ttlHours; }
    public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }

    public long getEvictionIntervalMs() { return evictionIntervalMs; }
    public void setEvictionIntervalMs(long evictionIntervalMs) { this.evictionIntervalMs = evictionIntervalMs; }
}
