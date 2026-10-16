package sn.gouv.mfp.workflow.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.service.WorkflowReadService.Viewer;

/**
 * Contrôle d'accès à l'<strong>initiation</strong> d'un dossier (cahier de recette, point 2) :
 * {@code /start} n'est permis qu'à une identité dont un rôle est responsable de l'étape de
 * départ effective.
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

    public WorkflowAuthorizationService(WorkflowExecutionService executionService) {
        this.executionService = executionService;
    }

    /** Autorise l'initiation d'un dossier sur l'étape de départ effective, sinon 403. */
    public void authorizeStart(String definitionKey, String startStepKey, Viewer viewer) {
        WorkflowDefinition definition = executionService.latestDefinition(definitionKey);
        WorkflowStep startStep = executionService.resolveStartStep(definition, startStepKey);
        StepRoleAuthority.require(startStep, viewer.roles(), "initier un dossier à l'étape");
    }
}
