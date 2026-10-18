package sn.gouv.mfp.workflow.web.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.web.JwtTestTokens;

/**
 * Chaîne de sécurité complète : un JWT d'administrateur NOMMÉ doit traverser la chaîne
 * d'authentification ET l'autorisation jusqu'au contrôleur (ni 401, ni 403). Un JWT
 * valide mais non administrateur est refusé sur l'administration (403).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "auth.jwt.issuers.test.issuer=https://test.mfp.local",
    "auth.jwt.issuers.test.audience=workflow-api",
    "auth.jwt.issuers.test.hmac-secret=jeton-test-local-32-octets-min!!",
    "auth.jwt.issuers.test.roles-claim=roles",
    // Second émetteur : sert à prouver que le préfixe d'émetteur fait partie de l'identité.
    "auth.jwt.issuers.usagers.issuer=https://usagers.mfp.local",
    "auth.jwt.issuers.usagers.audience=workflow-api",
    "auth.jwt.issuers.usagers.hmac-secret=jeton-test-local-32-octets-min!!",
    "auth.jwt.issuers.usagers.roles-claim=roles",
    // Administrateur nommé : identité PRÉFIXÉE par l'émetteur (SEC-03).
    "auth.admin.subjects=test:admin-01"
})
class AdminSecurityChainTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void importWithNamedAdminToken_shouldReachController() throws Exception {
        String token = JwtTestTokens.signed("admin-01", List.of());
        mockMvc.perform(post("/api/admin/workflows/import")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/x-yaml")
                        .content(minimalYaml()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("CHAIN_OK"));
    }

    @Test
    void importWithNonAdminToken_shouldReturn403() throws Exception {
        String token = JwtTestTokens.signed("agent-01", List.of("AGENT_BUREAU"));
        mockMvc.perform(post("/api/admin/workflows/import")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/x-yaml")
                        .content(minimalYaml()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminSubjectFromAnotherIssuer_shouldNotBeAdmin() throws Exception {
        // Même `sub` que l'administrateur nommé, mais émetteur différent : l'identité
        // complète (« usagers:admin-01 ») ne figure pas dans auth.admin.subjects → 403.
        String token = JwtTestTokens.signed(
                "https://usagers.mfp.local", JwtTestTokens.AUDIENCE, "admin-01",
                List.of(), java.time.Instant.now().plusSeconds(300), JwtTestTokens.SECRET);

        mockMvc.perform(post("/api/admin/workflows/import")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/x-yaml")
                        .content(minimalYaml()))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownWorkflowWithAdminToken_shouldReturn404() throws Exception {
        // Clé inconnue : le 404 prouve le passage de l'authentification (un 401 la trahirait).
        String token = JwtTestTokens.signed("admin-01", List.of());
        mockMvc.perform(post("/api/workflows/INCONNUE_CHAINE/start")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initiatorId\":\"agent-001\"}"))
                .andExpect(status().isNotFound());
    }

    private String minimalYaml() {
        return """
                definition:
                  key: "CHAIN_OK"
                  name: "Circuit chaîne"
                variables:
                  - "circuit"
                steps:
                  - key: "A"
                    type: "START"
                    owners: ["AGENT_BUREAU"]
                  - key: "B"
                    type: "END"
                transitions:
                  - source: "A"
                    action: "VALIDER"
                    target: "B"
                """;
    }
}
