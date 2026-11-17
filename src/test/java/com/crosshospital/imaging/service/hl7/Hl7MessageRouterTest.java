package com.crosshospital.imaging.service.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.crosshospital.imaging.config.Hl7ConfigProps;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.Hl7DeadLetter;
import com.crosshospital.imaging.domain.RouteConfig;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.exception.Hl7ParseException;
import com.crosshospital.imaging.exception.Hl7TriggerRejectedException;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.repository.Hl7DeadLetterRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.repository.RouteConfigRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.routing.PeerRoutingResolver;
import com.crosshospital.imaging.service.task.FetchTaskService;
import com.crosshospital.imaging.service.task.InMemoryFetchTaskQueue;
import com.crosshospital.imaging.support.OracleOrderMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Phase 4 · Task 19：触发报文路由器的业务语义测试。
 *
 * <p>覆盖 task 19 的验收口径：合法 ORM^O01 → {@code imaging_fetch_tasks} 出现
 * {@code status=queued} + 审计新增 1 行；以及三条异常路径（解析失败 / 缺 PID /
 * 消息类型不支持）全部进死信、且不假装成功（异常向上抛，线路层据此回 NAK）。
 *
 * <p>持久层用 Mockito 替身（开发机无 PostgreSQL 实例，Flyway 迁移是 PostgreSQL 方言），
 * 但解析链路走<b>真实现</b>：{@link Hl7MessageParser} + {@link Hl7SegmentFieldExtractor}，
 * 报文来自共享夹具 {@link OracleOrderMessage}，避免"测试自己造的报文恰好能被自己解析"。
 *
 * <p>{@link AuditService} 是具体类，本机 JVM（Java 27）上 ByteBuddy 无法内联改写，
 * 因此按仓库既有惯例改用自写桩（见 {@link CapturingAuditService}）。
 */
@DisplayName("Task 19 — HL7 触发报文路由 / 入队 / 死信分支")
class Hl7MessageRouterTest {

    private static final Charset CS = StandardCharsets.UTF_8;
    private static final String PEER_AET = "PEER_HZ";

    private final FetchTaskRepository taskRepository = mock(FetchTaskRepository.class);
    private final Hl7DeadLetterRepository deadLetterRepository = mock(Hl7DeadLetterRepository.class);
    private final RouteConfigRepository routeConfigRepository = mock(RouteConfigRepository.class);
    private final PeerRepository peerRepository = mock(PeerRepository.class);

    private final List<Hl7DeadLetter> deadLetters = new ArrayList<>();
    private CapturingAuditService auditService;
    private InMemoryFetchTaskQueue queue;
    private FetchTaskService fetchTaskService;
    private PeerRoutingResolver peerRoutingResolver;
    private Hl7DeadLetterHandler deadLetterHandler;
    private Hl7MessageRouter router;

    @BeforeEach
    void setUp() {
        when(taskRepository.save(any(FetchTask.class))).thenAnswer(inv -> inv.getArgument(0));
        deadLetters.clear();
        when(deadLetterRepository.save(any(Hl7DeadLetter.class))).thenAnswer(inv -> {
            Hl7DeadLetter row = inv.getArgument(0);
            deadLetters.add(row);
            return row;
        });

        // 运行期配置：主索引命名空间 PAT → 外院节点 PEER_HZ
        RouteConfig namespaces = new RouteConfig();
        namespaces.setKey(PeerRoutingResolver.CONFIG_MPI_NAMESPACES);
        namespaces.setValue("{\"PAT\": \"" + PEER_AET + "\"}");
        when(routeConfigRepository.findByKey(PeerRoutingResolver.CONFIG_MPI_NAMESPACES))
                .thenReturn(Optional.of(namespaces));
        when(peerRepository.existsByPeerAet(PEER_AET)).thenReturn(true);

        auditService = new CapturingAuditService();
        queue = new InMemoryFetchTaskQueue();
        fetchTaskService = new FetchTaskService(taskRepository, queue);
        peerRoutingResolver = new PeerRoutingResolver(routeConfigRepository, peerRepository, new ObjectMapper());
        deadLetterHandler = new Hl7DeadLetterHandler(deadLetterRepository, props(), new ObjectMapper(),
                HttpClient.newHttpClient(), Runnable::run);
        router = new Hl7MessageRouter(
                new Hl7MessageParser(new Hl7SegmentFieldExtractor()),
                new OrderMessageHandler(fetchTaskService, auditService, peerRoutingResolver, deadLetterHandler),
                new AdmitMessageHandler(auditService),
                deadLetterHandler,
                props());
    }

    private static Hl7ConfigProps props() {
        Hl7ConfigProps props = new Hl7ConfigProps();
        props.getTrigger().setCharset("UTF-8");
        props.getTrigger().setReleaseMode("MLLP2");
        props.getTrigger().setPort(0);
        props.getTrigger().setBindHost("127.0.0.1");
        return props;
    }

    private static MllpFrame frame(byte[] payload) {
        return new MllpFrame(payload, "127.0.0.1:51234", Instant.now(), 1L);
    }

    // ------------------------------------------------------------------ 成功路径

    @Test
    @DisplayName("合法 ORM^O01 → 落一行 status=queued 的调阅任务 + 审计新增 1 行 + 投进执行队列")
    void validOrmEnqueuesTaskAndWritesAudit() {
        router.route(frame(OracleOrderMessage.ormO01(CS)));

        ArgumentCaptor<FetchTask> task = ArgumentCaptor.forClass(FetchTask.class);
        verify(taskRepository).save(task.capture());
        FetchTask saved = task.getValue();
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(saved.getPatientMpi()).isEqualTo(OracleOrderMessage.PATIENT_MPI);
        assertThat(saved.getStudyUid()).isEqualTo(OracleOrderMessage.ACCESSION);
        assertThat(saved.getPeerAet()).isEqualTo(PEER_AET);
        assertThat(saved.getInitiatedSrc()).isEqualTo("hl7");
        assertThat(saved.getInitiatedBy()).isEqualTo("HIS_APP");
        assertThat(saved.getRetryCount()).isZero();

        assertThat(auditService.rows).hasSize(1);
        AuditRow row = auditService.rows.get(0);
        assertThat(row.getStatusTerminal()).isEqualTo(OrderMessageHandler.STATUS_QUEUED);
        assertThat(row.getWho()).isEqualTo("HIS_APP");
        assertThat(row.getToAet()).isEqualTo(PEER_AET);
        assertThat(row.getStudyUid()).isEqualTo(OracleOrderMessage.ACCESSION);
        assertThat(row.getFailureCode()).isNull();

        assertThat(queue.size()).isEqualTo(1);
        assertThat(deadLetters).isEmpty();
    }

    @Test
    @DisplayName("同一 (patient_mpi, study_uid) 重复触发 → 复用既有任务，不建第二个（幂等）")
    void duplicateTriggerReusesExistingTask() {
        FetchTask existing = new FetchTask();
        existing.setTaskId(java.util.UUID.randomUUID());
        existing.setStatus(TaskStatus.QUEUED);
        when(taskRepository.findActiveByPatientMpiAndStudyUid(
                OracleOrderMessage.PATIENT_MPI, OracleOrderMessage.ACCESSION))
                .thenReturn(Optional.of(existing));

        router.route(frame(OracleOrderMessage.ormO01(CS)));

        verify(taskRepository, never()).save(any(FetchTask.class));
        assertThat(queue.size()).isZero(); // 既有任务已在流程里，不重复投队列
        assertThat(auditService.rows).hasSize(1);
    }

    @Test
    @DisplayName("ADT^A04 入院登记 → 只留审计痕迹，不发起调阅（调阅由影像医嘱触发）")
    void admitMessageIsObservedButDoesNotEnqueue() {
        router.route(frame(OracleOrderMessage.adtA04(CS)));

        verify(taskRepository, never()).save(any(FetchTask.class));
        assertThat(auditService.rows).hasSize(1);
        assertThat(auditService.rows.get(0).getStatusTerminal())
                .isEqualTo(AdmitMessageHandler.STATUS_OBSERVED);
        assertThat(queue.size()).isZero();
        assertThat(deadLetters).isEmpty();
    }

    // ------------------------------------------------------------------ 异常路径（进死信 + 不假装成功）

    @Test
    @DisplayName("首段不是 MSH → 进死信 invalid_msh + 异常上抛（线路层回 NAK）")
    void invalidMshGoesToDeadLetterAndIsRethrown() {
        assertThatThrownBy(() -> router.route(frame(OracleOrderMessage.notMshAtAll(CS))))
                .isInstanceOf(Hl7ParseException.class)
                .satisfies(ex -> assertThat(((Hl7ParseException) ex).getCode())
                        .isEqualTo(Hl7ParseException.FailureCode.INVALID_MSH));

        assertThat(deadLetterCodes()).containsExactly(Hl7DeadLetter.FailureCode.INVALID_MSH);
        verify(taskRepository, never()).save(any(FetchTask.class));
    }

    @Test
    @DisplayName("缺 PID 段 → 进死信 missing_pid + 异常上抛")
    void missingPidGoesToDeadLetterAndIsRethrown() {
        assertThatThrownBy(() -> router.route(frame(OracleOrderMessage.ormO01WithoutPid(CS))))
                .isInstanceOf(Hl7ParseException.class)
                .satisfies(ex -> assertThat(((Hl7ParseException) ex).getCode())
                        .isEqualTo(Hl7ParseException.FailureCode.MISSING_PID));

        assertThat(deadLetterCodes()).containsExactly(Hl7DeadLetter.FailureCode.MISSING_PID);
        verify(taskRepository, never()).save(any(FetchTask.class));
    }

    @Test
    @DisplayName("消息类型不支持（SIU^S12）→ 进死信 unsupported_message_type + 异常上抛")
    void unsupportedMessageTypeGoesToDeadLetterAndIsRethrown() {
        assertThatThrownBy(() -> router.route(frame(OracleOrderMessage.unsupportedSiuS12(CS))))
                .isInstanceOf(Hl7ParseException.class)
                .satisfies(ex -> assertThat(((Hl7ParseException) ex).getCode())
                        .isEqualTo(Hl7ParseException.FailureCode.UNSUPPORTED_MESSAGE_TYPE));

        assertThat(deadLetterCodes()).containsExactly(Hl7DeadLetter.FailureCode.UNSUPPORTED_MESSAGE_TYPE);
        verify(taskRepository, never()).save(any(FetchTask.class));
    }

    @Test
    @DisplayName("OBR-3 影像引用为空 → 进死信 missing_study_reference + 异常上抛")
    void missingStudyReferenceGoesToDeadLetterAndIsRethrown() {
        assertThatThrownBy(() -> router.route(frame(OracleOrderMessage.ormO01WithoutStudyReference(CS))))
                .isInstanceOf(Hl7TriggerRejectedException.class)
                .satisfies(ex -> assertThat(((Hl7TriggerRejectedException) ex).getFailureCode())
                        .isEqualTo(Hl7DeadLetter.FailureCode.MISSING_STUDY_REFERENCE));

        assertThat(deadLetterCodes()).containsExactly(Hl7DeadLetter.FailureCode.MISSING_STUDY_REFERENCE);
        verify(taskRepository, never()).save(any(FetchTask.class));
    }

    @Test
    @DisplayName("找不到承接外院 → 进死信 no_target_peer，但**不**上抛（报文没毛病，回 NAK 只会重投风暴）")
    void noTargetPeerIsDeadLetteredWithoutFailingTheTrigger() {
        when(routeConfigRepository.findByKey(PeerRoutingResolver.CONFIG_MPI_NAMESPACES))
                .thenReturn(Optional.empty());

        router.route(frame(OracleOrderMessage.ormO01(CS))); // 不抛

        assertThat(deadLetterCodes()).containsExactly(Hl7DeadLetter.FailureCode.NO_TARGET_PEER);
        verify(taskRepository, never()).save(any(FetchTask.class));
        assertThat(auditService.rows).hasSize(1);
        assertThat(auditService.rows.get(0).getStatusTerminal())
                .isEqualTo(OrderMessageHandler.STATUS_NO_TARGET_PEER);
        assertThat(auditService.rows.get(0).getFailureCode()).isEqualTo("no_target_peer");
    }

    @Test
    @DisplayName("映射到的节点未在准入表登记 → 按未定位处理（不排注定建联失败的任务）")
    void unregisteredPeerIsTreatedAsNoTarget() {
        when(peerRepository.existsByPeerAet(PEER_AET)).thenReturn(false);

        router.route(frame(OracleOrderMessage.ormO01(CS)));

        assertThat(deadLetterCodes()).containsExactly(Hl7DeadLetter.FailureCode.NO_TARGET_PEER);
        verify(taskRepository, never()).save(any(FetchTask.class));
    }

    @Test
    @DisplayName("入队失败（数据库不可用）→ 进死信 enqueue_failed + 异常上抛")
    void enqueueFailureGoesToDeadLetterAndIsRethrown() {
        when(taskRepository.save(any(FetchTask.class))).thenThrow(new IllegalStateException("数据库连接不可用"));

        assertThatThrownBy(() -> router.route(frame(OracleOrderMessage.ormO01(CS))))
                .isInstanceOf(Hl7TriggerRejectedException.class)
                .satisfies(ex -> assertThat(((Hl7TriggerRejectedException) ex).getFailureCode())
                        .isEqualTo(Hl7DeadLetter.FailureCode.ENQUEUE_FAILED));

        assertThat(deadLetterCodes()).containsExactly(Hl7DeadLetter.FailureCode.ENQUEUE_FAILED);
    }

    @Test
    @DisplayName("死信表本身写失败 → 不掩盖原始失败原因（原始异常照样上抛）")
    void deadLetterStoreFailureDoesNotMaskTheOriginalError() {
        when(deadLetterRepository.save(any(Hl7DeadLetter.class)))
                .thenThrow(new IllegalStateException("死信表不可写"));

        assertThatThrownBy(() -> router.route(frame(OracleOrderMessage.ormO01WithoutPid(CS))))
                .isInstanceOf(Hl7ParseException.class)
                .satisfies(ex -> assertThat(((Hl7ParseException) ex).getCode())
                        .isEqualTo(Hl7ParseException.FailureCode.MISSING_PID));
    }

    @Test
    @DisplayName("死信行不落患者身份字段：只存 MSH 摘要 + 控制 ID + 分类")
    void deadLetterRowDoesNotPersistPatientIdentity() {
        assertThatThrownBy(() -> router.route(frame(OracleOrderMessage.ormO01WithoutPid(CS))))
                .isInstanceOf(Hl7ParseException.class);

        assertThat(deadLetters).hasSize(1);
        String excerpt = deadLetters.get(0).getMshExcerpt();
        assertThat(excerpt).contains("HIS_APP").contains("MSG_EXAMPLE_0001");
        assertThat(excerpt).doesNotContain(OracleOrderMessage.PATIENT_MPI);
        assertThat(excerpt).doesNotContain("<EXAMPLE_PATIENT_NAME>");
        assertThat(deadLetters.get(0).getPayloadBytes()).isPositive();
    }

    @Test
    @DisplayName("PID-3 复合字段只取第一个分量落 patient_mpi（幂等键不被赋号机构污染）")
    void patientMpiUsesFirstComponentOnly() {
        router.route(frame(OracleOrderMessage.ormO01(CS)));

        ArgumentCaptor<FetchTask> task = ArgumentCaptor.forClass(FetchTask.class);
        verify(taskRepository).save(task.capture());
        assertThat(task.getValue().getPatientMpi()).isEqualTo(OracleOrderMessage.PATIENT_MPI);
        assertThat(task.getValue().getPatientMpi()).doesNotContain("^");
    }

    // ------------------------------------------------------------------ 装配自检

    @Test
    @DisplayName("路由器装配拒绝 null 依赖（不等到第一条报文才 NPE）")
    void rejectsNullDependencies() {
        assertThatThrownBy(() -> new Hl7MessageRouter(null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderMessageHandler(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmitMessageHandler(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Hl7DeadLetterHandler(null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("路由器暴露支持的消息类型与死信分类全集（供健康检查展示）")
    void exposesSupportedRouteKeysAndDeadLetterCodes() {
        assertThat(router.supportedRouteKeys())
                .containsExactlyInAnyOrder("ORM^O01", "ADT^A04", "ADT^A08");
        assertThat(router.deadLetterCodes())
                .contains("invalid_msh", "missing_pid", "unsupported_message_type",
                        "missing_study_reference", "no_target_peer", "enqueue_failed");
    }

    @Test
    @DisplayName("死信分类码全小写下划线，且与 segment-mappings.yml protocol-errors 块同形")
    void deadLetterCodesAreLowerSnakeCase() {
        for (Hl7DeadLetter.FailureCode c : Hl7DeadLetter.FailureCode.values()) {
            assertThat(c.code()).matches("[a-z_]+");
        }
        assertThatThrownBy(() -> Hl7DeadLetter.FailureCode.fromDbValue("UNKNOWN_CODE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Hl7DeadLetter.FailureCode.fromDbValue("missing_pid"))
                .isEqualTo(Hl7DeadLetter.FailureCode.MISSING_PID);
    }

    @Test
    @DisplayName("未配置告警 webhook 时不外发（死信仍落库）")
    void noAlertWebhookConfiguredMeansNoOutboundCall() {
        Hl7ConfigProps props = props();
        assertThat(props.getDeadLetter().resolveAlertWebhookUrl()).isNull();
        assertThat(props.getDeadLetter().getAlertTimeoutMs()).isPositive();
        props.getDeadLetter().setAlertTimeoutMs(0);
        assertThatThrownBy(() -> props.getDeadLetter().validate())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 小工具

    /** 取出本轮落下的死信分类（没有落任何一行时返回空表）。 */
    private List<Hl7DeadLetter.FailureCode> deadLetterCodes() {
        return deadLetters.stream().map(Hl7DeadLetter::getFailureCode).toList();
    }

    /**
     * 审计替身：{@code AuditService} 是具体类，本机 JVM 上 ByteBuddy 无法内联改写，
     * 按仓库既有惯例自写桩（记录每次 record 的入参）。
     */
    static final class CapturingAuditService extends AuditService {

        final List<AuditRow> rows = new ArrayList<>();

        CapturingAuditService() {
            super(null);
        }

        @Override
        public AuditRow record(AuditRow row) {
            rows.add(row);
            return row;
        }
    }
}
