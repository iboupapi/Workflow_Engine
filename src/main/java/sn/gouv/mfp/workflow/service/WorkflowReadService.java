package sn.gouv.mfp.workflow.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.execution.InstanceStatus;
import sn.gouv.mfp.workflow.domain.execution.WorkflowHistory;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.exception.WorkflowForbiddenException;
import sn.gouv.mfp.workflow.exception.WorkflowInstanceNotFoundException;
import sn.gouv.mfp.workflow.repository.WorkflowHistoryRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;

/**
 * Lecture des dossiers (cahier de recette, section 3) : un seul prédicat de visibilité,
 * appliqué partout — un paramètre trafiqué n'élargit jamais le périmètre (recette 3.4).
 * <p>
 * Règle v1 (mappings métier du §14 en attente) : un dossier est visible s'il est à vous
 * (initiateur ou assigné), si vous êtes admin, ou s'il est ACTIF sur une étape dont un
 * de vos rôles est responsable ET non imputé à quelqu'un d'autre (boîte collective).
 * Invisible = 404 (aucune fuite d'existence).
 * <p>
 * Volume : filtrage en mémoire avec garde-fou (recette 11.5 : requêtes paginées côté
 * base + index à mesurer avec le volume cible).
 */
@Service
@Transactional(readOnly = true)
public class WorkflowReadService {

    /** Taille de page maximale imposée (recette 3.7 : size=100000 est rabattu). */
    public static final int MAX_PAGE_SIZE = 100;

    /** Garde-fou du balayage v1 (recette 11.5 : passer au SQL paginé avec le volume). */
    static final int MAX_SCAN = 2000;

    /** Tri stable : création décroissante, identifiant croissant en départage. */
    private static final Comparator<WorkflowInstance> BY_NEWEST = Comparator
            .comparing(WorkflowInstance::getCreatedAt)
            .reversed()
            .thenComparing(WorkflowInstance::getId);

    private final WorkflowInstanceRepository instances;
    private final WorkflowHistoryRepository histories;
    private final WorkflowStepRepository steps;
    private final WorkflowTransitionRepository transitions;

    public WorkflowReadService(
            WorkflowInstanceRepository instances,
            WorkflowHistoryRepository histories,
            WorkflowStepRepository steps,
            WorkflowTransitionRepository transitions) {
        this.instances = instances;
        this.histories = histories;
        this.steps = steps;
        this.transitions = transitions;
    }

    /** Demandeur : identité (sujet du jeton), rôles (sans préfixe) et administration. */
    public record Viewer(String userId, Set<String> roles, boolean admin) {

        public static Viewer from(Authentication authentication) {
            if (authentication instanceof JwtAuthenticationToken jwt) {
                Set<String> roles = jwt.getAuthorities().stream()
                        .map(a -> a.getAuthority().startsWith("ROLE_")
                                ? a.getAuthority().substring("ROLE_".length())
                                : a.getAuthority())
                        .collect(Collectors.toSet());
                return new Viewer(jwt.getName(), roles, roles.contains("ADMIN"));
            }
            if (authentication != null
                    && authentication.isAuthenticated()
                    && authentication.getAuthorities().stream()
                            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
                return new Viewer(authentication.getName(), Set.of("ADMIN"), true);
            }
            return new Viewer(null, Set.of(), false);
        }
    }

    /** Vues de recherche : mes dossiers, ma boîte collective, tout (admin). */
    public enum InstanceView {
        MINE,
        INBOX,
        ALL
    }

    /** Critères de recherche (tous facultatifs, combinés par ET). */
    public record SearchCriteria(
            InstanceView view,
            String definitionKey,
            InstanceStatus status,
            String currentStepKey,
            String initiatorId) {
    }

    /** Ligne d'historique exposée (jamais d'entité JPA sérialisée directement). */
    public record HistoryResponse(
            UUID id,
            String action,
            String fromStep,
            String toStep,
            String actorId,
            String toActorId,
            java.time.Instant timestamp,
            String comment) {

        static HistoryResponse from(WorkflowHistory history) {
            return new HistoryResponse(
                    history.getId(),
                    history.getAction(),
                    history.getFromStep(),
                    history.getToStep(),
                    history.getActorId(),
                    history.getToActorId(),
                    history.getTimestamp(),
                    history.getComment());
        }
    }

    /** Action possible à l'étape courante (structurelle : droits fins au chantier D). */
    public record ActionOption(String action, List<String> targets, List<String> requiredData) {
    }

    /** Dossier lisible par le demandeur, sinon 404. */
    public WorkflowInstance visibleOrThrow(UUID id, Viewer viewer) {
        WorkflowInstance instance = instances
                .findById(id)
                .orElseThrow(() -> new WorkflowInstanceNotFoundException(
                        "Aucun dossier pour l'id '" + id + "'"));
        if (!canRead(instance, viewer)) {
            throw new WorkflowInstanceNotFoundException(
                    "Aucun dossier pour l'id '" + id + "'");
        }
        return instance;
    }

    /** Historique chronologique d'un dossier lisible. */
    public List<HistoryResponse> history(UUID id, Viewer viewer) {
        WorkflowInstance instance = visibleOrThrow(id, viewer);
        return histories.findByInstanceIdOrderByTimestampAsc(instance.getId()).stream()
                .map(HistoryResponse::from)
                .toList();
    }

    /** Actions possibles à l'étape courante ; vide si le dossier est terminé (recette 3.10). */
    public List<ActionOption> possibleActions(UUID id, Viewer viewer) {
        WorkflowInstance instance = visibleOrThrow(id, viewer);
        if (instance.getStatus() != InstanceStatus.ACTIVE) {
            return List.of();
        }
        WorkflowStep currentStep = steps
                .findByDefinitionIdAndStepKey(
                        instance.getDefinition().getId(), instance.getCurrentStepKey())
                .orElseThrow(() -> new WorkflowInstanceNotFoundException(
                        "Aucun dossier pour l'id '" + id + "'"));
        Map<String, List<String>> targetsByAction = transitions
                .findBySourceStepId(currentStep.getId()).stream()
                .collect(Collectors.groupingBy(
                        t -> t.getAction(),
                        Collectors.mapping(t -> t.getTargetStepKey(), Collectors.toList())));
        List<String> requiredData = currentStep.getRequiredData() != null
                ? currentStep.getRequiredData()
                : List.of();
        return targetsByAction.entrySet().stream()
                .map(entry -> new ActionOption(
                        entry.getKey(),
                        entry.getValue().stream().distinct().sorted().toList(),
                        requiredData))
                .sorted(Comparator.comparing(ActionOption::action))
                .toList();
    }

    /**
     * Recherche paginée, tri stable (création décroissante, identifiant croissant).
     * Taille rabattue à {@link #MAX_PAGE_SIZE} ; {@code initiatorId} ignoré sauf admin ;
     * {@code ALL} réservé à l'admin (403 sinon).
     */
    public Page<WorkflowInstance> search(
            Viewer viewer, SearchCriteria criteria, Pageable pageable) {
        if (criteria.view() == InstanceView.ALL && !viewer.admin()) {
            throw new WorkflowForbiddenException(
                    "La vue complète est réservée aux administrateurs.");
        }
        int size = Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE);
        List<WorkflowInstance> candidates = candidates(viewer, criteria.view());
        List<WorkflowInstance> filtered = candidates.stream()
                .filter(instance -> matches(instance, viewer, criteria))
                .sorted(BY_NEWEST)
                .toList();
        int total = filtered.size();
        int from = Math.min(pageable.getPageNumber() * size, total);
        int to = Math.min(from + size, total);
        List<WorkflowInstance> page = filtered.subList(from, to);
        return new PageImpl<>(
                page,
                org.springframework.data.domain.PageRequest.of(
                        pageable.getPageNumber(), size, pageable.getSort()),
                total);
    }

    private List<WorkflowInstance> candidates(Viewer viewer, InstanceView view) {
        // Triés avant plafonnement : sans cela, les dossiers récents seraient invisibles
        // dès que la table dépasse le garde-fou.
        return switch (view) {
            case ALL -> instances.findAll().stream()
                    .sorted(BY_NEWEST)
                    .limit(MAX_SCAN)
                    .toList();
            case MINE -> {
                List<WorkflowInstance> own = new ArrayList<>();
                if (viewer.userId() != null) {
                    own.addAll(instances.findByInitiatorId(viewer.userId()));
                    own.addAll(instances.findByAssigneeId(viewer.userId()));
                }
                yield dedupe(own);
            }
            case INBOX -> instances.findByStatus(InstanceStatus.ACTIVE).stream()
                    .filter(instance -> isCurrentStepPooled(instance, viewer))
                    .sorted(BY_NEWEST)
                    .limit(MAX_SCAN)
                    .toList();
        };
    }

    private boolean matches(WorkflowInstance instance, Viewer viewer, SearchCriteria criteria) {
        if (!canRead(instance, viewer)) {
            return false;
        }
        if (criteria.definitionKey() != null
                && !criteria.definitionKey().equals(instance.getDefinition().getKey())) {
            return false;
        }
        if (criteria.status() != null && criteria.status() != instance.getStatus()) {
            return false;
        }
        if (criteria.currentStepKey() != null
                && !criteria.currentStepKey().equals(instance.getCurrentStepKey())) {
            return false;
        }
        // Recette 3.4 : le filtre initiateur d'un non-admin est sans effet.
        if (criteria.initiatorId() != null
                && viewer.admin()
                && !criteria.initiatorId().equals(instance.getInitiatorId())) {
            return false;
        }
        return true;
    }

    private boolean canRead(WorkflowInstance instance, Viewer viewer) {
        if (viewer.admin()) {
            return true;
        }
        if (viewer.userId() != null
                && (viewer.userId().equals(instance.getInitiatorId())
                        || viewer.userId().equals(instance.getAssigneeId()))) {
            return true;
        }
        // Règle de lecture (point 2) : rôle de l'étape courante OU d'une étape traversée.
        return instance.getStatus() == InstanceStatus.ACTIVE
                && readableByRole(instance, viewer, true);
    }

    /** Dossier en boîte collective : étape courante dont un rôle du demandeur est responsable. */
    private boolean isCurrentStepPooled(WorkflowInstance instance, Viewer viewer) {
        return readableByRole(instance, viewer, false);
    }

    /**
     * Lecture autorisée par rôle. {@code includeTraversed} étend la portée aux étapes
     * traversées (historique) — pour la consultation directe, la boîte collective ne
     * retenant que l'étape courante. Un dossier imputé à quelqu'un d'autre sort de la
     * portée des collègues de rôle (seuls assigné, initiateur et administrateur le voient).
     * Le rôle réservé {@code ADMIN} n'est jamais compté comme responsable d'étape.
     */
    private boolean readableByRole(
            WorkflowInstance instance, Viewer viewer, boolean includeTraversed) {
        if (viewer.roles().isEmpty()) {
            return false;
        }
        if (instance.getAssigneeId() != null
                && !instance.getAssigneeId().equals(viewer.userId())) {
            return false;
        }
        Set<String> owners = ownersOfRelevantSteps(instance, includeTraversed);
        return viewer.roles().stream()
                .filter(role -> role != null && !"ADMIN".equalsIgnoreCase(role))
                .anyMatch(owners::contains);
    }

    /** Rôles responsables des étapes pertinentes (courante, et traversées si demandé). */
    private Set<String> ownersOfRelevantSteps(
            WorkflowInstance instance, boolean includeTraversed) {
        Map<String, List<String>> ownersByStep = steps
                .findByDefinitionId(instance.getDefinition().getId()).stream()
                .filter(step -> step.getOwners() != null)
                .collect(Collectors.toMap(
                        WorkflowStep::getStepKey, WorkflowStep::getOwners, (first, second) -> first));
        Set<String> keys = new HashSet<>();
        keys.add(instance.getCurrentStepKey());
        if (includeTraversed) {
            for (WorkflowHistory history :
                    histories.findByInstanceIdOrderByTimestampAsc(instance.getId())) {
                if (history.getFromStep() != null) {
                    keys.add(history.getFromStep());
                }
                if (history.getToStep() != null) {
                    keys.add(history.getToStep());
                }
            }
        }
        Set<String> owners = new HashSet<>();
        for (String key : keys) {
            List<String> declared = ownersByStep.get(key);
            if (declared != null) {
                declared.stream()
                        .filter(owner -> owner != null && !"ADMIN".equalsIgnoreCase(owner))
                        .forEach(owners::add);
            }
        }
        return owners;
    }

    private List<WorkflowInstance> dedupe(List<WorkflowInstance> instances) {
        return instances.stream()
                .collect(Collectors.toMap(
                        WorkflowInstance::getId, instance -> instance, (first, second) -> first))
                .values()
                .stream()
                .toList();
    }
}
