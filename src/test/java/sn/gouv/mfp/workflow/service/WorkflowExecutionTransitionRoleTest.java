package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.definition.WorkflowTransition;
import sn.gouv.mfp.workflow.domain.execution.InstanceStatus;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.exception.WorkflowForbiddenException;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowHistoryRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;

/**
 * Point 2 (relecture P1c) : le contrôle du rôle d'étape à la transition est exécuté
 * <strong>sous le verrou</strong> ({@code findByIdWithLock}), donc atomique avec la
 * mutation. Rôles non responsables : refus AVANT toute mutation et sans lecture des
 * transitions. Rôles responsables : la transition s'exécute normalement.
 */
class WorkflowExecutionTransitionRoleTest {

    private final WorkflowDefinitionRepository definitions =
            Mockito.mock(WorkflowDefinitionRepository.class);
    private final WorkflowStepRepository steps = Mockito.mock(WorkflowStepRepository.class);
    private final WorkflowTransitionRepository transitions =
            Mockito.mock(WorkflowTransitionRepository.class);
    private final WorkflowInstanceRepository instances =
            Mockito.mock(WorkflowInstanceRepository.class);
    private final WorkflowHistoryRepository histories =
            Mockito.mock(WorkflowHistoryRepository.class);
    private final ConditionEvaluatorService conditionEvaluator =
            Mockito.mock(ConditionEvaluatorService.class);
    private final WorkflowExecutionService executionService = new WorkflowExecutionService(
            definitions, steps, transitions, instances, histories, conditionEvaluator);

    @Test
    void transition_refusesRoleNotResponsible_beforeAnyMutation() {
        UUID id = UUID.randomUUID();
        WorkflowDefinition definition = definition("CIRCUIT");
        WorkflowStep current = step(definition, "S1", StepType.TASK, List.of("R1"));
        when(instances.findByIdWithLock(id))
                .thenReturn(Optional.of(activeInstance(definition, "S1")));
        when(steps.findByDefinitionIdAndStepKey(any(), eq("S1"))).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> executionService.executeTransition(
                        id, "VALIDER", "agent-r2", null, null, Set.of("R2")))
                .isInstanceOf(WorkflowForbiddenException.class);

        // Le refus intervient après le verrou mais avant toute lecture de transition/mutation.
        verify(transitions, never()).findBySourceStepIdAndAction(any(), any());
        verify(histories, never()).save(any());
    }

    @Test
    void transition_allowsResponsibleRole_andPerformsTheAction() {
        UUID id = UUID.randomUUID();
        WorkflowDefinition definition = definition("CIRCUIT");
        WorkflowStep current = step(definition, "S1", StepType.TASK, List.of("R1"));
        WorkflowStep target = step(definition, "S2", StepType.TASK, List.of("R1"));
        WorkflowTransition transition = new WorkflowTransition();
        transition.setAction("VALIDER");
        transition.setTargetStepKey("S2");

        when(instances.findByIdWithLock(id))
                .thenReturn(Optional.of(activeInstance(definition, "S1")));
        when(steps.findByDefinitionIdAndStepKey(any(), eq("S1"))).thenReturn(Optional.of(current));
        when(steps.findByDefinitionIdAndStepKey(any(), eq("S2"))).thenReturn(Optional.of(target));
        when(transitions.findBySourceStepIdAndAction(any(), eq("VALIDER")))
                .thenReturn(List.of(transition));
        when(conditionEvaluator.evaluateStrict(isNull(), any())).thenReturn(true);
        when(histories.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowInstance result = executionService.executeTransition(
                id, "VALIDER", "agent-r1", null, null, Set.of("R1"));

        assertThat(result.getCurrentStepKey()).isEqualTo("S2");
        verify(histories).save(any());
    }

    @Test
    void finalRejection_setsRejectedFinalStatus() {
        UUID id = UUID.randomUUID();
        WorkflowDefinition definition = definition("CIRCUIT");
        WorkflowStep current = step(definition, "S1", StepType.TASK, List.of("R1"));
        WorkflowStep target = step(definition, "S2", StepType.TASK, List.of("R1"));
        WorkflowTransition transition = new WorkflowTransition();
        transition.setAction("REJET_DEFINITIF");
        transition.setTargetStepKey("S2");

        when(instances.findByIdWithLock(id))
                .thenReturn(Optional.of(activeInstance(definition, "S1")));
        when(steps.findByDefinitionIdAndStepKey(any(), eq("S1"))).thenReturn(Optional.of(current));
        when(steps.findByDefinitionIdAndStepKey(any(), eq("S2"))).thenReturn(Optional.of(target));
        when(transitions.findBySourceStepIdAndAction(any(), eq("REJET_DEFINITIF")))
                .thenReturn(List.of(transition));
        when(conditionEvaluator.evaluateStrict(isNull(), any())).thenReturn(true);
        when(histories.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowInstance result = executionService.executeTransition(
                id, "REJET_DEFINITIF", "dg-01", "Irrecevable", null, Set.of("R1"));

        assertThat(result.getStatus()).isEqualTo(InstanceStatus.REJECTED_FINAL);
    }

    @Test
    void handlerTarget_routesToProcessingStep() {
        UUID id = UUID.randomUUID();
        WorkflowDefinition definition = definition("CIRCUIT");
        WorkflowStep current = step(definition, "S1", StepType.TASK, List.of("R1"));
        WorkflowStep handler = step(definition, "SB", StepType.TASK, List.of("R1"));
        WorkflowTransition transition = new WorkflowTransition();
        transition.setAction("REJETER");
        transition.setTargetStepKey("RESPONSABLE_TRAITEMENT");

        when(instances.findByIdWithLock(id))
                .thenReturn(Optional.of(activeInstance(definition, "S1")));
        when(steps.findByDefinitionIdAndStepKey(any(), eq("S1"))).thenReturn(Optional.of(current));
        when(steps.findByDefinitionIdAndStepKey(any(), eq("SB"))).thenReturn(Optional.of(handler));
        when(steps.findFirstByDefinitionIdAndProcessingStepTrue(any())).thenReturn(Optional.of(handler));
        when(transitions.findBySourceStepIdAndAction(any(), eq("REJETER")))
                .thenReturn(List.of(transition));
        when(conditionEvaluator.evaluateStrict(isNull(), any())).thenReturn(true);
        when(histories.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowInstance result = executionService.executeTransition(
                id, "REJETER", "agent-r1", "À reprendre", null, Set.of("R1"));

        assertThat(result.getCurrentStepKey()).isEqualTo("SB");
    }

    private static WorkflowDefinition definition(String key) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(key);
        definition.setVersion(1);
        definition.setName("Circuit de test");
        return definition;
    }

    private static WorkflowStep step(
            WorkflowDefinition definition, String key, StepType type, List<String> owners) {
        WorkflowStep step = new WorkflowStep();
        step.setDefinition(definition);
        step.setStepKey(key);
        step.setType(type);
        step.setOwners(owners);
        return step;
    }

    private static WorkflowInstance activeInstance(WorkflowDefinition definition, String stepKey) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setDefinition(definition);
        instance.setCurrentStepKey(stepKey);
        instance.setStatus(InstanceStatus.ACTIVE);
        instance.setVariables(new HashMap<>());
        return instance;
    }
}
