package com.crosshospital.imaging.support;

import au.com.dius.pact.consumer.ConsumerPactBuilder;
import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * task 34 的 Pact 契约夹具：consumer DSL 定义一份真实的 Pact JSON，
 * 写到 {@code target/pacts/}，再由 provider 侧回放验证。
 *
 * <p>契约里全是脱敏占位值（UID / 工号 / 主索引），不来自真实医院数据。
 */
public final class PactFixtures {

    public static final String CONSUMER = "his-workorder";
    public static final String PROVIDER = "imaging-router";
    public static final String FETCH_PATH = "/api/imaging/studies/fetch";
    public static final Path PACT_FILE =
            Path.of("target/pacts/" + CONSUMER + "-" + PROVIDER + ".json");

    public static final String REQUEST_JSON = """
            {"patientMpi":"HZH^1234567","studyUid":"1.2.840.10008.5.1.4.1.1.2.EXAMPLE",
             "sopClassUid":"1.2.840.10008.5.1.4.1.1.2","peerAet":"PEER_HZ"}
            """;

    public static final String RESPONSE_JSON = """
            {"taskId":"3f8a2f16-9a5e-4c11-9c53-2b9d1e0a7c44","status":"queued",
             "links":{"self":"/api/imaging/tasks/3f8a2f16-9a5e-4c11-9c53-2b9d1e0a7c44"}}
            """;

    private PactFixtures() {
    }

    /** 用 Pact consumer DSL 定义「一键发起跨院影像调阅」的 request / response 契约。 */
    public static RequestResponsePact pact() {
        return ConsumerPactBuilder.consumer(CONSUMER)
                .hasPactWith(PROVIDER)
                .uponReceiving("一键发起跨院影像调阅")
                .path(FETCH_PATH)
                .method("POST")
                .headers(Map.of("Content-Type", "application/json"))
                .body(REQUEST_JSON)
                .willRespondWith()
                .status(202)
                .headers(Map.of("Content-Type", "application/json"))
                .body(RESPONSE_JSON)
                .toPact();
    }

    /** 把契约写成真实 Pact JSON 文件，并返回文本（供断言与 provider 回放）。 */
    public static String writePact() throws IOException {
        StringWriter out = new StringWriter();
        DefaultPactWriter.INSTANCE.writePact(pact(), new PrintWriter(out), PactSpecVersion.V3);
        Files.createDirectories(PACT_FILE.getParent());
        Files.writeString(PACT_FILE, out.toString(), StandardCharsets.UTF_8);
        return out.toString();
    }
}
