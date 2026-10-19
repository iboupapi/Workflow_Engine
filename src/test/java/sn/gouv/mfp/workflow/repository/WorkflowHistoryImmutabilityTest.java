package sn.gouv.mfp.workflow.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.definition.WorkflowTransition;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.service.WorkflowExecutionService;

/**
 * Cahier de recette, section 8 — immuabilité de l'historique sous le compte applicatif :
 * UPDATE et DELETE rejetés (privilèges + déclencheur), catalogue de droits conforme,
 * déclencheur présent et discriminant.
 */
@SpringBootTest
class WorkflowHistoryImmutabilityTest {

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
    private JdbcTemplate jdbc;

    @Test
    @Transactional
    void appPrivileges_shouldBeLeastPrivilege() {
        assertThat(privilegesOf("workflow_history")).containsExactlyInAnyOrder("INSERT", "SELECT");
        assertThat(privilegesOf("workflow_import_audit"))
                .containsExactlyInAnyOrder("INSERT", "SELECT");
        assertThat(privilegesOf("workflow_instance"))
                .containsExactlyInAnyOrder("INSERT", "SELECT", "UPDATE");
        assertThat(privilegesOf("workflow_definition"))
                .containsExactlyInAnyOrder("INSERT", "SELECT");
        assertThat(privilegesOf("request_type"))
                .containsExactlyInAnyOrder("INSERT", "SELECT", "UPDATE");
        assertThat(privilegesOf("request_type_version"))
                .containsExactlyInAnyOrder("INSERT", "SELECT");
        assertThat(privilegesOf("request_submission"))
                .containsExactlyInAnyOrder("INSERT", "SELECT", "UPDATE");
    }

    @Test
    @Transactional
    void immutabilityTrigger_shouldExistOnProtectedTables() {
        Number count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_immutable'", Number.class);
        assertThat(count.longValue()).isEqualTo(6);
        String body = jdbc.queryForObject(
                "SELECT pg_get_functiondef('forbid_protected_mutation()'::regprocedure)",
                String.class);
        assertThat(body).contains("workflow_owner").contains("RAISE EXCEPTION");
    }

    @Test
    void appUpdateOnHistory_shouldBeRejectedAndRowKept() {
        UUID historyId = givenCompletedTransition();

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE workflow_history SET comment = 'pirate' WHERE id = ?", historyId))
                .isInstanceOf(DataAccessException.class);
        assertThat(histories.findById(historyId)).isPresent();
    }

    @Test
    void appDeleteOnHistory_shouldBeRejectedAndRowKept() {
        UUID historyId = givenCompletedTransition();

        assertThatThrownBy(
                        () -> jdbc.update("DELETE FROM workflow_history WHERE id = ?", historyId))
                .isInstanceOf(DataAccessException.class);
        assertThat(histories.findById(historyId)).isPresent();
    }

    private List<String> privilegesOf(String table) {
        return jdbc.queryForList(
                "SELECT privilege_type FROM information_schema.role_table_grants "
                        + "WHERE grantee = 'workflow_app' AND table_name = ?",
                String.class,
                table);
    }

    /** Mini-circuit commité (contexte non transactionnel) avec une transition exécutée. */
    private UUID givenCompletedTransition() {
        String key = "IMMUT_" + UUID.randomUUID().toString().substring(0, 8);
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(key);
        definition.setVersion(1);
        definition.setName("Circuit immuabilité " + key);
        definition = definitions.save(definition);

        WorkflowStep start = new WorkflowStep();
        start.setDefinition(definition);
        start.setStepKey("SRC");
        start.setType(StepType.START);
        start = steps.save(start);

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

        WorkflowInstance instance =
                executionService.startWorkflow(key, "agent-immut", null, "SRC");
        executionService.executeTransition(instance.getId(), "VALIDER", "agent-immut", null, null);
        return histories.findByInstanceIdOrderByTimestampAsc(instance.getId()).stream()
                .filter(h -> "VALIDER".equals(h.getAction()))
                .findFirst()
                .orElseThrow()
                .getId();
    }
}
