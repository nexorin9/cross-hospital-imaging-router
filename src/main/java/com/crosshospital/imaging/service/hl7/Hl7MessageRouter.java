package com.crosshospital.imaging.service.hl7;

import java.nio.charset.Charset;

import com.crosshospital.imaging.config.Hl7ConfigProps;
import com.crosshospital.imaging.domain.Hl7DeadLetter;
import com.crosshospital.imaging.domain.Hl7ParsedMessage;
import com.crosshospital.imaging.exception.Hl7ParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 触发报文路由器：把一帧 MLLP 报文分派到对应的业务处理器。
 *
 * <p><b>源主路径采纳</b>：{@code github_ref/dcm4che/dcm4che-net-hl7/.../HL7ConnectionMonitor.java:54-62}
 * —— 源产品的 HL7 应用层是「连接 → 收报文 → {@code onMessageReceived} → 业务消费 → {@code onMessageProcessed}」
 * 的单向链路，收报文与业务消费之间就是这么一个回调点。本类是那个回调点的业务化形态：
 * 线路层只管分帧与 ACK/NAK，消息类型语义（ORM^O01 / ADT^A04 / ADT^A08）全在这里分派。
 *
 * <p><b>分派表</b>：
 * <table border="1">
 *   <caption>消息类型分派</caption>
 *   <tr><th>路由键（MSH-9）</th><th>处理器</th><th>动作</th></tr>
 *   <tr><td>ORM^O01</td><td>{@link OrderMessageHandler}</td><td>入队调阅任务 + 落审计</td></tr>
 *   <tr><td>ADT^A04 / ADT^A08</td><td>{@link AdmitMessageHandler}</td><td>留痕，不发起调阅</td></tr>
 *   <tr><td>其他</td><td>—</td><td>进死信 + 回 NAK（不让 HIS 以为已被受理）</td></tr>
 * </table>
 *
 * <p><b>关键约定：本类抛异常 = 线路层回 NAK</b>。解析失败与业务拒绝都要向上抛，
 * 线路层据此回 NAK 并断开连接，让 HIS 知道这条触发没被接住。
 * 唯一不抛的是"报文合规但没有承接外院"——那不是 HIS 的错，回 NAK 只会造成重投风暴，
 * 这种情况在 {@link OrderMessageHandler} 里落死信 + 审计后正常返回。
 *
 * <p>本类跑在 Netty 事件循环线程上，因此<b>不做任何阻塞式外呼</b>：
 * 数据库写入是本机短事务，告警 webhook 走独立线程（见 {@link Hl7DeadLetterHandler}）。
 *
 * <p>{@link Primary} 的理由：{@code MllpServerConfig} 里还有一个"只记入门日志"的兜底路由器
 * （{@code Hl7IngressLogRouter}），用 {@code @ConditionalOnMissingBean} 让位。条件求值依赖
 * 扫描顺序，标上 {@code @Primary} 是双保险 —— 万一两个 bean 同时存在，注入的一定是本业务路由器，
 * 而不是那个"只记日志、看起来成功但其实什么都没做"的兜底实现。
 */
@Service
@Primary
@Profile("!test")
public class Hl7MessageRouter implements Hl7TriggerRouter {

    private static final Logger LOG = LoggerFactory.getLogger(Hl7MessageRouter.class);

    /** ORM^O01 —— 影像医嘱，触发跨院调阅。 */
    public static final String ROUTE_ORM_O01 = "ORM^O01";
    /** ADT^A04 —— 患者入院登记。 */
    public static final String ROUTE_ADT_A04 = "ADT^A04";
    /** ADT^A08 —— 患者信息变更。 */
    public static final String ROUTE_ADT_A08 = "ADT^A08";

    private final Hl7MessageParser parser;
    private final OrderMessageHandler orderHandler;
    private final AdmitMessageHandler admitHandler;
    private final Hl7DeadLetterHandler deadLetterHandler;
    private final Hl7ConfigProps props;

    public Hl7MessageRouter(Hl7MessageParser parser,
                            OrderMessageHandler orderHandler,
                            AdmitMessageHandler admitHandler,
                            Hl7DeadLetterHandler deadLetterHandler,
                            Hl7ConfigProps props) {
        if (parser == null || orderHandler == null || admitHandler == null
                || deadLetterHandler == null || props == null) {
            throw new IllegalArgumentException("Hl7MessageRouter 依赖不能为 null");
        }
        this.parser = parser;
        this.orderHandler = orderHandler;
        this.admitHandler = admitHandler;
        this.deadLetterHandler = deadLetterHandler;
        this.props = props;
    }

    @Override
    public void route(MllpFrame frame) {
        Charset charset = props.getTrigger().resolveCharset();

        Hl7ParsedMessage message;
        try {
            message = parser.parse(frame, charset);
        } catch (Hl7ParseException ex) {
            // 报文本身不合法：落死信 + 继续向上抛（线路层回 NAK，让 HIS 知道这条没被接住）
            deadLetterHandler.recordParseFailure(frame, ex);
            throw ex;
        }

        String routeKey = message.routeKey();
        LOG.debug("触发报文已解析，按类型分派：routeKey={} control_id={} mpi={}",
                routeKey, message.messageControlId(), message.patientMpi());
        switch (routeKey) {
            case ROUTE_ORM_O01:
                orderHandler.handle(message, frame);
                break;
            case ROUTE_ADT_A04:
            case ROUTE_ADT_A08:
                admitHandler.handle(message, frame);
                break;
            default:
                // 解析器已拦掉不在白名单内的类型，这里是兜底（解析器白名单被放宽时仍安全）
                Hl7ParseException ex = new Hl7ParseException(
                        Hl7ParseException.FailureCode.UNSUPPORTED_MESSAGE_TYPE,
                        "routeKey=" + routeKey + " 无对应处理器");
                deadLetterHandler.recordParseFailure(frame, ex);
                throw ex;
        }
    }

    /** 供健康检查与日志查看：本路由器支持的消息类型。 */
    public java.util.Set<String> supportedRouteKeys() {
        return java.util.Set.of(ROUTE_ORM_O01, ROUTE_ADT_A04, ROUTE_ADT_A08);
    }

    /** 供健康检查查看：死信分类全集。 */
    public java.util.Set<String> deadLetterCodes() {
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        for (Hl7DeadLetter.FailureCode c : Hl7DeadLetter.FailureCode.values()) {
            codes.add(c.code());
        }
        return codes;
    }
}
