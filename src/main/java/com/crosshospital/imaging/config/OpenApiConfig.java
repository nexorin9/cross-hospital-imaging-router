package com.crosshospital.imaging.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * OpenAPI / springdoc 文档配置（task 33 扩展）。
 *
 * <p>除元信息外，这里显式声明<b>五个 tag</b>（peers / fetch / audit / dlq / health）
 * 与安全方案：{@code X-Employee-Id} 是院内 API 网关注入的操作人工号，
 * 生产环境可换成 OAuth2 / JWT，但文档要把「谁在调」这个凭据位写清楚。
 *
 * <p>{@code x-logo} 扩展供兼容该扩展的文档渲染器（ReDoc 等）显示院内标识；
 * Swagger UI 自身不渲染 logo，UI 侧标题由 {@code springdoc.swagger-ui.*} 配置。
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name:cross-hospital-imaging-router}")
    private String appName;

    @Bean
    public OpenAPI crossHospitalImagingOpenAPI(AppInfoProperties app) {
        return new OpenAPI()
                .info(new Info()
                        .title("跨院影像互认调阅路由系统 API")
                        .description("Cross-Hospital Imaging Recognition & Fetch Routing Service. "
                                + "对外暴露节点准入 / 握手健康检查 / 跨院调阅 / 审计查询 / 死信处置接口。"
                                + "所有凭据位从环境变量读取，审计表 append-only。")
                        .version(app.getVersion())
                        .contact(new Contact()
                                .name("Cross-Hospital Imaging Router Maintainers")
                                .email("imaging-router@example.local"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT"))
                        // addExtension(...) 返回 void，不能链式；用返回 Info 的 extensions(...)
                        .extensions(Map.of("x-logo", Map.of(
                                "url", "/imaging-logo.svg",
                                "altText", "跨院影像互认调阅路由系统"))))
                .servers(List.of(
                        new Server().url("http://127.0.0.1:8080").description("Local dev"),
                        new Server().url("http://{host}:8080").description("Generic")))
                .tags(List.of(
                        new Tag().name("peers").description("外院 DICOM 节点准入表与握手健康检查"),
                        new Tag().name("fetch").description("跨院影像调阅异步受理与任务状态查询"),
                        new Tag().name("audit").description("调阅审计只读查询（append-only）"),
                        new Tag().name("dlq").description("死信待办与人工处置"),
                        new Tag().name("health").description("服务健康与应用元信息")))
                .components(new Components()
                        .addSecuritySchemes("employeeIdHeader", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Employee-Id")
                                .description("操作人工号；生产环境由院内 OAuth2 / API 网关注入")))
                .addSecurityItem(new SecurityRequirement().addList("employeeIdHeader"));
    }
}
