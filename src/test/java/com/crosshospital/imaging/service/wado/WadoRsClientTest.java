package com.crosshospital.imaging.service.wado;

import com.crosshospital.imaging.config.WadoConfigProps;
import com.crosshospital.imaging.exception.ValidationException;
import com.crosshospital.imaging.exception.WadoRsHttpException;
import com.crosshospital.imaging.exception.WadoRsParseException;
import com.crosshospital.imaging.support.TestDicomFixtures;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 11 验收：WADO-RS HTTP 拉取 + multipart/related 解析。
 *
 * <p>用 WireMock 起真实 HTTP 端点，走真实 OkHttp 出站调用 —— 不 mock 客户端本身，
 * 覆盖「请求头 / URL 拼装 / 状态码分流 / multipart 逐 part 抽取 / DICOM 前导校验 / 容量上限」。
 */
class WadoRsClientTest {

    private static final String BOUNDARY = "boundary-CHIRADIAG-01";
    private static final String PEER = "PEER_HZ";
    private static final String STUDY = TestDicomFixtures.EXAMPLE_STUDY_UID;
    private static final String MULTIPART_CT = "multipart/related; type=\"application/dicom\"; boundary=\""
            + BOUNDARY + "\"";

    private WireMockServer server;
    private WadoConfigProps props;
    private MultipartDicomExtractor extractor;
    private WadoRsClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        props = new WadoConfigProps();
        props.setBaseUrlTemplate("http://127.0.0.1:" + server.port() + "/wado/studies/{studyUid}");
        extractor = new MultipartDicomExtractor(props);
        client = new WadoRsClient(props, extractor);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // ---------- 成功路径 ----------

    @Test
    @DisplayName("200 + multipart/related 含 3 个 DICOM 对象 → fetchStudy 解析出 3 个 ByteBuffer")
    void fetchStudyReturnsThreeDicomInstances() {
        stubMultipart(200, MULTIPART_CT,
                TestDicomFixtures.dicomInstance(STUDY, 1),
                TestDicomFixtures.dicomInstance(STUDY, 2),
                TestDicomFixtures.dicomInstance(STUDY, 3));

        List<ByteBuffer> instances = client.fetchStudy(PEER, STUDY);

        assertNotNull(instances);
        assertEquals(3, instances.size(), "应解析出 3 个 DICOM 对象");
    }

    @Test
    @DisplayName("每个 part 的前 128 字节是合法 DICOM 前导（magic DICM 在 offset 128）")
    void everyInstanceCarriesDicomPreambleAtOffset128() {
        stubMultipart(200, MULTIPART_CT,
                TestDicomFixtures.dicomInstance(STUDY, 1),
                TestDicomFixtures.dicomInstance(STUDY, 2),
                TestDicomFixtures.dicomInstance(STUDY, 3));

        List<ByteBuffer> instances = client.fetchStudy(PEER, STUDY);

        for (int i = 0; i < instances.size(); i++) {
            ByteBuffer buf = instances.get(i);
            assertTrue(buf.remaining() >= 132,
                    "part#" + (i + 1) + " 应 >= 132 字节，实际 " + buf.remaining());
            byte[] head = new byte[128];
            ByteBuffer view = buf.duplicate();
            view.get(head, 0, 128);
            assertArrayEquals(new byte[128], head,
                    "part#" + (i + 1) + " 的 128 字节前导应全为 0");
            byte[] magic = new byte[4];
            view.get(magic, 0, 4);
            assertEquals("DICM", new String(magic, StandardCharsets.US_ASCII),
                    "part#" + (i + 1) + " 的 offset 128 处应为 DICM");
        }
    }

    @Test
    @DisplayName("请求带 Accept: multipart/related + User-Agent")
    void sendsAcceptMultipartRelatedHeader() {
        stubMultipart(200, MULTIPART_CT, TestDicomFixtures.dicomInstance(STUDY, 1));

        client.fetchStudy(PEER, STUDY);

        server.verify(getRequestedFor(urlPathMatching("/wado/studies/.*"))
                .withHeader("Accept", equalTo("multipart/related; type=\"application/dicom\""))
                .withHeader("User-Agent", equalTo(props.getUserAgent())));
    }

    @Test
    @DisplayName("配置了 bearerToken 时请求带 Authorization: Bearer <token>")
    void sendsBearerTokenWhenConfigured() {
        props.setBearerToken("test-token-abc123");
        stubMultipart(200, MULTIPART_CT, TestDicomFixtures.dicomInstance(STUDY, 1));

        client.fetchStudy(PEER, STUDY);

        server.verify(getRequestedFor(urlPathMatching("/wado/studies/.*"))
                .withHeader("Authorization", equalTo("Bearer test-token-abc123")));
    }

    @Test
    @DisplayName("bearerToken 为空时不发 Authorization 头")
    void omitsAuthorizationWhenTokenBlank() {
        props.setBearerToken("");
        stubMultipart(200, MULTIPART_CT, TestDicomFixtures.dicomInstance(STUDY, 1));

        client.fetchStudy(PEER, STUDY);

        server.verify(getRequestedFor(urlPathMatching("/wado/studies/.*"))
                .withHeader("Authorization", absent()));
    }

    @Test
    @DisplayName("请求路径用模板占位 {studyUid} 替换")
    void requestUrlContainsStudyUid() {
        stubMultipart(200, MULTIPART_CT, TestDicomFixtures.dicomInstance(STUDY, 1));

        client.fetchStudy(PEER, STUDY);

        server.verify(getRequestedFor(urlEqualTo("/wado/studies/" + STUDY)));
    }

    @Test
    @DisplayName("模板缺 {studyUid} 占位时把 studyUid 追加到路径末尾")
    void urlTemplateWithoutPlaceholderAppendsStudyUid() {
        props.setBaseUrlTemplate("http://127.0.0.1:" + server.port() + "/wado/studies");

        assertEquals("http://127.0.0.1:" + server.port() + "/wado/studies/" + STUDY,
                client.buildUrl(PEER, STUDY));
    }

    // ---------- 状态码分流 ----------

    @Test
    @DisplayName("206 部分内容 → 照常解析但显式标记 partial=true")
    void partialContent206IsParsedAndFlagged() {
        stubMultipart(206, MULTIPART_CT,
                TestDicomFixtures.dicomInstance(STUDY, 1),
                TestDicomFixtures.dicomInstance(STUDY, 2));

        WadoRsFetchResult result = client.fetchStudyDetailed(PEER, STUDY);

        assertEquals(206, result.httpStatus());
        assertTrue(result.partial(), "206 必须标记 partial，不得当作整 study 已拉全");
        assertEquals(2, result.instanceCount());
    }

    @Test
    @DisplayName("200 整 study → partial=false")
    void fullContent200IsNotPartial() {
        stubMultipart(200, MULTIPART_CT, TestDicomFixtures.dicomInstance(STUDY, 1));

        WadoRsFetchResult result = client.fetchStudyDetailed(PEER, STUDY);

        assertEquals(200, result.httpStatus());
        assertFalse(result.partial());
        assertEquals(1, result.instanceCount());
        assertTrue(result.totalBytes() > 0);
    }

    @Test
    @DisplayName("404 → STUDY_NOT_FOUND，不可重试")
    void notFoundThrowsNonRetryable() {
        stubStatus(404, "application/dicom", "study not found on this PACS");

        WadoRsHttpException ex = assertThrows(WadoRsHttpException.class,
                () -> client.fetchStudy(PEER, STUDY));

        assertEquals(404, ex.getStatusCode());
        assertEquals(WadoRsHttpException.Reason.STUDY_NOT_FOUND, ex.getReason());
        assertFalse(ex.isRetryable(), "404 重试无意义");
        assertTrue(ex.getResponseBody().contains("study not found"));
    }

    @Test
    @DisplayName("416 → RANGE_NOT_SATISFIABLE，不可重试，且不返回空列表")
    void rangeNotSatisfiableThrows() {
        stubStatus(416, "application/dicom", "requested range not satisfiable");

        WadoRsHttpException ex = assertThrows(WadoRsHttpException.class,
                () -> client.fetchStudy(PEER, STUDY));

        assertEquals(416, ex.getStatusCode());
        assertEquals(WadoRsHttpException.Reason.RANGE_NOT_SATISFIABLE, ex.getReason());
        assertFalse(ex.isRetryable());
    }

    @Test
    @DisplayName("500 → SERVER_ERROR，可重试")
    void serverErrorIsRetryable() {
        stubStatus(500, "text/plain", "upstream gateway timeout");

        WadoRsHttpException ex = assertThrows(WadoRsHttpException.class,
                () -> client.fetchStudy(PEER, STUDY));

        assertEquals(500, ex.getStatusCode());
        assertEquals(WadoRsHttpException.Reason.SERVER_ERROR, ex.getReason());
        assertTrue(ex.isRetryable(), "5xx 应标记可重试供 task 12 接管");
    }

    @Test
    @DisplayName("403 → CLIENT_ERROR，不可重试")
    void forbiddenIsNotRetryable() {
        stubStatus(403, "text/plain", "token scope insufficient");

        WadoRsHttpException ex = assertThrows(WadoRsHttpException.class,
                () -> client.fetchStudy(PEER, STUDY));

        assertEquals(403, ex.getStatusCode());
        assertEquals(WadoRsHttpException.Reason.CLIENT_ERROR, ex.getReason());
        assertFalse(ex.isRetryable());
    }

    // ---------- 解析失败路径 ----------

    @Test
    @DisplayName("multipart/related 未声明 boundary → MISSING_BOUNDARY")
    void missingBoundaryThrows() {
        stubMultipart(200, "multipart/related; type=\"application/dicom\"",
                TestDicomFixtures.dicomInstance(STUDY, 1));

        WadoRsParseException ex = assertThrows(WadoRsParseException.class,
                () -> client.fetchStudy(PEER, STUDY));

        assertEquals(WadoRsParseException.ParseError.MISSING_BOUNDARY, ex.getParseError());
    }

    @Test
    @DisplayName("part 缺 DICOM Part-10 前导 → INVALID_DICOM_PREAMBLE（拒绝写库，不当空对象放过）")
    void partWithoutDicomPreambleIsRejected() {
        stubMultipart(200, MULTIPART_CT,
                TestDicomFixtures.dicomInstance(STUDY, 1),
                TestDicomFixtures.notDicom(256));

        WadoRsParseException ex = assertThrows(WadoRsParseException.class,
                () -> client.fetchStudy(PEER, STUDY));

        assertEquals(WadoRsParseException.ParseError.INVALID_DICOM_PREAMBLE, ex.getParseError());
        assertTrue(ex.getMessage().contains("part#2"), "报错应定位到出问题的 part 序号");
    }

    @Test
    @DisplayName("响应在 part 结束前断流 → TRUNCATED_MULTIPART")
    void truncatedMultipartIsRejected() {
        byte[] full = TestDicomFixtures.multipartRelated(BOUNDARY, "application/dicom",
                TestDicomFixtures.dicomInstance(STUDY, 1),
                TestDicomFixtures.dicomInstance(STUDY, 2));
        byte[] truncated = Arrays.copyOf(full, full.length - 40);
        stubBody(200, MULTIPART_CT, truncated);

        WadoRsParseException ex = assertThrows(WadoRsParseException.class,
                () -> client.fetchStudy(PEER, STUDY));

        assertEquals(WadoRsParseException.ParseError.TRUNCATED_MULTIPART, ex.getParseError());
    }

    @Test
    @DisplayName("非 multipart 的 application/dicom 单对象响应被包成 1 个 part")
    void singleApplicationDicomResponseIsAccepted() {
        byte[] one = TestDicomFixtures.dicomInstance(STUDY, 1);
        stubBody(200, "application/dicom", one);

        List<ByteBuffer> instances = client.fetchStudy(PEER, STUDY);

        assertEquals(1, instances.size());
        assertEquals(one.length, instances.get(0).remaining());
    }

    @Test
    @DisplayName("响应体超过 max-multipart-bytes → MULTIPART_TOO_LARGE")
    void oversizedResponseIsRejected() {
        WadoConfigProps small = new WadoConfigProps();
        small.setBaseUrlTemplate(props.getBaseUrlTemplate());
        small.setMaxMultipartBytes(64);
        MultipartDicomExtractor smallExtractor = new MultipartDicomExtractor(small);
        WadoRsClient smallClient = new WadoRsClient(small, smallExtractor);
        stubMultipart(200, MULTIPART_CT, TestDicomFixtures.dicomInstance(STUDY, 1));

        WadoRsParseException ex = assertThrows(WadoRsParseException.class,
                () -> smallClient.fetchStudy(PEER, STUDY));

        assertEquals(WadoRsParseException.ParseError.MULTIPART_TOO_LARGE, ex.getParseError());
    }

    @Test
    @DisplayName("studyUid 形态非法 → 发请求前就拒绝（ValidationException，零外发调用）")
    void invalidStudyUidIsRejectedBeforeHttpCall() {
        stubMultipart(200, MULTIPART_CT, TestDicomFixtures.dicomInstance(STUDY, 1));

        assertThrows(ValidationException.class, () -> client.fetchStudy(PEER, "../../etc/passwd"));
        assertThrows(ValidationException.class, () -> client.fetchStudy(PEER, "study-1"));
        assertThrows(ValidationException.class, () -> client.fetchStudy(PEER, null));

        assertTrue(server.getAllServeEvents().isEmpty(),
                "非法 UID 必须在出网前拦下，不应产生任何 HTTP 调用");
    }

    @Test
    @DisplayName("boundary 参数无引号写法（boundary=abc）也能解析")
    void unquotedBoundaryParameterIsAccepted() {
        String unquoted = "multipart/related; type=\"application/dicom\"; boundary=" + BOUNDARY;
        stubMultipart(200, unquoted,
                TestDicomFixtures.dicomInstance(STUDY, 1),
                TestDicomFixtures.dicomInstance(STUDY, 2));

        assertEquals(2, client.fetchStudy(PEER, STUDY).size());
    }

    @Test
    @DisplayName("multipart 无 part → EMPTY_MULTIPART（不静默返回空列表）")
    void emptyMultipartIsRejected() {
        byte[] onlyBoundary = ("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        stubBody(200, MULTIPART_CT, onlyBoundary);

        WadoRsParseException ex = assertThrows(WadoRsParseException.class,
                () -> client.fetchStudy(PEER, STUDY));

        assertEquals(WadoRsParseException.ParseError.EMPTY_MULTIPART, ex.getParseError());
    }

    // ---------- 辅助 ----------

    private void stubMultipart(int status, String contentType, byte[]... parts) {
        stubBody(status, contentType, TestDicomFixtures.multipartRelated(
                BOUNDARY, "application/dicom", parts));
    }

    private void stubStatus(int status, String contentType, String body) {
        stubBody(status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private void stubBody(int status, String contentType, byte[] body) {
        server.stubFor(get(urlPathMatching("/wado/studies/.*"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", contentType)
                        .withBody(body)));
    }
}