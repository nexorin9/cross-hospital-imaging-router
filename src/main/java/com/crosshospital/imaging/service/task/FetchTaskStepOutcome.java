package com.crosshospital.imaging.service.task;

/**
 * 一次协议子步的产出 —— 回写到 {@code imaging_fetch_tasks} 行上的补充字段。
 *
 * <p>子步（query / retrieve / store）跑完会带回一些「只有真跑了协议才知道」的信息：
 * 协商出来的 SOP Class、拉回的实例数、字节数、耗时。这些字段落进任务行，
 * 信息科在 {@code GET /api/imaging/fetch/{task_id}} 上就能看到「这次到底拉了多少东西」，
 * 不必再去翻 PACS。
 *
 * @param sopClassUid     协商出的 SOP Class UID（可空）
 * @param sopInstanceCount 实例数（可空）
 * @param bytesTotal      字节数（可空）
 * @param durationMs      本步耗时毫秒（可空）
 */
public record FetchTaskStepOutcome(String sopClassUid,
                                   Integer sopInstanceCount,
                                   Long bytesTotal,
                                   Long durationMs) {

    /** 空产出（该状态没有协议子步，或子步没有可回写字段）。 */
    public static FetchTaskStepOutcome none() {
        return new FetchTaskStepOutcome(null, null, null, null);
    }

    /** 是否什么都没带回来。 */
    public boolean isEmpty() {
        return sopClassUid == null && sopInstanceCount == null
                && bytesTotal == null && durationMs == null;
    }
}
