package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.definition.WorkflowTransition;
import sn.gouv.mfp.workflow.domain.execution.InstanceStatus;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.exception.InvalidEntryPointException;
import sn.gouv.mfp.workflow.exception.MissingRequiredDataException;
import sn.gouv.mfp.workflow.exception.WorkflowQuotaExceededException;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;

/**
 * Étape 6 — tests des gardes métier : plafond par initiateur (6.1), données obligatoires (6.2),
 * points d'entrée TASK déclarés (6.3) et stratégie de reprise après rejet (6.4).
 */
@SpringBootTest
@Transactional
class WorkflowBusinessGuardsTest {

    @Autowired
    private WorkflowExecutionService executionService;

    @Autowired
    private WorkflowDefinitionRepository definitions;

    @Autowired
    private WorkflowStepRepository steps;

    @Autowired
    private WorkflowTransitionRepository transitions;

    @Autowired
    private WorkflowInstanceRepository instances;

    // --- Module 6.1 : quota ---

    @Test
    void secondActiveInstanceForSameInitiator_shouldThrowQuota() {
        givenCappedCircuit("QUOTA_" + unique(), 1);

        executionService.startWorkflow(lastKey, "agent-quota", null);

        assertThatThrownBy(() -> executionService.startWorkflow(lastKey, "agent-quota", null))
                .isInstanceOf(WorkflowQuotaExceededException.class)
                .hasMessageContaining("Max: 1")
                .hasMessageContaining("Actuels: 1");
    }

    @Test
    void sameQuotaForDifferentInitiator_shouldPass() {
        givenCappedCircuit("QUOTA_MULTI_" + unique(), 1);

        executionService.startWorkflow(lastKey, "agent-a", null);
        WorkflowInstance other = executionService.startWorkflow(lastKey, "agent-b", null);

        assertThat(other.getStatus()).isEqualTo(InstanceStatus.ACTIVE);
    }

    @Test
    void newInstanceAfterCompletion_shouldPass() {
        givenCappedCircuit("QUOTA_REOPEN_" + unique(), 1);

        WorkflowInstance first = executionService.startWorkflow(lastKey, "agent-q", null);
        executionService.executeTransition(first.getId(), "VALIDER", "agent-q", null, null);

        WorkflowInstance second = executionService.startWorkflow(lastKey, "agent-q", null);
        assertThat(second.getStatus()).isEqualTo(InstanceStatus.ACTIVE);
    }

    @Test
    void noQuotaConfigured_shouldAllowUnlimited() {
        givenCappedCircuit("QUOTA_OFF_" + unique(), 0);

        executionService.startWorkflow(lastKey, "agent-free", null);
        executionService.startWorkflow(lastKey, "agent-free", null);
        executionService.startWorkflow(lastKey, "agent-free", null);

        assertThat(instances.findByStatus(InstanceStatus.ACTIVE)).hasSizeGreaterThanOrEqualTo(3);
    }

    // --- Module 6.2 : données obligatoires ---

    @Test
    void validateWithoutRequiredData_shouldThrowMissingData() {
        givenRequiredDataCircuit("REQDATA_" + unique());

        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "agent-001", null, "GUICHET");

        assertThatThrownBy(() -> executionService.executeTransition(
                        instance.getId(), "VALIDER", "agent-002", null, null))
                .isInstanceOf(MissingRequiredDataException.class)
                .hasMessageContaining("[GUICHET]")
                .hasMessageContaining("numeroOfficiel");
    }

    @Test
    void validateWithBlankRequiredData_shouldThrowMissingData() {
        givenRequiredDataCircuit("REQDATA_BLANK_" + unique());

        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "agent-001", null, "GUICHET");

        assertThatThrownBy(() -> executionService.executeTransition(
                        instance.getId(), "VALIDER", "agent-002", null,
                        Map.of("numeroOfficiel", "   ")))
                .isInstanceOf(MissingRequiredDataException.class);
    }

    @Test
    void validateWithRequiredData_shouldComplete() {
        givenRequiredDataCircuit("REQDATA_OK_" + unique());

        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "agent-001", null, "GUICHET");
        WorkflowInstance done = executionService.executeTransition(
                instance.getId(), "VALIDER", "agent-002", null,
                Map.of("numeroOfficiel", "2026-00123/MFP"));

        assertThat(done.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(done.getCurrentStepKey()).isEqualTo("CLOTURE");
    }

    // --- Module 6.3 : points d'entrée TASK ---

    @Test
    void startOnDeclaredTaskEntryPoint_shouldStartThere() {
        givenEntryCircuit("ENTRY_" + unique());

        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "chef-01", null, "BUREAU");

        assertThat(instance.getCurrentStepKey()).isEqualTo("BUREAU");
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.ACTIVE);
    }

    @Test
    void startOnUndeclaredTaskStep_shouldThrowInvalidEntryPoint() {
        givenEntryCircuit("ENTRY_KO_" + unique());

        assertThatThrownBy(() ->
                        executionService.startWorkflow(lastKey, "agent-01", null, "CONTROLE"))
                .isInstanceOf(InvalidEntryPointException.class)
                .hasMessageContaining("CONTROLE");
    }

    // --- Module 6.4 : reprise après rejet ---

    @Test
    void revalidateAfterReject_shouldJumpBackToRejectingStep() {
        givenResumeCircuit("RESUME_" + unique(), "FROM_REJECTING_STEP");

        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "agent-001", null, "ACCUEIL");
        executionService.executeTransition(instance.getId(), "TRANSMETTRE", "agent-001", null, null);
        executionService.executeTransition(
                instance.getId(), "REJETER", "verificateur-01", "Dossier incomplet", null);
        assertAt(instance.getId(), "ACCUEIL");
        assertThat(variablesOf(instance.getId())).containsEntry("rejectingStepKey", "VERIF");

        // AUTRE mènerait normalement à DERIVATION : la reprise saute directement à VERIF.
        WorkflowInstance resumed = executionService.executeTransition(
                instance.getId(), "AUTRE", "agent-001", null, null);

        assertThat(resumed.getCurrentStepKey()).isEqualTo("VERIF");
        assertThat(variablesOf(instance.getId())).doesNotContainKey("rejectingStepKey");
    }

    @Test
    void fromStartStrategy_shouldFollowLinearPath() {
        givenResumeCircuit("RESUME_LINEAR_" + unique(), "FROM_START");

        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "agent-001", null, "ACCUEIL");
        executionService.executeTransition(instance.getId(), "TRANSMETTRE", "agent-001", null, null);
        executionService.executeTransition(
                instance.getId(), "REJETER", "verificateur-01", "Dossier incomplet", null);

        WorkflowInstance resumed = executionService.executeTransition(
                instance.getId(), "AUTRE", "agent-001", null, null);

        assertThat(resumed.getCurrentStepKey()).isEqualTo("DERIVATION");
        assertThat(variablesOf(instance.getId())).doesNotContainKey("rejectingStepKey");
    }

    // --- Aides ---

    private String lastKey;

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void assertAt(UUID instanceId, String expectedStep) {
        assertThat(instances.findById(instanceId).orElseThrow().getCurrentStepKey())
                .isEqualTo(expectedStep);
    }

    private Map<String, Object> variablesOf(UUID instanceId) {
        return instances.findById(instanceId).orElseThrow().getVariables();
    }

    /** Circuit minimal A(START) -VALIDER-> B(END), avec plafond maxActiveInstancesPerInitiator. */
    private void givenCappedCircuit(String key, int max) {
        lastKey = key;
        WorkflowDefinition definition = newDefinition(key);
        if (max > 0) {
            definition.setSettings(new HashMap<>(Map.of("maxActiveInstancesPerInitiator", max)));
        }
        definition = definitions.save(definition);

        WorkflowStep start = newStep(definition, "ACCUEIL", StepType.START);
        newStep(definition, "FIN", StepType.END);
        newTransition(start, "VALIDER", "FIN", null);
    }

    /** Circuit GUICHET(START, requiredData=[numeroOfficiel]) -VALIDER-> CLOTURE(END). */
    private void givenRequiredDataCircuit(String key) {
        lastKey = key;
        WorkflowDefinition definition = definitions.save(newDefinition(key));

        // requiredData fixé avant le premier INSERT : le compte applicatif n'a pas
        // le droit UPDATE sur les référentiels (recette 8.5).
        WorkflowStep guichet = new WorkflowStep();
        guichet.setDefinition(definition);
        guichet.setStepKey("GUICHET");
        guichet.setType(StepType.START);
        guichet.setRequiredData(List.of("numeroOfficiel"));
        guichet = steps.save(guichet);
        newStep(definition, "CLOTURE", StepType.END);
        newTransition(guichet, "VALIDER", "CLOTURE", null);
    }

    /** Circuit avec BUREAU(TASK) déclaré en point d'entrée et CONTROLE(TASK) non déclaré. */
    private void givenEntryCircuit(String key) {
        lastKey = key;
        WorkflowDefinition definition = newDefinition(key);
        definition.setEntryPoints(List.of(new sn.gouv.mfp.workflow.domain.definition.EntryPoint(
                "CHEF_BUREAU", "BUREAU")));
        definition = definitions.save(definition);

        newStep(definition, "ACCUEIL", StepType.START);
        newStep(definition, "BUREAU", StepType.TASK);
        newStep(definition, "CONTROLE", StepType.TASK);
        newStep(definition, "FIN", StepType.END);
    }

    /**
     * Circuit de reprise : ACCUEIL(START) -TRANSMETTRE-> VERIF(TASK) ; VERIF -VALIDER-> FIN(END),
     * VERIF -REJETER-> INITIATEUR ; ACCUEIL -AUTRE-> DERIVATION(TASK) ; DERIVATION -VALIDER-> FIN.
     */
    private void givenResumeCircuit(String key, String strategy) {
        lastKey = key;
        WorkflowDefinition definition = newDefinition(key);
        definition.setSettings(new HashMap<>(Map.of("resumeStrategyDefault", strategy)));
        definition = definitions.save(definition);

        WorkflowStep accueil = newStep(definition, "ACCUEIL", StepType.START);
        WorkflowStep verif = newStep(definition, "VERIF", StepType.TASK);
        newStep(definition, "DERIVATION", StepType.TASK);
        newStep(definition, "FIN", StepType.END);
        newTransition(accueil, "TRANSMETTRE", "VERIF", null);
        newTransition(accueil, "AUTRE", "DERIVATION", null);
        newTransition(verif, "VALIDER", "FIN", null);
        newTransition(verif, "REJETER", "INITIATEUR", null);
        WorkflowStep derivation = steps.findByDefinitionIdAndStepKey(definition.getId(), "DERIVATION")
                .orElseThrow();
        newTransition(derivation, "VALIDER", "FIN", null);
    }

    private WorkflowDefinition newDefinition(String key) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(key);
        definition.setVersion(1);
        definition.setName("Circuit test " + key);
        return definition;
    }

    private WorkflowStep newStep(WorkflowDefinition definition, String stepKey, StepType type) {
        WorkflowStep step = new WorkflowStep();
        step.setDefinition(definition);
        step.setStepKey(stepKey);
        step.setType(type);
        return steps.save(step);
    }

    private WorkflowTransition newTransition(
            WorkflowStep source, String action, String target, String condition) {
        WorkflowTransition transition = new WorkflowTransition();
        transition.setSourceStep(source);
        transition.setAction(action);
        transition.setTargetStepKey(target);
        transition.setConditionExpression(condition);
        return transitions.save(transition);
    }
}
