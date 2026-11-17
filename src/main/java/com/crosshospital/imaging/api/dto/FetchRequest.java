package com.crosshospital.imaging.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 跨院影像调阅请求（{@code POST /api/imaging/studies/fetch}）。
 *
 * <p>{@code peerAet} 省略时由路由服务按 {@code patientMpi} 的命名空间自动定位外院节点；
 * 定位不到直接返回 400，不猜一个节点去连。
 */
@Schema(name = "FetchRequest", description = "跨院影像调阅请求：患者主索引 + 外院 StudyInstanceUID")
public class FetchRequest {

    @Schema(description = "患者主索引（HIS PID-3）", example = "HZH^1234567", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "patientMpi 不能为空")
    private String patientMpi;

    @Schema(description = "外院 StudyInstanceUID（不重命名）", example = "1.2.840.10008.5.1.4.1.1.2.EXAMPLE",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "studyUid 不能为空")
    private String studyUid;

    @Schema(description = "SOP Class UID（协议层识别，可空）", example = "1.2.840.10008.5.1.4.1.1.2")
    @Size(max = 128)
    private String sopClassUid;

    @Schema(description = "外院检查申请号（Accession Number，可空）", example = "<EXAMPLE_ACCESSION>")
    @Size(max = 128)
    private String accessionNumber;

    @Schema(description = "承接调阅的外院节点 AET；留空则按主索引命名空间自动定位", example = "PEER_HZ")
    @Size(max = 16)
    private String peerAet;

    public String getPatientMpi() { return patientMpi; }
    public void setPatientMpi(String patientMpi) { this.patientMpi = patientMpi; }

    public String getStudyUid() { return studyUid; }
    public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

    public String getSopClassUid() { return sopClassUid; }
    public void setSopClassUid(String sopClassUid) { this.sopClassUid = sopClassUid; }

    public String getAccessionNumber() { return accessionNumber; }
    public void setAccessionNumber(String accessionNumber) { this.accessionNumber = accessionNumber; }

    public String getPeerAet() { return peerAet; }
    public void setPeerAet(String peerAet) { this.peerAet = peerAet; }
}
