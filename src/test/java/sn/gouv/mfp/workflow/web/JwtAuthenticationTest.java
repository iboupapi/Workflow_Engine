package sn.gouv.mfp.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cahier de recette, section 2 — validation des jetons contre l'émetteur de test :
 * signature (2.2), expiration (2.3), émetteur et audience (2.4), identité du jeton
 * prioritaire sur le corps (2.5), rôle inconnu sans droit admin (2.7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "auth.jwt.issuers.test.issuer=https://test.mfp.local",
    "auth.jwt.issuers.test.audience=workflow-api",
    "auth.jwt.issuers.test.hmac-secret=jeton-test-local-32-octets-min!!",
    "auth.jwt.issuers.test.roles-claim=roles"
})
class JwtAuthenticationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private sn.gouv.mfp.workflow.repository.WorkflowHistoryRepository histories;

    @Test
    void validToken_shouldAuthenticateAndRecordTokenIdentity() throws Exception {
        String token = JwtTestTokens.signed("agent-jwt-01", List.of("AGENT_BUREAU"));

        String body = mockMvc.perform(post("/api/workflows/GIRAFE_ACTES/start")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"initiatorId":"imposteur","variables":{"circuit":"COURT"}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.initiatorId").value("agent-jwt-01"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID id = UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asText());

        // L'historique enregistre l'identité du jeton, pas celle du corps (recette 2.5).
        assertThat(histories.findByInstanceIdOrderByTimestampAsc(id))
                .anySatisfy(h -> assertThat(h.getActorId()).isEqualTo("agent-jwt-01"));
    }

    @Test
    void tamperedToken_shouldReturn401() throws Exception {
        String token = JwtTestTokens.signed("agent-jwt-01", List.of("AGENT_BUREAU"));
        String tampered = token.substring(0, token.length() - 2) + "xx";

        mockMvc.perform(post("/api/workflows/GIRAFE_ACTES/start")
                        .header("Authorization", "Bearer " + tampered)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initiatorId\":\"agent-jwt-01\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredToken_shouldReturn401() throws Exception {
        String token = JwtTestTokens.signed(
                JwtTestTokens.ISSUER,
                JwtTestTokens.AUDIENCE,
                "agent-jwt-01",
                List.of("AGENT_BUREAU"),
                Instant.now().minusSeconds(60),
                JwtTestTokens.SECRET);

        mockMvc.perform(post("/api/workflows/GIRAFE_ACTES/start")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initiatorId\":\"agent-jwt-01\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongIssuer_shouldReturn401() throws Exception {
        String token = JwtTestTokens.signed(
                "https://imposteur.example",
                JwtTestTokens.AUDIENCE,
                "agent-jwt-01",
                List.of("AGENT_BUREAU"),
                Instant.now().plusSeconds(300),
                JwtTestTokens.SECRET);

        mockMvc.perform(post("/api/workflows/GIRAFE_ACTES/start")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initiatorId\":\"agent-jwt-01\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongAudience_shouldReturn401() throws Exception {
        String token = JwtTestTokens.signed(
                JwtTestTokens.ISSUER,
                "autre-api",
                "agent-jwt-01",
                List.of("AGENT_BUREAU"),
                Instant.now().plusSeconds(300),
                JwtTestTokens.SECRET);

        mockMvc.perform(post("/api/workflows/GIRAFE_ACTES/start")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initiatorId\":\"agent-jwt-01\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRole_shouldGrantNoAdminRights() throws Exception {
        String token = JwtTestTokens.signed("agent-ninja", List.of("NINJA"));

        mockMvc.perform(post("/api/admin/workflows/import")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/x-yaml")
                        .content("definition:\n  key: X\n"))
                .andExpect(status().isForbidden());
    }

    @Test
    void citizenRole_shouldBeRefusedOnAdmin() throws Exception {
        String token = JwtTestTokens.signed("citoyen-01", List.of("CITOYEN"));

        mockMvc.perform(post("/api/admin/workflows/import")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/x-yaml")
                        .content("definition:\n  key: X\n"))
                .andExpect(status().isForbidden());
    }

    @Test
    void noCredentials_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/workflows/GIRAFE_ACTES/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initiatorId\":\"agent-001\"}"))
                .andExpect(status().isUnauthorized());
    }
}
