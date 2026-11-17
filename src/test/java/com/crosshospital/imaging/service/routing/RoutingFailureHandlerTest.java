package com.crosshospital.imaging.service.routing;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.exception.RoutingFailureException;
import com.crosshospital.imaging.exception.RoutingFailureException.FailureCode;
import com.crosshospital.imaging.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RoutingFailureHandler} 单测 —— 「一次失败 → 一行审计 + 一条死信 + 一次任务推终态」。
 *
 * <p>本类验证的是三条副作用的<b>独立性</b>：任一条落库失败都不应阻断另外两条，
 * 也不应把异常抛回主路径。调阅已经失败了，排障链路再断一环，运维看到的
 * 就只剩一句「失败了」。
 */
class RoutingFailureHandlerTest {

    private static final RoutingFailureContext CTX = new RoutingFailureContext(
            RoutingFailureContext.STEP_RETRIEVE, "REMOTE_PACS",
            "MPI-0000123", "1.2.826.0.1.3680043.10.9999.st.1",
            "11111111-2222-3333-4444-555555555555", "employee_007");

    /** 记录型审计替身（继承真 AuditService，只拦 record）。 */
    private static final class CapturingAuditService extends AuditService {
        final List<AuditRow> rows = new CopyOnWriteArrayList<>();
        private final boolean explode;

        CapturingAuditService(boolean explode) {
            super(null);
            this.explode = explode;
        }

        @Override
        public AuditRow record(AuditRow row) {
            if (explode) {
                throw new IllegalStateException("审计库连接失败（测试注入）");
            }
            rows.add(row);
            return row;
        }
    }

    /** 记录型端口替身。 */
    private static final class RecordingDlq implements DlqEnqueuePort {
        final List<DlqEnqueueRequest> requests = new CopyOnWriteArrayList<>();
        private final boolean explode;

        RecordingDlq(boolean explode) {
            this.explode = explode;
        }

        @Override
        public void enqueue(DlqEnqueueRequest request) {
            if (explode) {
                throw new IllegalStateException("死信表写入失败（测试注入）");
            }
            requests.add(request);
        }
    }

    private static final class RecordingTaskPort implements FetchTaskFailurePort {
        final List<String> taskIds = new CopyOnWriteArrayList<>();
        final List<String> codes = new CopyOnWriteArrayList<>();
        /** 收场方式；默认终局失败（task 22 的死信只收终局失败）。 */
        TaskFailureOutcome outcome = TaskFailureOutcome.TERMINAL_FAILURE;

        @Override
        public TaskFailureOutcome markFailed(String taskId, String failureCode) {
            taskIds.add(taskId);
            codes.add(failureCode);
            return outcome;
        }
    }

    private CapturingAuditService audit;
    private RecordingDlq dlq;
    private RecordingTaskPort taskPort;

    @BeforeEach
    void setUp() {
        audit = new CapturingAuditService(false);
        dlq = new RecordingDlq(false);
        taskPort = new RecordingTaskPort();
    }

    private RoutingFailureHandler newHandler() {
        RoutingFailureHandler handler = new RoutingFailureHandler(audit);
        handler.setDlqEnqueuePort(dlq);
        handler.setFetchTaskFailurePort(taskPort);
        return handler;
    }

    // ==================================================================
    // 三条副作用
    // ==================================================================

    @Test
    @DisplayName("一次失败 → 恰好 1 行审计 + 1 条死信 + 1 次任务推终态，字段逐个核对")
    void oneFailureProducesExactlyOneAuditOneDlqOneTaskFail() {
        RoutingFailureReport report = newHandler().handle(
                CTX,
                new RoutingFailureException(FailureCode.MOVE_TIMEOUT,
                        "C-MOVE 响应超时(120000ms): peer=REMOTE_PACS", "REMOTE_PACS", "MPI-0000123"),
                2);

        assertAll(
                () -> assertEquals(1, audit.rows.size(), "失败只落一行审计"),
                () -> assertEquals(1, dlq.requests.size(), "失败只进一条死信"),
                () -> assertEquals(1, taskPort.taskIds.size(), "任务只推一次失败终态"));

        AuditRow row = audit.rows.get(0);
        assertAll(
                () -> assertEquals("employee_007", row.getWho(), "who 应透传操作人工号"),
                () -> assertEquals("local", row.getFromAet()),
                () -> assertEquals("REMOTE_PACS", row.getToAet()),
                () -> assertEquals(CTX.studyUid(), row.getStudyUid()),
                () -> assertEquals(2, row.getRetryCount(), "retry_count 落真实已重试次数"),
                () -> assertEquals("retrieve_failed", row.getStatusTerminal(),
                        "status_terminal 取步骤终态名"),
                () -> assertEquals("move_timeout", row.getFailureCode()));

        DlqEnqueueRequest request = dlq.requests.get(0);
        assertAll(
                () -> assertEquals(CTX.taskId(), request.taskId()),
                () -> assertEquals("REMOTE_PACS", request.peerAet()),
                () -> assertEquals(CTX.studyUid(), request.studyUid()),
                () -> assertEquals(RoutingFailureContext.STEP_RETRIEVE, request.step()),
                () -> assertEquals("move_timeout", request.failureCode()),
                () -> assertEquals(2, request.retryAttempts()),
                () -> assertTrue(request.failureMessage().contains("C-MOVE 响应超时"),
                        "死信必须带原始失败消息，信息科要靠它排障"));

        assertAll(
                () -> assertEquals("move_timeout", report.failureCode()),
                () -> assertTrue(report.retryable()),
                () -> assertTrue(report.auditRecorded()),
                () -> assertTrue(report.dlqEnqueued()),
                () -> assertTrue(report.taskMarkedFailed()));
    }

    @Test
    @DisplayName("三步各自的 status_terminal 分别是 query_failed / retrieve_failed / store_failed")
    void statusTerminalFollowsTheStep() {
        RoutingFailureHandler handler = newHandler();
        handler.handle(ctxOf(RoutingFailureContext.STEP_QUERY),
                new RoutingFailureException(FailureCode.QUERY_TIMEOUT, "查询超时", "P", "M"), 0);
        handler.handle(ctxOf(RoutingFailureContext.STEP_RETRIEVE),
                new RoutingFailureException(FailureCode.RETRIEVE_FAILED, "拉回失败", "P", "M"), 0);
        handler.handle(ctxOf(RoutingFailureContext.STEP_STORE),
                new RoutingFailureException(FailureCode.STORE_FAILED, "写入失败", "P", "M"), 0);
        handler.handle(new RoutingFailureContext("whatever", "P", "M", "S", null, null),
                new RoutingFailureException(FailureCode.STORE_FAILED, "写入失败", "P", "M"), 0);

        List<String> terminals = new ArrayList<>();
        audit.rows.forEach(r -> terminals.add(r.getStatusTerminal()));
        assertEquals(List.of("query_failed", "retrieve_failed", "store_failed", "whatever_failed"),
                terminals, "步骤名到终态名的映射必须稳定，未知步骤不丢信息");
    }

    @Test
    @DisplayName("cause 链里的 ConnectException 会把失败码从 association_rejected 细化成 connection_refused")
    void causeChainRefinesTheFailureCode() {
        newHandler().handle(ctxOf(RoutingFailureContext.STEP_QUERY),
                new RoutingFailureException(FailureCode.ASSOCIATION_REJECTED,
                        "关联建立失败", "REMOTE_PACS", "MPI-1", new ConnectException("refused")), 0);

        assertAll(
                () -> assertEquals(1, audit.rows.size()),
                () -> assertEquals("connection_refused", audit.rows.get(0).getFailureCode(),
                        "运维去查网络而不是查 AE 白名单"),
                () -> assertEquals("connection_refused", dlq.requests.get(0).failureCode()));
    }

    @Test
    @DisplayName("配置型失败不重试，但照样落审计与死信（重试与否和是否留痕是两件事）")
    void nonRetryableFailureStillGetsRecorded() {
        RoutingFailureReport report = newHandler().handle(ctxOf(RoutingFailureContext.STEP_QUERY),
                new RoutingFailureException(FailureCode.ASSOCIATION_REJECTED,
                        "外院拒绝本院 AE Title", "REMOTE_PACS", "MPI-1"), 0);

        assertAll(
                () -> assertFalse(report.retryable()),
                () -> assertEquals("association_rejected", report.failureCode()),
                () -> assertTrue(report.auditRecorded()),
                () -> assertTrue(report.dlqEnqueued()),
                () -> assertTrue(report.suggestedAction().contains("AE 白名单"),
                        "配置型失败的处置建议必须指向具体改法"));
    }

    // ==================================================================
    // 副作用独立性（本类的核心不变量）
    // ==================================================================

    @Test
    @DisplayName("审计库故障不阻断死信与任务推终态，且报告如实标记 auditRecorded=false")
    void auditFailureDoesNotBlockDlqOrTaskFail() {
        RoutingFailureHandler handler = new RoutingFailureHandler(new CapturingAuditService(true));
        RecordingDlq stillRecording = new RecordingDlq(false);
        handler.setDlqEnqueuePort(stillRecording);
        handler.setFetchTaskFailurePort(taskPort);

        RoutingFailureReport report = handler.handle(CTX,
                new RoutingFailureException(FailureCode.STORE_FAILED, "写入失败", "LOCAL_PACS", null), 1);

        assertAll(
                () -> assertFalse(report.auditRecorded(), "审计没写成就要如实报 false"),
                () -> assertEquals(1, stillRecording.requests.size(), "死信仍须入队"),
                () -> assertEquals(1, taskPort.taskIds.size(), "任务仍须推终态"));
    }

    @Test
    @DisplayName("死信端口故障不冒泡、不影响审计与任务终态")
    void dlqFailureDoesNotBubbleUp() {
        RoutingFailureHandler handler = new RoutingFailureHandler(audit);
        handler.setDlqEnqueuePort(new RecordingDlq(true));
        handler.setFetchTaskFailurePort(taskPort);

        RoutingFailureReport report = handler.handle(CTX,
                new RoutingFailureException(FailureCode.STORE_FAILED, "写入失败", "LOCAL_PACS", null), 0);

        assertAll(
                () -> assertFalse(report.dlqEnqueued()),
                () -> assertTrue(report.auditRecorded()),
                () -> assertTrue(report.taskMarkedFailed()));
    }

    @Test
    @DisplayName("端口未接入时（task 20/22 尚未落地）降级为「未发生」，其余副作用照常")
    void missingPortsAreTolerated() {
        RoutingFailureReport report = new RoutingFailureHandler(audit).handle(CTX,
                new RoutingFailureException(FailureCode.STORE_FAILED, "写入失败", "LOCAL_PACS", null), 0);

        assertAll(
                () -> assertTrue(report.auditRecorded(), "审计不依赖端口，必须能写"),
                () -> assertFalse(report.dlqEnqueued()),
                () -> assertFalse(report.taskMarkedFailed()));
    }

    @Test
    @DisplayName("无 taskId 的主路径调用不推任务终态，但审计与死信照常")
    void withoutTaskIdTheTaskPortIsSkipped() {
        RoutingFailureReport report = newHandler().handle(
                new RoutingFailureContext(RoutingFailureContext.STEP_QUERY,
                        "REMOTE_PACS", "MPI-1", "1.2.3", null, null),
                new RoutingFailureException(FailureCode.QUERY_FAILED, "查询中断", "REMOTE_PACS", "MPI-1"), 0);

        assertAll(
                () -> assertEquals(0, taskPort.taskIds.size(), "无 taskId 时不应推任务终态"),
                () -> assertFalse(report.taskMarkedFailed()),
                () -> assertTrue(report.auditRecorded()),
                () -> assertEquals(1, dlq.requests.size()));
    }

    // ==================================================================
    // 边界
    // ==================================================================

    @Test
    @DisplayName("任务层已安排重试（RETRY_SCHEDULED）→ 不落终局审计、不进死信（不能留假待办）")
    void retryScheduledFailureDoesNotEnterDlq() {
        taskPort.outcome = TaskFailureOutcome.RETRY_SCHEDULED;

        RoutingFailureReport report = newHandler().handle(CTX,
                new RoutingFailureException(FailureCode.QUERY_TIMEOUT,
                        "查询超时", "REMOTE_PACS", "MPI-1"), 1);

        assertAll(
                () -> assertEquals(1, taskPort.taskIds.size(), "任务层仍要被告知这次失败"),
                () -> assertEquals(0, dlq.requests.size(),
                        "还在自动重跑的任务不是死信 —— 假待办比没待办更糟"),
                () -> assertEquals(0, audit.rows.size(),
                        "非终局失败不落终局失败审计（任务层自己记重试审计）"),
                () -> assertFalse(report.dlqEnqueued()),
                () -> assertFalse(report.auditRecorded()),
                () -> assertTrue(report.taskMarkedFailed()));
    }

    @Test
    @DisplayName("空上下文 / null 异常不抛异常，收敛为可读的占位值")
    void nullContextAndNullThrowableAreTolerated() {
        RoutingFailureReport report = newHandler().handle(null, null, 0);

        assertAll(
                () -> assertEquals(FailureCodeRegistry.UNKNOWN, report.code()),
                () -> assertEquals("(unknown)", report.peerAet()),
                () -> assertEquals(0, report.retryCount()),
                () -> assertEquals("(无异常对象)", report.message()),
                () -> assertNotNull(report.suggestedAction()));
    }

    @Test
    @DisplayName("负的重试次数收敛为 0（不写进 DB 触发 CHECK 约束）")
    void negativeRetryCountIsClamped() {
        RoutingFailureReport report = newHandler().handle(CTX,
                new RoutingFailureException(FailureCode.STORE_FAILED, "写入失败", "LOCAL_PACS", null), -3);

        assertAll(
                () -> assertEquals(0, report.retryCount()),
                () -> assertEquals(0, audit.rows.get(0).getRetryCount()),
                () -> assertEquals(0, dlq.requests.get(0).retryAttempts()));
    }

    @Test
    @DisplayName("超长失败消息截断到 2000 字符，信息科要的是可读不是完整堆栈")
    void oversizedMessageIsTruncated() {
        String huge = "x".repeat(5000);
        RoutingFailureReport report = newHandler().handle(CTX,
                new RoutingFailureException(FailureCode.STORE_FAILED, huge, "LOCAL_PACS", null), 0);

        assertAll(
                () -> assertTrue(report.message().length() <= 2020, "截断后长度: " + report.message().length()),
                () -> assertTrue(report.message().endsWith("(截断)")));
    }

    @Test
    @DisplayName("报告对象自洽：failureCode / retryable / suggestedAction 三者同源")
    void reportIsSelfConsistent() {
        RoutingFailureHandler handler = newHandler();
        // 逐个主路径失败码走一遍：报告里的三处说法必须来自同一个登记项
        for (FailureCode code : FailureCode.values()) {
            FailureCodeRegistry expected = FailureCodeRegistry.of(code);
            // 消息刻意写得「中规中矩」，不触发 cause 链 / 文本的细化分支
            RoutingFailureReport report = handler.handle(CTX,
                    new RoutingFailureException(code, "普通失败消息", "P", "M"), 0);
            assertAll(code.name(),
                    () -> assertSame(expected, report.code()),
                    () -> assertEquals(expected.code(), report.failureCode()),
                    () -> assertEquals(expected.retryable(), report.retryable()),
                    () -> assertEquals(expected.suggestedAction(), report.suggestedAction()));
        }
    }

    private static RoutingFailureContext ctxOf(String step) {
        return new RoutingFailureContext(step, "REMOTE_PACS", "MPI-0000123",
                "1.2.826.0.1.3680043.10.9999.st.1",
                "11111111-2222-3333-4444-555555555555", "employee_007");
    }
}