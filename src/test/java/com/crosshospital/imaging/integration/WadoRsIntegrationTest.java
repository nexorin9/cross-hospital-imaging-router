package com.crosshospital.imaging.integration;

import com.crosshospital.imaging.config.WadoConfigProps;
import com.crosshospital.imaging.exception.WadoRsHttpException;
import com.crosshospital.imaging.exception.WadoRsParseException;
import com.crosshospital.imaging.service.wado.MultipartDicomExtractor;
import com.crosshospital.imaging.service.wado.WadoRsClient;
import com.crosshospital.imaging.service.wado.WadoRsFetchResult;
import com.crosshospital.imaging.support.TestDicomFixtures;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 36 — WireMock 起真实 HTTP WADO-RS 网关，返回 fixture 里的 multipart/related。
 *
 * <p>端口用内核动态分配而不是写死 8081：CI 上 8081 可能被别的服务占用，
 * 固定端口会让集成测试随机失败；WADO 客户端的 URL 模板本来就是从配置注入的，
 * 动态端口不影响任何被测行为。
 *
 * <p>fixture 在 {@code src/test/fixtures/samples-wado/study-multipart.related}，
 * 三个 part 各自来自 {@code samples-dicom/instance-0N.dcm}，全是自造示例 UID。
 */
@DisplayName("Task 36 — WireMock WADO-RS 集成")
class WadoRsIntegrationTest {

    private static final String BOUNDARY = "boundary-CHIRADIAG-EXAMPLE";
    private static final String MULTIPART_CT = "multipart/related; type=\"application/dicom\"; boundary=\""
            + BOUNDARY + "\"";
    private static final String PEER = "PEER_HZ";
    private static final String STUDY = TestDicomFixtures.EXAMPLE_STUDY_UID;
    private static final Path FIXTURE =
            Path.of("src/test/fixtures/samples-wado/study-multipart.related");

    private WireMockServer server;
    private WadoRsClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        WadoConfigProps props = new WadoConfigProps();
        props.setBaseUrlTemplate("http://127.0.0.1:" + server.port() + "/wado/studies/{studyUid}");
        props.setMaxRetries(1);
        client = new WadoRsClient(props, new MultipartDicomExtractor(props));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private void stubBody(int status, String contentType, byte[] body) {
        server.stubFor(get(urlPathMatching("/wado/studies/.*"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", contentType)
                        .withBody(body)));
    }

    private void stubFixture() throws Exception {
        stubBody(200, MULTIPART_CT, Files.readAllBytes(FIXTURE));
    }

    @Test
    @DisplayName("200 multipart/related → 拉回 3 个 DICOM 对象，每个都有合法 Part-10 前导")
    void fixtureMultipartYieldsThreeDicomInstances() throws Exception {
        stubFixture();

        List<ByteBuffer> instances = client.fetchStudy(PEER, STUDY);

        assertEquals(3, instances.size(), "fixture 里有 3 个 part");
        for (ByteBuffer buffer : instances) {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.duplicate().get(bytes);
            assertTrue(bytes.length > 132);
            assertEquals("DICM", new String(bytes, 128, 4, StandardCharsets.US_ASCII));
        }
        server.verify(getRequestedFor(urlEqualTo("/wado/studies/" + STUDY))
                .withHeader("User-Agent", equalTo("cross-hospital-imaging-router/0.1")));
    }

    @Test
    @DisplayName("206 部分内容：照常解析但显式标记 partial=true，不得当整 study 已拉全")
    void partialContentIsFlagged() throws Exception {
        stubBody(206, MULTIPART_CT, Files.readAllBytes(FIXTURE));

        WadoRsFetchResult result = client.fetchStudyDetailed(PEER, STUDY);

        assertAll(
                () -> assertEquals(206, result.httpStatus()),
                () -> assertTrue(result.partial()),
                () -> assertEquals(3, result.instanceCount())
        );
    }

    @Test
    @DisplayName("失败 1：WADO-RS 5xx → SERVER_ERROR，标记可重试")
    void serverErrorIsRetryable() {
        stubBody(500, "text/plain", "upstream gateway timeout".getBytes(StandardCharsets.UTF_8));

        WadoRsHttpException ex = assertThrows(WadoRsHttpException.class,
                () -> client.fetchStudy(PEER, STUDY));
        assertAll(
                () -> assertEquals(500, ex.getStatusCode()),
                () -> assertEquals(WadoRsHttpException.Reason.SERVER_ERROR, ex.getReason()),
                () -> assertTrue(ex.isRetryable())
        );
    }

    @Test
    @DisplayName("失败 2：WADO-RS 4xx → STUDY_NOT_FOUND，不可重试且不返回空列表")
    void clientErrorIsNotRetryable() {
        stubBody(404, "application/dicom", "study not found on this PACS"
                .getBytes(StandardCharsets.UTF_8));

        WadoRsHttpException ex = assertThrows(WadoRsHttpException.class,
                () -> client.fetchStudy(PEER, STUDY));
        assertAll(
                () -> assertEquals(404, ex.getStatusCode()),
                () -> assertEquals(WadoRsHttpException.Reason.STUDY_NOT_FOUND, ex.getReason()),
                () -> assertFalse(ex.isRetryable())
        );
    }

    @Test
    @DisplayName("失败 3：响应体没声明 boundary → MISSING_BOUNDARY（不静默返回空列表）")
    void missingBoundaryIsRejected() throws Exception {
        stubBody(200, "multipart/related; type=\"application/dicom\"",
                Files.readAllBytes(FIXTURE));

        WadoRsParseException ex = assertThrows(WadoRsParseException.class,
                () -> client.fetchStudy(PEER, STUDY));
        assertEquals(WadoRsParseException.ParseError.MISSING_BOUNDARY, ex.getParseError());
    }
}
