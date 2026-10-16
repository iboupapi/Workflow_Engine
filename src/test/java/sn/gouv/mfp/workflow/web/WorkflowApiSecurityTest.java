package sn.gouv.mfp.workflow.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refus par défaut au niveau HTTP (contexte complet, vraie chaîne de sécurité) :
 * sans identifiants : 401 ; seule la documentation reste publique.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WorkflowApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void startWithoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/workflows/GIRAFE_ACTES/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initiatorId\":\"agent-001\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void transitionWithoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(post(
                                "/api/instances/00000000-0000-0000-0000-000000000000/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VALIDER\",\"actorId\":\"agent-002\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminImportWithoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/admin/workflows/import")
                        .contentType("application/x-yaml")
                        .content("definition:\n  key: X\n"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiDocs_shouldStayPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void health_shouldStayPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
