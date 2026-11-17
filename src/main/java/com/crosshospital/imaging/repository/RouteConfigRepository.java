package com.crosshospital.imaging.repository;

import com.crosshospital.imaging.domain.RouteConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 路由 / 协议 / 业务规则持久化配置 Repository。
 *
 * <p>用于持久化需要运行时调整但又不能放进 {@code application.yml} 的策略
 * （黑白名单例外、互认模态白名单、AI 提示词版本等）。
 *
 * <p>本表允许更新（{@code updated_at} 由 {@code @PreUpdate} 自动刷新），与
 * {@code audit_imaging_fetch} 的 append-only 形成对比。
 */
public interface RouteConfigRepository extends JpaRepository<RouteConfig, Long> {

    Optional<RouteConfig> findByKey(String key);

    boolean existsByKey(String key);

    List<RouteConfig> findAllByOrderByKeyAsc();
}