package com.crosshospital.imaging.service.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.crosshospital.imaging.api.dto.DlqEntryResponse;
import com.crosshospital.imaging.config.DlqConfigProps;
import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.DlqEntry;
import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.exception.ConflictException;
import com.crosshospital.imaging.exception.NotFoundException;
import com.crosshospital.imaging.repository.DlqRepository;
import com.crosshospital.imaging.repository.FetchTaskRepository;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.routing.DlqEnqueuePort;
import com.crosshospital.imaging.service.routing.DlqEnqueueRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调阅任务死信队列业务层：入队 / 未处置列表 / 人工处置。
 *
 * <p><b>死信是什么</b>：一次跨院调阅反复失败且失败码判定为「不可重试」或「重试已耗尽」，
 * 自动重试救不回来，只能等人处置 —— 外院那台 PACS 关了、白名单把某个 AET 挡了、
 * 传输语法根本对不上。这类失败进 {@code dlq_fetch_tasks}，信息科在这里看见「哪些单子卡住了、
 * 卡在哪、为什么」，处置完标记 {@code resolved_at}，行保留 30 天后自动清理。
 *
 * <p><b>入队链路</b>：本类实现 {@link DlqEnqueuePort}，被两个上游共用 ——
 * {@code RoutingFailureHandler}（协议层失败统一出口）与 {@code FetchTaskService}
 * （重试耗尽判死）。两者都用 setter 注入这个端口，所以本类一落地，
 * 「失败 → 审计 → 死信 → 告警」这条链就自动通了，不需要改上游一行代码。
 *
 * <p><b>不静默原则</b>：
 * <ul>
 *   <li>入队失败（数据库不可达）<b>向上抛</b> —— 调用方会兜住并记日志，
 *       但绝不会假装「已入队」；</li>
 *   <li>告警失败<b>不向上抛</b> —— 告警只是通知，死信本身已经落库了，
 *       告警网关挂了不该让「排障失败」变成「调阅流程崩掉」。</li>
 *   <li>处置动作落审计行（{@code status_terminal = dlq_resolved}）——
 *       谁在什么时候处置了哪一条，是等保与内审语境下必须能回查的。</li>
 * </ul>
 *
 * <p><b>任务上下文缺失的处理</b>：{@code dlq_fetch_tasks.task_id} 是 {@code NOT NULL}，
 * 而协议层失败可能发生在任务体系之外（直接调 DICOM 三步、没有任务行）。
 * 这类情况写入<b>零值 UUID</b>（{@code 00000000-0000-0000-0000-000000000000}）占位，
 * 并在 API 响应里用 {@code taskContextPresent=false} 明确标出 ——
 * 而不是悄悄丢一条死信，或塞一个编造的 UUID 让人以为真有这么个任务。
 *
 * <p>源主路径证据：通用分布式系统 DLQ 模式（重试耗尽 → 死信 → 告警 → 人工处置 → TTL 清理）。
 */
@Service
@Profile("!test")
public class DlqService implements DlqEnqueuePort {

    private static final Logger LOG = LoggerFactory.getLogger(DlqService.class);

    /** 「无任务上下文」占位 UUID —— V5 的 task_id 列 NOT NULL，但协议层失败可能没有任务行。 */
    public static final UUID NO_TASK_CONTEXT = new UUID(0L, 0L);

    /** 失败消息写入死信表的最大长度（信息科要的是可读，不是完整堆栈）。 */
    public static final int MESSAGE_MAX_CHARS = 2000;

    private final DlqRepository dlqRepository;
    private final FetchTaskRepository fetchTaskRepository;
    private final AuditService auditService;
    private final DlqAlertPort alertPort;
    private final DlqConfigProps props;

    @Autowired
    public DlqService(DlqRepository dlqRepository,
                      FetchTaskRepository fetchTaskRepository,
                      AuditService auditService,
                      DlqAlertPort alertPort,
                      DlqConfigProps props) {
        this.dlqRepository = dlqRepository;
        this.fetchTaskRepository = fetchTaskRepository;
        this.auditService = auditService;
        this.alertPort = alertPort;
        this.props = props;
    }

    // ------------------------------------------------------------------ 入队

    /**
     * {@link DlqEnqueuePort} 入口：把一条「不可恢复或重试耗尽」的失败入队。
     *
     * <p>本方法<b>不吞异常</b> —— 入队失败必须让调用方知道（调用方会兜住并记日志）。
     */
    @Override
    public void enqueue(DlqEnqueueRequest request) {
        if (request == null) {
            return;
        }
        enqueue(request.taskId(), request.failureCode(), request.failureMessage(),
                request.retryAttempts());
    }

    /**
     * 入队一条死信。
     *
     * @param taskId         调阅任务 UUID（字符串形式）；不可得时传 {@code null} → 记零值占位
     * @param failureCode    失败码（与 {@code FailureCodeRegistry} 同一词表）
     * @param failureMessage 失败消息原文
     * @param retryAttempts  已重试次数
     * @return 已落库的死信行
     */
    @Transactional
    public DlqEntry enqueue(String taskId, String failureCode, String failureMessage,
                            int retryAttempts) {
        Instant now = Instant.now();
        DlqEntry entry = new DlqEntry();
        entry.setTaskId(parseTaskId(taskId));
        entry.setFailureCode(failureCode == null || failureCode.isBlank()
                ? "unknown" : failureCode.trim());
        entry.setFailureMessage(truncate(failureMessage));
        entry.setRetryAttempts(Math.max(0, retryAttempts));
        entry.setFirstFailedAt(now);
        entry.setLastRetryAt(now);
        entry.setCreatedAt(now);
        entry.setAlertWebhookSent(Boolean.FALSE);

        DlqEntry saved = dlqRepository.save(entry);
        LOG.warn("调阅失败已进死信：task={} code={} retry={} 说明={}",
                saved.getTaskId(), saved.getFailureCode(), saved.getRetryAttempts(),
                saved.getFailureMessage());

        dispatchAlert(saved);
        return saved;
    }

    /**
     * 推告警。告警失败只降级为日志 —— 死信已经落库，告警网关挂了不该把排障链路带崩。
     */
    private void dispatchAlert(DlqEntry entry) {
        DlqAlertPort port = alertPort;
        if (port == null) {
            LOG.debug("未接入告警端口，死信只落库不推告警：task={}", entry.getTaskId());
            return;
        }
        try {
            DlqAlertResult result = port.dispatch(entry);
            if (result == null) {
                return;
            }
            if (result.delivered()) {
                entry.setAlertWebhookSent(Boolean.TRUE);
                dlqRepository.save(entry);
            } else if (result.status() == DlqAlertResult.Status.FAILED) {
                LOG.error("死信告警投递失败（降级为日志告警）：task={} code={} 原因={}",
                        entry.getTaskId(), entry.getFailureCode(), result.detail());
            }
        } catch (RuntimeException ex) {
            LOG.error("死信告警分发异常（已吞掉，死信已落库）：task={} 原因={}",
                    entry.getTaskId(), ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ 处置

    /**
     * 信息科人工处置一条死信：标记已处置并落审计行。
     *
     * @param dlqId 死信行 ID
     * @param who   操作人（工号；空 → {@code system}）
     * @param note  处置说明（可空）
     * @return 处置后的死信（含任务上下文）
     */
    @Transactional
    public DlqEntryResponse resolve(long dlqId, String who, String note) {
        DlqEntry entry = dlqRepository.findById(dlqId)
                .orElseThrow(() -> new NotFoundException("dlq", String.valueOf(dlqId)));
        if (entry.getResolvedAt() != null) {
            throw new ConflictException("死信已处置，不能重复处置：dlq_id=" + dlqId
                    + " resolved_at=" + entry.getResolvedAt());
        }
        entry.setResolvedAt(Instant.now());
        entry.setResolvedBy(normalizeWho(who));
        entry.setResolutionNote(note);
        DlqEntry saved = dlqRepository.save(entry);
        recordResolveAudit(saved, normalizeWho(who));
        LOG.info("死信已处置：dlq_id={} task={} by={}", saved.getId(), saved.getTaskId(),
                saved.getResolvedBy());
        return toResponse(saved, lookupTask(saved));
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 未处置死信分页列表（按创建时间倒序）。
     *
     * @param page        页码（0 起）
     * @param size        页大小
     * @param failureCode 失败码过滤；{@code null} / 空 = 不过滤
     */
    @Transactional(readOnly = true)
    public Page<DlqEntryResponse> listUnresolved(int page, int size, String failureCode) {
        int safePage = Math.max(0, page);
        int maxSize = props == null ? 200 : Math.max(1, props.getMaxPageSize());
        int safeSize = Math.min(Math.max(1, size), maxSize);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<DlqEntry> rows = (failureCode == null || failureCode.isBlank())
                ? dlqRepository.findUnresolved(pageable)
                : dlqRepository.findUnresolvedByFailureCode(failureCode.trim(), pageable);
        if (rows == null) {
            // 仓储返回 null（不应发生）时按空列表处理，不把 NPE 抛给信息科
            return new PageImpl<>(List.of(), pageable, 0);
        }
        Map<UUID, FetchTask> tasks = lookupTasks(rows.getContent());
        return rows.map(entry -> toResponse(entry,
                entry.getTaskId() == null ? null : tasks.get(entry.getTaskId())));
    }

    /** 单条死信详情。 */
    @Transactional(readOnly = true)
    public DlqEntryResponse get(long dlqId) {
        DlqEntry entry = dlqRepository.findById(dlqId)
                .orElseThrow(() -> new NotFoundException("dlq", String.valueOf(dlqId)));
        return toResponse(entry, lookupTask(entry));
    }

    /** 未处置死信条数（信息科首页看「还有多少单子没人管」）。 */
    @Transactional(readOnly = true)
    public long countUnresolved() {
        return dlqRepository.countByResolvedAtIsNull();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 查任务上下文（一次查整页，避免 N+1）。任务不存在或已清理时返回 {@code null}。
     */
    private Map<UUID, FetchTask> lookupTasks(Collection<DlqEntry> entries) {
        List<UUID> ids = entries.stream()
                .map(DlqEntry::getTaskId)
                .filter(id -> id != null && hasTaskContext(id))
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, FetchTask> map = new HashMap<>();
        for (FetchTask task : fetchTaskRepository.findByTaskIdIn(ids)) {
            if (task.getTaskId() != null) {
                map.put(task.getTaskId(), task);
            }
        }
        return map;
    }

    private FetchTask lookupTask(DlqEntry entry) {
        if (entry == null || entry.getTaskId() == null || !hasTaskContext(entry.getTaskId())) {
            return null;
        }
        return fetchTaskRepository.findByTaskId(entry.getTaskId()).orElse(null);
    }

    private DlqEntryResponse toResponse(DlqEntry entry, FetchTask task) {
        return DlqEntryResponse.from(entry, task);
    }

    /** 解析任务 UUID；不可得或格式非法时回落到零值占位。 */
    static UUID parseTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return NO_TASK_CONTEXT;
        }
        try {
            UUID parsed = UUID.fromString(taskId.trim());
            return parsed.equals(NO_TASK_CONTEXT) ? NO_TASK_CONTEXT : parsed;
        } catch (IllegalArgumentException ex) {
            LOG.warn("死信入队的 task_id 不是合法 UUID，按「无任务上下文」处理: {}", taskId);
            return NO_TASK_CONTEXT;
        }
    }

    /** 该死信是否带真实任务上下文（零值 UUID = 没有）。 */
    public static boolean hasTaskContext(UUID taskId) {
        return taskId != null && !NO_TASK_CONTEXT.equals(taskId);
    }

    private static String normalizeWho(String who) {
        return who == null || who.isBlank() ? "system" : who.trim();
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MESSAGE_MAX_CHARS
                ? message
                : message.substring(0, MESSAGE_MAX_CHARS) + "…(截断)";
    }

    /**
     * 处置动作落审计行（append-only）。
     *
     * <p>{@code status_terminal = dlq_resolved}；{@code to_aet} / {@code study_uid}
     * 从任务上下文补齐（任务已清理时写 {@code (unknown)}）——
     * 内审问「这条死信是哪个外院、哪份影像」时要能一次查到。
     */
    private void recordResolveAudit(DlqEntry entry, String who) {
        if (auditService == null) {
            return;
        }
        FetchTask task = lookupTask(entry);
        String toAet = task == null || task.getPeerAet() == null ? "(unknown)" : task.getPeerAet();
        String studyUid = task == null ? null : task.getStudyUid();
        try {
            auditService.record(new AuditRow()
                    .withWho(who)
                    .withFromAet("local")
                    .withToAet(toAet)
                    .withStudyUid(studyUid)
                    .withRetryCount(entry.getRetryAttempts() == null ? 0 : entry.getRetryAttempts())
                    .withStatusTerminal("dlq_resolved")
                    .withFailureCode(entry.getFailureCode()));
        } catch (RuntimeException ex) {
            // 审计写失败不影响处置结果 —— 处置本身已经落库，审计只是留痕
            LOG.error("死信处置审计写入失败（已吞掉）：dlq_id={} 原因={}",
                    entry.getId(), ex.getMessage());
        }
    }

    /** 32 字节 0 —— task 24 之前审计校验和的占位值。 */
    static final byte[] EMPTY_CHECKSUM = new byte[32];

    /** 与 PeerRegistry 同口径的简化校验和（task 24 换成链式 SHA-256）。 */
    static byte[] computeSimpleChecksum(String who, Instant when, String aet, String status) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((who == null ? "system" : who).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(when.toString().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(aet.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(status.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            return EMPTY_CHECKSUM;
        }
    }
}
