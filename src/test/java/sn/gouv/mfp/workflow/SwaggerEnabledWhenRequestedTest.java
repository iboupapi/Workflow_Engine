package sn.gouv.mfp.workflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 0 / SEC-08 — le basculement est effectif : activé explicitement
 * ({@code SWAGGER_ENABLED=true}), l'endpoint OpenAPI est bien exposé. Complément
 * « positif » de {@link Phase0GuardsTest#swagger_shouldBeDisabledByDefault()}.
 */
@SpringBootTest(properties = "SWAGGER_ENABLED=true")
@AutoConfigureMockMvc
class SwaggerEnabledWhenRequestedTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocs_shouldBeExposedWhenExplicitlyEnabled() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists());
    }
}
