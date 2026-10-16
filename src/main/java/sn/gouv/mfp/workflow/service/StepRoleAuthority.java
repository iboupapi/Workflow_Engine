package sn.gouv.mfp.workflow.service;

import java.util.List;
import java.util.Set;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.exception.WorkflowForbiddenException;

/**
 * Contrôle par rôle d'étape (point 2), logique pure et sans état : une identité ne peut
 * agir sur une étape que si l'un de ses rôles est déclaré responsable ({@code owners}).
 * <p>
 * Règles :
 * <ul>
 *   <li>étape sans rôle responsable : refus (refus par défaut) ;</li>
 *   <li>le rôle réservé {@code ADMIN} n'est jamais validateur — ni côté {@code owners}
 *   (déjà refusé à l'import), ni côté rôles de l'identité ;</li>
 *   <li>rôles disjoints : refus 403.</li>
 * </ul>
 * Partagé entre l'autorisation d'initiation ({@link WorkflowAuthorizationService}) et le
 * contrôle de transition exécuté <strong>sous le verrou</strong>
 * ({@link WorkflowExecutionService}) — ce dernier garantit l'atomicité du contrôle et de
 * l'action.
 */
final class StepRoleAuthority {

    static final String ADMIN_ROLE = "ADMIN";

    private StepRoleAuthority() {
    }

    /** Rôles responsables utilisables d'une étape, {@code ADMIN} réservé exclu. */
    static List<String> effectiveOwners(WorkflowStep step) {
        if (step.getOwners() == null) {
            return List.of();
        }
        return step.getOwners().stream()
                .filter(owner -> owner != null && !ADMIN_ROLE.equalsIgnoreCase(owner.trim()))
                .toList();
    }

    /** Vrai si l'un des rôles de l'identité est responsable de l'étape. */
    static boolean isAllowed(WorkflowStep step, Set<String> roles) {
        List<String> owners = effectiveOwners(step);
        if (owners.isEmpty() || roles == null) {
            return false;
        }
        return roles.stream()
                .filter(role -> role != null && !ADMIN_ROLE.equalsIgnoreCase(role.trim()))
                .anyMatch(owners::contains);
    }

    /** Exige un rôle responsable de l'étape, sinon {@link WorkflowForbiddenException}. */
    static void require(WorkflowStep step, Set<String> roles, String action) {
        List<String> owners = effectiveOwners(step);
        if (owners.isEmpty()) {
            throw new WorkflowForbiddenException("Action refusée : l'étape '" + step.getStepKey()
                    + "' ne déclare aucun rôle responsable (" + action + ").");
        }
        if (!isAllowed(step, roles)) {
            throw new WorkflowForbiddenException("Action refusée : aucun de vos rôles "
                    + roles + " n'est responsable de l'étape '" + step.getStepKey()
                    + "' (" + action + ").");
        }
    }
}
