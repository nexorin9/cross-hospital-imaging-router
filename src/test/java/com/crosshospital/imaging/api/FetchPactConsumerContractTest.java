package com.crosshospital.imaging.api;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import com.crosshospital.imaging.api.dto.FetchRequest;
import com.crosshospital.imaging.api.dto.FetchResponse;
import com.crosshospital.imaging.support.PactFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 34 — Pact consumer 侧：HIS 工单系统作为 consumer，对 imaging-router 的
 * {@code POST /api/imaging/studies/fetch} 提出期望，Pact mock provider 真实起 HTTP 服务，
 * 消费端真实发一次请求，验证通过后生成 Pact JSON。
 *
 * <p>不用 JUnit4 Rule / JUnit5 Extension（当前依赖里没有对应 artifact），
 * 直接调 Pact JVM 4.6.5 的 {@link ConsumerPactRunnerKt#runConsumerTest}，
 * 行为等价：起服务 → 发请求 → 比对期望 → 校验交互是否全部命中 → 写契约文件。
 */
@DisplayName("Task 34 — Pact consumer：HIS 工单 → imaging-router")
class FetchPactConsumerContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("consumer 请求命中 Pact mock provider，交互零 mismatch，契约写入 target/pacts/")
    void consumerInteractionIsVerifiedAndPactIsWritten() throws Exception {
        MockProviderConfig config = MockProviderConfig.createDefault(PactSpecVersion.V3);

        PactVerificationResult result = ConsumerPactRunnerKt.runConsumerTest(
                PactFixtures.pact(), config, (server, context) -> {
                    HttpClient client = HttpClient.newBuilder().build();
                    HttpRequest request = HttpRequest.newBuilder(
                                    URI.create(server.getUrl() + PactFixtures.FETCH_PATH))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    PactFixtures.REQUEST_JSON, StandardCharsets.UTF_8))
                            .build();
                    HttpResponse<String> response = client.send(request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                    assertEquals(202, response.statusCode(), "契约约定异步受理返回 202");
                    JsonNode body = mapper.readTree(response.body());
                    assertTrue(body.has("taskId"), "回执必须给 taskId");
                    assertEquals("queued", body.path("status").asText());
                    assertTrue(body.path("links").path("self").asText()
                            .contains(body.path("taskId").asText()));
                    return null;
                });

        assertTrue(result instanceof PactVerificationResult.Ok,
                "Pact 交互未通过：" + result.getDescription() + " / " + result);

        // 显式再写一份到固定路径，确保 provider 回放测试有稳定输入
        String pactJson = PactFixtures.writePact();
        Path pactFile = PactFixtures.PACT_FILE;
        assertTrue(Files.exists(pactFile), "Pact JSON 未生成：" + pactFile);
        assertEquals(pactJson, Files.readString(pactFile), "落盘契约与 DSL 产出不一致");
    }

    @Test
    @DisplayName("生成的 Pact JSON 结构完整：consumer/provider/request/response 四段齐全")
    void pactJsonStructureIsComplete() throws Exception {
        JsonNode pact = mapper.readTree(PactFixtures.writePact());

        assertEquals(PactFixtures.CONSUMER, pact.path("consumer").path("name").asText());
        assertEquals(PactFixtures.PROVIDER, pact.path("provider").path("name").asText());
        assertEquals(1, pact.path("interactions").size());

        JsonNode interaction = pact.path("interactions").get(0);
        assertEquals("一键发起跨院影像调阅", interaction.path("description").asText());
        assertEquals("POST", interaction.path("request").path("method").asText());
        assertEquals(PactFixtures.FETCH_PATH, interaction.path("request").path("path").asText());
        assertEquals("application/json",
                interaction.path("request").path("headers").path("Content-Type").asText());
        assertEquals(202, interaction.path("response").path("status").asInt());
    }

    @Test
    @DisplayName("契约里的 request/response 字段名全部落在 Java DTO 的 OpenAPI schema 内（0 不一致）")
    void pactBodiesMatchDtoSchemas() throws Exception {
        JsonNode interaction = mapper.readTree(PactFixtures.writePact())
                .path("interactions").get(0);

        Set<String> requestFields = fieldNames(interaction.path("request").path("body"));
        Set<String> requestSchema = schemaProperties(FetchRequest.class);
        assertTrue(requestSchema.containsAll(requestFields),
                "Pact 请求体出现 schema 未声明的字段：" + requestFields + " / " + requestSchema);

        Set<String> responseFields = fieldNames(interaction.path("response").path("body"));
        Set<String> responseSchema = schemaProperties(FetchResponse.class);
        assertEquals(responseSchema, responseFields,
                "Pact 响应体字段与 FetchResponse schema 不一致");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        if (node.isTextual()) {
            // Pact V3 也允许把 body 存成字符串；两种形态都兼容
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
        assertFalse(names.isEmpty());
        return names;
    }
}
