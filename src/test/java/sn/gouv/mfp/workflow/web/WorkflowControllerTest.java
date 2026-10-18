package sn.gouv.mfp.workflow.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.execution.InstanceStatus;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.exception.InvalidEntryPointException;
import sn.gouv.mfp.workflow.exception.MissingRequiredDataException;
import sn.gouv.mfp.workflow.exception.WorkflowConflictException;
import sn.gouv.mfp.workflow.exception.WorkflowNotFoundException;
import sn.gouv.mfp.workflow.exception.WorkflowQuotaExceededException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.service.WorkflowAuthorizationService;
import sn.gouv.mfp.workflow.service.WorkflowExecutionService;
import sn.gouv.mfp.workflow.service.WorkflowReadService;
import sn.gouv.mfp.workflow.web.dto.WorkflowDtos.StartWorkflowRequest;
import sn.gouv.mfp.workflow.web.dto.WorkflowDtos.TransitionRequest;
import sn.gouv.mfp.workflow.web.security.AdminRateLimitFilter;

/** Tests de l'API centrale : routes, statuts HTTP et mapping des erreurs métier. */
@WebMvcTest(WorkflowController.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private WorkflowExecutionService executionService;

    @TestConfiguration
    static class MockConfig {

        @Bean
        WorkflowExecutionService executionService() {
            return Mockito.mock(WorkflowExecutionService.class);
        }

        @Bean
        WorkflowReadService readService() {
            return Mockito.mock(WorkflowReadService.class);
        }

        @Bean
        RequestActor requestActor() {
            return new RequestActor();
        }

        @Bean
        WorkflowAuthorizationService authorizationService() {
            return Mockito.mock(WorkflowAuthorizationService.class);
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

    @Test
    void start_shouldReturn201WithInstance() throws Exception {
        when(executionService.startWorkflow(
                eq("GIRAFE_ACTES"), eq("agent-001"), any(), any(), any(), any(), any()))
                .thenReturn(givenInstance("S_VISA", InstanceStatus.ACTIVE));

        mockMvc.perform(post("/api/workflows/GIRAFE_ACTES/start")
                        
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartWorkflowRequest("agent-001", Map.of("circuit", "COURT"), null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definitionKey").value("GIRAFE_ACTES"))
                .andExpect(jsonPath("$.currentStepKey").value("S_VISA"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.variables.circuit").value("COURT"));
    }

    @Test
    void start_withUnknownKey_shouldReturn404() throws Exception {
        when(executionService.startWorkflow(eq("INCONNU"), any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowNotFoundException("Aucune définition"));

        mockMvc.perform(post("/api/workflows/INCONNU/start")
                        
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartWorkflowRequest("agent-001", null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void transition_shouldReturn200WithAdvancedInstance() throws Exception {
        UUID id = UUID.randomUUID();
        when(executionService.executeTransition(
                        eq(id), eq("VALIDER"), eq("agent-002"), any(), any(), any(), any(), any()))
                .thenReturn(givenInstance("DGFP", InstanceStatus.COMPLETED));

        mockMvc.perform(post("/api/instances/{instanceId}/transition", id)
                        
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransitionRequest(
                                "VALIDER", "agent-002", null, Map.of("numeroOfficiel", "2026-1")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStepKey").value("DGFP"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void transition_onCompletedInstance_shouldReturn409() throws Exception {
        UUID id = UUID.randomUUID();
        when(executionService.executeTransition(eq(id), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowConflictException("Dossier non actif"));

        mockMvc.perform(post("/api/instances/{instanceId}/transition", id)
                        
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransitionRequest("VALIDER", "agent-003", null, null))))
                .andExpect(status().isConflict());
    }

    @Test
    void transition_withInvalidAction_shouldReturn400() throws Exception {
        UUID id = UUID.randomUUID();
        when(executionService.executeTransition(eq(id), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowValidationException("Aucune transition valide"));

        mockMvc.perform(post("/api/instances/{instanceId}/transition", id)
                        
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransitionRequest("ARCHIVER", "agent-002", null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void start_overQuota_shouldReturn409() throws Exception {
        when(executionService.startWorkflow(
                eq("GIRAFE_ACTES"), eq("agent-001"), any(), any(), any(), any(), any()))
                .thenThrow(new WorkflowQuotaExceededException(
                        "Plafond de dossiers actifs atteint pour cet initiateur (Max: 1, Actuels: 1)."));

        mockMvc.perform(post("/api/workflows/GIRAFE_ACTES/start")
                        
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartWorkflowRequest("agent-001", null, null))))
                .andExpect(status().isConflict());
    }

    @Test
    void transition_missingRequiredData_shouldReturn400() throws Exception {
        UUID id = UUID.randomUUID();
        when(executionService.executeTransition(eq(id), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new MissingRequiredDataException(
                        "Validation impossible. Donnée(s) obligatoire(s) manquante(s) "
                                + "pour l'étape [NUMEROTEUR] : [numeroOfficiel]."));

        mockMvc.perform(post("/api/instances/{instanceId}/transition", id)
                        
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransitionRequest("VALIDER", "agent-002", null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void start_invalidEntryPoint_shouldReturn400() throws Exception {
        when(executionService.startWorkflow(
                eq("GIRAFE_ACTES"), eq("agent-001"), any(), any(), any(), any(), any()))
                .thenThrow(new InvalidEntryPointException(
                        "Point d'entrée 'S_VISA' non autorisé."));

        mockMvc.perform(post("/api/workflows/GIRAFE_ACTES/start")
                        
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartWorkflowRequest("agent-001", null, "S_VISA"))))
                .andExpect(status().isBadRequest());
    }

    private WorkflowInstance givenInstance(String stepKey, InstanceStatus status) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey("GIRAFE_ACTES");
        definition.setVersion(1);
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId(UUID.randomUUID());
        instance.setDefinition(definition);
        instance.setCurrentStepKey(stepKey);
        instance.setStatus(status);
        instance.setInitiatorId("agent-001");
        instance.setVariables(Map.of("circuit", "COURT"));
        return instance;
    }
}
