package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.definition.WorkflowTransition;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.exception.WorkflowConflictException;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowHistoryRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;

/**
 * Étape 3 — test de concurrence : deux validations du même dossier envoyées simultanément.
 * Le verrou pessimiste sérialise les transactions : la première termine le dossier,
 * la seconde est rejetée par la garde d'idempotence (409), sans corruption d'état.
 * <p>
 * Volontairement sans {@code @Transactional} : chaque thread doit engager sa propre transaction.
 */
@SpringBootTest
class WorkflowConcurrencyTest {

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
    void twoSimultaneousValidations_onlyOneShouldSucceed() throws Exception {
        String key = "RACE_" + UUID.randomUUID().toString().substring(0, 8);
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(key);
        definition.setVersion(1);
        definition.setName("Circuit course " + key);
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
        transitions.save(valider);

        WorkflowInstance instance = executionService.startWorkflow(
                key, "agent-001", Map.of("circuit", "COURT"));
        UUID instanceId = instance.getId();

        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> attempt(gate, instanceId, successes, conflicts));
            Future<?> second = pool.submit(() -> attempt(gate, instanceId, successes, conflicts));
            gate.countDown(); // libère les deux threads au même moment
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        // INITIATION + une seule VALIDER : pas de double validation.
        assertThat(histories.findByInstanceIdOrderByTimestampAsc(instanceId)).hasSize(2);
        assertThat(instances.findById(instanceId).orElseThrow().getCurrentStepKey())
                .isEqualTo("DGFP");
    }

    private void attempt(
            CountDownLatch gate, UUID instanceId, AtomicInteger successes, AtomicInteger conflicts) {
        try {
            gate.await(30, TimeUnit.SECONDS);
            executionService.executeTransition(instanceId, "VALIDER", "agent-concurrent", null, null);
            successes.incrementAndGet();
        } catch (WorkflowConflictException e) {
            conflicts.incrementAndGet();
        } catch (Exception e) {
            throw new IllegalStateException("Échec inattendu de la transition concurrente", e);
        }
    }
}
