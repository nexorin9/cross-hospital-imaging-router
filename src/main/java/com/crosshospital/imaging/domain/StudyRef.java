package com.crosshospital.imaging.domain;

import java.util.Objects;

/**
 * 一条 STUDY 级 C-FIND 查询命中的外院检查记录（不可变值对象）。
 *
 * <p>字段全部保留 **DICOM 线值**：StudyInstanceUID / PatientID / StudyDate 一律
 * 原样透传，本服务**不**重命名、不做日期格式转换 —— 后续 C-MOVE / C-STORE
 * 必须用同一组 UID 找回同一份影像（见 spec.md「权威数据源 / 实体绑定」验收）。
 *
 * <p>{@link #sopClassUid} 记的是本次查询实际使用的 Query/Retrieve SOP Class UID
 * （如 Study Root Query/Retrieve Information Model - FIND
 * {@code 1.2.840.10008.5.1.4.1.2.2.1}）。STUDY 级 C-FIND 的响应数据集里
 * 并**不**含 SOP Class UID（DICOM PS3.4 C.2.2.1.2），因此这里记录的是
 * 「用哪个查询模型问出来」，供审计与回放定位，而不是某个影像对象的存储 SOP Class。
 *
 * <p>源主路径证据：dcm4che-net {@code Association.cfind(cuid, priority, keys, tsuid, timeout)}
 * —— C-FIND-RQ 逐条 Pending 响应 + 终态 Cancel 响应，本类为 {@code DimseRSP}
 * 循环中每条 Pending 响应数据集的投影。
 */
public final class StudyRef {

    private final String studyUid;
    private final String sopClassUid;
    private final String transferSyntax;
    private final String patientId;
    private final String patientName;
    private final String accessionNumber;
    private final String modality;
    private final String studyDate;
    private final Integer numberOfInstances;

    public StudyRef(String studyUid,
                    String sopClassUid,
                    String transferSyntax,
                    String patientId,
                    String patientName,
                    String accessionNumber,
                    String modality,
                    String studyDate,
                    Integer numberOfInstances) {
        this.studyUid = studyUid;
        this.sopClassUid = sopClassUid;
        this.transferSyntax = transferSyntax;
        this.patientId = patientId;
        this.patientName = patientName;
        this.accessionNumber = accessionNumber;
        this.modality = modality;
        this.studyDate = studyDate;
        this.numberOfInstances = numberOfInstances;
    }

    /** 外院 StudyInstanceUID —— 全流程主锚，**不重命名**。 */
    public String getStudyUid() { return studyUid; }

    /** 本次查询使用的 Query/Retrieve SOP Class UID（STUDY 级响应不含存储 SOP Class）。 */
    public String getSopClassUid() { return sopClassUid; }

    /** 关联实际协商成功的传输语法（C-FIND 响应数据集的解码 TS）。 */
    public String getTransferSyntax() { return transferSyntax; }

    public String getPatientId() { return patientId; }

    public String getPatientName() { return patientName; }

    public String getAccessionNumber() { return accessionNumber; }

    public String getModality() { return modality; }

    /** 检查日期，保留 DICOM DA 原始串（yyyyMMdd），不做本地化转换。 */
    public String getStudyDate() { return studyDate; }

    /** 外院报告的 instance 总数；外院未返回时为 {@code null}（**不**臆造为 0）。 */
    public Integer getNumberOfInstances() { return numberOfInstances; }

    /** STUDY 级查询的最小可判定条件：必须有 StudyInstanceUID，否则 C-MOVE 无从发起。 */
    public boolean hasRetrievableUid() {
        return studyUid != null && !studyUid.isBlank();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StudyRef other)) return false;
        return Objects.equals(studyUid, other.studyUid)
                && Objects.equals(sopClassUid, other.sopClassUid)
                && Objects.equals(transferSyntax, other.transferSyntax)
                && Objects.equals(patientId, other.patientId)
                && Objects.equals(patientName, other.patientName)
                && Objects.equals(accessionNumber, other.accessionNumber)
                && Objects.equals(modality, other.modality)
                && Objects.equals(studyDate, other.studyDate)
                && Objects.equals(numberOfInstances, other.numberOfInstances);
    }

    @Override
    public int hashCode() {
        return Objects.hash(studyUid, sopClassUid, transferSyntax, patientId,
                patientName, accessionNumber, modality, studyDate, numberOfInstances);
    }

    @Override
    public String toString() {
        return "StudyRef{studyUid=" + studyUid
                + ", patientId=" + patientId
                + ", modality=" + modality
                + ", studyDate=" + studyDate
                + ", accessionNumber=" + accessionNumber
                + ", instances=" + numberOfInstances
                + ", sopClass=" + sopClassUid
                + ", transferSyntax=" + transferSyntax + '}';
    }
}