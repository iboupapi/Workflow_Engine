package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.EntryPoint;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.definition.WorkflowTransition;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.exception.WorkflowForbiddenException;
import sn.gouv.mfp.workflow.exception.WorkflowInstanceNotFoundException;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;
import sn.gouv.mfp.workflow.service.WorkflowReadService.Viewer;

/**
 * Tests de validation du cloisonnement par périmètre organisationnel (SEC-04)
 * et des points d'entrée conditionnels (MOT-04).
 */
@SpringBootTest
@Transactional
class WorkflowPerimeterScopingTest {

    @Autowired
    private WorkflowExecutionService executionService;

    @Autowired
    private WorkflowReadService readService;

    @Autowired
    private WorkflowAuthorizationService authorizationService;

    @Autowired
    private WorkflowDefinitionRepository definitions;

    @Autowired
    private WorkflowStepRepository steps;

    @Autowired
    private WorkflowTransitionRepository transitions;

    @Autowired
    private WorkflowInstanceRepository instances;

    @Test
    void readInstance_withMatchingDirectionAndBureau_shouldBeVisible() {
        String key = "SCOPE_OK_" + UUID.randomUUID().toString().substring(0, 8);
        WorkflowDefinition definition = createCircuit(key);

        WorkflowInstance instance = executionService.startWorkflow(
                key, "initiateur-01", null, "ETAPE_INIT", "DGFP", "BUREAU_SOLDE");

        Viewer viewer = new Viewer(
                "agent-01", Set.of("AGENT_SOLDE"), false, false, "DGFP", "BUREAU_SOLDE");

        WorkflowInstance reloaded = readService.visibleOrThrow(instance.getId(), viewer);
        assertThat(reloaded).isNotNull();
    }

    @Test
    void readInstance_withDifferentDirectionOrBureau_shouldReturn404() {
        String key = "SCOPE_DIFF_" + UUID.randomUUID().toString().substring(0, 8);
        createCircuit(key);

        WorkflowInstance instance = executionService.startWorkflow(
                key, "initiateur-01", null, "ETAPE_INIT", "DGFP", "BUREAU_SOLDE");

        // Même rôle AGENT_SOLDE, mais bureau différent
        Viewer viewerOtherBureau = new Viewer(
                "agent-02", Set.of("AGENT_SOLDE"), false, false, "DGFP", "BUREAU_RECRUTEMENT");

        assertThatThrownBy(() -> readService.visibleOrThrow(instance.getId(), viewerOtherBureau))
                .isInstanceOf(WorkflowInstanceNotFoundException.class);

        // Même rôle, mais direction différente
        Viewer viewerOtherDirection = new Viewer(
                "agent-03", Set.of("AGENT_SOLDE"), false, false, "DGTCP", "BUREAU_SOLDE");

        assertThatThrownBy(() -> readService.visibleOrThrow(instance.getId(), viewerOtherDirection))
                .isInstanceOf(WorkflowInstanceNotFoundException.class);
    }

    @Test
    void readInstance_withAuditorRole_shouldBypassPerimeter() {
        String key = "SCOPE_AUDIT_" + UUID.randomUUID().toString().substring(0, 8);
        createCircuit(key);

        WorkflowInstance instance = executionService.startWorkflow(
                key, "initiateur-01", null, "ETAPE_INIT", "DGFP", "BUREAU_SOLDE");

        // Auditeur sans périmètre
        Viewer auditor = new Viewer("auditeur-01", Set.of("AUDITEUR"), false, true, null, null);

        WorkflowInstance reloaded = readService.visibleOrThrow(instance.getId(), auditor);
        assertThat(reloaded).isNotNull();
    }

    @Test
    void actionOnInstance_withDifferentPerimeter_shouldThrowForbidden() {
        String key = "SCOPE_ACT_" + UUID.randomUUID().toString().substring(0, 8);
        createCircuit(key);

        WorkflowInstance instance = executionService.startWorkflow(
                key, "initiateur-01", null, "ETAPE_INIT", "DGFP", "BUREAU_SOLDE");

        assertThatThrownBy(() -> executionService.executeTransition(
                instance.getId(),
                "VALIDER",
                "agent-autre-bureau",
                "Commentaire",
                null,
                Set.of("AGENT_SOLDE"),
                "DGFP",
                "AUTRE_BUREAU"))
                .isInstanceOf(WorkflowForbiddenException.class);
    }

    @Test
    void conditionalEntryPoint_whenConditionSatisfied_shouldAuthorize() {
        String key = "COND_EP_OK_" + UUID.randomUUID().toString().substring(0, 8);
        // Point d'entrée conditionnel : retraite (posé à l'insertion, la définition est immuable).
        EntryPoint ep = new EntryPoint("AGENT_SOLDE", "ETAPE_INIT", "#variables['typeActe'] == 'RETRAITE'");
        createCircuit(key, List.of(ep));

        Viewer viewer = new Viewer("agent-01", Set.of("AGENT_SOLDE"), false, false, "DGFP", "BUREAU_SOLDE");

        // Avec typeActe == RETRAITE : autorisé
        authorizationService.authorizeStart(key, "ETAPE_INIT", viewer, Map.of("typeActe", "RETRAITE"));
    }

    @Test
    void conditionalEntryPoint_whenConditionNotSatisfied_shouldThrowForbidden() {
        String key = "COND_EP_KO_" + UUID.randomUUID().toString().substring(0, 8);
        EntryPoint ep = new EntryPoint("AGENT_SOLDE", "ETAPE_INIT", "#variables['typeActe'] == 'RETRAITE'");
        createCircuit(key, List.of(ep));

        Viewer viewer = new Viewer("agent-01", Set.of("AGENT_SOLDE"), false, false, "DGFP", "BUREAU_SOLDE");

        // Avec typeActe == AVANCEMENT : condition non satisfaite -> refusé 403
        assertThatThrownBy(() -> authorizationService.authorizeStart(
                key, "ETAPE_INIT", viewer, Map.of("typeActe", "AVANCEMENT")))
                .isInstanceOf(WorkflowForbiddenException.class);
    }

    private WorkflowDefinition createCircuit(String key) {
        return createCircuit(key, null);
    }

    private WorkflowDefinition createCircuit(String key, List<EntryPoint> entryPoints) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(key);
        definition.setVersion(1);
        definition.setName("Circuit test périmètre " + key);
        if (entryPoints != null) {
            definition.setEntryPoints(entryPoints);
        }
        definition = definitions.save(definition);

        WorkflowStep start = new WorkflowStep();
        start.setDefinition(definition);
        start.setStepKey("ETAPE_INIT");
        start.setType(StepType.START);
        start.setOwners(List.of("AGENT_SOLDE"));
        start = steps.save(start);

        WorkflowStep end = new WorkflowStep();
        end.setDefinition(definition);
        end.setStepKey("FIN");
        end.setType(StepType.END);
        end = steps.save(end);

        WorkflowTransition t = new WorkflowTransition();
        t.setSourceStep(start);
        t.setAction("VALIDER");
        t.setTargetStepKey("FIN");
        transitions.save(t);

        return definition;
    }
}
