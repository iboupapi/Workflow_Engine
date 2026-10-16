package sn.gouv.mfp.workflow.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.exception.WorkflowForbiddenException;
import sn.gouv.mfp.workflow.exception.WorkflowInstanceNotFoundException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.service.WorkflowReadService.Viewer;

/**
 * Contrôle d'accès aux <strong>actions</strong> du moteur (cahier de recette, point 2) :
 * une action d'étape ({@code /start}, {@code /transition}) n'est permise qu'à une identité
 * dont un rôle est déclaré responsable de l'étape concernée.
 * <p>
 * Règles :
 * <ul>
 *   <li>l'étape concernée est l'étape de départ effective ({@code /start}) ou l'étape
 *   courante du dossier ({@code /transition}) ;</li>
 *   <li>ses rôles responsables proviennent du YAML versionné ({@code owners}) ;</li>
 *   <li>le rôle {@code ADMIN} n'est <strong>jamais</strong> validateur : il n'ouvre
 *   aucune action d'étape ;</li>
 *   <li>étape sans rôle responsable ou rôles disjoints : refus 403 (refus par défaut) ;</li>
 *   <li>rôle du jeton inconnu (hors correspondance de l'émetteur) : aucun droit.</li>
 * </ul>
 * Ce contrôle est placé au niveau web, avant toute mutation : les appels internes de
 * service (tests d'intégration, batch) ne le contournent pas par accident — il est
 * simplement exercé à l'entrée de l'API.
 */
@Service
@Transactional(readOnly = true)
public class WorkflowAuthorizationService {

    /** Rôle d'administration : administre et supervise, ne valide jamais une étape. */
    public static final String ADMIN_ROLE = "ADMIN";

    private final WorkflowExecutionService executionService;
    private final WorkflowStepRepository steps;
    private final WorkflowInstanceRepository instances;

    public WorkflowAuthorizationService(
            WorkflowExecutionService executionService,
            WorkflowStepRepository steps,
            WorkflowInstanceRepository instances) {
        this.executionService = executionService;
        this.steps = steps;
        this.instances = instances;
    }

    /** Autorise l'initiation d'un dossier sur l'étape de départ effective, sinon 403. */
    public void authorizeStart(String definitionKey, String startStepKey, Viewer viewer) {
        WorkflowDefinition definition = executionService.latestDefinition(definitionKey);
        WorkflowStep startStep = executionService.resolveStartStep(definition, startStepKey);
        requireStepRole(startStep, viewer, "initier un dossier à l'étape");
    }

    /** Autorise une action sur l'étape courante d'un dossier, sinon 403 (404 si inexistant). */
    public void authorizeTransition(UUID instanceId, Viewer viewer) {
        WorkflowInstance instance = instances.findById(instanceId)
                .orElseThrow(() -> new WorkflowInstanceNotFoundException(
                        "Aucune instance de workflow pour l'id '" + instanceId + "'"));
        WorkflowStep currentStep = steps
                .findByDefinitionIdAndStepKey(
                        instance.getDefinition().getId(), instance.getCurrentStepKey())
                .orElseThrow(() -> new WorkflowValidationException("Étape courante '"
                        + instance.getCurrentStepKey() + "' inconnue dans la définition"));
        requireStepRole(currentStep, viewer, "agir sur l'étape");
    }

    private void requireStepRole(WorkflowStep step, Viewer viewer, String action) {
        List<String> owners = effectiveOwners(step);
        if (owners.isEmpty()) {
            throw new WorkflowForbiddenException("Action refusée : l'étape '" + step.getStepKey()
                    + "' ne déclare aucun rôle responsable (" + action + ").");
        }
        boolean allowed = viewer.roles().stream()
                .filter(role -> role != null && !ADMIN_ROLE.equalsIgnoreCase(role))
                .anyMatch(owners::contains);
        if (!allowed) {
            throw new WorkflowForbiddenException("Action refusée : aucun de vos rôles "
                    + viewer.roles() + " n'est responsable de l'étape '" + step.getStepKey()
                    + "' (" + action + ").");
        }
    }

    /**
     * Rôles responsables utilisables : les rôles déclarés dans {@code owners}, en excluant
     * le rôle réservé {@code ADMIN} (un administrateur n'est pas validateur). Défensif :
     * le rôle réservé est déjà refusé à l'import.
     */
    private List<String> effectiveOwners(WorkflowStep step) {
        List<String> owners = new ArrayList<>();
        if (step.getOwners() != null) {
            step.getOwners().stream()
                    .filter(owner -> owner != null && !ADMIN_ROLE.equalsIgnoreCase(owner))
                    .forEach(owners::add);
        }
        return owners;
    }
}
