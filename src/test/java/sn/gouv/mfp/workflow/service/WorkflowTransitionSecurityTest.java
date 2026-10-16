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
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.exception.ConditionEvaluationException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowHistoryRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;

/**
 * Cahier de recette 1.7 / 1.9 — comportement à l'exécution face aux conditions en erreur,
 * ordre d'évaluation, et confinement du contexte SpEL (même si une condition dangereuse
 * contournait l'import, aucune écriture ni exécution n'est possible).
 */
@SpringBootTest
@Transactional
class WorkflowTransitionSecurityTest {

    @Autowired
    private WorkflowExecutionService executionService;

    @Autowired
    private ConditionEvaluatorService evaluator;

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
    void evaluationError_shouldRefuseActionAndNeverTakeDefault() {
        // SRC -VALIDER(erronée)-> ERREUR et SRC -VALIDER(sans condition)-> DEFAUT.
        // La condition déréférence une clé absente : elle est en erreur, pas fausse.
        String key = "ERR_" + unique();
        WorkflowDefinition definition = newDefinition(key);
        WorkflowStep src = newStep(definition, "SRC", StepType.START);
        newStep(definition, "ERREUR", StepType.TASK);
        newStep(definition, "DEFAUT", StepType.END);
        newTransition(src, "VALIDER", "ERREUR", "#variables['absent'].length() > 1");
        newTransition(src, "VALIDER", "DEFAUT", null);

        WorkflowInstance instance =
                executionService.startWorkflow(key, "agent-001", Map.of(), "SRC");

        assertThatThrownBy(() -> executionService.executeTransition(
                        instance.getId(), "VALIDER", "agent-002", null, null))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Évaluation de la condition impossible");

        // Dossier inchangé : toujours à SRC, sans ligne VALIDER, sans bascule vers DEFAUT.
        assertThat(instances.findById(instance.getId()).orElseThrow().getCurrentStepKey())
                .isEqualTo("SRC");
        assertThat(histories.findByInstanceIdOrderByTimestampAsc(instance.getId())).hasSize(1);
    }

    @Test
    void falseCondition_shouldFallBackToUnconditional() {
        String key = "ORD_" + unique();
        WorkflowDefinition definition = newDefinition(key);
        WorkflowStep src = newStep(definition, "SRC", StepType.START);
        newStep(definition, "CIBLE", StepType.TASK);
        newStep(definition, "DEFAUT", StepType.END);
        newTransition(src, "VALIDER", "CIBLE", "#variables['circuit'] == 'COURT'");
        newTransition(src, "VALIDER", "DEFAUT", null);

        WorkflowInstance instance = executionService.startWorkflow(
                key, "agent-001", Map.of("circuit", "LONG"), "SRC");
        WorkflowInstance advanced = executionService.executeTransition(
                instance.getId(), "VALIDER", "agent-002", null, null);

        assertThat(advanced.getCurrentStepKey()).isEqualTo("DEFAUT");
    }

    @Test
    void runtimeAttack_shouldBeContainedWithoutEffect() {
        Map<String, Object> variables = new HashMap<>(Map.of("circuit", "COURT"));

        assertThat(evaluator.evaluate("T(java.lang.Math).random() > 0", variables)).isFalse();
        assertThatThrownBy(() -> evaluator.evaluateStrict(
                        "T(java.lang.Math).random() > 0", variables))
                .isInstanceOf(ConditionEvaluationException.class);

        // Tentative d'écriture : bloquée, variables intactes (recette 1.3, second volet).
        assertThat(evaluator.evaluate("#variables.put('role','ADMIN')", variables)).isFalse();
        assertThat(variables).doesNotContainKey("role");
        assertThat(evaluator.evaluate("#variables['x'] = 1", variables)).isFalse();
        assertThat(variables).doesNotContainKey("x");
    }

    @Test
    void strictMode_shouldEvaluateLegitConditions() {
        assertThat(evaluator.evaluateStrict(
                        "#variables['circuit'] == 'COURT'", Map.of("circuit", "COURT")))
                .isTrue();
        assertThat(evaluator.evaluateStrict(
                        "#variables['circuit'] == 'COURT'", Map.of("circuit", "LONG")))
                .isFalse();
        assertThat(evaluator.evaluateStrict(null, Map.of())).isTrue();
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private WorkflowDefinition newDefinition(String key) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(key);
        definition.setVersion(1);
        definition.setName("Circuit sécurité " + key);
        return definitions.save(definition);
    }

    private WorkflowStep newStep(WorkflowDefinition definition, String stepKey, StepType type) {
        WorkflowStep step = new WorkflowStep();
        step.setDefinition(definition);
        step.setStepKey(stepKey);
        step.setType(type);
        return steps.save(step);
    }

    private void newTransition(WorkflowStep source, String action, String target, String condition) {
        WorkflowTransition transition = new WorkflowTransition();
        transition.setSourceStep(source);
        transition.setAction(action);
        transition.setTargetStepKey(target);
        transition.setConditionExpression(condition);
        transitions.save(transition);
    }
}
