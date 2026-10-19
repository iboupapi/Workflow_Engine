package sn.gouv.mfp.workflow.web.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import sn.gouv.mfp.workflow.exception.WorkflowConflictException;
import sn.gouv.mfp.workflow.exception.WorkflowNotFoundException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.service.admin.AdminRequestTypeService;
import sn.gouv.mfp.workflow.service.admin.AdminRequestTypeService.AdminRequestTypeDetailView;
import sn.gouv.mfp.workflow.service.admin.AdminRequestTypeService.AdminRequestTypeView;
import sn.gouv.mfp.workflow.service.admin.AdminRequestTypeService.DeployedWorkflowView;
import sn.gouv.mfp.workflow.service.admin.AdminRequestTypeService.FormSchemaView;
import sn.gouv.mfp.workflow.web.security.AdminRateLimitFilter;

/**
 * Endpoints d'administration des E-Services : création (201/400/409), schéma de formulaire,
 * publication (201/404) et lecture du référentiel complet et des circuits déployés. Le
 * contrôle d'accès (administrateur nommé) est couvert par les tests de la chaîne de sécurité.
 */
@WebMvcTest(AdminRequestTypeController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminRequestTypeControllerTest {

    @Autowired
    private AdminRequestTypeService service;

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class MockConfig {
        @Bean
        AdminRequestTypeService adminRequestTypeService() {
            return Mockito.mock(AdminRequestTypeService.class);
        }

        @Bean
        AdminRateLimitFilter adminRateLimitFilter() {
            return new AdminRateLimitFilter();
        }

        @Bean
        org.springframework.security.authentication.AuthenticationManagerResolver<
                        jakarta.servlet.http.HttpServletRequest>
                jwtAuthenticationManagerResolver() {
            return Mockito.mock(
                    org.springframework.security.authentication.AuthenticationManagerResolver.class);
        }
    }

    @BeforeEach
    void resetSharedMocks() {
        Mockito.reset(service);
    }

    @Test
    void listTypes_shouldExposePublishedAndDraftTypes() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.listAll()).thenReturn(List.of(
                new AdminRequestTypeView(id, "CONGE_TEST", "Congé test", "Congés", "PUBLISHED",
                        2, Instant.parse("2026-10-19T10:00:00Z"), "E_SERVICE_DEMANDE"),
                new AdminRequestTypeView(UUID.randomUUID(), "MEDAILLE", "Médaille", null, "DRAFT",
                        null, null, null)));

        mockMvc.perform(get("/api/admin/request-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("CONGE_TEST"))
                .andExpect(jsonPath("$[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].status").value("DRAFT"))
                .andExpect(jsonPath("$[1].version").doesNotExist());
    }

    @Test
    void createType_shouldReturn201WithTheDraft() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(eq("CONGE_MATERNITE"), eq("Congé maternité"), eq("Congés")))
                .thenReturn(new AdminRequestTypeView(
                        id, "CONGE_MATERNITE", "Congé maternité", "Congés", "DRAFT", null, null, null));

        mockMvc.perform(post("/api/admin/request-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"typeKey": "CONGE_MATERNITE", "label": "Congé maternité",
                                 "category": "Congés"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.key").value("CONGE_MATERNITE"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void createTypeWithDuplicateKey_shouldReturn409() throws Exception {
        when(service.create(anyString(), anyString(), any()))
                .thenThrow(new WorkflowConflictException("Un type de demande existe déjà pour la clé 'X'."));

        mockMvc.perform(post("/api/admin/request-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeKey\": \"X\", \"label\": \"Type X\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void createTypeWithInvalidKey_shouldReturn400() throws Exception {
        when(service.create(anyString(), anyString(), any()))
                .thenThrow(new WorkflowValidationException("Clé de type de demande invalide ('x')."));

        mockMvc.perform(post("/api/admin/request-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeKey\": \"x\", \"label\": \"Type x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createFormSchema_shouldReturn201WithTheIdentifier() throws Exception {
        UUID schemaId = UUID.randomUUID();
        when(service.createFormSchema(any(), any())).thenReturn(new FormSchemaView(
                schemaId,
                Map.of("type", "object", "properties", Map.of("dateDebut", Map.of("type", "string"))),
                Map.of("ui:order", List.of("dateDebut")),
                Instant.parse("2026-10-19T10:00:00Z")));

        mockMvc.perform(post("/api/admin/form-schemas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaJson": {"type": "object",
                                  "properties": {"dateDebut": {"type": "string"}}},
                                 "uiSchema": {"ui:order": ["dateDebut"]}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(schemaId.toString()))
                .andExpect(jsonPath("$.jsonSchema.type").value("object"));
    }

    @Test
    void createFormSchemaForAnUnusableSchema_shouldReturn400() throws Exception {
        when(service.createFormSchema(any(), any()))
                .thenThrow(new WorkflowValidationException(
                        "Un formulaire doit décrire un objet (`type: object`)."));

        mockMvc.perform(post("/api/admin/form-schemas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaJson\": {\"type\": \"string\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publish_shouldReturn201WithThePublishedVersion() throws Exception {
        UUID typeId = UUID.randomUUID();
        UUID schemaId = UUID.randomUUID();
        when(service.publish(eq(typeId), eq(schemaId), eq("E_SERVICE_DEMANDE"), any()))
                .thenReturn(new AdminRequestTypeView(
                        typeId, "CONGE_MATERNITE", "Congé maternité", "Congés", "PUBLISHED", 1,
                        Instant.parse("2026-10-19T10:00:00Z"), "E_SERVICE_DEMANDE"));

        mockMvc.perform(post("/api/admin/request-type-versions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestTypeId\": \"" + typeId + "\", \"formSchemaId\": \""
                                + schemaId + "\", \"workflowDefinitionKey\": \"E_SERVICE_DEMANDE\","
                                + " \"fieldMapping\": {\"typeActe\": \"natureActe\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.definitionKey").value("E_SERVICE_DEMANDE"));
    }

    @Test
    void publishWithAnUnknownType_shouldReturn404() throws Exception {
        UUID typeId = UUID.randomUUID();
        when(service.publish(any(), any(), anyString(), any()))
                .thenThrow(new WorkflowNotFoundException("Type de demande inconnu."));

        mockMvc.perform(post("/api/admin/request-type-versions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestTypeId\": \"" + typeId + "\", \"formSchemaId\": \""
                                + UUID.randomUUID() + "\", "
                                + "\"workflowDefinitionKey\": \"E_SERVICE_DEMANDE\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishWithAnInconsistentMapping_shouldReturn400() throws Exception {
        when(service.publish(any(), any(), anyString(), any()))
                .thenThrow(new WorkflowValidationException(
                        "Liaison de champ invalide (REQ-02) : la variable 'x' n'est pas déclarée."));

        mockMvc.perform(post("/api/admin/request-type-versions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestTypeId\": \"" + UUID.randomUUID()
                                + "\", \"formSchemaId\": \"" + UUID.randomUUID() + "\", "
                                + "\"workflowDefinitionKey\": \"E_SERVICE_DEMANDE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detail_shouldServeTheActiveSchema() throws Exception {
        UUID typeId = UUID.randomUUID();
        when(service.detail(typeId)).thenReturn(new AdminRequestTypeDetailView(
                typeId, "CONGE_TEST", "Congé test", "Congés", "PUBLISHED", 1,
                Instant.parse("2026-10-19T10:00:00Z"), "E_SERVICE_DEMANDE",
                Map.of("dateDebut", "natureActe"),
                Map.of("type", "object"),
                Map.of("ui:order", List.of("dateDebut"))));

        mockMvc.perform(get("/api/admin/request-types/" + typeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("CONGE_TEST"))
                .andExpect(jsonPath("$.jsonSchema.type").value("object"));
    }

    @Test
    void deployedWorkflows_shouldListTheCircuits() throws Exception {
        when(service.deployedWorkflows()).thenReturn(
                List.of(new DeployedWorkflowView("E_SERVICE_DEMANDE", "Service demande", 3)));

        mockMvc.perform(get("/api/admin/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("E_SERVICE_DEMANDE"))
                .andExpect(jsonPath("$[0].version").value(3));
    }
}
