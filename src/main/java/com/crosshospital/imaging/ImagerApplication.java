package com.crosshospital.imaging;

import com.crosshospital.imaging.config.AppInfoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Cross-Hospital Imaging Recognition & Fetch Routing Service.
 *
 * <p>主入口；负责：①加载 application.yml；②扫描 {@code com.crosshospital.imaging.**}
 * 包下所有组件；③暴露 {@code /actuator/health} 与 {@code /v3/api-docs} 入口；
 * ④启用 {@code @ConfigurationProperties}、{@code @Scheduled}、{@code @Async}。
 *
 * <p>源产品主路径证据：Spring Boot 启动序列与 springdoc-openapi 自动文档。
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.crosshospital.imaging.config")
@EnableConfigurationProperties(AppInfoProperties.class)
@EnableAsync
@EnableScheduling
public class ImagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImagerApplication.class, args);
    }
}