package com.crosshospital.imaging.service.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.crosshospital.imaging.config.Hl7ConfigProps;
import com.crosshospital.imaging.config.MllpServerConfig;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.Hl7DeadLetter;
import com.crosshospital.imaging.domain.RouteConfig;
import com.crosshospital.imaging.domain.TaskStatus;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Phase 4 · Task 19 端到端：真 MLLP 线路 + 真触发路由器。
 *
 * <p>本类不 mock 传输层 —— HIS 侧的 MLLP 通道就是一个裸 TCP 客户端，
 * 用真 Socket 打进去，才能验证「报文进来 → ACK/NAK」这条链上，
 * <b>ACK 究竟代表什么</b>：
 *
 * <ul>
 *   <li>合法 ORM^O01 → ACK，<b>并且</b> {@code imaging_fetch_tasks} 真多出一行
 *       {@code status=queued}、审计多出一行；</li>
 *   <li>报文不合法 → NAK + 断连 + 死信表多出一行（不假装收下）；</li>
 *   <li>ADT^A04 → ACK + 审计留痕，但<b>不</b>发起调阅。</li>
 * </ul>
 *
 * <p>源主路径对照：ACK/NAK 的线路字节与 {@code MLLPConnection} 完全一致
 * （{@code 0x0B 0x06 0x1C 0x0D} / {@code 0x0B 0x15 0x1C 0x0D}），
 * 因此对接本守护的 HIS 无需改任何 MLLP 参数。
 */
@DisplayName("Task 19 端到端 — MLLP 线路 → 触发路由 → 调阅任务")
class Hl7TriggerEndToEndTest {

    private static final Charset CS = StandardCharsets.UTF_8;
    private static final int READ_TIMEOUT_MS = 3000;
    private static final String PEER_AET = "PEER_HZ";

    private final FetchTaskRepository taskRepository = mock(FetchTaskRepository.class);
    private final Hl7DeadLetterRepository deadLetterRepository = mock(Hl7DeadLetterRepository.class);
    private final RouteConfigRepository routeConfigRepository = mock(RouteConfigRepository.class);
    private final PeerRepository peerRepository = mock(PeerRepository.class);

    private final List<Hl7DeadLetter> deadLetters = new ArrayList<>();
    private final List<AuditRow> auditRows = new ArrayList<>();
    private final List<FetchTask> savedTasks = new ArrayList<>();

    private InMemoryFetchTaskQueue queue;
    private MllpTriggerService service;
    private int port;

    @BeforeEach
    void setUp() {
        when(taskRepository.save(any(FetchTask.class))).thenAnswer(inv -> {
            FetchTask t = inv.getArgument(0);
            savedTasks.add(t);
            return t;
        });
        when(deadLetterRepository.save(any(Hl7DeadLetter.class))).thenAnswer(inv -> {
            Hl7DeadLetter row = inv.getArgument(0);
            deadLetters.add(row);
            return row;
        });

        RouteConfig namespaces = new RouteConfig();
        namespaces.setKey(PeerRoutingResolver.CONFIG_MPI_NAMESPACES);
        namespaces.setValue("{\"PAT\": \"" + PEER_AET + "\"}");
        when(routeConfigRepository.findByKey(PeerRoutingResolver.CONFIG_MPI_NAMESPACES))
                .thenReturn(Optional.of(namespaces));
        when(peerRepository.existsByPeerAet(PEER_AET)).thenReturn(true);

        queue = new InMemoryFetchTaskQueue();
        FetchTaskService fetchTaskService = new FetchTaskService(taskRepository, queue);
        PeerRoutingResolver resolver = new PeerRoutingResolver(routeConfigRepository, peerRepository,
                new ObjectMapper());
        Hl7DeadLetterHandler deadLetterHandler = new Hl7DeadLetterHandler(deadLetterRepository, props(),
                new ObjectMapper(), java.net.http.HttpClient.newHttpClient(), Runnable::run);
        Hl7MessageRouter router = new Hl7MessageRouter(
                new Hl7MessageParser(new Hl7SegmentFieldExtractor()),
                new OrderMessageHandler(fetchTaskService, new CapturingAuditService(auditRows), resolver, deadLetterHandler),
                new AdmitMessageHandler(new CapturingAuditService(auditRows)),
                deadLetterHandler,
                props());

        MllpAckResponder ack = new MllpAckResponder(MllpRelease.MLLP2);
        service = new MllpTriggerService(props(), router, ack,
                new MllpServerConfig.MllpPipelineFactory(props()), new SimpleMeterRegistry(), "mllp-e2e");
        service.start();
        port = service.boundPort();
    }

    @AfterEach
    void stop() {
        service.stop();
    }

    private static Hl7ConfigProps props() {
        Hl7ConfigProps props = new Hl7ConfigProps();
        Hl7ConfigProps.Trigger t = props.getTrigger();
        t.setPort(0); // 内核分配空闲端口，避免与 6661 及并发跑测冲突
        t.setBindHost("127.0.0.1");
        t.setCharset("UTF-8");
        t.setReleaseMode("MLLP2");
        t.setIdleTimeoutMs(0);
        t.setWorkerThreads(2);
        t.setBacklog(16);
        return props;
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("HIS 发合法 ORM^O01 → ACK + 落一行 status=queued 的调阅任务 + 审计一行")
    void ormO01OverRealSocketEnqueuesTaskAndAcknowledges() throws Exception {
        try (Socket s = connect()) {
            long start = System.nanoTime();
            write(s, OracleOrderMessage.ormO01Frame(CS));
            byte[] ack = readExactly(s, 4);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertThat(ack).containsExactly(0x0B, 0x06, 0x1C, 0x0D);
            assertThat(elapsedMs).isLessThan(300L);
        }

        assertThat(savedTasks).hasSize(1);
        FetchTask task = savedTasks.get(0);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(task.getPatientMpi()).isEqualTo(OracleOrderMessage.PATIENT_MPI);
        assertThat(task.getStudyUid()).isEqualTo(OracleOrderMessage.ACCESSION);
        assertThat(task.getPeerAet()).isEqualTo(PEER_AET);
        assertThat(task.getInitiatedSrc()).isEqualTo("hl7");
        assertThat(queue.size()).isEqualTo(1);
        assertThat(auditRows).hasSize(1);
        assertThat(auditRows.get(0).getStatusTerminal()).isEqualTo("hl7_trigger_queued");
        assertThat(service.acksSent()).isEqualTo(1L);
        assertThat(service.naksSent()).isZero();
        assertThat(deadLetters).isEmpty();
    }

    @Test
    @DisplayName("HIS 发缺 PID 的 ORM^O01 → NAK + 断连 + 死信一行（不假装收下）")
    void ormO01WithoutPidOverRealSocketIsRejectedWithNak() throws Exception {
        try (Socket s = connect()) {
            write(s, OracleOrderMessage.mllpFrame(OracleOrderMessage.ormO01WithoutPid(CS)));
            byte[] nak = readExactly(s, 4);
            assertThat(nak).containsExactly(0x0B, 0x15, 0x1C, 0x0D);
            assertThat(readExactly(s, 1)).isNull(); // 随后对端读到 EOF：连接已断开
        }

        assertThat(service.naksSent()).isEqualTo(1L);
        assertThat(service.acksSent()).isZero();
        assertThat(savedTasks).isEmpty();
        assertThat(deadLetters).hasSize(1);
        assertThat(deadLetters.get(0).getFailureCode())
                .isEqualTo(Hl7DeadLetter.FailureCode.MISSING_PID);
    }

    @Test
    @DisplayName("HIS 发 ADT^A04 → ACK，但不起调阅任务")
    void adtOverRealSocketIsAcknowledgedWithoutEnqueue() throws Exception {
        try (Socket s = connect()) {
            write(s, OracleOrderMessage.mllpFrame(OracleOrderMessage.adtA04(CS)));
            assertThat(readExactly(s, 4)).containsExactly(0x0B, 0x06, 0x1C, 0x0D);
        }

        assertThat(savedTasks).isEmpty();
        assertThat(queue.size()).isZero();
        assertThat(service.acksSent()).isEqualTo(1L);
        assertThat(deadLetters).isEmpty();
    }

    // ------------------------------------------------------------------ 小工具

    private Socket connect() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
        s.setSoTimeout(READ_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        return s;
    }

    private static void write(Socket s, byte[] bytes) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    /** 读满 n 字节；返回 null 表示对端已断开（读到 EOF）。 */
    private static byte[] readExactly(Socket s, int n) throws IOException {
        InputStream in = s.getInputStream();
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r < 0) {
                return read == 0 ? null : java.util.Arrays.copyOf(buf, read);
            }
            read += r;
        }
        return buf;
    }

    /**
     * 审计替身（{@code AuditService} 是具体类，本机 JVM 上 ByteBuddy 无法内联改写）。
     */
    static final class CapturingAuditService extends AuditService {

        final List<AuditRow> rows;

        CapturingAuditService(List<AuditRow> sink) {
            super(null);
            this.rows = sink;
        }

        @Override
        public AuditRow record(AuditRow row) {
            rows.add(row);
            return row;
        }
    }
}
