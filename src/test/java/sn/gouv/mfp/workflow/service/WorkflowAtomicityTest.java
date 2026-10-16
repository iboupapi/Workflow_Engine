package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.definition.WorkflowTransition;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowHistoryRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;

/**
 * Cahier de recette 8.8 — atomicité : l'écriture de l'historique et le changement d'étape
 * partagent la même transaction. Si l'unité de travail échoue, ni le dossier ni
 * l'historique ne sont persistés (tout ou rien).
 */
@SpringBootTest
class WorkflowAtomicityTest {

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void rolledBackUnitOfWork_shouldPersistNeitherInstanceNorHistory() {
        String key = "ATOM_" + UUID.randomUUID().toString().substring(0, 8);
        givenCircuit(key);
        TransactionTemplate write = new TransactionTemplate(transactionManager);

        UUID id = write.execute(status -> {
            WorkflowInstance instance = executionService.startWorkflow(
                    key, "agent-atom", Map.of("circuit", "COURT"), "SRC");
            executionService.executeTransition(
                    instance.getId(), "VALIDER", "agent-atom", null, null);
            status.setRollbackOnly();
            return instance.getId();
        });

        TransactionTemplate read = new TransactionTemplate(transactionManager);
        Boolean instanceExists = read.execute(status -> instances.findById(id).isPresent());
        Boolean historyExists = read.execute(
                status -> !histories.findByInstanceIdOrderByTimestampAsc(id).isEmpty());
        assertThat(instanceExists).isFalse();
        assertThat(historyExists).isFalse();
    }

    @Test
    void committedUnitOfWork_shouldPersistBothTogether() {
        String key = "ATOM_OK_" + UUID.randomUUID().toString().substring(0, 8);
        givenCircuit(key);
        TransactionTemplate write = new TransactionTemplate(transactionManager);

        UUID id = write.execute(status -> {
            WorkflowInstance instance = executionService.startWorkflow(
                    key, "agent-atom", Map.of("circuit", "COURT"), "SRC");
            executionService.executeTransition(
                    instance.getId(), "VALIDER", "agent-atom", null, null);
            return instance.getId();
        });

        TransactionTemplate read = new TransactionTemplate(transactionManager);
        Boolean persisted = read.execute(status -> instances.findById(id).isPresent());
        Integer historySize = read.execute(
                status -> histories.findByInstanceIdOrderByTimestampAsc(id).size());
        assertThat(persisted).isTrue();
        assertThat(historySize).isEqualTo(2);
    }

    private void givenCircuit(String key) {
        TransactionTemplate setup = new TransactionTemplate(transactionManager);
        setup.executeWithoutResult(status -> {
            WorkflowDefinition definition = new WorkflowDefinition();
            definition.setKey(key);
            definition.setVersion(1);
            definition.setName("Circuit atomicité " + key);
            definitions.save(definition);

            WorkflowStep start = new WorkflowStep();
            start.setDefinition(definition);
            start.setStepKey("SRC");
            start.setType(StepType.START);
            steps.save(start);

            WorkflowStep end = new WorkflowStep();
            end.setDefinition(definition);
            end.setStepKey("FIN");
            end.setType(StepType.END);
            steps.save(end);

            WorkflowTransition transition = new WorkflowTransition();
            transition.setSourceStep(start);
            transition.setAction("VALIDER");
            transition.setTargetStepKey("FIN");
            transitions.save(transition);
        });
    }
}
