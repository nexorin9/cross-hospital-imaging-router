package com.crosshospital.imaging.tools;

import com.crosshospital.imaging.ImagerApplication;
import com.crosshospital.imaging.config.RoutingConfigProps;
import com.crosshospital.imaging.service.routing.MoveDestinationStoreScp;
import com.crosshospital.imaging.support.EmbeddedPostgresSupport;
import com.crosshospital.imaging.support.FakeLocalPacs;
import org.dcm4che3.data.Attributes;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Task 39 — 采集真实运行截图时使用的「真实服务」启动器。
 *
 * <p><b>不是 mock 服务、不是设计稿</b>：本类在同一个 JVM 里起四样真东西，
 * 然后用 {@code SpringApplication.run} 以默认生产 profile 启动完整应用：
 * <ol>
 *   <li>真 PostgreSQL（embedded-postgres 自带二进制，V1–V10 Flyway 迁移原样跑一遍）；</li>
 *   <li>本院 PACS 真 dcm4che C-STORE-SCP（{@link FakeLocalPacs}）；</li>
 *   <li>外院 PACS 真 dcm4che SCP（{@link EvidenceCombinedPacs}，同一端口同时
 *       提供 C-FIND / C-MOVE，字节来自磁盘上 3 个真实 DICOM Part-10 fixture）；</li>
 *   <li>真正的 Spring Boot Web 容器（8080）、springdoc Swagger UI、全部
 *       {@code @Profile("!test")} 生产 bean。</li>
 * </ol>
 *
 * <p>启动完成后在 {@code target/evidence-runtime/} 写出 READY 标记、peer 端口、
 * 建节点 / 发起调阅的请求体，供 {@code scripts/capture-evidence.sh} 用真实 curl 打接口。
 * 采集结束后由脚本杀掉本进程，不留在后台。
 *
 * <p>本类只存在于 test source / test classpath，不打进生产包。
 */
public final class RealRuntimeLauncher {

    private static final String PEER_AET = "PEER_DEMO";
    private static final String LOCAL_PACS_AET = "LOCAL_PACS";
    private static final String PATIENT_MPI = "PAT_MPI_EXAMPLE";
    private static final String ACCESSION = "ACC_EXAMPLE";
    private static final String STUDY_UID = "1.2.826.0.1.3680043.10.9999.1.20260101";
    private static final String CT_STORAGE = "1.2.840.10008.5.1.4.1.1.2";
    private static final String EXPLICIT_VR_LE = "1.2.840.10008.1.2.1";
    private static final String IMPLICIT_VR_LE = "1.2.840.10008.1.2";

    private RealRuntimeLauncher() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of("target", "evidence-runtime");
        Files.createDirectories(out);

        if (!EmbeddedPostgresSupport.isAvailable()) {
            throw new IllegalStateException("内嵌 PostgreSQL 不可用："
                    + EmbeddedPostgresSupport.failureMessage());
        }
        String jdbcUrl = EmbeddedPostgresSupport.jdbcUrl();

        // 本院 PACS：真 C-STORE-SCP，端口先定下来再传给 Spring
        FakeLocalPacs localPacs = FakeLocalPacs.normal(LOCAL_PACS_AET, 0).start();
        int localPacsPort = localPacs.getPort();

        SpringApplication application = new SpringApplication(ImagerApplication.class);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);

        ConfigurableApplicationContext ctx = application.run(
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + EmbeddedPostgresSupport.username(),
                "--spring.datasource.password=" + EmbeddedPostgresSupport.password(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.flyway.enabled=true",
                "--spring.jpa.hibernate.ddl-auto=none",
                "--server.port=8080",
                "--hl7.trigger.port=0",
                "--routing.local-move-dest-port=0",
                "--routing.local-pacs-port=" + localPacsPort,
                "--dicom.remote-connect-timeout-ms=2000",
                "--routing.query-timeout-ms=8000",
                "--routing.retrieve-timeout-ms=8000",
                "--routing.store-timeout-ms=8000",
                "--model.base-url=",
                "--model.fallback.enabled=true",
                "--notification.his-webhook-url=",
                "--logging.level.root=INFO");

        // 生产容器里的本端 C-STORE-SCP sink：C-MOVE 的 sub-op 字节落点
        MoveDestinationStoreScp moveDestination = ctx.getBean(MoveDestinationStoreScp.class);
        RoutingConfigProps routingProps = ctx.getBean(RoutingConfigProps.class);

        List<byte[]> dicomInstances = EvidenceCombinedPacs.loadDicomFixtures(
                Path.of("src", "test", "fixtures", "samples-dicom"));
        List<String> sopUids = List.of(
                "1.2.826.0.1.3680043.10.9999.1.20260101.1",
                "1.2.826.0.1.3680043.10.9999.1.20260101.2",
                "1.2.826.0.1.3680043.10.9999.1.20260101.3");
        Attributes study = EvidenceCombinedPacs.studyOf(PATIENT_MPI, ACCESSION, STUDY_UID,
                "CT", "20260101", dicomInstances.size());

        EvidenceCombinedPacs peerPacs = new EvidenceCombinedPacs(PEER_AET, 0, study,
                dicomInstances, sopUids, moveDestination.getSink(),
                routingProps.getLocalMoveDestAet()).start();
        int peerPort = peerPacs.getPort();

        String peerCreateJson = """
                {
                  "peerAet": "%s",
                  "host": "127.0.0.1",
                  "port": %d,
                  "tlsMode": "none",
                  "presentContexts": ["%s", "%s", "%s"],
                  "transferSyntaxes": ["%s", "%s"],
                  "blacklistAets": [],
                  "maxRetries": 1,
                  "backoffMs": 1000,
                  "notes": "实测截图用本地 DICOM SCP，自造示例 UID，无真实患者数据"
                }
                """.formatted(PEER_AET, peerPort,
                EvidenceCombinedPacs.STUDY_ROOT_FIND,
                EvidenceCombinedPacs.STUDY_ROOT_MOVE, CT_STORAGE,
                EXPLICIT_VR_LE, IMPLICIT_VR_LE);
        Files.writeString(out.resolve("peer-create.json"), peerCreateJson, StandardCharsets.UTF_8);

        String fetchCreateJson = """
                {
                  "patientMpi": "%s",
                  "studyUid": "%s",
                  "peerAet": "%s",
                  "accessionNumber": "%s"
                }
                """.formatted(PATIENT_MPI, STUDY_UID, PEER_AET, ACCESSION);
        Files.writeString(out.resolve("fetch-create.json"), fetchCreateJson, StandardCharsets.UTF_8);

        Files.writeString(out.resolve("facts.json"), """
                {
                  "peerAet": "%s",
                  "peerPort": %d,
                  "localPacsPort": %d,
                  "studyUid": "%s",
                  "patientMpi": "%s",
                  "dicomInstances": %d,
                  "jdbcUrl": "%s",
                  "serverPort": 8080
                }
                """.formatted(PEER_AET, peerPort, localPacsPort, STUDY_UID, PATIENT_MPI,
                dicomInstances.size(), jdbcUrl), StandardCharsets.UTF_8);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            peerPacs.close();
            localPacs.close();
            EmbeddedPostgresSupport.close();
        }, "evidence-runtime-cleanup"));

        Files.writeString(out.resolve("READY"),
                "started at " + java.time.Instant.now() + System.lineSeparator());
        System.out.println("[evidence] READY peerPort=" + peerPort
                + " localPacsPort=" + localPacsPort + " jdbc=" + jdbcUrl);
        System.out.println("[evidence] swagger=http://127.0.0.1:8080/swagger-ui.html");
        ctx.registerShutdownHook();
        new CountDownLatch(1).await();
    }
}
