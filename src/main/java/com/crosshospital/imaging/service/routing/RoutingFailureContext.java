package com.crosshospital.imaging.service.routing;

/**
 * 一次路由失败的**定位上下文** —— 「哪一步、对哪个节点、哪个病人、哪份影像、哪个任务」。
 *
 * <p>审计行与死信行需要的是同一组定位字段，两者共用本 record，避免两处各写一份
 * 导致「审计说 AET 是 X，死信说是 Y」这种对不上的脏数据。
 *
 * @param step      主路径步骤：{@code query} / {@code retrieve} / {@code store}
 * @param peerAet   对端 AE Title（外院节点；C-STORE 步骤是本院 PACS 的 AET）
 * @param patientMpi 病人主索引；不可得时为 {@code null}（C-STORE 按协议不带 study 级病人标识）
 * @param studyUid  目标 StudyInstanceUID；不可得时为 {@code null}
 * @param taskId    调阅任务 UUID（字符串形式）；主路径可独立于任务调用时为 {@code null}
 * @param who       操作人工号；系统级动作为 {@code null}（落库时取 {@code system}）
 */
public record RoutingFailureContext(String step,
                                    String peerAet,
                                    String patientMpi,
                                    String studyUid,
                                    String taskId,
                                    String who) {

    public static final String STEP_QUERY = "query";
    public static final String STEP_RETRIEVE = "retrieve";
    public static final String STEP_STORE = "store";

    /** 审计 {@code status_terminal} 列取值：把步骤名翻成终态名。 */
    public String statusTerminal() {
        String normalized = step == null || step.isBlank() ? "unknown" : step.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case STEP_QUERY -> "query_failed";
            case STEP_RETRIEVE -> "retrieve_failed";
            case STEP_STORE -> "store_failed";
            default -> normalized + "_failed";
        };
    }

    /** 审计 {@code who} 列取值（空值统一收敛成 {@code system}，与 AuditService 一致）。 */
    public String auditWho() {
        return who == null || who.isBlank() ? "system" : who;
    }

    /** 审计 {@code from_aet} 列：本服务永远是发起方。 */
    public String fromAet() {
        return "local";
    }

    /** 审计 {@code to_aet} 列；对端 AET 不可得时写 {@code "(unknown)"} 而不是空值。 */
    public String toAet() {
        return peerAet == null || peerAet.isBlank() ? "(unknown)" : peerAet;
    }
}