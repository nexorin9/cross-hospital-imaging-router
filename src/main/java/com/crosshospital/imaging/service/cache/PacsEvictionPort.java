package com.crosshospital.imaging.service.cache;

/**
 * PACS 存储侧淘汰端口（task 32）。
 *
 * <p>DICOM 网络协议没有通用的「删除某个 study」原语，各厂商 PACS 的存储管理接口
 * 各不相同。缓存账本只决定「该淘汰谁」，真正删除由这个端口对接本院 PACS 的
 * 存储管理接口；默认实现只记录审计日志（不改数据），避免在没有厂商接口时
 * 假装删掉了影像。
 */
@FunctionalInterface
public interface PacsEvictionPort {

    /** 从本院 PACS 存储中移除该 study 的缓存对象。 */
    void delete(String studyUid);
}
