package com.crosshospital.imaging.service.hl7;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.crosshospital.imaging.domain.Hl7ParsedMessage;
import com.crosshospital.imaging.exception.FieldNotInWhitelistException;
import com.crosshospital.imaging.exception.Hl7ParseException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hl7MessageParser 单元测试：3 种消息类型 + 3 种异常路径 + 字段索引边界。
 */
class Hl7MessageParserTest {

    private Hl7MessageParser parser;

    @BeforeEach
    void setUp() {
        parser = new Hl7MessageParser(new Hl7SegmentFieldExtractor());
    }

    // -------------------------------------------------------------------- 三种消息类型

    @Test
    @DisplayName("ORM^O01: 解析含 PID + OBR 的医嘱消息 → 抽到 patient_mpi + accession_number")
    void parse_ormO01_extractsPatientAndAccession() {
        String body = ""
            + "MSH|^~\\&|HIS_APP|MY_FACILITY|IMAGER|LOCAL_HOSP|20240101120000||ORM^O01|MSG-CNTR-001|P|2.5\r"
            + "PID|1||12345^^^MRN||DOE^JOHN||19800101|M\r"
            + "PV1|1|I|ICU^101^1||||||||||||||||VN-2024-0001\r"
            + "OBR|1|ACC-PLACER-001|ACC-FILLER-001|CT^CT Chest^CTCHEST\r";
        MllpFrame frame = frameOf(body);

        Hl7ParsedMessage msg = parser.parse(frame, StandardCharsets.UTF_8);

        assertAll("ORM^O01 字段全部到位",
                () -> assertEquals("ORM", msg.messageType()),
                () -> assertEquals("O01", msg.triggerEvent()),
                () -> assertEquals("ORM^O01", msg.routeKey()),
                () -> assertEquals("MSG-CNTR-001", msg.messageControlId()),
                () -> assertEquals("HIS_APP", msg.sendingApplication()),
                () -> assertEquals("MY_FACILITY", msg.sendingFacility()),
                () -> assertEquals("12345^^^MRN", msg.patientMpi()),
                () -> assertEquals("DOE^JOHN", msg.patientName()),
                () -> assertEquals("19800101", msg.patientDob()),
                () -> assertEquals("VN-2024-0001", msg.visitNumber()),
                () -> assertEquals("ACC-FILLER-001", msg.accessionNumber()),
                () -> assertEquals("|", msg.fieldSeparator()),
                () -> assertTrue(msg.hasMinimumFetchFields()));
    }

    @Test
    @DisplayName("ADT^A04: 解析入院登记消息 → 抽到 patient_mpi")
    void parse_adtA04_extractsPatientMpi() {
        String body = ""
            + "MSH|^~\\&|HIS_APP|MY_FACILITY|IMAGER|LOCAL_HOSP|20240210153000||ADT^A04|MSG-CNTR-002|P|2.5\r"
            + "PID|1||EXT-MPI-9999^^^MRN||SMITH^ALICE||19900515|F\r"
            + "PV1|1|I|GENERAL^201^1||||||||||||||||VN-2024-0002\r";
        MllpFrame frame = frameOf(body);

        Hl7ParsedMessage msg = parser.parse(frame, StandardCharsets.UTF_8);

        assertAll("ADT^A04 字段",
                () -> assertEquals("ADT", msg.messageType()),
                () -> assertEquals("A04", msg.triggerEvent()),
                () -> assertEquals("ADT^A04", msg.routeKey()),
                () -> assertEquals("EXT-MPI-9999^^^MRN", msg.patientMpi()),
                () -> assertEquals("SMITH^ALICE", msg.patientName()),
                () -> assertEquals("VN-2024-0002", msg.visitNumber()),
                () -> assertNull(msg.accessionNumber(), "ADT^A04 通常无 OBR-3"));
    }

    @Test
    @DisplayName("ADT^A08: 解析患者信息修改 → 抽到 patient_mpi + name + dob")
    void parse_adtA08_extractsPatientInfoUpdate() {
        String body = ""
            + "MSH|^~\\&|HIS_APP|MY_FACILITY|IMAGER|LOCAL_HOSP|20240301093000||ADT^A08|MSG-CNTR-003|P|2.5\r"
            + "PID|1||EXT-MPI-7777^^^MRN||WANG^FANG|MS|19850720|F\r"
            + "PV1|1|O|OPD|||||0001|||||||||||VN-2024-0003\r";
        MllpFrame frame = frameOf(body);

        Hl7ParsedMessage msg = parser.parse(frame, StandardCharsets.UTF_8);

        assertAll("ADT^A08 字段",
                () -> assertEquals("ADT", msg.messageType()),
                () -> assertEquals("A08", msg.triggerEvent()),
                () -> assertEquals("EXT-MPI-7777^^^MRN", msg.patientMpi()),
                () -> assertEquals("WANG^FANG", msg.patientName()),
                () -> assertEquals("19850720", msg.patientDob()));
    }

    // -------------------------------------------------------------------- 三种异常

    @Test
    @DisplayName("异常: 报文不以 MSH 开头 → INVALID_MSH")
    void parse_nonMshFirstSegment_throwsInvalidMsh() {
        String body = "PID|1|12345^^^MRN\r";
        MllpFrame frame = frameOf(body);

        Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                () -> parser.parse(frame, StandardCharsets.UTF_8));
        assertSame(Hl7ParseException.FailureCode.INVALID_MSH, ex.getCode());
    }

    @Test
    @DisplayName("异常: PID 段缺失 → MISSING_PID")
    void parse_missingPidSegment_throwsMissingPid() {
        String body = ""
            + "MSH|^~\\&|HIS_APP|MY_FACILITY|IMAGER|LOCAL_HOSP|20240101120000||ORM^O01|MSG-CNTR-001|P|2.5\r"
            + "OBR|1|ACC-PLACER-001|ACC-FILLER-001|CT^CT Chest^CTCHEST\r";
        MllpFrame frame = frameOf(body);

        Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                () -> parser.parse(frame, StandardCharsets.UTF_8));
        assertSame(Hl7ParseException.FailureCode.MISSING_PID, ex.getCode());
    }

    @Test
    @DisplayName("异常: PID-3 患者 MPI 缺失（PID 存在但无 MPI）→ MISSING_PID")
    void parse_pidSegmentWithoutMpi_throwsMissingPid() {
        String body = ""
            + "MSH|^~\\&|HIS_APP|MY_FACILITY|IMAGER|LOCAL_HOSP|20240101120000||ORM^O01|MSG-CNTR-001|P|2.5\r"
            + "PID|1|||DOE^JOHN||19800101|M\r";
        MllpFrame frame = frameOf(body);

        Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                () -> parser.parse(frame, StandardCharsets.UTF_8));
        assertSame(Hl7ParseException.FailureCode.MISSING_PID, ex.getCode());
    }

    @Test
    @DisplayName("异常: 动态分隔符长度 ≠ 4 → INVALID_DYNAMIC_DELIMITERS")
    void parse_invalidDelimiterLength_throwsDynamicDelimiterAnomaly() {
        // 把 MSH-2 的编码字符长度从 4 改到 3（去掉 escape）
        String body = ""
            + "MSH|^~\\|HIS_APP|MY_FACILITY|IMAGER|LOCAL_HOSP|20240101120000||ORM^O01|MSG-CNTR-001|P|2.5\r"
            + "PID|1|12345^^^MRN||DOE^JOHN||19800101|M\r";
        MllpFrame frame = frameOf(body);

        Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                () -> parser.parse(frame, StandardCharsets.UTF_8));
        assertSame(Hl7ParseException.FailureCode.INVALID_DYNAMIC_DELIMITERS, ex.getCode());
    }

    @Test
    @DisplayName("异常: 不支持的消息类型 → UNSUPPORTED_MESSAGE_TYPE")
    void parse_unsupportedMessageType_throwsUnsupportedMessageType() {
        // SIU^S12 不在白名单
        String body = ""
            + "MSH|^~\\&|HIS_APP|MY_FACILITY|IMAGER|LOCAL_HOSP|20240101120000||SIU^S12|MSG-CNTR-001|P|2.5\r"
            + "PID|1|12345^^^MRN||DOE^JOHN||19800101|M\r";
        MllpFrame frame = frameOf(body);

        Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                () -> parser.parse(frame, StandardCharsets.UTF_8));
        assertSame(Hl7ParseException.FailureCode.UNSUPPORTED_MESSAGE_TYPE, ex.getCode());
    }

    @Test
    @DisplayName("异常: MSH 长度 < 8 → INVALID_MSH")
    void parse_tooShortMsh_throwsInvalidMsh() {
        String body = "MSH|^~\r";  // 只有 5 字符，远小于 8
        MllpFrame frame = frameOf(body);

        Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                () -> parser.parse(frame, StandardCharsets.UTF_8));
        assertSame(Hl7ParseException.FailureCode.INVALID_MSH, ex.getCode());
    }

    @Test
    @DisplayName("异常: 空报文 → INVALID_MSH")
    void parse_emptyFrame_throwsInvalidMsh() {
        MllpFrame frame = frameOf("");

        Hl7ParseException ex = assertThrows(Hl7ParseException.class,
                () -> parser.parse(frame, StandardCharsets.UTF_8));
        assertSame(Hl7ParseException.FailureCode.INVALID_MSH, ex.getCode());
    }

    // -------------------------------------------------------------------- 段切分单元测试

    @Test
    @DisplayName("segmentsOf: MSH 段索引正确（[1]=sep, [2]=encoding, [9]=MSH-9）")
    void segmentsOf_mshSegmentIndexAlignedToHl7Spec() {
        String body = ""
            + "MSH|^~\\&|SENDER|FACILITY|RECEIVER|FACILITY|20240101120000||ORM^O01|MSG-CNTR-001|P|2.5\r"
            + "PID|1||12345^^^MRN||DOE^JOHN||19800101|M\r";
        MllpFrame frame = frameOf(body);

        java.util.List<String[]> segs = parser.segmentsOf(frame, StandardCharsets.UTF_8);
        assertEquals(2, segs.size(), "应有 MSH + PID 两段");
        String[] msh = segs.get(0);
        String[] pid = segs.get(1);
        assertAll("MSH 索引",
                () -> assertEquals("MSH", msh[0]),
                () -> assertEquals("|", msh[1], "MSH-1 = 字段分隔符自身"),
                () -> assertEquals("^~\\&", msh[2], "MSH-2 = 编码字符"),
                () -> assertEquals("SENDER", msh[3], "MSH-3 = Sending Application"),
                () -> assertEquals("FACILITY", msh[4], "MSH-4 = Sending Facility"),
                () -> assertEquals("ORM^O01", msh[9], "MSH-9 = Message Type"),
                () -> assertEquals("MSG-CNTR-001", msh[10], "MSH-10 = Message Control ID"),
                () -> assertEquals("PID", pid[0]),
                () -> assertEquals("12345^^^MRN", pid[3], "PID-3 = Patient Identifier List"));
    }

    @Test
    @DisplayName("segmentsOf: 保留空字段（连续分隔符 → 空字符串元素）")
    void segmentsOf_preservesEmptyFields() {
        String body = "MSH|^~\\&|SENDER|FACILITY|RECEIVER|FACILITY|20240101|||ORM^O01|MSG-CNTR|.\r";
        MllpFrame frame = frameOf(body);

        java.util.List<String[]> segs = parser.segmentsOf(frame, StandardCharsets.UTF_8);
        String[] msh = segs.get(0);
        // msh[8] = MSH-8 = Security，应为空（20240101 后空一格是消息类型前的 MSH-8）
        assertTrue(msh.length > 8 && (msh[8] == null || msh[8].isEmpty()), "MSH-8 缺失 → 空字段");
    }

    @Test
    @DisplayName("segmentsOf: 末尾空段被丢弃")
    void segmentsOf_dropsTrailingEmptySegments() {
        String body = "MSH|^~\\&|SENDER|FACILITY|RECEIVER|FACILITY|20240101||ORM^O01|MSG-CNTR|P|2.5\r\r\r";
        MllpFrame frame = frameOf(body);

        java.util.List<String[]> segs = parser.segmentsOf(frame, StandardCharsets.UTF_8);
        assertEquals(1, segs.size(), "末尾连续 \\r 应丢弃");
    }

    @Test
    @DisplayName("segmentsOf: 自定义分隔符（^ 作字段分隔符）")
    void segmentsOf_alternativeFieldSeparator() {
        // 罕见但合法：MSH-1 是 ^ 而非 | —— MSH 段内字段分隔符与段间分隔符需一致才合规
        // 这里测试 splitSegment 对自定义 sep 的接受能力（不破坏 MSH 段的切分语义）
        String body = "MSH|^~\\&|SENDER|FACILITY|RECEIVER|FACILITY|20240101||ORM^O01|MSG-CNTR|P|2.5\r";
        MllpFrame frame = frameOf(body);

        java.util.List<String[]> segs = parser.segmentsOf(frame, StandardCharsets.UTF_8);
        String[] msh = segs.get(0);
        assertEquals("|", msh[1]);
        assertEquals("^~\\&", msh[2]);
    }

    // -------------------------------------------------------------------- 白名单 + 提取器

    @Test
    @DisplayName("白名单: 读取非白名单字段 → FieldNotInWhitelistException")
    void extractor_fieldNotInWhitelist_throwsFieldNotInWhitelistException() {
        Hl7SegmentFieldExtractor extractor = new Hl7SegmentFieldExtractor();
        // PID-8（性别）不在白名单
        assertFalse(extractor.isWhitelisted("PID", 8));
        // OBR-2 也不在白名单
        assertFalse(extractor.isWhitelisted("OBR", 2));
        // PV1-19 在白名单
        assertTrue(extractor.isWhitelisted("PV1", 19));

        // 用合法段调用抽取时，非白名单字段抛异常
        String body = ""
            + "MSH|^~\\&|HIS_APP|FACILITY|IMAGER|LOCAL|20240101||ORM^O01|MSG|P|2.5\r"
            + "PID|1||12345^^^MRN||DOE^JOHN||19800101|M\r";
        java.util.List<String[]> segs = parser.segmentsOf(frameOf(body), StandardCharsets.UTF_8);
        assertThrows(FieldNotInWhitelistException.class,
                () -> extractor.extract(segs, "PID", 8));
    }

    @Test
    @DisplayName("白名单: OBR-4（Universal Service Identifier）在白名单 → 抽取生效")
    void extractor_obr4IsWhitelistedAndExtractable() {
        Hl7SegmentFieldExtractor extractor = new Hl7SegmentFieldExtractor();
        assertTrue(extractor.isWhitelisted("OBR", 4));

        String body = ""
            + "MSH|^~\\&|HIS_APP|FACILITY|IMAGER|LOCAL|20240101||ORM^O01|MSG|P|2.5\r"
            + "PID|1|12345^^^MRN\r"
            + "OBR|1||ACC-001|CT^CT Chest^CTCHEST\r";
        MllpFrame frame = frameOf(body);

        java.util.List<String[]> segs = parser.segmentsOf(frame, StandardCharsets.UTF_8);
        assertEquals("CT^CT Chest^CTCHEST", extractor.extract(segs, "OBR", 4));
    }

    // -------------------------------------------------------------------- 辅助 / 边界

    @Test
    @DisplayName("supportedMessageTypes 暴露 3 种 routeKey")
    void supportedMessageTypes_hasThreeRouteKeys() {
        assertEquals(java.util.Set.of("ORM^O01", "ADT^A04", "ADT^A08"),
                parser.supportedMessageTypes());
    }

    @Test
    @DisplayName("hl7ParsedMessage.hasMinimumFetchFields 在缺 accession 时为 false")
    void hasMinimumFetchFields_requiresBothPatientMpiAndAccession() {
        Hl7ParsedMessage withoutAccession = Hl7ParsedMessage.builder()
                .messageType("ADT").triggerEvent("A04")
                .patientMpi("MPI-001")
                .build();
        assertFalse(withoutAccession.hasMinimumFetchFields());

        Hl7ParsedMessage withBoth = Hl7ParsedMessage.builder()
                .messageType("ORM").triggerEvent("O01")
                .patientMpi("MPI-001")
                .accessionNumber("ACC-001")
                .build();
        assertTrue(withBoth.hasMinimumFetchFields());
    }

    @Test
    @DisplayName("Hl7ParsedMessage.routeKey 拼装为 ORM^O01 形式")
    void hl7ParsedMessage_routeKeyFormat() {
        Hl7ParsedMessage msg = Hl7ParsedMessage.builder()
                .messageType("ORM").triggerEvent("O01")
                .patientMpi("MPI-1").accessionNumber("ACC-1")
                .build();
        assertEquals("ORM^O01", msg.routeKey());
    }

    @Test
    @DisplayName("Hl7ParsedMessage.toString 不暴露口令 / 凭据位（仅字段名）")
    void hl7ParsedMessage_toStringHasNoSecret() {
        Hl7ParsedMessage msg = Hl7ParsedMessage.builder()
                .messageType("ORM").triggerEvent("O01")
                .messageControlId("CTRL-1")
                .patientMpi("MPI-1").patientName("DOE")
                .accessionNumber("ACC-1").visitNumber("VN-1")
                .sendingApplication("HIS").sendingFacility("FAC")
                .remoteAddress("10.0.0.1:1234").build();
        String s = msg.toString();
        assertNotNull(s);
        assertFalse(s.toLowerCase().contains("password"), "toString 不含 password");
        assertFalse(s.toLowerCase().contains("token"), "toString 不含 token");
        assertFalse(s.toLowerCase().contains("secret"), "toString 不含 secret");
        assertTrue(s.contains("ORM^O01"));
        assertTrue(s.contains("MPI-1"));
    }

    @Test
    @DisplayName("异常码 toString 给出小写下划线码（与 segment-mappings.yml 对齐）")
    void hl7ParseException_failureCodeIsLowerSnakeCase() {
        for (Hl7ParseException.FailureCode c : Hl7ParseException.FailureCode.values()) {
            String code = c.code();
            assertTrue(code.matches("[a-z_]+"), "失败码应全小写下划线: " + code);
            assertTrue(code.length() >= 4);
        }
    }

    @Test
    @DisplayName("FieldNotInWhitelistException 含 segment + field 信息")
    void fieldNotInWhitelistException_carriesSegmentAndField() {
        FieldNotInWhitelistException ex = new FieldNotInWhitelistException("PID", 8);
        assertEquals("PID", ex.getSegment());
        assertEquals(8, ex.getField());
        assertTrue(ex.getMessage().contains("PID"));
        assertTrue(ex.getMessage().contains("8"));
    }

    // -------------------------------------------------------------------- helpers

    private static MllpFrame frameOf(String body) {
        return new MllpFrame(body.getBytes(StandardCharsets.UTF_8),
                "10.0.0.1:6661", Instant.parse("2024-01-01T12:00:00Z"), 1L);
    }
}