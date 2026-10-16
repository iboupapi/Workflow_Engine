package sn.gouv.mfp.workflow;

import static org.assertj.core.api.Assertions.assertThat;

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
import sn.gouv.mfp.workflow.domain.execution.WorkflowHistory;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowHistoryRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;

/**
 * Étape 1 — test d'acceptation du socle de données : un dossier portant des variables
 * fictives est persisté en JSONB puis relu avec succès, avec sa définition et son historique.
 */
@SpringBootTest
@Transactional
class WorkflowPersistenceTest {

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
    void shouldPersistAndReloadInstanceWithJsonbVariables() {
        String key = "E_SERVICE_DEMANDE_IT_" + UUID.randomUUID().toString().substring(0, 8);

        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(key);
        definition.setVersion(1);
        definition.setName("Demande e-service (test)");
        definition = definitions.save(definition);

        WorkflowStep start = new WorkflowStep();
        start.setDefinition(definition);
        start.setStepKey("S_VISA");
        start.setType(StepType.START);
        start = steps.save(start);

        WorkflowStep end = new WorkflowStep();
        end.setDefinition(definition);
        end.setStepKey("DGFP");
        end.setType(StepType.END);
        steps.save(end);

        WorkflowTransition transition = new WorkflowTransition();
        transition.setSourceStep(start);
        transition.setTargetStepKey("DGFP");
        transition.setAction("VALIDER");
        transition.setConditionExpression("#variables['circuit'] == 'COURT'");
        transitions.save(transition);

        Map<String, Object> variables = new HashMap<>();
        variables.put("natureActe", "DECISION");
        variables.put("numeroOfficiel", "2026-TEST-001");
        variables.put("circuit", "COURT");
        variables.put("demandeur", Map.of("matricule", "SN-999", "nom", "Testeur"));

        WorkflowInstance instance = new WorkflowInstance();
        instance.setDefinition(definition);
        instance.setCurrentStepKey("S_VISA");
        instance.setStatus(InstanceStatus.ACTIVE);
        instance.setVariables(variables);
        instance = instances.save(instance);

        WorkflowHistory history = new WorkflowHistory();
        history.setInstance(instance);
        history.setAction("VALIDER");
        history.setFromStep("S_VISA");
        history.setToStep("DGFP");
        history.setActorId("agent-007");
        history.setComment(null);
        histories.save(history);

        instances.flush();

        WorkflowInstance reloaded = instances.findById(instance.getId()).orElseThrow();
        assertThat(reloaded.getCurrentStepKey()).isEqualTo("S_VISA");
        assertThat(reloaded.getStatus()).isEqualTo(InstanceStatus.ACTIVE);
        assertThat(reloaded.getVariables())
                .containsEntry("natureActe", "DECISION")
                .containsEntry("numeroOfficiel", "2026-TEST-001")
                .containsEntry("circuit", "COURT");
        assertThat(reloaded.getVariables()).containsKey("demandeur");

        assertThat(histories.findByInstanceIdOrderByTimestampAsc(instance.getId()))
                .hasSize(1)
                .first()
                .satisfies(h -> {
                    assertThat(h.getAction()).isEqualTo("VALIDER");
                    assertThat(h.getActorId()).isEqualTo("agent-007");
                    assertThat(h.getTimestamp()).isNotNull();
                });

        assertThat(steps.findByDefinitionIdAndStepKey(definition.getId(), "DGFP")).isPresent();
        assertThat(transitions.findBySourceStepIdAndAction(start.getId(), "VALIDER")).hasSize(1);
    }
}
