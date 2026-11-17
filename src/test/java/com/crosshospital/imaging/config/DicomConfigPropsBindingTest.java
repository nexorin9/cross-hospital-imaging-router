package com.crosshospital.imaging.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置面回归：{@code dicom.*} 的键名与 DICOM AE Title 合法性。
 *
 * <p><b>为什么需要它</b>：{@link DicomConfigProps} 是扁平绑定（prefix=dicom），
 * 而早期 application.yml 写成 {@code dicom.local.aet} 嵌套块 —— 应用照样启动、
 * 不打任何警告，但 {@code LOCAL_AET} 从未生效，实际用的是类里那个 20 字符默认值。
 * dcm4che 的 {@code AAssociateRQ.setCallingAET()} 校验 AET ≤ 16 字符，
 * 直到 task 39 真跑一次 C-FIND 才以 {@code callingAET: CROSSHOSPITAL_ROUTER} 炸出来。
 * 这里把「键位能绑上」与「默认值本身合法」两件事都锁死。
 */
@DisplayName("dicom.* 扁平绑定与合法 AE Title")
class DicomConfigPropsBindingTest {

    @Test
    @DisplayName("扁平键位能绑定到配置类，且 AET 不超过 16 字符")
    void flatKeysBind() {
        DicomConfigProps bound = bind(Map.of(
                "dicom.local-aet", "ROUTER_SCU",
                "dicom.local-host", "127.0.0.1",
                "dicom.bind-port", "11112",
                "dicom.remote-connect-timeout-ms", "2000",
                "dicom.tls-protocols", "TLSv1.3,TLSv1.2"));

        assertEquals("ROUTER_SCU", bound.getLocalAet());
        assertEquals("127.0.0.1", bound.getLocalHost());
        assertEquals(11112, bound.getBindPort());
        assertEquals(2000, bound.getRemoteConnectTimeoutMs());
        assertEquals(2, bound.getTlsProtocols().size());
        assertTrue(bound.getLocalAet().length() <= 16, "DICOM AE Title 最长 16 字符");
    }

    @Test
    @DisplayName("未配置时默认 AET 本身就是合法值，且空 cipher 不误开 TLS")
    void defaultsAreValid() {
        DicomConfigProps bound = bind(Map.of());

        assertEquals("IMAGER", bound.getLocalAet());
        assertTrue(bound.getLocalAet().length() <= 16);
        assertFalse(bound.getLocalAet().isBlank());
        assertTrue(bound.getTlsCipherSuites().isEmpty(),
                "空 cipher 列表 = 不启用 TLS；若绑成 [\"\"] 会让握手误判 TLS 不匹配");
    }

    @Test
    @DisplayName("旧的嵌套键 dicom.local.aet 不再被误当成已绑定配置")
    void nestedLegacyKeyDoesNotOverride() {
        DicomConfigProps bound = bind(Map.of("dicom.local.aet", "WRONG_NESTED_AET"));

        assertEquals("IMAGER", bound.getLocalAet(),
                "嵌套块不该绑到扁平字段；yml 已改为扁平键，这里防止有人又改回去");
    }

    private static DicomConfigProps bind(Map<String, Object> source) {
        Binder binder = new Binder(new MapConfigurationPropertySource(source));
        return binder.bind("dicom", DicomConfigProps.class).orElseGet(DicomConfigProps::new);
    }
}
