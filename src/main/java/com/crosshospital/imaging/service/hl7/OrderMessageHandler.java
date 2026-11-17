package com.crosshospital.imaging.service.hl7;

import java.util.UUID;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.Hl7DeadLetter;
import com.crosshospital.imaging.domain.Hl7ParsedMessage;
import com.crosshospital.imaging.exception.Hl7TriggerRejectedException;
import com.crosshospital.imaging.service.AuditService;
import com.crosshospital.imaging.service.routing.PeerRoutingResolver;
import com.crosshospital.imaging.service.task.FetchTaskEnqueueRequest;
import com.crosshospital.imaging.service.task.FetchTaskService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * ORM^O01（影像医嘱）处理器：把一条触发报文变成一条真实的调阅任务。
 *
 * <p><b>本类的存在意义</b>：HIS 的 ORM^O01 到了，只意味着"有人开了一张检查单"，
 * 不意味着"这次调阅已经安排上了"。中间缺的三步就在这里补齐：
 *
 * <ol>
 *   <li><b>取影像引用</b>（OBR-3）—— 没有它就不知道要拉哪一次检查；</li>
 *   <li><b>定位承接外院</b>—— 主索引 → 外院 AET（{@link PeerRoutingResolver}）；</li>
 *   <li><b>入队 + 落审计</b>—— 落一行 {@code status=queued} 的调阅任务，
 *       让"这次调阅被安排上了"成为可查的业务状态，而不是一句日志。</li>
 * </ol>
 *
 * <p><b>三种结局，三种对 HIS 的答复</b>：
 * <table border="1">
 *   <caption>结局与应答</caption>
 *   <tr><th>结局</th><th>死信分类</th><th>线路层答复</th><th>为什么</th></tr>
 *   <tr><td>成功入队</td><td>—</td><td>ACK</td><td>调阅已安排，HIS 可以放心</td></tr>
 *   <tr><td>报文不合法 / 缺影像引用</td><td>invalid_msh / missing_pid / missing_study_reference</td>
 *       <td>NAK</td><td>是 HIS 这条报文有问题，重投有意义</td></tr>
 *   <tr><td>找不到承接外院</td><td>no_target_peer</td><td>ACK</td>
 *       <td>报文没毛病，只是这个患者没有外院影像；回 NAK 只会造成重投风暴</td></tr>
 * </table>
 *
 * <p>源主路径证据：{@code github_ref/dcm4che/dcm4che-net-hl7/.../HL7ConnectionMonitor.java:54-62}
 * —— 源产品把「收到」与「处理完」分成两个回调，本类照此把「收到」与「安排上了」分开。
 */
@Service
@Profile("!test")
public class OrderMessageHandler {

    private static final Logger LOG = LoggerFactory.getLogger(OrderMessageHandler.class);

    /** 审计终态：触发已入队。 */
    public static final String STATUS_QUEUED = "hl7_trigger_queued";
    /** 审计终态：触发合规但没有承接外院。 */
    public static final String STATUS_NO_TARGET_PEER = "hl7_trigger_no_target_peer";

    private final FetchTaskService fetchTaskService;
    private final AuditService auditService;
    private final PeerRoutingResolver peerRoutingResolver;
    private final Hl7DeadLetterHandler deadLetterHandler;

    public OrderMessageHandler(FetchTaskService fetchTaskService,
                               AuditService auditService,
                               PeerRoutingResolver peerRoutingResolver,
                               Hl7DeadLetterHandler deadLetterHandler) {
        if (fetchTaskService == null || auditService == null
                || peerRoutingResolver == null || deadLetterHandler == null) {
            throw new IllegalArgumentException("OrderMessageHandler 依赖不能为 null");
        }
        this.fetchTaskService = fetchTaskService;
        this.auditService = auditService;
        this.peerRoutingResolver = peerRoutingResolver;
        this.deadLetterHandler = deadLetterHandler;
    }

    /**
     * 处理一条 ORM^O01。
     *
     * @param message 已解析的触发消息
     * @param frame   原始帧（用于死信摘要与日志定位）
     * @throws Hl7TriggerRejectedException 报文缺发起调阅所必需的字段（线路层回 NAK）
     */
    public void handle(Hl7ParsedMessage message, MllpFrame frame) {
        // PID-3 是复合字段（值^^^赋号机构^类型码）；跨院调阅要的是稳定的那一个标识
        String patientId = message.patientIdentifier();
        if (patientId == null || patientId.isBlank()) {
            String detail = "PID-3 患者标识为空：无法定位外院的那一次检查";
            deadLetterHandler.recordMissingStudyReference(message, frame, detail);
            throw new Hl7TriggerRejectedException(Hl7DeadLetter.FailureCode.MISSING_STUDY_REFERENCE, detail);
        }

        String studyReference = message.accessionNumber();
        if (studyReference == null || studyReference.isBlank()) {
            String detail = "OBR-3 影像引用为空：无法定位外院的那一次检查（患者主索引 "
                    + patientId + "）";
            deadLetterHandler.recordMissingStudyReference(message, frame, detail);
            throw new Hl7TriggerRejectedException(Hl7DeadLetter.FailureCode.MISSING_STUDY_REFERENCE, detail);
        }

        PeerRoutingResolver.PeerRoutingResult routing = peerRoutingResolver.resolve(patientId);
        if (!routing.resolved()) {
            // 报文本身合规 —— 不回 NAK。只落死信 + 审计，让信息科看见这个缺口
            LOG.warn("ORM^O01 触发未找到承接外院，已记缺口不发起调阅：mpi={} 原因={}",
                    patientId, routing.reason());
            deadLetterHandler.recordNoTargetPeer(message, frame, routing.reason());
            recordAudit(message, null, STATUS_NO_TARGET_PEER, routing.reason());
            return;
        }

        UUID taskId;
        try {
            taskId = fetchTaskService.enqueue(FetchTaskEnqueueRequest.fromHl7(
                    patientId,
                    studyReference,
                    routing.peerAet(),
                    message.sendingApplication(),
                    studyReference));
        } catch (RuntimeException ex) {
            deadLetterHandler.recordEnqueueFailed(message, frame, ex);
            throw new Hl7TriggerRejectedException(Hl7DeadLetter.FailureCode.ENQUEUE_FAILED,
                    "调阅任务入队失败: " + ex.getMessage());
        }

        recordAudit(message, routing.peerAet(), STATUS_QUEUED, null);
        LOG.info("ORM^O01 触发已入队：taskId={} mpi={} study_ref={} peer_aet={} control_id={}",
                taskId, patientId, studyReference, routing.peerAet(), message.messageControlId());
    }

    /** 落一行审计：谁、什么时候、把哪次检查派给哪家外院。 */
    private void recordAudit(Hl7ParsedMessage message,
                             String peerAet,
                             String statusTerminal,
                             String failureReason) {
        AuditRow row = new AuditRow()
                .withWho(initiatedBy(message))
                .withFromAet("local")
                .withToAet(peerAet)
                .withStudyUid(message.accessionNumber())
                .withRetryCount(0)
                .withStatusTerminal(statusTerminal);
        if (failureReason != null) {
            row.withFailureCode(Hl7DeadLetter.FailureCode.NO_TARGET_PEER.code());
        }
        try {
            auditService.record(row);
        } catch (RuntimeException ex) {
            // 审计写失败不能反过来把已入队的任务变成失败：任务已经落库可查
            LOG.error("HL7 触发审计写入失败（任务已入库，不回滚）：{}", ex.getMessage());
        }
    }

    /** 发起人：HL7 发送应用名（MSH-3），缺失时归到 {@code system}。 */
    private static String initiatedBy(Hl7ParsedMessage message) {
        String app = message.sendingApplication();
        return app == null || app.isBlank() ? "system" : app;
    }
}
