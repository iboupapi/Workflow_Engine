package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import sn.gouv.mfp.workflow.domain.execution.InstanceStatus;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;

/**
 * Étape 5 — test bout-en-bout sur le circuit GIRAFE chargé depuis le YAML :
 * un dossier COURT saute DPR/DS/DPB après le S-Visa, un dossier LONG passe par DS,
 * un dossier PLUS_LONG passe par DPR.
 */
@SpringBootTest
@Transactional
class GirafeWorkflowE2ETest {

    @Autowired
    private WorkflowDefinitionLoaderService loaderService;

    @Autowired
    private WorkflowExecutionService executionService;

    @Autowired
    private sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository instances;

    @Test
    void courtCircuit_shouldSkipDirectlyToDgfp() throws Exception {
        loaderService.loadAndSaveYamlDefinition(girafeYaml());

        WorkflowInstance instance = executionService.startWorkflow(
                "GIRAFE_ACTES", "agent-bureau-01",
                Map.of("circuit", "COURT", "natureActe", "AVANCEMENT"), "AGENT_BUREAU");
        assertThat(instance.getCurrentStepKey()).isEqualTo("AGENT_BUREAU");

        advance(instance.getId(), "TRANSMETTRE", "agent-bureau-01");
        assertAt(instance.getId(), "CHEF_BUREAU");
        advance(instance.getId(), "VALIDER", "chef-bureau-01");
        assertAt(instance.getId(), "CHEF_DIVISION");
        advance(instance.getId(), "VALIDER", "chef-division-01");
        assertAt(instance.getId(), "S_VISA");

        // Aiguillage : COURT -> DGFP, sans passer par DPR, DS ni DPB.
        advance(instance.getId(), "VALIDER", "s-visa-01");
        assertAt(instance.getId(), "DGFP");

        // Fin commune toujours franchissable.
        advance(instance.getId(), "VALIDER", "dgfp-01");
        assertAt(instance.getId(), "SG_CABINET");
    }

    @Test
    void longCircuit_shouldRouteThroughSolde() throws Exception {
        loaderService.loadAndSaveYamlDefinition(girafeYaml());

        WorkflowInstance instance = executionService.startWorkflow(
                "GIRAFE_ACTES", "agent-bureau-02",
                Map.of("circuit", "LONG", "natureActe", "INTEGRATION"), "AGENT_BUREAU");
        runToVisa(instance.getId());

        advance(instance.getId(), "VALIDER", "s-visa-01");
        assertAt(instance.getId(), "DS");

        advance(instance.getId(), "VALIDER", "ds-01");
        assertAt(instance.getId(), "DPB");
    }

    @Test
    void plusLongCircuit_shouldRouteThroughPensions() throws Exception {
        loaderService.loadAndSaveYamlDefinition(girafeYaml());

        WorkflowInstance instance = executionService.startWorkflow(
                "GIRAFE_ACTES", "agent-bureau-03",
                Map.of("circuit", "PLUS_LONG", "natureActe", "RETRAITE"), "AGENT_BUREAU");
        runToVisa(instance.getId());

        advance(instance.getId(), "VALIDER", "s-visa-01");
        assertAt(instance.getId(), "DPR");
    }

    @Test
    void rejectAtVisa_shouldReturnToChefDivision() throws Exception {
        loaderService.loadAndSaveYamlDefinition(girafeYaml());

        WorkflowInstance instance = executionService.startWorkflow(
                "GIRAFE_ACTES", "agent-bureau-04",
                Map.of("circuit", "COURT"), "AGENT_BUREAU");
        runToVisa(instance.getId());

        WorkflowInstance rejected = executionService.executeTransition(
                instance.getId(), "REJETER", "s-visa-01", "Dossier incomplet", null);
        assertThat(rejected.getCurrentStepKey()).isEqualTo("CHEF_DIVISION");
        assertThat(rejected.getStatus()).isEqualTo(InstanceStatus.ACTIVE);
    }

    private void runToVisa(java.util.UUID instanceId) {
        advance(instanceId, "TRANSMETTRE", "agent-bureau-01");
        advance(instanceId, "VALIDER", "chef-bureau-01");
        advance(instanceId, "VALIDER", "chef-division-01");
        assertAt(instanceId, "S_VISA");
    }

    private void advance(java.util.UUID instanceId, String action, String actor) {
        Map<String, Object> vars = new HashMap<>();
        executionService.executeTransition(instanceId, action, actor, null, vars);
    }

    private void assertAt(java.util.UUID instanceId, String expectedStep) {
        assertThat(instances.findById(instanceId).orElseThrow().getCurrentStepKey())
                .isEqualTo(expectedStep);
    }

    private String girafeYaml() throws Exception {
        return StreamUtils.copyToString(
                new ClassPathResource("workflows/girafe-workflow.yaml").getInputStream(),
                StandardCharsets.UTF_8);
    }
}
