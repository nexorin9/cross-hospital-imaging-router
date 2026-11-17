package com.crosshospital.imaging.service.hl7;

import com.crosshospital.imaging.domain.AuditRow;
import com.crosshospital.imaging.domain.Hl7ParsedMessage;
import com.crosshospital.imaging.service.AuditService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * ADT^A04 / ADT^A08（入院登记 / 患者信息变更）处理器。
 *
 * <p><b>为什么入院登记不发起调阅</b>：跨院互认的调阅应由<b>影像医嘱</b>发起 ——
 * 医生开了检查单，说明这次检查确实要做，才需要把外院的旧片子调回来做对照。
 * 若改成"患者一入院就拉外院历史影像"，本院每一位住院患者都会触发一次跨院 DICOM 拉取，
 * 流量放大到全院级别，而且拉回来的片子未必是本次检查要对照的那一份。
 *
 * <p>因此本处理器的职责是<b>留痕</b>：把触发通道里进来的 ADT 流量记进审计，
 * 让信息科能判断「HIS 侧的接入是否正常」——如果一条 ADT 都没有，说明 HIS 没在发；
 * 如果 ADT 很多而 ORM^O01 没有，说明 HIS 只接了入院登记、没接影像医嘱，
 * 调阅链路就是断的。这条判断在排障时非常关键。
 *
 * <p>源主路径证据：{@code github_ref/dcm4che/dcm4che-net-hl7/.../HL7ConnectionMonitor.java:54-62}
 * —— 源产品按消息类型分派到不同处理回调，本类是该分派在 ADT 分支上的落点。
 */
@Service
@Profile("!test")
public class AdmitMessageHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AdmitMessageHandler.class);

    /** 审计终态：收到入院登记（未据此发起调阅）。 */
    public static final String STATUS_OBSERVED = "hl7_admit_observed";

    private final AuditService auditService;

    public AdmitMessageHandler(AuditService auditService) {
        if (auditService == null) {
            throw new IllegalArgumentException("AdmitMessageHandler 依赖不能为 null");
        }
        this.auditService = auditService;
    }

    /**
     * 处理一条 ADT 报文：留痕，不发起调阅。
     *
     * @param message 已解析的触发消息
     * @param frame   原始帧（仅用于日志）
     */
    public void handle(Hl7ParsedMessage message, MllpFrame frame) {
        LOG.info("收到 {} 报文（不据此发起调阅）：control_id={} mpi={} facility={}",
                message.routeKey(), message.messageControlId(), message.patientMpi(),
                message.sendingFacility());
        try {
            auditService.record(new AuditRow()
                    .withWho(initiatedBy(message))
                    .withFromAet("local")
                    .withToAet(null)
                    .withRetryCount(0)
                    .withStatusTerminal(STATUS_OBSERVED));
        } catch (RuntimeException ex) {
            LOG.error("ADT 报文审计写入失败：{}", ex.getMessage());
        }
    }

    private static String initiatedBy(Hl7ParsedMessage message) {
        String app = message.sendingApplication();
        return app == null || app.isBlank() ? "system" : app;
    }
}
