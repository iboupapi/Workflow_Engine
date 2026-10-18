package sn.gouv.mfp.workflow.web.dev;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Preuve du prérequis de développement (SEC-08) : l'endpoint de jetons, monté en profil
 * {@code dev}, émet des jetons réellement acceptés par la chaîne de sécurité, et le CORS
 * ouvre l'origine de la console.
 * <p>
 * Le profil {@code dev} charge {@code application-dev.yml} (émetteur HMAC) ; le secret vient
 * de {@code TEST_ISSUER_SECRET} — jamais en dur dans un fichier.
 */
@SpringBootTest(properties = "TEST_ISSUER_SECRET=jeton-test-local-32-octets-min!!")
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "migrate"})
class DevTokenEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void tokens_shouldIssueOnePerDeclaredRole() throws Exception {
        mockMvc.perform(get("/api/dev/tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(7)))
                .andExpect(jsonPath("$[0].role").isNotEmpty())
                .andExpect(jsonPath("$[0].token").isNotEmpty())
                .andExpect(jsonPath("$[0].subject").isNotEmpty());
    }

    @Test
    void issuedToken_shouldBeAcceptedByTheApi() throws Exception {
        String body = mockMvc.perform(get("/api/dev/tokens/CITOYEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CITOYEN"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = objectMapper.readTree(body).get("token").asText();

        mockMvc.perform(get("/api/instances")
                        .param("view", "MINE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void issuedToken_withUnknownRole_shouldBeRefused() throws Exception {
        mockMvc.perform(get("/api/dev/tokens/INCONNU")).andExpect(status().isBadRequest());
    }

    @Test
    void cors_shouldAllowConsoleOrigin() throws Exception {
        mockMvc.perform(options("/api/instances")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}
