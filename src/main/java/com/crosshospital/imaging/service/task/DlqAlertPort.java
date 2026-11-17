package com.crosshospital.imaging.service.task;

import com.crosshospital.imaging.domain.DlqEntry;

/**
 * 死信告警外发端口。
 *
 * <p>拆成接口的理由与 {@code DlqEnqueuePort} 同源：{@link DlqService}（业务层）要能
 * 在「死信已落库」之后触发一次告警外发，但这三件事（落库 / 告警 / 处置）分属不同步骤，
 * 各自要能单独验收、单独失败重放。端口让业务层不直接依赖具体 HTTP 实现。
 *
 * <p>约定：实现方<b>不得</b>抛异常 —— 告警失败只降级为日志，绝不能反过来把
 * 「调阅已经失败」这件事的排障链路带崩。
 */
@FunctionalInterface
public interface DlqAlertPort {

    /**
     * 把一条死信推给告警网关。
     *
     * @param entry 已落库的死信行（其 {@code taskId / failureCode / failureMessage /
     *              retryAttempts} 构成告警报文主体）
     * @return 投递回执（不返回 {@code null}）
     */
    DlqAlertResult dispatch(DlqEntry entry);
}
