package com.crosshospital.imaging.service.notification;

import java.util.UUID;

/**
 * HIS 工单回调消息体（task 32）。
 *
 * @param taskId        调阅任务 ID
 * @param studyUid      外院 StudyInstanceUID（不重命名）
 * @param targetPacsAet 影像写入的本院 PACS AE Title
 * @param status        终态（notified）
 */
public record NotificationRequest(UUID taskId,
                                  String studyUid,
                                  String targetPacsAet,
                                  String status) {
}
