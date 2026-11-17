package com.crosshospital.imaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 2 smoke test: verifies Spring Boot context starts and exposes the
 * {@code /api/imaging/health}, {@code /api/imaging/info}, and
 * {@code /v3/api-docs} endpoints. Runs under {@code test} profile which
 * excludes Flyway / JPA / DataSource autoconfig so the test does not need a
 * real PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BootstrapHealthAndOpenApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
        // Spring Boot will fail to start if any bean cannot be created.
        // Holding this as a smoke test that the configuration wires up.
    }

    @Test
    void customHealthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/api/imaging/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.dicom.status").value("UP"))
                .andExpect(jsonPath("$.components.hl7.status").value("UP"));
    }

    @Test
    void customInfoEndpointReturnsBuildMetadata() throws Exception {
        mockMvc.perform(get("/api/imaging/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.name").exists())
                .andExpect(jsonPath("$.app.version").exists())
                .andExpect(jsonPath("$.app.buildTime").exists());
    }

    @Test
    void openApiJsonIsExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("跨院影像互认调阅路由系统 API"));
    }
}