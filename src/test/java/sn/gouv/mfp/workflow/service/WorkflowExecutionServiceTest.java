package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
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
import sn.gouv.mfp.workflow.exception.WorkflowConflictException;
import sn.gouv.mfp.workflow.exception.WorkflowNotFoundException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowHistoryRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;

/**
 * Étape 3 — tests du chef d'orchestre : initiation, transitions, gardes métier
 * et redirection INITIATEUR. Base PostgreSQL réelle, rollback par test.
 */
@SpringBootTest
@Transactional
class WorkflowExecutionServiceTest {

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

    @Autowired
    private WorkflowHistoryRepository histories;

    @Test
    void start_shouldCreateActiveInstanceOnStartStepWithInitiationHistory() {
        givenCircuit("START_OK_" + unique(), false);

        WorkflowInstance instance = executionService.startWorkflow(
                lastKey, "agent-001", Map.of("circuit", "COURT"));

        assertThat(instance.getId()).isNotNull();
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.ACTIVE);
        assertThat(instance.getCurrentStepKey()).isEqualTo("S_VISA");
        assertThat(instance.getInitiatorId()).isEqualTo("agent-001");
        assertThat(instance.getVariables()).containsEntry("circuit", "COURT");
        assertThat(histories.findByInstanceIdOrderByTimestampAsc(instance.getId()))
                .hasSize(1)
                .first()
                .satisfies(h -> {
                    assertThat(h.getAction()).isEqualTo("INITIATION");
                    assertThat(h.getActorId()).isEqualTo("agent-001");
                });
    }

    @Test
    void start_withUnknownKey_shouldThrowNotFound() {
        assertThatThrownBy(() -> executionService.startWorkflow(
                        "CLE_INCONNUE_XYZ", "agent-001", null))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    @Test
    void transition_shouldAdvanceMergeVariablesAndRecordHistory() {
        givenCircuit("TRANSITION_OK_" + unique(), false);
        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "agent-001", Map.of("circuit", "COURT"));

        Map<String, Object> extra = new HashMap<>();
        extra.put("numeroOfficiel", "2026-00456/MFP");
        WorkflowInstance advanced = executionService.executeTransition(
                instance.getId(), "VALIDER", "agent-002", null, extra);

        assertThat(advanced.getCurrentStepKey()).isEqualTo("DGFP");
        assertThat(advanced.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(advanced.getVariables())
                .containsEntry("circuit", "COURT")
                .containsEntry("numeroOfficiel", "2026-00456/MFP");
        assertThat(histories.findByInstanceIdOrderByTimestampAsc(instance.getId()))
                .hasSize(2)
                .last()
                .satisfies(h -> {
                    assertThat(h.getAction()).isEqualTo("VALIDER");
                    assertThat(h.getFromStep()).isEqualTo("S_VISA");
                    assertThat(h.getToStep()).isEqualTo("DGFP");
                    assertThat(h.getActorId()).isEqualTo("agent-002");
                });
    }

    @Test
    void transition_onCompletedInstance_shouldThrowConflict() {
        givenCircuit("CONFLICT_OK_" + unique(), false);
        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "agent-001", Map.of("circuit", "COURT"));
        executionService.executeTransition(instance.getId(), "VALIDER", "agent-002", null, null);

        assertThatThrownBy(() -> executionService.executeTransition(
                        instance.getId(), "VALIDER", "agent-003", null, null))
                .isInstanceOf(WorkflowConflictException.class);
    }

    @Test
    void transition_withUnmetCondition_shouldThrowValidation() {
        givenCircuit("CONDITION_KO_" + unique(), true);
        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "agent-001", Map.of("circuit", "LONG"));

        // La seule transition VALIDER exige circuit == COURT : aucune n'est franchissable.
        assertThatThrownBy(() -> executionService.executeTransition(
                        instance.getId(), "VALIDER", "agent-002", null, null))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Aucune transition valide");
    }

    @Test
    void transition_withUnknownAction_shouldThrowValidation() {
        givenCircuit("ACTION_KO_" + unique(), false);
        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "agent-001", Map.of("circuit", "COURT"));

        assertThatThrownBy(() -> executionService.executeTransition(
                        instance.getId(), "ARCHIVER", "agent-002", null, null))
                .isInstanceOf(WorkflowValidationException.class);
    }

    @Test
    void rejectToInitiateur_shouldReturnToInitialStep() {
        givenCircuitWithControl("INITIATEUR_OK_" + unique());
        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "agent-001", Map.of("circuit", "LONG"));

        executionService.executeTransition(instance.getId(), "VALIDER", "agent-002", null, null);
        assertThat(instances.findById(instance.getId()).orElseThrow().getCurrentStepKey())
                .isEqualTo("CONTROLE");

        WorkflowInstance rejected = executionService.executeTransition(
                instance.getId(), "REJETER", "agent-003", "Pièce manquante", null);

        assertThat(rejected.getCurrentStepKey()).isEqualTo("S_VISA");
        assertThat(rejected.getStatus()).isEqualTo(InstanceStatus.ACTIVE);
        assertThat(histories.findByInstanceIdOrderByTimestampAsc(instance.getId()))
                .hasSize(3)
                .last()
                .satisfies(h -> {
                    assertThat(h.getAction()).isEqualTo("REJETER");
                    assertThat(h.getFromStep()).isEqualTo("CONTROLE");
                    assertThat(h.getToStep()).isEqualTo("S_VISA");
                    assertThat(h.getComment()).isEqualTo("Pièce manquante");
                });
    }

    // --- Aides de construction ---

    private String lastKey;

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Circuit S_VISA (START) -&gt; DGFP (END) via VALIDER.
     *
     * @param conditional si true, VALIDER exige circuit == COURT ; sinon inconditionnelle
     */
    private void givenCircuit(String key, boolean conditional) {
        lastKey = key;
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(key);
        definition.setVersion(1);
        definition.setName("Circuit test " + key);
        definition = definitions.save(definition);

        WorkflowStep visa = new WorkflowStep();
        visa.setDefinition(definition);
        visa.setStepKey("S_VISA");
        visa.setType(StepType.START);
        visa = steps.save(visa);

        WorkflowStep dgfp = new WorkflowStep();
        dgfp.setDefinition(definition);
        dgfp.setStepKey("DGFP");
        dgfp.setType(StepType.END);
        steps.save(dgfp);

        WorkflowTransition valider = new WorkflowTransition();
        valider.setSourceStep(visa);
        valider.setTargetStepKey("DGFP");
        valider.setAction("VALIDER");
        valider.setConditionExpression(conditional ? "#variables['circuit'] == 'COURT'" : null);
        transitions.save(valider);
    }

    /** Circuit S_VISA (START) -&gt; CONTROLE (TASK), avec REJETER CONTROLE -&gt; INITIATEUR. */
    private void givenCircuitWithControl(String key) {
        lastKey = key;
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(key);
        definition.setVersion(1);
        definition.setName("Circuit contrôle " + key);
        definition = definitions.save(definition);

        WorkflowStep visa = new WorkflowStep();
        visa.setDefinition(definition);
        visa.setStepKey("S_VISA");
        visa.setType(StepType.START);
        visa = steps.save(visa);

        WorkflowStep controle = new WorkflowStep();
        controle.setDefinition(definition);
        controle.setStepKey("CONTROLE");
        controle.setType(StepType.TASK);
        controle = steps.save(controle);

        WorkflowStep dgfp = new WorkflowStep();
        dgfp.setDefinition(definition);
        dgfp.setStepKey("DGFP");
        dgfp.setType(StepType.END);
        steps.save(dgfp);

        WorkflowTransition valider = new WorkflowTransition();
        valider.setSourceStep(visa);
        valider.setTargetStepKey("CONTROLE");
        valider.setAction("VALIDER");
        transitions.save(valider);

        WorkflowTransition rejeter = new WorkflowTransition();
        rejeter.setSourceStep(controle);
        rejeter.setTargetStepKey("INITIATEUR");
        rejeter.setAction("REJETER");
        transitions.save(rejeter);

        WorkflowTransition valider2 = new WorkflowTransition();
        valider2.setSourceStep(controle);
        valider2.setTargetStepKey("DGFP");
        valider2.setAction("VALIDER");
        transitions.save(valider2);
    }
}
