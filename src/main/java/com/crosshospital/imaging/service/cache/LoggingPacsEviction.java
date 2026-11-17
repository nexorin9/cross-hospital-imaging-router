package com.crosshospital.imaging.service.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 默认淘汰实现：只落审计日志（task 32）。
 *
 * <p><b>为什么不假装删除</b>：没有本院 PACS 的存储管理接口时，
 * 把「已淘汰」写进日志却没真的删，会让容量监控与 PACS 实际占用越差越远。
 * 这里明确写出「待厂商接口对接」，信息科在日志里看得见。
 */
@Component
@Profile("!test")
public class LoggingPacsEviction implements PacsEvictionPort {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingPacsEviction.class);

    @Override
    public void delete(String studyUid) {
        LOG.warn("PACS 缓存淘汰待本院存储管理接口对接：studyUid={}（本实现只登记，不删除存储对象）",
                studyUid);
    }
}
