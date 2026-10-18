package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.definition.WorkflowTransition;
import sn.gouv.mfp.workflow.domain.execution.InstanceStatus;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;

/**
 * Phase 1 — clôture de l'écart E-Service : le <strong>responsable de traitement</strong> est le
 * Chef de Division, qu'il ait traité en direct ou délégué le traitement à un agent. Un rejet des
 * autorités supérieures (DLEC/DGFP) doit donc toujours revenir au Chef de Division pour arbitrage
 * ou réimputation.
 * <p>
 * Exigence MOT-06 : la cible {@code RESPONSABLE_TRAITEMENT} s'appuie sur le marqueur
 * {@code setsHandler} porté par la <strong>transition de traitement</strong> (délégation ET
 * direct) — le test vérifie d'abord l'emplacement du marqueur, puis les deux scénarios.
 */
@SpringBootTest
@Transactional
class EServiceHandlerRejectTest {

    @Autowired
    private WorkflowDefinitionLoaderService loaderService;

    @Autowired
    private WorkflowExecutionService executionService;

    @Autowired
    private WorkflowStepRepository steps;

    @Autowired
    private WorkflowTransitionRepository transitions;

    @Autowired
    private WorkflowInstanceRepository instances;

    @Test
    void setsHandlerMarker_shouldBeCarriedByTreatmentTransitions() throws Exception {
        WorkflowDefinition definition = loadUnique();

        assertThat(setsHandlerTransitions(definition))
                .containsExactlyInAnyOrder(
                        "AGENT_TRAITEMENT|TRANSMETTRE|CHEF_DIVISION", // traitement délégué
                        "CHEF_DIVISION|VALIDER|DLEC");                // traitement direct
    }

    @Test
    void directTreatment_rejectAtDlec_shouldReturnToChefDivision() throws Exception {
        String key = loadUniqueKey();
        loaderService.loadAndSaveYamlDefinition(eserviceYamlAs(key));

        WorkflowInstance instance = executionService.startWorkflow(
                key, "citoyen-01", null, "DEPOT_CITOYEN");
        executionService.executeTransition(instance.getId(), "SOUMETTRE", "citoyen-01", null, null);
        assertAt(instance.getId(), "CHEF_DIVISION");

        // Traitement DIRECT : le chef valide lui-même vers la DLEC.
        executionService.executeTransition(
                instance.getId(), "VALIDER", "chef-division-01", "Validé en direct", null);
        assertAt(instance.getId(), "DLEC");

        WorkflowInstance rejected = executionService.executeTransition(
                instance.getId(), "REJETER", "dlec-01", "Dossier incomplet", null);

        assertThat(rejected.getCurrentStepKey()).isEqualTo("CHEF_DIVISION");
        assertThat(rejected.getStatus()).isEqualTo(InstanceStatus.ACTIVE);
    }

    @Test
    void delegatedTreatment_rejectAtDlec_shouldReturnToChefDivision() throws Exception {
        String key = loadUniqueKey();
        loaderService.loadAndSaveYamlDefinition(eserviceYamlAs(key));

        WorkflowInstance instance = executionService.startWorkflow(
                key, "citoyen-01", null, "DEPOT_CITOYEN");
        executionService.executeTransition(instance.getId(), "SOUMETTRE", "citoyen-01", null, null);

        // Traitement DÉLÉGUÉ : le chef impute à un agent, qui traite et transmet.
        executionService.executeTransition(instance.getId(), "IMPUTER", "chef-division-01", null,
                Map.of("assignee", "agent-traitement-01"));
        executionService.executeTransition(
                instance.getId(), "TRANSMETTRE", "agent-traitement-01", null, null);
        assertAt(instance.getId(), "CHEF_DIVISION");

        // Le chef valide vers la DLEC (traitement de référence).
        executionService.executeTransition(instance.getId(), "VALIDER", "chef-division-01", null, null);
        assertAt(instance.getId(), "DLEC");

        WorkflowInstance rejected = executionService.executeTransition(
                instance.getId(), "REJETER", "dlec-01", "Dossier incomplet", null);

        assertThat(rejected.getCurrentStepKey()).isEqualTo("CHEF_DIVISION");
        assertThat(rejected.getStatus()).isEqualTo(InstanceStatus.ACTIVE);
    }

    private List<String> setsHandlerTransitions(WorkflowDefinition definition) {
        List<String> marked = new ArrayList<>();
        for (WorkflowStep step : steps.findByDefinitionId(definition.getId())) {
            for (WorkflowTransition transition : transitions.findBySourceStepId(step.getId())) {
                if (transition.isSetsHandler()) {
                    marked.add(step.getStepKey() + "|" + transition.getAction()
                            + "|" + transition.getTargetStepKey());
                }
            }
        }
        return marked;
    }

    private WorkflowDefinition loadUnique() throws Exception {
        String key = loadUniqueKey();
        return loaderService.loadAndSaveYamlDefinition(eserviceYamlAs(key)).definition();
    }

    private String loadUniqueKey() {
        return "ESERVICE_HR_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void assertAt(UUID instanceId, String expectedStep) {
        assertThat(instances.findById(instanceId).orElseThrow().getCurrentStepKey())
                .isEqualTo(expectedStep);
    }

    private String eserviceYamlAs(String key) throws Exception {
        return StreamUtils.copyToString(
                        new ClassPathResource("workflows/eservice-workflow.yaml").getInputStream(),
                        StandardCharsets.UTF_8)
                .replace("\"E_SERVICE_DEMANDE\"", "\"" + key + "\"");
    }
}
