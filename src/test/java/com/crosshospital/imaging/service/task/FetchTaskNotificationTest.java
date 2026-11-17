package com.crosshospital.imaging.service.task;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 32 — 主路径 notified 之后回推 HIS；通知失败不改写任务状态。 */
@DisplayName("Task 32 — HIS 通知接入调阅主路径")
class FetchTaskNotificationTest {

    private FetchTaskRepository repository;
    private FetchTaskService service;
    private FetchTask task;
    private final List<String> notified = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(FetchTaskRepository.class);
        service = new FetchTaskService(repository, mock(FetchTaskQueuePort.class));
        service.setNotificationPort((taskId, studyUid, status) -> notified.add(taskId + ":" + status));
        task = new FetchTask();
        task.setTaskId(UUID.randomUUID());
        task.setStudyUid("1.2.840.EXAMPLE");
        task.setPatientMpi("HZH^1234567");
        task.setPeerAet("PEER_HZ");
        task.setStatus(TaskStatus.STORED);
        when(repository.findByTaskId(task.getTaskId())).thenReturn(Optional.of(task));
        when(repository.save(any(FetchTask.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("stored → notified 推进后触发一次 HIS 通知")
    void notifiedTriggersWebhookOnce() {
        FetchTask advanced = service.advance(task.getTaskId(), TaskStatus.NOTIFIED);

        assertEquals(TaskStatus.NOTIFIED, advanced.getStatus());
        assertEquals(List.of(task.getTaskId() + ":notified"), notified);
    }

    @Test
    @DisplayName("通知端口抛异常 → 任务仍为 notified（影像已在 PACS，失败落死信补推）")
    void webhookFailureDoesNotRollbackStatus() {
        service.setNotificationPort((taskId, studyUid, status) -> {
            throw new IllegalStateException("HIS 网关不可达");
        });

        FetchTask advanced = service.advance(task.getTaskId(), TaskStatus.NOTIFIED);

        assertEquals(TaskStatus.NOTIFIED, advanced.getStatus());
        assertEquals(null, advanced.getFailureCode());
    }

    @Test
    @DisplayName("非 notified 的状态推进不触发通知")
    void otherTransitionsDoNotNotify() {
        task.setStatus(TaskStatus.QUEUED);

        service.advance(task.getTaskId(), TaskStatus.QUERYING);

        assertEquals(List.of(), notified);
        assertEquals(TaskStatus.QUERYING, task.getStatus());
    }

    @Test
    @DisplayName("未接通知端口时行为与没有这一层完全一致")
    void noPortMeansNoNotification() {
        service.setNotificationPort(null);

        FetchTask advanced = service.advance(task.getTaskId(), TaskStatus.NOTIFIED);

        assertEquals(TaskStatus.NOTIFIED, advanced.getStatus());
        assertEquals(List.of(), notified);
    }
}
