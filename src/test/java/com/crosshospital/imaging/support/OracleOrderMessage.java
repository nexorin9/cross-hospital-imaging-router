package com.crosshospital.imaging.support;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import com.crosshospital.imaging.service.hl7.MllpConstants;

/**
 * 测试用 HL7 v2.x 报文构造器（ORM^O01 调阅触发 + ADT^A04）。
 *
 * <p><b>脱敏纪律</b>：本类只产出占位标识（{@code PAT_MPI_EXAMPLE} / {@code <EXAMPLE_STUDY_UID>} /
 * {@code ACC_EXAMPLE} / {@code MSG_EXAMPLE_0001}），绝不生成形似真实医院流水号的批次号、住院号、
 * 处方号或患者化名；日期固定为 2026-10-20，不随日期漂移，便于断言稳定。
 *
 * <p>段分隔符用 {@code \r}（HL7 标准段分隔符，也是本守护解码 {@code <FS><CR>} 围栏时最先扫到的字符）。
 */
public final class OracleOrderMessage {

    /** 占位患者主索引。 */
    public static final String PATIENT_MPI = "PAT_MPI_EXAMPLE";
    /** 占位检查号。 */
    public static final String ACCESSION = "ACC_EXAMPLE";
    /** 占位就诊号。 */
    public static final String VISIT_NO = "V_EXAMPLE_0001";
    /** 占位消息控制 ID。 */
    public static final String MESSAGE_CONTROL_ID = "MSG_EXAMPLE_0001";
    /** 占位报告文本（task 18 的报告归一会消费这段）。 */
    public static final String REPORT_TEXT = "胸部CT平扫：双肺未见明显异常密度影。";

    private OracleOrderMessage() {
    }

    /**
     * 构造一条 ORM^O01 报文（触发跨院影像调阅）。
     *
     * @param charset 报文编码（国内 HIS 常见 GBK，国标要求 UTF-8）
     */
    public static byte[] ormO01(Charset charset) {
        return ormO01("CT", charset);
    }

    /** 构造一条指定检查类别的 ORM^O01 报文。 */
    public static byte[] ormO01(String modality, Charset charset) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("MSH|^~\\&|HIS_APP|HIS_FACILITY|IMAGER|LOCAL_HOSP|20261020093000||ORM^O01|")
          .append(MESSAGE_CONTROL_ID).append("|P|2.5.1\r");
        sb.append("PID|1||").append(PATIENT_MPI).append("^^^HIS_MPI^MR||<EXAMPLE_PATIENT_NAME>||19700101|M\r");
        sb.append("PV1|1|O|||||").append(VISIT_NO).append("||||||||V_EXAMPLE_0001\r");
        sb.append("ORC|NW|").append(ACCESSION).append("||||||20261020093000\r");
        sb.append("OBR|1|").append(ACCESSION).append('|').append(ACCESSION).append('|')
          .append(modality).append('^').append(modality).append("^L|||20261020093000\r");
        sb.append("OBX|1|TX|").append(REPORT_TEXT).append("||||||F\r");
        return sb.toString().getBytes(charset == null ? StandardCharsets.UTF_8 : charset);
    }

    /** 构造一条 ADT^A04（入院登记）报文，用于 task 18/19 的消息类型分流用例。 */
    public static byte[] adtA04(Charset charset) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("MSH|^~\\&|HIS_APP|HIS_FACILITY|IMAGER|LOCAL_HOSP|20261020093000||ADT^A04|MSG_EXAMPLE_0002|P|2.5.1\r");
        sb.append("PID|1||").append(PATIENT_MPI).append("^^^HIS_MPI^MR||<EXAMPLE_PATIENT_NAME>||19700101|M\r");
        sb.append("PV1|1|A|||||").append(VISIT_NO).append("||||||||V_EXAMPLE_0001\r");
        return sb.toString().getBytes(charset == null ? StandardCharsets.UTF_8 : charset);
    }

    /**
     * 构造一条 OBR-3 影像引用<b>为空</b>的 ORM^O01 —— 报文结构合法，但发起一次跨院调阅
     * 所必需的影像引用没带（task 19 的 missing_study_reference 死信分支用）。
     */
    public static byte[] ormO01WithoutStudyReference(Charset charset) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("MSH|^~\\&|HIS_APP|HIS_FACILITY|IMAGER|LOCAL_HOSP|20261020093000||ORM^O01|")
          .append(MESSAGE_CONTROL_ID).append("|P|2.5.1\r");
        sb.append("PID|1||").append(PATIENT_MPI).append("^^^HIS_MPI^MR||<EXAMPLE_PATIENT_NAME>||19700101|M\r");
        sb.append("PV1|1|O|||||").append(VISIT_NO).append("||||||||V_EXAMPLE_0001\r");
        sb.append("ORC|NW|").append(ACCESSION).append("||||||20261020093000\r");
        // OBR-3（Filler Order Number / 影像引用）留空
        sb.append("OBR|1|").append(ACCESSION).append("||CT^CT^L|||20261020093000\r");
        return sb.toString().getBytes(charset == null ? StandardCharsets.UTF_8 : charset);
    }

    /**
     * 构造一条<b>缺 PID 段</b>的 ORM^O01 —— MSH 头合法但患者主索引没带
     * （task 19 的 missing_pid 死信分支用）。
     */
    public static byte[] ormO01WithoutPid(Charset charset) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("MSH|^~\\&|HIS_APP|HIS_FACILITY|IMAGER|LOCAL_HOSP|20261020093000||ORM^O01|")
          .append(MESSAGE_CONTROL_ID).append("|P|2.5.1\r");
        sb.append("PV1|1|O|||||").append(VISIT_NO).append("||||||||V_EXAMPLE_0001\r");
        sb.append("ORC|NW|").append(ACCESSION).append("||||||20261020093000\r");
        sb.append("OBR|1|").append(ACCESSION).append('|').append(ACCESSION).append('|')
          .append("CT^CT^L|||20261020093000\r");
        return sb.toString().getBytes(charset == null ? StandardCharsets.UTF_8 : charset);
    }

    /**
     * 构造一条<b>消息类型不支持</b>的报文（SIU^S12 排程消息）—— 用于 unsupported_message_type 分支。
     */
    public static byte[] unsupportedSiuS12(Charset charset) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("MSH|^~\\&|HIS_APP|HIS_FACILITY|IMAGER|LOCAL_HOSP|20261020093000||SIU^S12|")
          .append(MESSAGE_CONTROL_ID).append("|P|2.5.1\r");
        sb.append("SCH|").append(VISIT_NO).append("|||||20261020093000\r");
        return sb.toString().getBytes(charset == null ? StandardCharsets.UTF_8 : charset);
    }

    /**
     * 构造一条<b>首段不是 MSH</b>的报文（结构非法）—— 用于 invalid_msh 分支。
     */
    public static byte[] notMshAtAll(Charset charset) {
        String payload = "PID|1||" + PATIENT_MPI + "^^^HIS_MPI^MR||\r";
        return payload.getBytes(charset == null ? StandardCharsets.UTF_8 : charset);
    }

    /** 把 payload 包成一条完整 MLLP 线路帧（{@code <VT> payload <FS><CR>}）。 */
    public static byte[] mllpFrame(byte[] payload) {
        return MllpConstants.frame(payload);
    }

    /** 便利方法：ORM^O01 的完整 MLLP 帧。 */
    public static byte[] ormO01Frame(Charset charset) {
        return mllpFrame(ormO01(charset));
    }
}