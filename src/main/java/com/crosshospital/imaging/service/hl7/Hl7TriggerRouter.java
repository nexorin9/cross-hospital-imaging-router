package com.crosshospital.imaging.service.hl7;

/**
 * 触发报文路由端口：MLLP 守护收全一帧后交给它决定怎么走。
 *
 * <p>源主路径证据：{@code github_ref/dcm4che/dcm4che-net-hl7/.../HL7ConnectionMonitor.java:54-62}
 * —— 源产品的 HL7 应用层是「连接 → 收报文 → {@code onMessageReceived} 回调 → 业务消费」的单向链路，
 * 收报文与业务消费之间就是这么一个回调点。
 *
 * <p>本端口就是那个回调点在本产品里的形态：{@link MllpTriggerService} 只负责线路层（分帧 + ACK/NAK），
 * 不认识 ORM^O01 / ADT^A04 的业务语义；task 18/19 提供的实现负责段解析与入队。
 */
public interface Hl7TriggerRouter {

    /**
     * 处理一条已收全的触发帧。
     *
     * <p><b>实现约定</b>：
     * <ul>
     *   <li>返回即视为「线路层已应答」——守护会立刻回 ACK；</li>
     *   <li>抛异常则守护回 NAK 并断开该连接（对端会重投，语义清晰）；</li>
     *   <li>实现<b>不得</b>阻塞事件循环线程（长耗时交由任务队列），否则一条慢报文会拖垮整个 worker。</li>
     * </ul>
     */
    void route(MllpFrame frame) throws Exception;
}