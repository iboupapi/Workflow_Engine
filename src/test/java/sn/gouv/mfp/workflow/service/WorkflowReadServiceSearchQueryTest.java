package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.execution.InstanceStatus;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.repository.WorkflowHistoryRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;
import sn.gouv.mfp.workflow.service.WorkflowReadService.InstanceView;
import sn.gouv.mfp.workflow.service.WorkflowReadService.SearchCriteria;
import sn.gouv.mfp.workflow.service.WorkflowReadService.Viewer;

/**
 * Point 2 (relecture P1b) : la recherche ne doit PAS rejouer une requête d'étapes (ni
 * d'historique) par instance. Les {@code owners} de la définition sont chargés une seule
 * fois et la liste ne retient que l'étape courante.
 */
class WorkflowReadServiceSearchQueryTest {

    private final WorkflowInstanceRepository instances =
            Mockito.mock(WorkflowInstanceRepository.class);
    private final WorkflowHistoryRepository histories =
            Mockito.mock(WorkflowHistoryRepository.class);
    private final WorkflowStepRepository steps = Mockito.mock(WorkflowStepRepository.class);
    private final WorkflowTransitionRepository transitions =
            Mockito.mock(WorkflowTransitionRepository.class);
    private final WorkflowReadService readService =
            new WorkflowReadService(instances, histories, steps, transitions);

    @Test
    void search_loadsOwnersOnceAndNoHistory_perCandidate() {
        UUID definitionId = UUID.randomUUID();
        WorkflowDefinition definition = Mockito.mock(WorkflowDefinition.class);
        when(definition.getId()).thenReturn(definitionId);

        when(steps.findByDefinitionId(definitionId)).thenReturn(List.of(
                step(definition, "S1", List.of("AGENT_BUREAU")),
                step(definition, "S2", List.of("CHEF_DIVISION"))));

        List<WorkflowInstance> actives = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            actives.add(activeInstance(definition, "S1", "initiateur-" + i));
        }
        when(instances.findByStatus(InstanceStatus.ACTIVE)).thenReturn(actives);

        Page<WorkflowInstance> page = readService.search(
                new Viewer("agent-r1", Set.of("AGENT_BUREAU"), false),
                new SearchCriteria(InstanceView.INBOX, null, null, null, null),
                PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(3);
        // Une seule requête d'étapes pour la définition, quelle que soit la masse d'instances.
        verify(steps, times(1)).findByDefinitionId(definitionId);
        // La liste ne touche JAMAIS l'historique (pas de N+1).
        verify(histories, never()).findByInstanceIdOrderByTimestampAsc(Mockito.any());
    }

    private static WorkflowStep step(WorkflowDefinition definition, String key, List<String> owners) {
        WorkflowStep step = new WorkflowStep();
        step.setDefinition(definition);
        step.setStepKey(key);
        step.setType(StepType.TASK);
        step.setOwners(owners);
        return step;
    }

    private static WorkflowInstance activeInstance(
            WorkflowDefinition definition, String stepKey, String initiatorId) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId(UUID.randomUUID());
        instance.setDefinition(definition);
        instance.setCurrentStepKey(stepKey);
        instance.setInitiatorId(initiatorId);
        instance.setStatus(InstanceStatus.ACTIVE);
        instance.setCreatedAt(java.time.Instant.now());
        return instance;
    }
}
