package com.crosshospital.imaging.api;

import com.crosshospital.imaging.api.dto.AuditQueryRequest;
import com.crosshospital.imaging.api.dto.AuditRowResponse;
import com.crosshospital.imaging.api.dto.FetchRequest;
import com.crosshospital.imaging.api.dto.FetchResponse;
import com.crosshospital.imaging.api.dto.TaskStatusResponse;
import com.crosshospital.imaging.config.AppInfoProperties;
import com.crosshospital.imaging.config.OpenApiConfig;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 33/34 — OpenAPI schema 与 Java DTO 字段名一致性校验。
 *
 * <p>用 springdoc 底层同一个 {@link ModelConverters}（就是生成 {@code /v3/api-docs}
 * 的那套转换器）读 DTO，逐字段比对 Java 声明：文档里少一个字段、多一个内部字段，
 * 都算「不一致」。再把快照写到 {@code target/pacts/} 供人工核对。
 */
@DisplayName("Task 33/34 — OpenAPI schema 合规校验")
class OpenApiSchemaComplianceTest {

    private static final List<Class<?>> DTOS = List.of(
            FetchRequest.class, FetchResponse.class, TaskStatusResponse.class,
            AuditQueryRequest.class, AuditRowResponse.class);

    private static final List<Class<?>> CONTROLLERS = List.of(
            FetchController.class, AuditController.class,
            com.crosshospital.imaging.api.PeersController.class,
            com.crosshospital.imaging.api.PeersHealthController.class,
            com.crosshospital.imaging.api.DlqController.class,
            com.crosshospital.imaging.api.HealthController.class);

    private static Set<String> declaredFields(Class<?> type) {
        Set<String> names = new TreeSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            names.add(field.getName());
        }
        return names;
    }

    @Test
    @DisplayName("所有 DTO 的 OpenAPI schema 字段名与 Java 声明逐一一致（0 不一致）")
    void schemaFieldsMatchJavaDeclarations() {
        Map<String, Set<String>> contract = new LinkedHashMap<>();
        for (Class<?> dto : DTOS) {
            Map<String, Schema> schemas = ModelConverters.getInstance().readAll(dto);
            Schema<?> schema = schemas.get(dto.getSimpleName());
            assertNotNull(schema, dto.getSimpleName() + " 未生成 OpenAPI schema");
            assertNotNull(schema.getProperties(), dto.getSimpleName() + " schema 无字段");
            Set<String> documented = new TreeSet<>(schema.getProperties().keySet());
            Set<String> declared = declaredFields(dto);
            assertEquals(declared, documented,
                    dto.getSimpleName() + " 的 schema 字段与 Java 声明不一致");
            contract.put(dto.getSimpleName(), documented);
        }
        // 写一份可读的契约快照，便于人工核对与后续契约测试复用
        writeSnapshot(contract);
        assertTrue(Files.exists(Path.of("target/pacts/openapi-schema-contract.json")));
    }

    @Test
    @DisplayName("审计响应刻意不暴露 rowChecksum / prevChecksum")
    void checksumsAreNotInSchema() {
        Schema<?> schema = ModelConverters.getInstance()
                .readAll(AuditRowResponse.class).get("AuditRowResponse");
        assertNotNull(schema);
        assertFalse(schema.getProperties().containsKey("rowChecksum"));
        assertFalse(schema.getProperties().containsKey("prevChecksum"));
    }

    @Test
    @DisplayName("所有 controller 有 @Tag，端点方法有 @Operation")
    void controllersAreFullyAnnotated() {
        for (Class<?> controller : CONTROLLERS) {
            assertTrue(controller.isAnnotationPresent(RestController.class),
                    controller.getSimpleName() + " 缺少 @RestController");
            assertTrue(controller.isAnnotationPresent(Tag.class)
                            || controller.getAnnotation(Tag.class) != null,
                    controller.getSimpleName() + " 缺少 @Tag");
            List<Method> handlers = new ArrayList<>();
            for (Method method : controller.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())
                        && method.isAnnotationPresent(Operation.class)) {
                    handlers.add(method);
                }
            }
            assertFalse(handlers.isEmpty(),
                    controller.getSimpleName() + " 没有任何带 @Operation 的端点方法");
        }
    }

    @Test
    @DisplayName("OpenAPI bean 声明五个 tag + 安全方案 + x-logo 扩展")
    void openApiBeanHasTagsAndSecurityScheme() {
        OpenAPI openApi = new OpenApiConfig()
                .crossHospitalImagingOpenAPI(new AppInfoProperties());

        assertEquals("跨院影像互认调阅路由系统 API", openApi.getInfo().getTitle());
        assertEquals(Set.of("peers", "fetch", "audit", "dlq", "health"),
                openApi.getTags().stream().map(io.swagger.v3.oas.models.tags.Tag::getName)
                        .collect(Collectors.toSet()));
        assertTrue(openApi.getComponents().getSecuritySchemes().containsKey("employeeIdHeader"));
        assertTrue(openApi.getInfo().getExtensions().containsKey("x-logo"));
        assertEquals(1, openApi.getSecurity().size());
    }

    private static void writeSnapshot(Map<String, Set<String>> contract) {
        try {
            Files.createDirectories(Path.of("target/pacts"));
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                    Path.of("target/pacts/openapi-schema-contract.json").toFile(), contract);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("契约快照写入失败", e);
        }
    }
}
