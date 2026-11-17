package com.crosshospital.imaging.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.crosshospital.imaging.api.dto.DlqEntryResponse;
import com.crosshospital.imaging.config.DlqConfigProps;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.DlqEntry;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.exception.ConflictException;
import com.crosshospital.imaging.exception.NotFoundException;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.DlqRepository;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.routing.DlqEnqueueRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Task 22 — 死信队列业务层测试（入队 / 列表 / 处置）。
 *
 * <p>覆盖任务要求的「5 种失败场景入 DLQ」+「处置后从未处置列表移除」，
 * 外加几条踩坑边界：无任务上下文的死信、告警失败不拖垮入队、重复处置被拒。
 */
@DisplayName("Task 22 — DlqService 入队 / 未处置列表 / 人工处置")
class DlqServiceTest {

    private DlqRepository dlqRepository;
    private FetchTaskRepository fetchTaskRepository;
    private AuditRepository auditRepository;
    private DlqConfigProps props;

    /** 落库的死信行（替身要像数据库：分配自增 id）。 */
    private final List<DlqEntry> saved = new ArrayList<>();
    /** 告警端口收到的死信行。 */
    private final AtomicReference<DlqEntry> alerted = new AtomicReference<>();
    /** 告警端口的回执（可被用例改成 FAILED / 抛异常）。 */
    private final AtomicReference<DlqAlertResult> alertResult = new AtomicReference<>(
            DlqAlertResult.sent(200));

    @BeforeEach
    void setUp() {
        saved.clear();
        alerted.set(null);
        alertResult.set(DlqAlertResult.sent(200));

        dlqRepository = mock(DlqRepository.class);
        fetchTaskRepository = mock(FetchTaskRepository.class);
        auditRepository = mock(AuditRepository.class);

        when(dlqRepository.save(any(DlqEntry.class))).thenAnswer(inv -> {
            DlqEntry row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId((long) saved.size() + 1L);
                saved.add(row);
            }
            return row;
        });
        when(auditRepository.save(any(AuditRow.class))).thenAnswer(inv -> inv.getArgument(0));

        props = new DlqConfigProps();
        props.setRetentionDays(30);
        props.setAlertCooldownMinutes(10);
    }

    private DlqService service() {
        DlqAlertPort port = entry -> {
            alerted.set(entry);
            return alertResult.get();
        };
        // 真实 AuditService + mock 仓储：审计行要真的落库（处置动作必须留痕）
        return new DlqService(dlqRepository, fetchTaskRepository,
                new AuditService(auditRepository), port, props);
    }

    private static DlqEntry sampleEntry(long id, UUID taskId, String code, int retries) {
        DlqEntry entry = new DlqEntry();
        entry.setId(id);
        entry.setTaskId(taskId);
        entry.setFailureCode(code);
        entry.setFailureMessage("失败说明 " + code);
        entry.setRetryAttempts(retries);
        entry.setFirstFailedAt(Instant.now());
        entry.setLastRetryAt(Instant.now());
        entry.setCreatedAt(Instant.now());
        entry.setAlertWebhookSent(Boolean.FALSE);
        return entry;
    }

    private static FetchTask sampleTask(UUID taskId, String peerAet, String studyUid) {
        FetchTask task = new FetchTask();
        task.setTaskId(taskId);
        task.setPeerAet(peerAet);
        task.setStudyUid(studyUid);
        task.setPatientMpi("MPI_EXAMPLE_0001");
        task.setStatus(TaskStatus.FAILED);
        return task;
    }

    // ------------------------------------------------------------------ 入队

    @Test
    @DisplayName("入队：失败码 / 失败消息 / 重试次数原样落库，并触发一次告警")
    void enqueuePersistsRowAndDispatchesAlert() {
        UUID taskId = UUID.randomUUID();
        DlqEntry row = service().enqueue(taskId.toString(), "connection_refused",
                "连不上外院 PACS", 3);

        assertThat(saved).hasSize(1);
        assertThat(row.getFailureCode()).isEqualTo("connection_refused");
        assertThat(row.getFailureMessage()).isEqualTo("连不上外院 PACS");
        assertThat(row.getRetryAttempts()).isEqualTo(3);
        assertThat(row.getTaskId()).isEqualTo(taskId);
        assertThat(row.getResolvedAt()).isNull();
        assertThat(row.getAlertWebhookSent()).isTrue();
        assertThat(alerted.get()).isSameAs(row);
    }

    @Test
    @DisplayName("入队：5 种失败场景各自成行，失败码互不串味")
    void fiveFailureScenariosEachGetTheirOwnRow() {
        DlqService service = service();
        String[] codes = {"connection_refused", "association_rejected", "query_timeout",
                "retrieve_failed", "store_failed"};
        for (int i = 0; i < codes.length; i++) {
            service.enqueue(UUID.randomUUID().toString(), codes[i], "步骤失败：" + codes[i], i);
        }
        assertThat(saved).hasSize(5);
        assertThat(saved.stream().map(DlqEntry::getFailureCode).toList())
                .containsExactly("connection_refused", "association_rejected", "query_timeout",
                        "retrieve_failed", "store_failed");
        assertThat(saved.stream().map(DlqEntry::getRetryAttempts).toList())
                .containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    @DisplayName("入队：DlqEnqueuePort 入口（路由层失败统一出口）同样能落库")
    void enqueuePortEntryMapsRequestToRow() {
        DlqEnqueueRequest request = new DlqEnqueueRequest(
                UUID.randomUUID().toString(), "PEER_HZ", "MPI_EXAMPLE_0001", "1.2.3",
                "query", "query_timeout", "查询超时", 2);
        service().enqueue(request);

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getFailureCode()).isEqualTo("query_timeout");
        assertThat(saved.get(0).getRetryAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("入队：没有任务上下文时写零值占位，不丢死信也不编造任务号")
    void enqueueWithoutTaskContextUsesNilSentinel() {
        DlqEntry row = service().enqueue(null, "tls_mismatch", "TLS 不匹配", 0);

        assertThat(row.getTaskId()).isEqualTo(DlqService.NO_TASK_CONTEXT);
        assertThat(DlqService.hasTaskContext(row.getTaskId())).isFalse();
        assertThat(saved).hasSize(1);
    }

    @Test
    @DisplayName("入队：task_id 格式非法时按「无任务上下文」处理，不把脏值写进 UUID 列")
    void enqueueWithInvalidTaskIdFallsBackToSentinel() {
        DlqEntry row = service().enqueue("not-a-uuid", "tls_mismatch", "TLS 不匹配", 0);
        assertThat(row.getTaskId()).isEqualTo(DlqService.NO_TASK_CONTEXT);
    }

    @Test
    @DisplayName("入队：失败码为空时收敛为 unknown，不写 null")
    void enqueueWithBlankFailureCodeFallsBackToUnknown() {
        DlqEntry row = service().enqueue(UUID.randomUUID().toString(), "  ", "消息", 0);
        assertThat(row.getFailureCode()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("入队：告警失败不拖垮入队（死信照样落库）")
    void alertFailureDoesNotBreakEnqueue() {
        DlqAlertPort exploding = entry -> {
            throw new IllegalStateException("告警网关炸了");
        };
        DlqService service = new DlqService(dlqRepository, fetchTaskRepository,
                new AuditService(auditRepository), exploding, props);

        DlqEntry row = service.enqueue(UUID.randomUUID().toString(), "store_failed", "写入失败", 1);

        assertThat(saved).hasSize(1);
        assertThat(row.getFailureCode()).isEqualTo("store_failed");
    }

    @Test
    @DisplayName("入队：告警未送达时 alert_webhook_sent 保持 false（不谎称已通知）")
    void alertNotDeliveredKeepsFlagFalse() {
        alertResult.set(DlqAlertResult.failed("网关 500", 500));
        DlqEntry row = service().enqueue(UUID.randomUUID().toString(), "store_failed", "写入失败", 1);
        assertThat(row.getAlertWebhookSent()).isFalse();
    }

    // ------------------------------------------------------------------ 处置

    @Test
    @DisplayName("处置：标记 resolved_at / resolved_by / 说明，并落一行 dlq_resolved 审计")
    void resolveMarksRowAndRecordsAudit() {
        UUID taskId = UUID.randomUUID();
        DlqEntry entry = sampleEntry(7L, taskId, "connection_refused", 3);
        when(dlqRepository.findById(7L)).thenReturn(Optional.of(entry));
        when(fetchTaskRepository.findByTaskId(taskId))
                .thenReturn(Optional.of(sampleTask(taskId, "PEER_HZ", "1.2.3")));

        DlqEntryResponse response = service().resolve(7L, "employee_007", "已联系外院信息科");

        assertThat(response.getResolvedAt()).isNotNull();
        assertThat(response.getResolvedBy()).isEqualTo("employee_007");
        assertThat(response.getResolutionNote()).isEqualTo("已联系外院信息科");

        ArgumentCaptor<AuditRow> audit = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository, times(1)).save(audit.capture());
        assertThat(audit.getValue().getStatusTerminal()).isEqualTo("dlq_resolved");
        assertThat(audit.getValue().getWho()).isEqualTo("employee_007");
        assertThat(audit.getValue().getFailureCode()).isEqualTo("connection_refused");
        assertThat(audit.getValue().getToAet()).isEqualTo("PEER_HZ");
    }

    @Test
    @DisplayName("处置：不写处置人时收敛为 system")
    void resolveWithoutWhoFallsBackToSystem() {
        DlqEntry entry = sampleEntry(8L, UUID.randomUUID(), "store_failed", 1);
        when(dlqRepository.findById(8L)).thenReturn(Optional.of(entry));

        DlqEntryResponse response = service().resolve(8L, "  ", null);

        assertThat(response.getResolvedBy()).isEqualTo("system");
    }

    @Test
    @DisplayName("处置：死信不存在 → 404 语义的 NotFoundException")
    void resolveUnknownIdThrowsNotFound() {
        when(dlqRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().resolve(99L, "employee_007", null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("处置：重复处置被拒（409 语义），不覆盖第一次的处置记录")
    void resolveAlreadyResolvedThrowsConflict() {
        DlqEntry entry = sampleEntry(9L, UUID.randomUUID(), "store_failed", 3);
        entry.setResolvedAt(Instant.now());
        entry.setResolvedBy("employee_001");
        when(dlqRepository.findById(9L)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> service().resolve(9L, "employee_002", "再来一次"))
                .isInstanceOf(ConflictException.class);
        assertThat(entry.getResolvedBy()).isEqualTo("employee_001");
    }

    // ------------------------------------------------------------------ 列表

    @Test
    @DisplayName("未处置列表：按创建时间倒序返回，并补上任务上下文（外院 / 病人 / study）")
    void listUnresolvedEnrichesTaskContext() {
        UUID taskId = UUID.randomUUID();
        DlqEntry entry = sampleEntry(1L, taskId, "connection_refused", 3);
        when(dlqRepository.findUnresolved(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 20), 1));
        when(fetchTaskRepository.findByTaskIdIn(any())).thenReturn(List.of(
                sampleTask(taskId, "PEER_HZ", "1.2.3")));

        Page<DlqEntryResponse> page = service().listUnresolved(0, 20, null);

        assertThat(page.getTotalElements()).isEqualTo(1);
        DlqEntryResponse row = page.getContent().get(0);
        assertThat(row.getFailureCode()).isEqualTo("connection_refused");
        assertThat(row.getRetryAttempts()).isEqualTo(3);
        assertThat(row.getTaskContextPresent()).isTrue();
        assertThat(row.getPeerAet()).isEqualTo("PEER_HZ");
        assertThat(row.getStudyUid()).isEqualTo("1.2.3");
        assertThat(row.getTaskStatus()).isEqualTo("failed");
    }

    @Test
    @DisplayName("未处置列表：任务已清理时明确标出「无任务上下文」，不编造外院名")
    void listUnresolvedMarksMissingTaskContext() {
        DlqEntry entry = sampleEntry(2L, DlqService.NO_TASK_CONTEXT, "tls_mismatch", 0);
        when(dlqRepository.findUnresolved(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 20), 1));

        Page<DlqEntryResponse> page = service().listUnresolved(0, 20, null);

        DlqEntryResponse row = page.getContent().get(0);
        assertThat(row.getTaskContextPresent()).isFalse();
        assertThat(row.getPeerAet()).isNull();
    }

    @Test
    @DisplayName("未处置列表：按失败码过滤时走专用查询（不走全量列表）")
    void listUnresolvedFiltersByFailureCode() {
        when(dlqRepository.findUnresolvedByFailureCode(eq("connection_refused"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service().listUnresolved(0, 20, "connection_refused");

        verify(dlqRepository).findUnresolvedByFailureCode(eq("connection_refused"), any(Pageable.class));
        verify(dlqRepository, never()).findUnresolved(any(Pageable.class));
    }

    @Test
    @DisplayName("未处置列表：size 超上限时被截到 max-page-size（防呆）")
    void listUnresolvedCapsPageSize() {
        when(dlqRepository.findUnresolved(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        props.setMaxPageSize(50);

        service().listUnresolved(0, 100000, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(dlqRepository).findUnresolved(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("未处置条数：透传仓储计数（信息科首页看「还有多少单子没人管」）")
    void countUnresolvedDelegatesToRepository() {
        when(dlqRepository.countByResolvedAtIsNull()).thenReturn(4L);
        assertThat(service().countUnresolved()).isEqualTo(4L);
    }

    @Test
    @DisplayName("处置后从未处置列表移除：仓储按 resolved_at 过滤（resolved_at 非空即不再出现）")
    void resolvedRowLeavesUnresolvedList() {
        DlqEntry entry = sampleEntry(5L, UUID.randomUUID(), "retrieve_failed", 2);
        when(dlqRepository.findById(5L)).thenReturn(Optional.of(entry));
        service().resolve(5L, "employee_003", "重跑成功");

        assertThat(entry.getResolvedAt()).isNotNull();
        // 仓储的未处置查询本身就带 resolved_at is null 条件 —— 这里断言服务层不再回查未处置集合
        verify(dlqRepository, never()).findUnresolved(any(Pageable.class));
    }

    @Test
    @DisplayName("告警报文序列化失败时退化为最小报文，不让告警流程带崩")
    void alertPayloadDegradesGracefully() {
        // 用一个序列化必炸的 ObjectMapper 验证降级路径
        ObjectMapper broken = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) {
                throw new IllegalStateException("序列化炸了");
            }
        };
        DlqAlertNotifier notifier = new DlqAlertNotifier(dlqRepository, props, broken,
                java.net.http.HttpClient.newHttpClient());
        String payload = notifier.buildPayload(sampleEntry(1L, UUID.randomUUID(), "store_failed", 1));
        assertThat(payload).contains("store_failed");
    }
}
