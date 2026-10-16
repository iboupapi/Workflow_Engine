package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import sn.gouv.mfp.workflow.exception.MissingRequiredDataException;
import sn.gouv.mfp.workflow.exception.WorkflowConflictException;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowHistoryRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;

/**
 * Chantier D — cibles de rejet dynamiques (ETAPE_PRECEDENTE, RESPONSABLE_TRAITEMENT),
 * imputation avec traçabilité (setsHandler) et rejet définitif.
 */
@SpringBootTest
@Transactional
class WorkflowRejectTargetsTest {

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
    void rejectToPreviousStep_shouldReturnToLastTraversedStep() {
        givenCircuit("PREV_" + unique(), true);

        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "agent-001", null, "A");
        executionService.executeTransition(instance.getId(), "AVANCER", "agent-001", null, null);
        executionService.executeTransition(instance.getId(), "AVANCER", "agent-002", null, null);
        assertAt(instance.getId(), "C");

        WorkflowInstance rejected = executionService.executeTransition(
                instance.getId(), "REJETER", "agent-003", "Non conforme", null);

        // Retour à B (dernière étape traversée), pas à A.
        assertThat(rejected.getCurrentStepKey()).isEqualTo("B");
        assertThat(rejected.getStatus()).isEqualTo(InstanceStatus.ACTIVE);
    }

    @Test
    void rejectToPreviousStepWithoutMovement_shouldStay() {
        givenCircuit("PREV0_" + unique(), true);

        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "agent-001", null, "A");
        WorkflowInstance rejected = executionService.executeTransition(
                instance.getId(), "REJETER", "agent-001", "Erreur de saisie", null);

        assertThat(rejected.getCurrentStepKey()).isEqualTo("A");
    }

    @Test
    void rejectToHandler_shouldReturnToAssigneeLastStep() {
        givenCircuit("HAND_" + unique(), false);

        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "chef-01", null, "A");
        executionService.executeTransition(instance.getId(), "AVANCER", "chef-01", null, null);
        // Imputation à l'agent, qui traite à l'étape B.
        executionService.executeTransition(instance.getId(), "IMPUTER", "chef-01", null,
                Map.of("assignee", "agent-09"));
        executionService.executeTransition(instance.getId(), "AVANCER", "agent-09", null, null);
        assertAt(instance.getId(), "C");

        WorkflowInstance rejected = executionService.executeTransition(
                instance.getId(), "REJETER", "verif-01", "À reprendre", null);

        // Retour à B, dernière étape où l'imputé a agi (pas à A).
        assertThat(rejected.getCurrentStepKey()).isEqualTo("B");
    }

    @Test
    void rejectToHandlerWithoutAssignee_shouldFallBackToPrevious() {
        givenCircuit("HAND2_" + unique(), false);

        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "agent-001", null, "A");
        executionService.executeTransition(instance.getId(), "AVANCER", "agent-001", null, null);
        executionService.executeTransition(instance.getId(), "AVANCER", "agent-002", null, null);

        WorkflowInstance rejected = executionService.executeTransition(
                instance.getId(), "REJETER", "agent-003", "À reprendre", null);

        assertThat(rejected.getCurrentStepKey()).isEqualTo("B");
    }

    @Test
    void impute_shouldAssignAndRecordBothSides() {
        givenCircuit("IMP_" + unique(), false);

        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "chef-01", null, "A");
        executionService.executeTransition(instance.getId(), "AVANCER", "chef-01", null, null);
        WorkflowInstance imputed = executionService.executeTransition(
                instance.getId(), "IMPUTER", "chef-01", "Traitement urgent",
                Map.of("assignee", "agent-09"));

        assertThat(imputed.getAssigneeId()).isEqualTo("agent-09");
        assertThat(histories.findByInstanceIdOrderByTimestampAsc(instance.getId()))
                .filteredOn(h -> "IMPUTER".equals(h.getAction()))
                .hasSize(1)
                .first()
                .satisfies(h -> {
                    assertThat(h.getActorId()).isEqualTo("chef-01");
                    assertThat(h.getToActorId()).isEqualTo("agent-09");
                    assertThat(h.getComment()).isEqualTo("Traitement urgent");
                });

        // Réimputation : les deux lignes sont conservées (recette 5.4).
        executionService.executeTransition(instance.getId(), "IMPUTER", "chef-01", null,
                Map.of("assignee", "agent-10"));
        assertThat(instances.findById(instance.getId()).orElseThrow().getAssigneeId())
                .isEqualTo("agent-10");
        assertThat(histories.findByInstanceIdOrderByTimestampAsc(instance.getId()))
                .filteredOn(h -> "IMPUTER".equals(h.getAction()))
                .hasSize(2);
    }

    @Test
    void imputeWithoutPerson_shouldBeRefused() {
        givenCircuit("IMP_KO_" + unique(), false);

        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "chef-01", null, "A");
        executionService.executeTransition(instance.getId(), "AVANCER", "chef-01", null, null);
        assertThat(instances.findById(instance.getId()).orElseThrow().getCurrentStepKey())
                .isEqualTo("B");

        assertThatThrownBy(() -> executionService.executeTransition(
                        instance.getId(), "IMPUTER", "chef-01", null, null))
                .isInstanceOf(MissingRequiredDataException.class)
                .hasMessageContaining("assignee");
    }

    @Test
    void finalRejection_shouldCloseAndBlockAnyLaterAction() {
        givenCircuit("DEFFIN_" + unique(), true);

        WorkflowInstance instance =
                executionService.startWorkflow(lastKey, "agent-001", null, "A");
        executionService.executeTransition(instance.getId(), "AVANCER", "agent-001", null, null);
        executionService.executeTransition(instance.getId(), "AVANCER", "agent-002", null, null);
        WorkflowInstance closed = executionService.executeTransition(
                instance.getId(), "REJET_DEFINITIF", "dg-01", "Irrecevable", null);

        assertThat(closed.getStatus()).isEqualTo(InstanceStatus.CANCELLED);
        assertThatThrownBy(() -> executionService.executeTransition(
                        instance.getId(), "AVANCER", "agent-001", null, null))
                .isInstanceOf(WorkflowConflictException.class);
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

    /**
     * Circuit A(START) -AVANCER-> B(TASK) -AVANCER-> C(TASK) [+ FIN(END)], avec
     * IMPUTER en boucle sur B et les rejets demandés.
     *
     * @param finalRejection ajoute C -REJET_DEFINITIF-> C et B -IMPUTER-> B
     */
    private void givenCircuit(String key, boolean finalRejection) {
        lastKey = key;
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(key);
        definition.setVersion(1);
        definition.setName("Circuit cibles " + key);
        definition = definitions.save(definition);

        WorkflowStep a = newStep(definition, "A", StepType.START);
        WorkflowStep b = newStep(definition, "B", StepType.TASK);
        WorkflowStep c = newStep(definition, "C", StepType.TASK);
        if (finalRejection) {
            newStep(definition, "FIN", StepType.END);
        }
        newTransition(a, "AVANCER", "B");
        newTransition(b, "AVANCER", "C");
        newTransition(a, "REJETER", "ETAPE_PRECEDENTE");
        newTransition(b, "IMPUTER", "B");
        newTransition(c, "REJETER", "RESPONSABLE_TRAITEMENT");
        if (finalRejection) {
            newTransition(b, "VALIDER", "FIN");
            newTransition(c, "REJET_DEFINITIF", "C");
        }
    }

    private WorkflowStep newStep(WorkflowDefinition definition, String stepKey, StepType type) {
        WorkflowStep step = new WorkflowStep();
        step.setDefinition(definition);
        step.setStepKey(stepKey);
        step.setType(type);
        return steps.save(step);
    }

    private void newTransition(WorkflowStep source, String action, String target) {
        WorkflowTransition transition = new WorkflowTransition();
        transition.setSourceStep(source);
        transition.setAction(action);
        transition.setTargetStepKey(target);
        transitions.save(transition);
    }
}
