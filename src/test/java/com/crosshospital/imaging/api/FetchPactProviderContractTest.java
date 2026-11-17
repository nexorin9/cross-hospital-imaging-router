package com.crosshospital.imaging.api;

import com.crosshospital.imaging.api.dto.FetchRequest;
import com.crosshospital.imaging.api.dto.FetchResponse;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.exception.GlobalExceptionHandler;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.repository.RouteConfigRepository;
import com.crosshospital.imaging.service.routing.PeerRoutingResolver;
import com.crosshospital.imaging.service.task.FetchTaskQueuePort;
import com.crosshospital.imaging.service.task.FetchTaskService;
import com.crosshospital.imaging.support.PactFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 34 — Pact provider 侧：加载 consumer 生成的 Pact JSON，把里面的 request
 * 原样打到真实 {@link FetchController}（standalone MockMvc），再把真实 response
 * 与契约里的 response 逐字段核对。
 *
 * <p>契约里的 taskId 是示例 UUID，provider 实际会生成新的 UUID，所以这里校验的是
 * <b>形状</b>（字段名 / 类型 / 状态 / links 自洽），不是示例值本身 ——
 * 这正是 Pact 的 type matching 语义，避免把契约测成「硬编码样例比对」。
 */
@DisplayName("Task 34 — Pact provider 回放：imaging-router")
class FetchPactProviderContractTest {

    private static JsonNode interaction;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeAll
    static void ensurePactExists() throws Exception {
        // 不依赖 consumer 测试的执行顺序：先按同一份 DSL 落盘契约
        interaction = new ObjectMapper().readTree(PactFixtures.writePact())
                .path("interactions").get(0);
    }

    @BeforeEach
    void setUp() {
        FetchTaskRepository tasks = mock(FetchTaskRepository.class);
        FetchTaskQueuePort queue = mock(FetchTaskQueuePort.class);
        FetchTaskService service = new FetchTaskService(tasks, queue);
        RouteConfigRepository routeConfig = mock(RouteConfigRepository.class);
        when(routeConfig.findByKey(anyString())).thenReturn(Optional.empty());
        PeerRoutingResolver resolver = new PeerRoutingResolver(
                routeConfig, mock(PeerRepository.class), new ObjectMapper());

        mvc = MockMvcBuilders.standaloneSetup(new FetchController(service, resolver))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        when(tasks.findActiveByPatientMpiAndStudyUid(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(tasks.save(any(FetchTask.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("回放契约 request → 真实 controller 返回契约约定的 202 与字段形状")
    void providerSatisfiesPactInteraction() throws Exception {
        JsonNode request = interaction.path("request");
        JsonNode response = interaction.path("response");

        String body = asJson(request.path("body"));
        MvcResult result = mvc.perform(post(request.path("path").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertEquals(response.path("status").asInt(), result.getResponse().getStatus(),
                "provider 返回状态码与契约不一致");
        assertTrue(result.getResponse().getHeader("Location") != null,
                "202 受理必须给 Location");

        JsonNode actual = mapper.readTree(result.getResponse().getContentAsString());
        Set<String> actualFields = fieldNames(actual);
        Set<String> expectedFields = fieldNames(response.path("body"));
        assertEquals(expectedFields, actualFields,
                "provider 响应字段与 Pact 契约不一致");
        assertEquals(schemaProperties(FetchResponse.class), actualFields,
                "provider 响应字段与 OpenAPI schema 不一致");

        assertEquals("queued", actual.path("status").asText());
        String taskId = actual.path("taskId").asText();
        assertNotNull(UUID.fromString(taskId), "taskId 必须是合法 UUID");
        assertEquals("/api/imaging/tasks/" + taskId,
                actual.path("links").path("self").asText(),
                "links.self 必须指向本次生成的 taskId");
    }

    @Test
    @DisplayName("契约 request 字段全部在 FetchRequest 的 OpenAPI schema 内（0 不一致）")
    void pactRequestMatchesRequestSchema() throws Exception {
        Set<String> requestFields = fieldNames(interaction.path("request").path("body"));
        Set<String> schema = schemaProperties(FetchRequest.class);
        assertTrue(schema.containsAll(requestFields),
                "契约请求体含 schema 未声明字段：" + requestFields + " / " + schema);
        assertTrue(requestFields.contains("patientMpi") && requestFields.contains("studyUid"),
                "契约必须带患者主索引与外院 StudyInstanceUID");
    }

    private String asJson(JsonNode body) throws Exception {
        return body.isTextual() ? body.asText() : mapper.writeValueAsString(body);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        if (node.isTextual()) {
            try {
                node = new ObjectMapper().readTree(node.asText());
            } catch (Exception ignored) {
                return names;
            }
        }
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names;
    }

    private static Set<String> schemaProperties(Class<?> dto) {
        Schema<?> schema = ModelConverters.getInstance()
                .readAll(dto).get(dto.getSimpleName());
        assertNotNull(schema, dto.getSimpleName() + " 未生成 schema");
        Set<String> names = new TreeSet<>();
        if (schema.getProperties() != null) {
            names.addAll(schema.getProperties().keySet());
        }
        return names;
    }
}
