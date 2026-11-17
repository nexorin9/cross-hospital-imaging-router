package com.crosshospital.imaging.api;

import com.crosshospital.imaging.api.dto.AuditQueryRequest;
import com.crosshospital.imaging.api.dto.AuditRowResponse;
import com.crosshospital.imaging.api.dto.DlqEntryResponse;
import com.crosshospital.imaging.api.dto.DlqResolveRequest;
import com.crosshospital.imaging.api.dto.FetchRequest;
import com.crosshospital.imaging.api.dto.FetchResponse;
import com.crosshospital.imaging.api.dto.HandshakeResponse;
import com.crosshospital.imaging.api.dto.PeerCreateRequest;
import com.crosshospital.imaging.api.dto.PeerResponse;
import com.crosshospital.imaging.api.dto.PeerUpdateRequest;
import com.crosshospital.imaging.api.dto.TaskStatusResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 33/34 — 真启动 Spring 上下文后取 {@code /v3/api-docs}，做端到端 schema 校验。
 *
 * <p><b>为什么要额外注册 controller bean</b>：主路径 controller 都带
 * {@code @Profile("!test")}（test profile 排除了 JPA / Flyway，真实 controller 的
 * 构造依赖装配不起来）。本测试只校验「文档吐出来长什么样」，不调用任何业务方法，
 * 因此用 {@link DocsControllers} 把 controller 以空依赖注册进上下文，
 * 让 springdoc 能扫描到它们的 {@code @RequestMapping / @Operation / @Schema}。
 * 真实行为契约由 {@code *ContractTest} 的 standalone MockMvc 用例覆盖，两者不重复也不互相替代。
 *
 * <p>与 {@link OpenApiSchemaComplianceTest}（离线 ModelConverters）互补：
 * 这里断言的是服务实际吐出来的那份 JSON。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OpenApiDocumentContractTest.DocsControllers.class)
@DisplayName("Task 33/34 — /v3/api-docs 端到端契约")
class OpenApiDocumentContractTest {

    /** 请求体 / 响应体 DTO：springdoc 会在 components.schemas 里生成同名 schema。 */
    private static final List<Class<?>> BODY_DTOS = List.of(
            FetchRequest.class, FetchResponse.class, TaskStatusResponse.class,
            AuditRowResponse.class, DlqEntryResponse.class, DlqResolveRequest.class,
            HandshakeResponse.class, PeerCreateRequest.class, PeerResponse.class,
            PeerUpdateRequest.class);

    private static final List<String> REQUIRED_TAGS =
            List.of("peers", "fetch", "audit", "dlq", "health");

    private static final List<String> REQUIRED_PATHS = List.of(
            "/api/imaging/studies/fetch",
            "/api/imaging/tasks/{taskId}",
            "/api/imaging/audit",
            "/api/imaging/peers",
            "/api/imaging/dlq",
            "/api/imaging/health",
            "/api/imaging/info");

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode apiDocs() throws Exception {
        String json = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(json);
    }

    @Test
    @DisplayName("/v3/api-docs 返回合法 OpenAPI 3.1 JSON，含五 tag 与 X-Employee-Id 安全方案")
    void apiDocsIsValidOpenApi31() throws Exception {
        JsonNode doc = apiDocs();
        assertEquals("3.1.0", doc.path("openapi").asText(),
                "springdoc 应按 springdoc.api-docs.version=OPENAPI_3_1 输出 3.1.0");
        assertEquals("跨院影像互认调阅路由系统 API", doc.path("info").path("title").asText());

        Set<String> tags = new TreeSet<>();
        for (JsonNode tag : doc.path("tags")) {
            tags.add(tag.path("name").asText());
        }
        assertTrue(tags.containsAll(REQUIRED_TAGS), "缺少规定的 tag，实际=" + tags);

        JsonNode scheme = doc.path("components").path("securitySchemes")
                .path("employeeIdHeader");
        assertFalse(scheme.isMissingNode(), "缺少 employeeIdHeader 安全方案");
        assertEquals("apiKey", scheme.path("type").asText());
        assertEquals("header", scheme.path("in").asText());
        assertEquals("X-Employee-Id", scheme.path("name").asText());
        assertFalse(doc.path("security").isEmpty(), "全局 security requirement 未声明");
    }

    @Test
    @DisplayName("所有 controller 端点出现在 paths 中，fetch 明确声明 202 响应")
    void allControllerPathsAreDocumented() throws Exception {
        JsonNode paths = apiDocs().path("paths");
        for (String path : REQUIRED_PATHS) {
            assertTrue(paths.has(path), "OpenAPI 缺少端点 " + path);
        }
        JsonNode fetchPost = paths.path("/api/imaging/studies/fetch").path("post");
        assertTrue(fetchPost.has("requestBody"), "fetch 端点缺 requestBody");
        assertTrue(fetchPost.path("responses").has("202"), "fetch 端点缺 202 响应声明");
        assertTrue(paths.path("/api/imaging/audit").has("get"), "审计端点缺 GET");
        assertFalse(paths.path("/api/imaging/audit").has("post"), "审计端点必须是只读的");
    }

    @Test
    @DisplayName("components.schemas 中每个请求体/响应体 DTO 的字段名与 Java 声明一致（0 不一致）")
    void bodySchemaFieldsMatchJavaDto() throws Exception {
        JsonNode schemas = apiDocs().path("components").path("schemas");
        Map<String, String> mismatches = new LinkedHashMap<>();
        for (Class<?> dto : BODY_DTOS) {
            String name = dto.getSimpleName();
            JsonNode schema = schemas.path(name);
            if (schema.isMissingNode()) {
                mismatches.put(name, "components.schemas 中不存在");
                continue;
            }
            Set<String> documented = new TreeSet<>();
            Iterator<String> it = schema.path("properties").fieldNames();
            while (it.hasNext()) {
                documented.add(it.next());
            }
            Set<String> declared = declaredFields(dto);
            if (!declared.equals(documented)) {
                mismatches.put(name, "expected=" + declared + " actual=" + documented);
            }
        }
        assertTrue(mismatches.isEmpty(), "OpenAPI schema 与 Java DTO 不一致：" + mismatches);
    }

    @Test
    @DisplayName("审计查询 DTO 以 query parameters 暴露，8 个字段一个不少")
    void queryDtoIsExposedAsParameters() throws Exception {
        JsonNode parameters = apiDocs().path("paths")
                .path("/api/imaging/audit").path("get").path("parameters");
        Set<String> documented = new TreeSet<>();
        for (JsonNode parameter : parameters) {
            documented.add(parameter.path("name").asText());
        }
        assertEquals(declaredFields(AuditQueryRequest.class), documented,
                "审计查询条件与 OpenAPI parameters 不一致");
    }

    @Test
    @DisplayName("/swagger-ui.html 可访问并落到 Swagger UI 资源，logo 可取")
    void swaggerUiIsReachable() throws Exception {
        MvcResult result = mvc.perform(get("/swagger-ui.html")).andReturn();
        int status = result.getResponse().getStatus();
        assertTrue(status >= 200 && status < 400,
                "/swagger-ui.html 返回 " + status + "，Swagger UI 未装配");

        String location = result.getResponse().getHeader("Location");
        if (location != null) {
            assertTrue(location.contains("swagger-ui"),
                    "重定向目标不是 Swagger UI：" + location);
        } else {
            assertTrue(result.getResponse().getContentAsString().toLowerCase().contains("swagger"),
                    "响应体不含 Swagger UI 标识");
        }

        mvc.perform(get("/imaging-logo.svg")).andExpect(status().isOk());
    }

    private static Set<String> declaredFields(Class<?> type) {
        Set<String> names = new TreeSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                names.add(field.getName());
            }
        }
        return names;
    }

    /**
     * 只为 springdoc 的注解扫描提供 bean，不参与请求处理。
     * 构造依赖传 null 是刻意的：test profile 下装配真实 service 会要求数据库。
     */
    @TestConfiguration
    static class DocsControllers {

        @Bean
        FetchController docsFetchController() {
            return new FetchController(null, null);
        }

        @Bean
        AuditController docsAuditController() {
            return new AuditController(null);
        }

        @Bean
        DlqController docsDlqController() {
            return new DlqController(null);
        }

        @Bean
        PeersController docsPeersController() {
            return new PeersController(null);
        }

        @Bean
        PeersHealthController docsPeersHealthController() {
            return new PeersHealthController(null, null);
        }
    }
}
