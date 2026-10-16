package sn.gouv.mfp.workflow.web.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.definition.WorkflowTransition;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;
import sn.gouv.mfp.workflow.service.WorkflowDefinitionLoaderService;
import sn.gouv.mfp.workflow.service.dto.DefinitionImport;
import sn.gouv.mfp.workflow.web.security.AdminRateLimitFilter;

/**
 * Endpoint d'import à chaud : YAML brut et multipart (201 créé, 200 identique), erreur 400.
 * Le contrôle d'accès administrateur (administrateur nommé par JWT) est couvert par les
 * tests d'intégration de la chaîne de sécurité.
 */
@WebMvcTest(WorkflowAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowAdminControllerTest {

    @Autowired
    private WorkflowDefinitionLoaderService loaderService;

    @Autowired
    private WorkflowStepRepository steps;

    @Autowired
    private WorkflowTransitionRepository transitions;

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class MockConfig {
        @Bean
        WorkflowDefinitionLoaderService loaderService() {
            return Mockito.mock(WorkflowDefinitionLoaderService.class);
        }

        @Bean
        WorkflowStepRepository stepRepository() {
            return Mockito.mock(WorkflowStepRepository.class);
        }

        @Bean
        WorkflowTransitionRepository transitionRepository() {
            return Mockito.mock(WorkflowTransitionRepository.class);
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
    void importRawYaml_shouldReturn201WithSummary() throws Exception {
        stubImport(7, 2, true);

        mockMvc.perform(post("/api/admin/workflows/import")
                        
                        .contentType("application/x-yaml")
                        .content("definition:\n  key: GIRAFE_ACTES\n"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("GIRAFE_ACTES"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.stepCount").value(7))
                .andExpect(jsonPath("$.transitionCount").value(2));
    }

    @Test
    void reimportIdentical_shouldReturn200() throws Exception {
        stubImport(13, 21, false);

        mockMvc.perform(post("/api/admin/workflows/import")
                        
                        .contentType("application/x-yaml")
                        .content("definition:\n  key: GIRAFE_ACTES\n"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("GIRAFE_ACTES"));
    }

    @Test
    void importMultipartFile_shouldReturn201WithSummary() throws Exception {
        stubMultipartImport(true);
        MockMultipartFile file = new MockMultipartFile(
                "file", "girafe-workflow.yaml", "application/x-yaml",
                "definition:\n  key: GIRAFE_ACTES\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/admin/workflows/import")
                        .file(file)
                        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("GIRAFE_ACTES"))
                .andExpect(jsonPath("$.stepCount").value(13))
                .andExpect(jsonPath("$.transitionCount").value(21));
    }

    @Test
    void importInvalidYaml_shouldReturn400() throws Exception {
        when(loaderService.loadAndSaveYamlDefinition(anyString()))
                .thenThrow(new WorkflowValidationException("YAML incohérent : étape source inconnue."));

        mockMvc.perform(post("/api/admin/workflows/import")
                        
                        .contentType("application/x-yaml")
                        .content("transitions: []\n"))
                .andExpect(status().isBadRequest());
    }

    @BeforeEach
    void resetSharedMocks() {
        // Les mocks sont partagés via le contexte Spring : on purge les stubbings entre tests
        // pour éviter qu'un thenThrow ne se déclenche pendant le stubbing du test suivant.
        Mockito.reset(loaderService, steps, transitions);
    }

    private void stubImport(int stepCount, int transitionCount, boolean created) {
        WorkflowDefinition definition = stubDefinition(stepCount, transitionCount);
        // doReturn-when (sans invoquer la méthode) : sûr même si un stub proche existe déjà.
        Mockito.doReturn(new DefinitionImport(definition, created))
                .when(loaderService)
                .loadAndSaveYamlDefinition(anyString());
    }

    private void stubMultipartImport(boolean created) {
        WorkflowDefinition definition = stubDefinition(13, 21);
        Mockito.doReturn(new DefinitionImport(definition, created))
                .when(loaderService)
                .loadAndSaveYamlDefinition(anyString(), eq("girafe-workflow.yaml"));
    }

    private WorkflowDefinition stubDefinition(int stepCount, int transitionCount) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(UUID.randomUUID());
        definition.setKey("GIRAFE_ACTES");
        definition.setName("Circuit de Validation des Actes Administratifs");
        definition.setVersion(1);

        var stepList = new java.util.ArrayList<WorkflowStep>();
        var byStep = new java.util.HashMap<UUID, List<WorkflowTransition>>();
        for (int i = 0; i < stepCount; i++) {
            WorkflowStep step = new WorkflowStep();
            step.setId(UUID.randomUUID());
            stepList.add(step);
            var list = new java.util.ArrayList<WorkflowTransition>();
            for (int j = 0; j < (i == 0 ? transitionCount : 0); j++) {
                list.add(new WorkflowTransition());
            }
            byStep.put(step.getId(), list);
        }
        Mockito.doReturn(stepList).when(steps).findByDefinitionId(definition.getId());
        when(transitions.findBySourceStepId(any()))
                .thenAnswer(inv -> byStep.getOrDefault(inv.getArgument(0), List.of()));
        return definition;
    }
}
