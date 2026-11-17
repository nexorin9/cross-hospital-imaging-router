package com.crosshospital.imaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 外院检查资料（V9）—— AI 两个介入点的真实输入。
 *
 * <p><b>为什么单独一张表</b>：调阅任务行只记「路由状态」，报告原文与序列清单属于
 * 外院资料，两者生命周期不同（一份检查资料可被多次调阅复用）。
 * 资料由 C-FIND / WADO-RS 拉回后登记，未登记时增强层不产出草稿，
 * <b>不凭空造一份外院报告给医生看</b>。
 *
 * <p>三个 JSONB 列以 JSON 文本承载，{@link JdbcTypeCode} 指定 JSON 绑定
 * （task 27 在 ai_summaries.payload 上踩过 varchar→jsonb 的 42804 错误）。
 */
@Entity
@Table(name = "external_study_materials")
public class ExternalStudyMaterial {

    @Id
    @Column(name = "study_uid", nullable = false)
    private String studyUid;

    @Column(name = "report_text")
    private String reportText;

    @Column(name = "modality")
    private String modality;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "series", nullable = false, columnDefinition = "jsonb")
    private String series = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "study_items", nullable = false, columnDefinition = "jsonb")
    private String studyItems = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "local_study_items", nullable = false, columnDefinition = "jsonb")
    private String localStudyItems = "[]";

    public String getStudyUid() { return studyUid; }
    public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

    public String getReportText() { return reportText; }
    public void setReportText(String reportText) { this.reportText = reportText; }

    public String getModality() { return modality; }
    public void setModality(String modality) { this.modality = modality; }

    public String getSeries() { return series; }
    public void setSeries(String series) { this.series = series == null ? "[]" : series; }

    public String getStudyItems() { return studyItems; }
    public void setStudyItems(String studyItems) { this.studyItems = studyItems == null ? "[]" : studyItems; }

    public String getLocalStudyItems() { return localStudyItems; }
    public void setLocalStudyItems(String localStudyItems) {
        this.localStudyItems = localStudyItems == null ? "[]" : localStudyItems;
    }
}
