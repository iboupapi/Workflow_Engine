package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.exception.WorkflowForbiddenException;
import sn.gouv.mfp.workflow.exception.WorkflowInstanceNotFoundException;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.service.WorkflowReadService.Viewer;

/**
 * Contrôle par rôle d'étape (point 2) : actions /start et /transition soumises aux rôles
 * responsables de l'étape (owners du YAML), rôle ADMIN jamais validateur, refus par défaut.
 */
class WorkflowAuthorizationServiceTest {

    private final WorkflowExecutionService executionService =
            Mockito.mock(WorkflowExecutionService.class);
    private final WorkflowStepRepository steps = Mockito.mock(WorkflowStepRepository.class);
    private final WorkflowInstanceRepository instances =
            Mockito.mock(WorkflowInstanceRepository.class);
    private final WorkflowAuthorizationService authorization =
            new WorkflowAuthorizationService(executionService, steps, instances);

    @Test
    void start_allowsRoleResponsibleOfStartStep() {
        WorkflowDefinition definition = definition("GIRAFE_ACTES");
        WorkflowStep start =
                step(definition, "AGENT_BUREAU", StepType.START, List.of("AGENT_BUREAU"));
        when(executionService.latestDefinition("GIRAFE_ACTES")).thenReturn(definition);
        when(executionService.resolveStartStep(definition, null)).thenReturn(start);

        assertThatCode(() -> authorization.authorizeStart(
                        "GIRAFE_ACTES", null, viewer("a1", Set.of("AGENT_BUREAU"), false)))
                .doesNotThrowAnyException();
    }

    @Test
    void start_refusesViewerWithoutResponsibleRole() {
        WorkflowDefinition definition = definition("GIRAFE_ACTES");
        WorkflowStep start =
                step(definition, "AGENT_BUREAU", StepType.START, List.of("AGENT_BUREAU"));
        when(executionService.latestDefinition("GIRAFE_ACTES")).thenReturn(definition);
        when(executionService.resolveStartStep(definition, null)).thenReturn(start);

        assertThatThrownBy(() -> authorization.authorizeStart(
                        "GIRAFE_ACTES", null, viewer("x", Set.of("CHEF_DIVISION"), false)))
                .isInstanceOf(WorkflowForbiddenException.class);
    }

    @Test
    void start_refusesAdminAlone_administratorIsNotValidator() {
        WorkflowDefinition definition = definition("GIRAFE_ACTES");
        WorkflowStep start =
                step(definition, "AGENT_BUREAU", StepType.START, List.of("AGENT_BUREAU"));
        when(executionService.latestDefinition("GIRAFE_ACTES")).thenReturn(definition);
        when(executionService.resolveStartStep(definition, null)).thenReturn(start);

        assertThatThrownBy(() -> authorization.authorizeStart(
                        "GIRAFE_ACTES", null, viewer("admin-01", Set.of("ADMIN"), true)))
                .isInstanceOf(WorkflowForbiddenException.class);
    }

    @Test
    void start_refusesStepWithoutOwners() {
        WorkflowDefinition definition = definition("GIRAFE_ACTES");
        WorkflowStep start = step(definition, "AGENT_BUREAU", StepType.START, null);
        when(executionService.latestDefinition("GIRAFE_ACTES")).thenReturn(definition);
        when(executionService.resolveStartStep(definition, null)).thenReturn(start);

        assertThatThrownBy(() -> authorization.authorizeStart(
                        "GIRAFE_ACTES", null, viewer("a1", Set.of("AGENT_BUREAU"), false)))
                .isInstanceOf(WorkflowForbiddenException.class);
    }

    @Test
    void transition_allowsRoleResponsibleOfCurrentStep() {
        WorkflowDefinition definition = definition("GIRAFE_ACTES");
        WorkflowStep current = step(definition, "S_VISA", StepType.TASK, List.of("S_VISA"));
        UUID id = UUID.randomUUID();
        when(instances.findById(id)).thenReturn(Optional.of(instance(definition, "S_VISA")));
        when(steps.findByDefinitionIdAndStepKey(definition.getId(), "S_VISA"))
                .thenReturn(Optional.of(current));

        assertThatCode(() -> authorization.authorizeTransition(
                        id, viewer("v1", Set.of("S_VISA"), false)))
                .doesNotThrowAnyException();
    }

    @Test
    void transition_refusesViewerWithoutResponsibleRole() {
        WorkflowDefinition definition = definition("GIRAFE_ACTES");
        WorkflowStep current = step(definition, "S_VISA", StepType.TASK, List.of("S_VISA"));
        UUID id = UUID.randomUUID();
        when(instances.findById(id)).thenReturn(Optional.of(instance(definition, "S_VISA")));
        when(steps.findByDefinitionIdAndStepKey(definition.getId(), "S_VISA"))
                .thenReturn(Optional.of(current));

        assertThatThrownBy(() -> authorization.authorizeTransition(
                        id, viewer("x", Set.of("AGENT_BUREAU"), false)))
                .isInstanceOf(WorkflowForbiddenException.class);
    }

    @Test
    void transition_refusesAdminAlone() {
        WorkflowDefinition definition = definition("GIRAFE_ACTES");
        WorkflowStep current = step(definition, "S_VISA", StepType.TASK, List.of("S_VISA"));
        UUID id = UUID.randomUUID();
        when(instances.findById(id)).thenReturn(Optional.of(instance(definition, "S_VISA")));
        when(steps.findByDefinitionIdAndStepKey(definition.getId(), "S_VISA"))
                .thenReturn(Optional.of(current));

        assertThatThrownBy(() -> authorization.authorizeTransition(
                        id, viewer("admin-01", Set.of("ADMIN"), true)))
                .isInstanceOf(WorkflowForbiddenException.class);
    }

    @Test
    void transition_returns404WhenInstanceMissing() {
        UUID id = UUID.randomUUID();
        when(instances.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authorization.authorizeTransition(
                        id, viewer("v1", Set.of("S_VISA"), false)))
                .isInstanceOf(WorkflowInstanceNotFoundException.class);
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

    private static WorkflowInstance instance(WorkflowDefinition definition, String stepKey) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setDefinition(definition);
        instance.setCurrentStepKey(stepKey);
        return instance;
    }

    private static Viewer viewer(String userId, Set<String> roles, boolean admin) {
        return new Viewer(userId, roles, admin);
    }
}
