package sn.gouv.mfp.workflow.service;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.EntryPoint;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.exception.WorkflowForbiddenException;
import sn.gouv.mfp.workflow.service.WorkflowReadService.Viewer;

/**
 * Contrôle d'accès à l'<strong>initiation</strong> d'un dossier (cahier de recette, point 2 et MOT-04) :
 * {@code /start} n'est permis qu'à une identité dont un rôle est responsable de l'étape de
 * départ effective, et dont la condition d'éligibilité du point d'entrée est satisfaite.
 * <p>
 * Le contrôle de <strong>transition</strong> est réalisé, quant à lui, sous le verrou
 * pessimiste dans {@link WorkflowExecutionService} (atomicité contrôle/action). Ce service
 * ne porte que la vérification d'initiation, où aucune ligne n'existe encore.
 */
@Service
@Transactional(readOnly = true)
public class WorkflowAuthorizationService {

    /** Rôle d'administration : administre et supervise, ne valide jamais une étape. */
    public static final String ADMIN_ROLE = StepRoleAuthority.ADMIN_ROLE;

    private final WorkflowExecutionService executionService;
    private final ConditionEvaluatorService conditionEvaluator;

    public WorkflowAuthorizationService(
            WorkflowExecutionService executionService,
            ConditionEvaluatorService conditionEvaluator) {
        this.executionService = executionService;
        this.conditionEvaluator = conditionEvaluator;
    }

    /** Autorise l'initiation d'un dossier sur l'étape de départ effective, sinon 403. */
    public void authorizeStart(String definitionKey, String startStepKey, Viewer viewer) {
        authorizeStart(definitionKey, startStepKey, viewer, Map.of());
    }

    /** Autorise l'initiation avec évaluation des conditions SpEL du point d'entrée (MOT-04). */
    public void authorizeStart(
            String definitionKey,
            String startStepKey,
            Viewer viewer,
            Map<String, Object> variables) {
        WorkflowDefinition definition = executionService.latestDefinition(definitionKey);
        WorkflowStep startStep = executionService.resolveStartStep(definition, startStepKey);
        StepRoleAuthority.require(startStep, viewer.roles(), "initier un dossier à l'étape");

        // MOT-04 : vérification des conditions SpEL sur les points d'entrée
        if (definition.getEntryPoints() != null) {
            for (EntryPoint ep : definition.getEntryPoints()) {
                if (startStep.getStepKey().equals(ep.startStepKey())
                        && ep.condition() != null && !ep.condition().isBlank()) {
                    if (viewer.roles().contains(ep.role())) {
                        Boolean satisfied = conditionEvaluator.evaluateStrict(ep.condition(), variables);
                        if (!Boolean.TRUE.equals(satisfied)) {
                            throw new WorkflowForbiddenException(
                                    "Condition d'accès au point d'entrée '" + startStep.getStepKey()
                                            + "' non satisfaite pour le rôle '" + ep.role() + "'.");
                        }
                    }
                }
            }
        }
    }
}
