package com.crosshospital.imaging.service.task;

import java.util.UUID;

/**
 * 调阅完成后的通知插头（task 32）。
 *
 * <p><b>为什么是可选端口</b>：HIS 通知是「成功之后的动作」，不是主路径的前置条件。
 * 端口没接上时 {@code FetchTaskService} 的行为与没有这一层完全一致；
 * 接上后，通知失败也<b>不回退 notified</b> —— 影像已经在本院 PACS 里，
 * 把回调失败算成调阅失败会让信息科去查网络，而真正该做的是补推一次通知。
 */
public interface NotificationPort {

    /**
     * 任务推进到 notified 后调用。
     *
     * <p>实现方<b>不得</b>抛异常；投递失败自行落死信与告警。
     *
     * @param taskId  任务 ID
     * @param studyUid 外院 StudyInstanceUID
     * @param status  终态（notified）
     */
    void onNotified(UUID taskId, String studyUid, String status);
}
