package com.crosshospital.imaging.service.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.Peer;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.exception.RoutingFailureException;
import com.crosshospital.imaging.repository.AuditRepository;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.repository.PeerRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.task.FetchTaskQueuePort;
import com.crosshospital.imaging.service.task.FetchTaskService;
import com.crosshospital.imaging.service.task.InMemoryFetchTaskQueue;
import com.crosshospital.imaging.service.task.RetryBudgetResolver;
import com.crosshospital.imaging.service.task.RetryPolicy;
import com.crosshospital.imaging.service.task.RetryScheduler;
import com.crosshospital.imaging.service.task.TaskRequeueService;
import com.crosshospital.imaging.service.task.BackoffScheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Task 21 核查 —— 失败处置链与任务级重试的契约。
 *
 * <p>{@code RetryScheduleResult} 的文档写明：失败处置链（task 16 的
 * {@code RoutingFailureHandler}）看到 {@code retried()==true} 就
 * <b>不</b>把这次失败当终局失败记账。也就是「可重试的失败不该进死信」。
 *
 * <p>本类把失败处置链接到<b>真实</b>的 {@code FetchTaskService}（带重试端口）上，
 * 验证一条可重试失败经过处置链之后，任务行与死信表是否自洽。
 */
@DisplayName("Task 21 核查 — 失败处置链与任务级重试的契约")
class RoutingFailureHandlerRetryContractTest {

    private FetchTaskRepository repository;
    private PeerRepository peerRepository;
    private AuditRepository auditRepository;
    private FetchTaskService fetchTaskService;
    private List<DlqEnqueueRequest> dlq;
    private Map<UUID, FetchTask> store;
    private Map<String, Peer> peers;

    @BeforeEach
    void setUp() {
        repository = mock(FetchTaskRepository.class);
        peerRepository = mock(PeerRepository.class);
        auditRepository = mock(AuditRepository.class);
        store = new HashMap<>();
        peers = new HashMap<>();
        dlq = new ArrayList<>();

        when(repository.save(any(FetchTask.class))).thenAnswer(inv -> {
            FetchTask task = inv.getArgument(0);
            store.put(task.getTaskId(), task);
            return task;
        });
        when(repository.findByTaskId(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(store.get((UUID) inv.getArgument(0))));
        when(repository.findActiveByPatientMpiAndStudyUid(anyString(), anyString()))
                .thenAnswer(inv -> Optional.empty());
        when(peerRepository.findByPeerAet(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(peers.get((String) inv.getArgument(0))));
        when(auditRepository.save(any(AuditRow.class))).thenAnswer(inv -> inv.getArgument(0));

        InMemoryFetchTaskQueue queue = new InMemoryFetchTaskQueue(8);
        fetchTaskService = new FetchTaskService(repository, queue, new AuditService(auditRepository));

        // 接上任务级重试端口（可重试失败 → 退回 queued 等重跑）
        RetryPolicy policy = new RetryPolicy();
        policy.setJitterSource(() -> 0.0d);
        TaskRequeueService requeueService =
                new TaskRequeueService(repository, new AuditService(auditRepository), queue);
        fetchTaskService.setTaskRetryPort(new RetryScheduler(policy, requeueService,
                new RetryBudgetResolver(peerRepository), new BackoffScheduler() {
                    @Override
                    public void schedule(Runnable task, long delayMs) {
                        // 不真等
                    }
                }));
    }

    @Test
    @DisplayName("可重试失败经处置链：任务退回 queued（未判死），但死信表不应留下未处置的死信行")
    void retryableFailureThroughFailureChainDoesNotEnterDlq() {
        UUID taskId = seedTask(TaskStatus.QUERYING, 0);
        RoutingFailureHandler handler = new RoutingFailureHandler(new AuditService(auditRepository));
        handler.setDlqEnqueuePort(dlq::add);
        handler.setFetchTaskFailurePort(fetchTaskService);

        handler.handle(new RoutingFailureContext(RoutingFailureContext.STEP_QUERY,
                        "PEER_HZ", "PAT_MPI_EXAMPLE", "STUDY_EXAMPLE_1", taskId.toString(), "system"),
                new RoutingFailureException(RoutingFailureException.FailureCode.QUERY_TIMEOUT,
                        "C-FIND 响应超时", "PEER_HZ", "PAT_MPI_EXAMPLE"), 0);

        FetchTask stored = store.get(taskId);
        assertEquals(TaskStatus.QUEUED, stored.getStatus(),
                "可重试失败不应把任务判死（应退回 queued 等重跑）");
        assertEquals(1, stored.getRetryCount());
        assertEquals("query_timeout", stored.getFailureCode());

        assertEquals(0, dlq.size(),
                "可重试失败不是终局失败，不该进死信表（否则信息科会对着一条还在重跑的任务去人工处置）："
                        + dlq);
    }

    @Test
    @DisplayName("不可重试失败经处置链：任务判死 + 进死信（回归护栏）")
    void nonRetryableFailureStillEntersDlq() {
        UUID taskId = seedTask(TaskStatus.QUERYING, 0);
        RoutingFailureHandler handler = new RoutingFailureHandler(new AuditService(auditRepository));
        handler.setDlqEnqueuePort(dlq::add);
        handler.setFetchTaskFailurePort(fetchTaskService);

        handler.handle(new RoutingFailureContext(RoutingFailureContext.STEP_QUERY,
                        "PEER_HZ", "PAT_MPI_EXAMPLE", "STUDY_EXAMPLE_1", taskId.toString(), "system"),
                new RoutingFailureException(RoutingFailureException.FailureCode.ASSOCIATION_REJECTED,
                        "外院拒绝本院 AE Title", "PEER_HZ", "PAT_MPI_EXAMPLE"), 0);

        assertEquals(TaskStatus.FAILED, store.get(taskId).getStatus());
        assertEquals(1, dlq.size(), "不可重试的失败必须进死信");
    }

    private UUID seedTask(TaskStatus status, int retryCount) {
        FetchTask task = new FetchTask();
        task.setTaskId(UUID.randomUUID());
        task.setPatientMpi("PAT_MPI_EXAMPLE");
        task.setStudyUid("STUDY_EXAMPLE_1");
        task.setPeerAet("PEER_HZ");
        task.setAccessionNo("ACC_EXAMPLE");
        task.setStatus(status);
        task.setRetryCount(retryCount);
        task.setInitiatedBy("employee_007");
        task.setInitiatedSrc("api");
        store.put(task.getTaskId(), task);

        Peer peer = new Peer();
        peer.setPeerAet("PEER_HZ");
        peer.setHost("127.0.0.1");
        peer.setPort(11112);
        peer.setMaxRetries(3);
        peer.setBackoffMs(1000);
        peers.put("PEER_HZ", peer);
        return task.getTaskId();
    }
}
