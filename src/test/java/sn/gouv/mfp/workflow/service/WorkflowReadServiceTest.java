package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.definition.WorkflowTransition;
import sn.gouv.mfp.workflow.domain.execution.InstanceStatus;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.exception.WorkflowForbiddenException;
import sn.gouv.mfp.workflow.exception.WorkflowInstanceNotFoundException;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;
import sn.gouv.mfp.workflow.service.WorkflowReadService.InstanceView;
import sn.gouv.mfp.workflow.service.WorkflowReadService.SearchCriteria;
import sn.gouv.mfp.workflow.service.WorkflowReadService.Viewer;

/**
 * Cahier de recette, section 3 — visibilité des dossiers : un seul prédicat appliqué
 * partout (initiateur, assigné, boîte collective du rôle, admin), invisible = 404,
 * pagination bornée et tri stable, recherche sans élargissement possible.
 */
@SpringBootTest
@Transactional
class WorkflowReadServiceTest {

    @Autowired
    private WorkflowReadService readService;

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

    private static final Viewer ALICE = new Viewer("alice", Set.of("R1"), false);
    private static final Viewer BOB = new Viewer("bob", Set.of("R1"), false);
    private static final Viewer DAVE = new Viewer("dave", Set.of("R2"), false);
    private static final Viewer ADMIN = new Viewer("admin", Set.of("ADMIN"), true);

    @Test
    void stranger_shouldGet404OnInstanceAndHistory() {
        UUID id = givenInstanceAt("S1", "alice");

        assertThatThrownBy(() -> readService.visibleOrThrow(id, DAVE))
                .isInstanceOf(WorkflowInstanceNotFoundException.class);
        assertThatThrownBy(() -> readService.history(id, DAVE))
                .isInstanceOf(WorkflowInstanceNotFoundException.class);
    }

    @Test
    void initiator_shouldReadInstanceAndHistory() {
        UUID id = givenInstanceAt("S1", "alice");

        assertThat(readService.visibleOrThrow(id, ALICE).getCurrentStepKey()).isEqualTo("S1");
        assertThat(readService.history(id, ALICE))
                .hasSize(1)
                .first()
                .satisfies(h -> assertThat(h.actorId()).isEqualTo("alice"));
    }

    @Test
    void assignee_shouldReadButRoleColleagueShouldNot() {
        UUID id = givenInstanceAt("S1", "alice");
        assign(id, "bob");

        assertThat(readService.visibleOrThrow(id, BOB).getAssigneeId()).isEqualTo("bob");
        // Dossier imputé : invisible pour les collègues du rôle (comportement décidé v1).
        assertThatThrownBy(() -> readService.visibleOrThrow(id, new Viewer("zoe", Set.of("R1"), false)))
                .isInstanceOf(WorkflowInstanceNotFoundException.class);
        // L'initiateur garde l'accès.
        assertThat(readService.visibleOrThrow(id, ALICE).getId()).isEqualTo(id);
    }

    @Test
    void poolMember_shouldSeeUnassignedDossierInInbox() {
        UUID id = givenInstanceAt("S1", "alice");

        assertThat(readService.visibleOrThrow(id, BOB).getId()).isEqualTo(id);
        Page<WorkflowInstance> inbox = readService.search(
                BOB, new SearchCriteria(InstanceView.INBOX, null, null, null, null),
                PageRequest.of(0, 20));
        assertThat(inbox.getContent()).extracting(WorkflowInstance::getId).contains(id);
    }

    @Test
    void admin_shouldSeeEverything() {
        UUID id = givenInstanceAt("S2", "alice");

        assertThat(readService.visibleOrThrow(id, ADMIN).getId()).isEqualTo(id);
        assertThat(readService.history(id, ADMIN)).isNotEmpty();
    }

    @Test
    void tamperedInitiatorFilter_shouldNotWidenScope() {
        UUID id = givenInstanceAt("S2", "alice");

        Page<WorkflowInstance> plain = readService.search(
                DAVE, new SearchCriteria(InstanceView.INBOX, null, null, null, null),
                PageRequest.of(0, 20));
        Page<WorkflowInstance> tampered = readService.search(
                DAVE,
                new SearchCriteria(InstanceView.INBOX, null, null, null, "alice"),
                PageRequest.of(0, 20));

        // DAVE (R2) voit le dossier via sa boîte, pas via le filtre trafiqué : identique.
        assertThat(plain.getContent()).extracting(WorkflowInstance::getId).contains(id);
        assertThat(tampered.getContent()).extracting(WorkflowInstance::getId).contains(id);

        // ... et ce filtre ne lui ouvre rien d'autre : dossier hors boîte reste invisible.
        Page<WorkflowInstance> other = readService.search(
                DAVE,
                new SearchCriteria(InstanceView.INBOX, null, null, null, "zoe"),
                PageRequest.of(0, 20));
        assertThat(other.getContent()).extracting(WorkflowInstance::getId).contains(id);
    }

    @Test
    void adminInitiatorFilter_shouldApply() {
        UUID id = givenInstanceAt("S1", "alice");

        Page<WorkflowInstance> filtered = readService.search(
                ADMIN,
                new SearchCriteria(InstanceView.ALL, null, null, null, "alice"),
                PageRequest.of(0, 20));

        assertThat(filtered.getContent()).extracting(WorkflowInstance::getId).contains(id);
    }

    @Test
    void viewAll_shouldBeForbiddenForNonAdmin() {
        assertThatThrownBy(() -> readService.search(
                        BOB, new SearchCriteria(InstanceView.ALL, null, null, null, null),
                        PageRequest.of(0, 20)))
                .isInstanceOf(WorkflowForbiddenException.class);
    }

    @Test
    void pageSize_shouldBeClampedAndOrderStable() {
        String key = givenCircuit("PAGE_" + unique());
        Instant base = Instant.now().minusSeconds(3600);
        for (int i = 0; i < 3; i++) {
            WorkflowInstance instance =
                    executionService.startWorkflow(key, "alice", null, "S1");
            instance.setCreatedAt(base.plusSeconds(i * 60));
            instances.save(instance);
        }

        SearchCriteria criteria = new SearchCriteria(InstanceView.MINE, null, null, null, null);
        Page<WorkflowInstance> huge = readService.search(ALICE, criteria, PageRequest.of(0, 100000));
        assertThat(huge.getPageable().getPageSize()).isLessThanOrEqualTo(100);
        assertThat(huge.getTotalElements()).isEqualTo(3);

        Page<WorkflowInstance> first = readService.search(ALICE, criteria, PageRequest.of(0, 2));
        Page<WorkflowInstance> second = readService.search(ALICE, criteria, PageRequest.of(1, 2));
        assertThat(first.getContent()).hasSize(2);
        assertThat(second.getContent()).hasSize(1);
        assertThat(first.getContent()).doesNotContain(second.getContent().get(0));
        // Tri stable : le plus récent d'abord.
        assertThat(first.getContent().get(0).getCreatedAt())
                .isAfterOrEqualTo(first.getContent().get(1).getCreatedAt());
        // Relecture identique.
        Page<WorkflowInstance> replay = readService.search(ALICE, criteria, PageRequest.of(0, 2));
        assertThat(replay.getContent()).isEqualTo(first.getContent());
    }

    @Test
    void searchFilters_shouldNarrow() {
        String keyA = givenCircuit("FILT_A_" + unique());
        String keyB = givenCircuit("FILT_B_" + unique());
        executionService.startWorkflow(keyA, "alice", null, "S1");
        WorkflowInstance atStep = executionService.startWorkflow(keyB, "alice", null, "S1");
        executionService.executeTransition(atStep.getId(), "GO", "alice", null, null);
        WorkflowInstance done = executionService.startWorkflow(keyB, "alice", null, "S1");
        executionService.executeTransition(done.getId(), "GO", "alice", null, null);
        executionService.executeTransition(done.getId(), "GO", "alice", null, null);

        SearchCriteria byKey =
                new SearchCriteria(InstanceView.MINE, keyB, null, null, null);
        assertThat(readService.search(ALICE, byKey, PageRequest.of(0, 20)).getContent())
                .extracting(i -> i.getDefinition().getKey())
                .containsOnly(keyB);

        SearchCriteria byStep =
                new SearchCriteria(InstanceView.MINE, null, null, "S2", null);
        assertThat(readService.search(ALICE, byStep, PageRequest.of(0, 20)).getContent())
                .extracting(WorkflowInstance::getCurrentStepKey)
                .containsOnly("S2");

        SearchCriteria completed =
                new SearchCriteria(InstanceView.MINE, null, InstanceStatus.COMPLETED, null, null);
        assertThat(readService.search(ALICE, completed, PageRequest.of(0, 20)).getContent())
                .extracting(WorkflowInstance::getStatus)
                .containsOnly(InstanceStatus.COMPLETED);
    }

    @Test
    void actions_shouldListStepActionsAndBeEmptyWhenCompleted() {
        UUID active = givenInstanceAt("S1", "alice");
        assertThat(readService.possibleActions(active, ALICE))
                .extracting(WorkflowReadService.ActionOption::action)
                .containsExactly("GO");

        UUID done = givenInstanceAt("S1", "alice");
        executionService.executeTransition(done, "GO", "alice", null, null);
        executionService.executeTransition(done, "GO", "alice", null, null);
        assertThat(instances.findById(done).orElseThrow().getStatus())
                .isEqualTo(InstanceStatus.COMPLETED);
        assertThat(readService.possibleActions(done, ALICE)).isEmpty();
    }

    @Test
    void roleOfTraversedStep_shouldReadActiveUnassignedDossier() {
        String key = "TRAV_" + unique();
        givenCircuit(key);
        WorkflowInstance instance = executionService.startWorkflow(key, "other-agent", null, "S1");
        WorkflowInstance advanced =
                executionService.executeTransition(instance.getId(), "GO", "other-agent", null, null);
        assertThat(advanced.getCurrentStepKey()).isEqualTo("S2");

        // BOB (R1) ne possède pas l'étape courante S2 (R2) mais a traversé S1 : lecture permise.
        assertThat(readService.visibleOrThrow(advanced.getId(), BOB).getId())
                .isEqualTo(advanced.getId());
        // DAVE (R2) possède l'étape courante : lecture permise.
        assertThat(readService.visibleOrThrow(advanced.getId(), DAVE).getId())
                .isEqualTo(advanced.getId());
        // Un rôle étranger reste exclu (404, aucune fuite d'existence).
        assertThatThrownBy(() -> readService.visibleOrThrow(
                        advanced.getId(), new Viewer("zoe", Set.of("R9"), false)))
                .isInstanceOf(WorkflowInstanceNotFoundException.class);
    }

    // --- Aides ---

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Instance à l'étape voulue, initiée par le matricule donné (circuit S1 -GO-> S2 -GO-> FIN). */
    private UUID givenInstanceAt(String step, String initiator) {
        String key = "READ_" + unique();
        givenCircuit(key);
        WorkflowInstance instance = executionService.startWorkflow(key, initiator, null, "S1");
        while (!instance.getCurrentStepKey().equals(step)) {
            instance = executionService.executeTransition(
                    instance.getId(), "GO", initiator, null, null);
        }
        return instance.getId();
    }

    private void assign(UUID instanceId, String assignee) {
        WorkflowInstance instance = instances.findById(instanceId).orElseThrow();
        instance.setAssigneeId(assignee);
        instances.save(instance);
    }

    /**
     * Circuit S1(START, responsables R1) -GO-> S2(TASK, responsables R2) -GO-> FIN(END).
     */
    private String givenCircuit(String key) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(key);
        definition.setVersion(1);
        definition.setName("Circuit lecture " + key);
        definition = definitions.save(definition);

        WorkflowStep s1 = newStep(definition, "S1", StepType.START, List.of("R1"));
        WorkflowStep s2 = newStep(definition, "S2", StepType.TASK, List.of("R2"));
        newStep(definition, "FIN", StepType.END, null);
        newTransition(s1, "GO", "S2");
        newTransition(s2, "GO", "FIN");
        return key;
    }

    private WorkflowStep newStep(
            WorkflowDefinition definition, String stepKey, StepType type, List<String> owners) {
        WorkflowStep step = new WorkflowStep();
        step.setDefinition(definition);
        step.setStepKey(stepKey);
        step.setType(type);
        step.setOwners(owners);
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
