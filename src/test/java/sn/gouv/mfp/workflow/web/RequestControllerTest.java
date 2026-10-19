package sn.gouv.mfp.workflow.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.test.web.servlet.MockMvc;
import sn.gouv.mfp.workflow.domain.request.RequestSubmissionStatus;
import sn.gouv.mfp.workflow.exception.FormSchemaValidationException;
import sn.gouv.mfp.workflow.exception.WorkflowConflictException;
import sn.gouv.mfp.workflow.exception.WorkflowNotFoundException;
import sn.gouv.mfp.workflow.service.RequestSubmissionService;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.FormView;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.SubmissionView;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.TypeView;
import sn.gouv.mfp.workflow.web.dto.RequestDtos.FormDataRequest;
import sn.gouv.mfp.workflow.web.security.AdminRateLimitFilter;

/**
 * API des demandes d'usagers : catalogue, formulaire servi, soumission (avec écarts de
 * validation en clair), brouillons et refus explicites.
 */
@WebMvcTest(RequestController.class)
@AutoConfigureMockMvc(addFilters = false)
class RequestControllerTest {

    private static final String TYPE = "CERTIFICAT_TRAVAIL";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestSubmissionService submissions;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TestConfiguration
    static class MockConfig {

        @Bean
        RequestSubmissionService requestSubmissionService() {
            return Mockito.mock(RequestSubmissionService.class);
        }

        @Bean
        RequestActor requestActor() {
            return new RequestActor();
        }

        @Bean
        AdminRateLimitFilter adminRateLimitFilter() {
            return new AdminRateLimitFilter();
        }

        @Bean
        AuthenticationManagerResolver<jakarta.servlet.http.HttpServletRequest>
                jwtAuthenticationManagerResolver() {
            return Mockito.mock(AuthenticationManagerResolver.class);
        }
    }

    @Test
    void types_shouldReturnThePublishedCatalog() throws Exception {
        when(submissions.publishedTypes())
                .thenReturn(List.of(new TypeView(UUID.randomUUID(), TYPE, "Certificat de travail", "TRAVAIL")));

        mockMvc.perform(get("/api/request-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value(TYPE))
                .andExpect(jsonPath("$[0].label").value("Certificat de travail"));
    }

    @Test
    void form_shouldReturnTheActiveSchemaAndUiSchema() throws Exception {
        when(submissions.activeForm(TYPE)).thenReturn(new FormView(
                TYPE,
                "Certificat de travail",
                2,
                Map.of("type", "object", "required", List.of("nom")),
                Map.of("nom", Map.of("label", "Nom"))));

        mockMvc.perform(get("/api/request-types/{typeKey}/form", TYPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typeKey").value(TYPE))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.schema.required[0]").value("nom"))
                .andExpect(jsonPath("$.uiSchema.nom.label").value("Nom"));
    }

    @Test
    void form_shouldReturn404WhenTheTypeIsNotAvailable() throws Exception {
        when(submissions.activeForm("INCONNU"))
                .thenThrow(new WorkflowNotFoundException("Aucun formulaire publié."));

        mockMvc.perform(get("/api/request-types/{typeKey}/form", "INCONNU"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WorkflowNotFoundException"));
    }

    @Test
    void submit_shouldReturn201WithTheCreatedInstance() throws Exception {
        UUID instanceId = UUID.randomUUID();
        when(submissions.submit(eq(TYPE), eq(Map.of("nom", "Awa Diop")), isNull()))
                .thenReturn(new SubmissionView(
                        UUID.randomUUID(),
                        TYPE,
                        2,
                        RequestSubmissionStatus.SUBMITTED,
                        instanceId,
                        Instant.parse("2026-01-01T10:00:00Z"),
                        Instant.parse("2026-01-01T10:00:00Z")));

        mockMvc.perform(post("/api/requests/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new FormDataRequest(TYPE, Map.of("nom", "Awa Diop")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.instanceId").value(instanceId.toString()))
                .andExpect(jsonPath("$.version").value(2));

        verify(submissions).submit(eq(TYPE), eq(Map.of("nom", "Awa Diop")), isNull());
    }

    @Test
    void submit_shouldReturn400WithTheViolations() throws Exception {
        when(submissions.submit(eq(TYPE), eq(Map.of()),
                        isNull()))
                .thenThrow(new FormSchemaValidationException(
                        "Les données de la demande ne respectent pas le formulaire publié.",
                        List.of("/nom : required property 'nom' not found")));

        mockMvc.perform(post("/api/requests/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FormDataRequest(TYPE, Map.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FormSchemaValidationException"))
                .andExpect(jsonPath("$.violations[0]").value("/nom : required property 'nom' not found"));
    }

    @Test
    void saveDraft_shouldReturn201() throws Exception {
        when(submissions.saveDraft(eq(TYPE), eq(Map.of("motif", "Promotion")), isNull()))
                .thenReturn(new SubmissionView(
                        UUID.randomUUID(),
                        TYPE,
                        2,
                        RequestSubmissionStatus.DRAFT,
                        null,
                        Instant.parse("2026-01-01T10:00:00Z"),
                        null));

        mockMvc.perform(post("/api/requests/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new FormDataRequest(TYPE, Map.of("motif", "Promotion")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.instanceId").doesNotExist())
                .andExpect(jsonPath("$.submittedAt").doesNotExist());
    }

    @Test
    void drafts_shouldListTheAuthorDrafts() throws Exception {
        when(submissions.drafts(isNull()))
                .thenReturn(List.of(new SubmissionView(
                        UUID.randomUUID(),
                        TYPE,
                        2,
                        RequestSubmissionStatus.DRAFT,
                        null,
                        Instant.parse("2026-01-01T10:00:00Z"),
                        null)));

        mockMvc.perform(get("/api/requests/drafts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].typeKey").value(TYPE));
    }

    @Test
    void submitDraft_shouldReturn409WhenAlreadySubmitted() throws Exception {
        UUID draftId = UUID.randomUUID();
        when(submissions.submitDraft(eq(draftId), isNull()))
                .thenThrow(new WorkflowConflictException("La demande a déjà été soumise."));

        mockMvc.perform(post("/api/requests/drafts/{draftId}/submit", draftId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WorkflowConflictException"));
    }
}
