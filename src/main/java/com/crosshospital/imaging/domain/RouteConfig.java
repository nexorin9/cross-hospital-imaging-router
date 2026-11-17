package com.crosshospital.imaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 路由 / 协议 / 业务规则持久化配置（{@code route_config}）。
 *
 * <p>key 唯一，value 为 JSONB；用于持久化需要运行时调整但又不能放进
 * {@code application.yml} 的策略（黑白名单例外、互认模态白名单、AI 提示词版本等）。
 *
 * <p>本表允许更新（{@code updated_at} 由 {@code @PreUpdate} 自动刷新），与
 * {@code audit_imaging_fetch} 的 append-only 约束形成对比。
 */
@Entity
@Table(name = "route_config")
public class RouteConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "key", nullable = false, unique = true)
    private String key;

    /** V7 是 JSONB 列；Java 侧用 String，必须显式声明 JDBC JSON 类型，真 PG 才肯收。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value", nullable = false, columnDefinition = "jsonb")
    private String value;

    @Column(name = "description")
    private String description;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @PrePersist
    void onInsert() {
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public RouteConfig() {
    }

    // -- getters / setters --
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}