package com.crosshospital.imaging.service.task;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.model.AiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 27 验收：AI 增强不阻断调阅主路径。
 *
 * <p><b>这一组用例守住的是产品责任边界</b>：
 * <ul>
 *   <li><b>AI 挂了，调阅照样完成</b> —— 影像已经在本院 PACS 里，
 *       AI 摘要只是给医生的辅助材料；因为模型服务不可达就让 HIS 收不到通知，
 *       是把增强层当成了前置条件；</li>
 *   <li><b>没有产出就不留痕</b> —— {@code ai_summary_id} 保持为空，
 *       不能指向一条不存在的摘要；</li>
 *   <li><b>规则兜底也是产出</b> —— {@code source=rule_fallback} 的摘要照样落库、
 *       照样回填 {@code ai_summary_id}，医生能分清它是模型判的还是规则凑的；</li>
 *   <li><b>幂等</b> —— 已有摘要就不重复产出（HIS 重投通知是常态）。</li>
 * </ul>
 */
@DisplayName("Task 27 — AI 增强不阻断 notified 推进")
class FetchTaskAiEnrichmentTest {

    private FetchTaskRepository repository;
    private AuditRepository auditRepository;
    private FetchTaskService service;
    private Map<UUID, FetchTask> store;

    @BeforeEach
    void setUp() {
        repository = mock(FetchTaskRepository.class);
        auditRepository = mock(AuditRepository.class);
        service = new FetchTaskService(repository, new InMemoryFetchTaskQueue(16),
                new AuditService(auditRepository));
        store = new HashMap<>();

        when(repository.save(any(FetchTask.class))).thenAnswer(inv -> {
            FetchTask task = inv.getArgument(0);
            store.put(task.getTaskId(), task);
            return task;
        });
        when(repository.findByTaskId(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(store.get((UUID) inv.getArgument(0))));
        when(repository.findActiveByPatientMpiAndStudyUid(anyString(), anyString()))
                .thenAnswer(inv -> store.values().stream()
                        .filter(t -> t.getPatientMpi().equals(inv.getArgument(0))
                                && t.getStudyUid().equals(inv.getArgument(1))
                                && t.getStatus().isIdempotentBlock())
                        .findFirst());
        when(auditRepository.save(any(AuditRow.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private FetchTask storedTask(TaskStatus status) {
        FetchTask task = new FetchTask();
        task.setTaskId(UUID.randomUUID());
        task.setPatientMpi("MPI-001");
        task.setStudyUid("1.2.826.0.1.3680043.10.9999.1.1");
        task.setPeerAet("PEER_HZ");
        task.setAccessionNo("ACC-001");
        task.setStatus(status);
        task.setRetryCount(0);
        store.put(task.getTaskId(), task);
        return task;
    }

    @Test
    @DisplayName("AI 端口抛异常 → notified 照常推进，ai_summary_id 保持为空，审计仍是 notified")
    void aiFailureDoesNotBlockNotifiedAdvancement() {
        FetchTask task = storedTask(TaskStatus.STORED);
        AiEnrichmentPort port = mock(AiEnrichmentPort.class);
        when(port.enrich(any(FetchTask.class))).thenThrow(new IllegalStateException("模型服务炸了"));
        service.setAiEnrichmentPort(port);

        FetchTask after = service.advance(task.getTaskId(), TaskStatus.NOTIFIED, "employee_007");

        assertEquals(TaskStatus.NOTIFIED, after.getStatus());
        assertNull(after.getAiSummaryId(), "AI 失败不应留下悬空的 ai_summary_id");
        verify(port, times(1)).enrich(any(FetchTask.class));

        ArgumentCaptor<AuditRow> captor = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("task_notified", captor.getValue().getStatusTerminal(),
                "AI 失败不是调阅失败：审计终态必须是 notified，不能带失败码");
        assertNull(captor.getValue().getFailureCode());
    }

    @Test
    @DisplayName("模型可用 → 摘要落库后回填 ai_summary_id，审计仍是 notified")
    void modelSummarySetsAiSummaryId() {
        FetchTask task = storedTask(TaskStatus.STORED);
        AiEnrichmentPort port = mock(AiEnrichmentPort.class);
        when(port.enrich(any(FetchTask.class)))
                .thenReturn(AiEnrichmentOutcome.of(4242L, AiResult.SOURCE_MODEL, null));
        service.setAiEnrichmentPort(port);

        FetchTask after = service.advance(task.getTaskId(), TaskStatus.NOTIFIED, "employee_007");

        assertEquals(TaskStatus.NOTIFIED, after.getStatus());
        assertEquals(4242L, after.getAiSummaryId());
        verify(repository, times(2)).save(any(FetchTask.class));
    }

    @Test
    @DisplayName("规则兜底（source=rule_fallback）也是产出：摘要照落、id 照回填，审计不带失败码")
    void ruleFallbackSummaryIsStillPersisted() {
        FetchTask task = storedTask(TaskStatus.STORED);
        AiEnrichmentPort port = mock(AiEnrichmentPort.class);
        when(port.enrich(any(FetchTask.class)))
                .thenReturn(AiEnrichmentOutcome.of(77L, AiResult.SOURCE_RULE_FALLBACK, "模型服务超时"));
        service.setAiEnrichmentPort(port);

        FetchTask after = service.advance(task.getTaskId(), TaskStatus.NOTIFIED, "employee_007");

        assertEquals(77L, after.getAiSummaryId(), "规则兜底的摘要同样要回填 id");
        ArgumentCaptor<AuditRow> captor = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("task_notified", captor.getValue().getStatusTerminal());
        assertNull(captor.getValue().getFailureCode(),
                "走了规则兜底不等于这次调阅失败 —— 审计行不能带失败码");
    }

    @Test
    @DisplayName("端口返回「没有产出」→ notified 照常推进，ai_summary_id 保持为空")
    void unavailableOutcomeLeavesAiSummaryIdNull() {
        FetchTask task = storedTask(TaskStatus.STORED);
        AiEnrichmentPort port = mock(AiEnrichmentPort.class);
        when(port.enrich(any(FetchTask.class)))
                .thenReturn(AiEnrichmentOutcome.unavailable("模型与规则兜底都不可用"));
        service.setAiEnrichmentPort(port);

        FetchTask after = service.advance(task.getTaskId(), TaskStatus.NOTIFIED, "employee_007");

        assertEquals(TaskStatus.NOTIFIED, after.getStatus());
        assertNull(after.getAiSummaryId());
        ArgumentCaptor<AuditRow> captor = ArgumentCaptor.forClass(AuditRow.class);
        verify(auditRepository, times(1)).save(captor.capture());
        assertEquals("task_notified", captor.getValue().getStatusTerminal());
    }

    @Test
    @DisplayName("没接 AI 端口 → 行为与「没有 AI 这一层」完全一致（主路径不依赖增强层）")
    void noPortMeansNoAiBehaviourAtAll() {
        FetchTask task = storedTask(TaskStatus.STORED);

        FetchTask after = service.advance(task.getTaskId(), TaskStatus.NOTIFIED, "employee_007");

        assertEquals(TaskStatus.NOTIFIED, after.getStatus());
        assertNull(after.getAiSummaryId());
        verify(repository, times(1)).save(any(FetchTask.class));
    }

    @Test
    @DisplayName("AI 增强只在 notified 这一步跑（stored 等中间态不碰模型）")
    void enrichmentOnlyRunsOnNotified() {
        FetchTask task = storedTask(TaskStatus.PULLING);
        AiEnrichmentPort port = mock(AiEnrichmentPort.class);
        AtomicInteger calls = new AtomicInteger();
        when(port.enrich(any(FetchTask.class))).thenAnswer(inv -> {
            calls.incrementAndGet();
            return AiEnrichmentOutcome.of(1L, AiResult.SOURCE_MODEL, null);
        });
        service.setAiEnrichmentPort(port);

        service.advance(task.getTaskId(), TaskStatus.STORED, "employee_007");

        assertEquals(0, calls.get(), "中间态不该调模型");
    }

    @Test
    @DisplayName("已有摘要 → 幂等跳过（不再重复产出，也不重复落审计）")
    void enrichmentIsIdempotentWhenSummaryAlreadyExists() {
        FetchTask task = storedTask(TaskStatus.STORED);
        task.setAiSummaryId(999L);
        AiEnrichmentPort port = mock(AiEnrichmentPort.class);
        service.setAiEnrichmentPort(port);

        FetchTask after = service.advance(task.getTaskId(), TaskStatus.NOTIFIED, "employee_007");

        verify(port, never()).enrich(any(FetchTask.class));
        assertEquals(999L, after.getAiSummaryId());
    }

    @Test
    @DisplayName("AI 增强不写 PACS、不推 HIS：只改任务行的 ai_summary_id")
    void enrichmentOnlyTouchesAiSummaryId() {
        FetchTask task = storedTask(TaskStatus.STORED);
        AiEnrichmentPort port = mock(AiEnrichmentPort.class);
        when(port.enrich(any(FetchTask.class)))
                .thenReturn(AiEnrichmentOutcome.of(5L, AiResult.SOURCE_MODEL, null));
        service.setAiEnrichmentPort(port);

        FetchTask after = service.advance(task.getTaskId(), TaskStatus.NOTIFIED, "employee_007");

        assertNotNull(after.getAiSummaryId());
        assertEquals(TaskStatus.NOTIFIED, after.getStatus());
        assertEquals("PEER_HZ", after.getPeerAet(), "节点信息不该被 AI 增强改动");
        assertEquals("1.2.826.0.1.3680043.10.9999.1.1", after.getStudyUid());
        assertNull(after.getFailureCode(), "AI 增强不该给任务打失败码");
    }
}
