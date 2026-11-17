package com.crosshospital.imaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;

/**
 * 应用静态元信息。
 *
 * <p>由 {@code app.name} / {@code app.version} / {@code app.build-time} 配置驱动；
 * 用于暴露 {@code /actuator/info} 与 OpenAPI {@code info} section。
 */
@ConfigurationProperties(prefix = "app")
public class AppInfoProperties {

    private String name = "cross-hospital-imaging-router";
    private String version = "0.1.0";
    private String buildTime = "2026-10-20T00:00:00Z";
    private String gitCommit = "unknown";

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getBuildTime() { return buildTime; }
    public void setBuildTime(String buildTime) { this.buildTime = buildTime; }

    public String getGitCommit() { return gitCommit; }
    public void setGitCommit(String gitCommit) { this.gitCommit = gitCommit; }

    /** Convenience: parse buildTime as ISO-8601 instant for ordering/display. */
    public Instant parsedBuildTime() {
        try {
            return Instant.parse(buildTime);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}