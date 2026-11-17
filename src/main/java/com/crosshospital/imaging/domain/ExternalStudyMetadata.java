package com.crosshospital.imaging.domain;

import java.util.List;

/**
 * 外院检查元数据（互认初筛的输入，task 29）。
 *
 * <p>来自 C-FIND 的 Study / Series 级属性与外院报告里的检查项目名，
 * <b>不是</b>模型编出来的内容。缺字段时初筛一律往保守一侧判。
 *
 * @param studyUid  外院 StudyInstanceUID
 * @param modality  外院模态（CT / MR / XA …）
 * @param studyItem 外院检查项目名
 * @param series    外院序列清单
 */
public record ExternalStudyMetadata(String studyUid,
                                    String modality,
                                    String studyItem,
                                    List<String> series) {

    public ExternalStudyMetadata {
        series = series == null ? List.of() : List.copyOf(series);
    }

    /** 转成提示词视图。 */
    public com.crosshospital.imaging.service.model.PromptTemplates.ExternalStudyMetadataView toView() {
        return new com.crosshospital.imaging.service.model.PromptTemplates.ExternalStudyMetadataView(
                studyUid, modality, studyItem, series);
    }
}
