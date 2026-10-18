package sn.gouv.mfp.workflow.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.VariableDeclaration;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.definition.WorkflowTransition;
import sn.gouv.mfp.workflow.domain.execution.InstanceStatus;
import sn.gouv.mfp.workflow.domain.execution.WorkflowHistory;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.exception.ConditionEvaluationException;
import sn.gouv.mfp.workflow.exception.InvalidEntryPointException;
import sn.gouv.mfp.workflow.exception.MissingRequiredDataException;
import sn.gouv.mfp.workflow.exception.WorkflowConflictException;
import sn.gouv.mfp.workflow.exception.WorkflowForbiddenException;
import sn.gouv.mfp.workflow.exception.WorkflowInstanceNotFoundException;
import sn.gouv.mfp.workflow.exception.WorkflowNotFoundException;
import sn.gouv.mfp.workflow.exception.WorkflowQuotaExceededException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowHistoryRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;

/**
 * Étape 3 — chef d'orchestre du moteur : initiation des dossiers et exécution des transitions.
 * <p>
 * Toutes les mutations passent par un verrou pessimiste en écriture sur la ligne
 * {@code workflow_instance} ({@code SELECT ... FOR UPDATE}) : deux validations concurrentes
 * du même dossier sont sérialisées par PostgreSQL, la seconde observant l'état déjà modifié
 * par la première (garde d'idempotence).
 */
@Service
public class WorkflowExecutionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionService.class);

    /** Cible virtuelle : renvoie le dossier à l'étape où il a été initié (résolue via l'historique). */
    public static final String INITIATEUR_TARGET = "INITIATEUR";

    /**
     * Cible virtuelle (chantier D, recette 6.3) : renvoie à la dernière étape réellement
     * traversée (from_step de la dernière entrée d'historique).
     */
    public static final String PREVIOUS_STEP_TARGET = "ETAPE_PRECEDENTE";

    /**
     * Cible virtuelle (recette 6.5, Phase 1) : renvoie à l'étape où le traitement de
     * référence a été effectivement réalisé (en délégation ou en direct). Le moteur
     * mémorise cet emplacement via le marqueur {@code setsHandler: true} porté par la
     * transition de traitement, qui est obligatoire à l'import dès que cette cible est
     * utilisée ; la reprise est refusée si aucune transition marquée n'a été franchie.
     */
    public static final String HANDLER_TARGET = "RESPONSABLE_TRAITEMENT";

    /** Action de rejet : mémorise l'étape émettrice et n'est jamais bloquée par les données obligatoires. */
    public static final String REJECT_ACTION = "REJETER";

    /**
     * Action de rejet définitif (recette 6.10) : clôt le dossier en REJECTED_FINAL
     * (rejet administratif, distinct de l'annulation usager CANCELLED), sans action
     * ultérieure possible (409).
     */
    public static final String FINAL_REJECT_ACTION = "REJET_DEFINITIF";

    /**
     * Action d'imputation (chantier D, setsHandler, recette 5.3) : la variable de dossier
     * {@code assignee} désigne la personne, enregistrée sur le dossier et l'historique.
     */
    public static final String IMPUTE_ACTION = "IMPUTER";

    /** Clé de variable portant la personne désignée par une imputation. */
    public static final String ASSIGNEE_VARIABLE = "assignee";

    /** Variable de dossier mémorisant l'étape ayant émis le rejet (module 6.4). */
    public static final String REJECTING_STEP_VARIABLE = "rejectingStepKey";

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowStepRepository steps;
    private final WorkflowTransitionRepository transitions;
    private final WorkflowInstanceRepository instances;
    private final WorkflowHistoryRepository histories;
    private final ConditionEvaluatorService conditionEvaluator;

    public WorkflowExecutionService(
            WorkflowDefinitionRepository definitions,
            WorkflowStepRepository steps,
            WorkflowTransitionRepository transitions,
            WorkflowInstanceRepository instances,
            WorkflowHistoryRepository histories,
            ConditionEvaluatorService conditionEvaluator) {
        this.definitions = definitions;
        this.steps = steps;
        this.transitions = transitions;
        this.instances = instances;
        this.histories = histories;
        this.conditionEvaluator = conditionEvaluator;
    }

    /**
     * Initie un dossier sur la dernière version du circuit identifié par {@code definitionKey}.
     *
     * @param definitionKey    clé fonctionnelle du circuit (ex. "GIRAFE_ACTES")
     * @param initiatorId      identifiant de l'agent initiateur
     * @param initialVariables données initiales du dossier (peuvent être nulles)
     */
    @Transactional
    public WorkflowInstance startWorkflow(
            String definitionKey, String initiatorId, Map<String, Object> initialVariables) {
        return startWorkflow(definitionKey, initiatorId, initialVariables, null);
    }

    /**
     * Initie un dossier en imposant l'étape de départ (point d'entrée du YAML selon le rôle).
     * L'étape imposée doit exister dans la définition : de type START, ou de type TASK
     * si elle est déclarée dans les entryPoints (module 6.3).
     *
     * @param startStepKey code de l'étape de départ, ou null pour la première étape START trouvée
     */
    @Transactional
    public WorkflowInstance startWorkflow(
            String definitionKey,
            String initiatorId,
            Map<String, Object> initialVariables,
            String startStepKey) {
        return startWorkflow(definitionKey, initiatorId, initialVariables, startStepKey, null, null);
    }

    /**
     * Initie un dossier en imposant l'étape de départ et en associant le périmètre organisationnel (direction, bureau).
     * Chemin interne/de confiance : aucune règle de plafond par rôle n'est appliquée.
     */
    @Transactional
    public WorkflowInstance startWorkflow(
            String definitionKey,
            String initiatorId,
            Map<String, Object> initialVariables,
            String startStepKey,
            String scopeDirection,
            String scopeBureau) {
        return startWorkflow(
                definitionKey, initiatorId, initialVariables, startStepKey, scopeDirection, scopeBureau, null);
    }

    /**
     * Initie un dossier en contrôlant le plafond de dossiers actifs <strong>par rôle
     * initiateur ET par type d'acte</strong> (MOT-05), sous verrou consultatif.
     *
     * @param actorRoles rôles moteur de l'initiateur ; seules les règles de plafond visant
     *                   l'un de ces rôles s'appliquent. {@code null} = aucune règle par rôle
     *                   (seuls les plafonds historiques non scopés s'appliquent).
     */
    @Transactional
    public WorkflowInstance startWorkflow(
            String definitionKey,
            String initiatorId,
            Map<String, Object> initialVariables,
            String startStepKey,
            String scopeDirection,
            String scopeBureau,
            Set<String> actorRoles) {
        WorkflowDefinition definition = latestDefinition(definitionKey);
        WorkflowStep startStep = resolveStartStep(definition, startStepKey);

        // MOT-10 : variables déclarées, obligatoires et typées dès la création du dossier.
        enforceCreationVariables(definition, initialVariables);

        // MOT-05 : plafond de dossiers actifs par rôle initiateur et par type d'acte,
        // contrôlé SOUS VERROU (voir enforceQuota).
        String natureActe = null;
        if (initialVariables != null && initialVariables.containsKey("natureActe")) {
            natureActe = (String) initialVariables.get("natureActe");
        }
        enforceQuota(definition, initiatorId, natureActe, actorRoles);

        WorkflowInstance instance = new WorkflowInstance();
        instance.setDefinition(definition);
        instance.setCurrentStepKey(startStep.getStepKey());
        instance.setInitiatorId(initiatorId);
        instance.setScopeDirection(scopeDirection);
        instance.setScopeBureau(scopeBureau);
        instance.setStatus(InstanceStatus.ACTIVE);
        instance.setVariables(initialVariables != null ? new HashMap<>(initialVariables) : new HashMap<>());
        instance = instances.save(instance);

        WorkflowHistory history = new WorkflowHistory();
        history.setInstance(instance);
        history.setAction("INITIATION");
        history.setFromStep(startStep.getStepKey());
        history.setToStep(startStep.getStepKey());
        history.setActorId(initiatorId);
        histories.save(history);

        log.info("Dossier {} initié sur '{}' v{} à l'étape '{}' par '{}' (direction='{}', bureau='{}')",
                instance.getId(), definitionKey, definition.getVersion(),
                startStep.getStepKey(), initiatorId, scopeDirection, scopeBureau);
        return instance;
    }

    /**
     * Dernière version déployée d'un circuit (résolution partagée avec le contrôle des
     * rôles d'étape, qui doit raisonner sur EXACTEMENT la même définition que l'exécution).
     */
    public WorkflowDefinition latestDefinition(String definitionKey) {
        return definitions.findByKey(definitionKey).stream()
                .max(Comparator.comparing(WorkflowDefinition::getVersion))
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Aucune définition de workflow pour la clé '" + definitionKey + "'"));
    }

    /**
     * Résout l'étape de départ effective (module 6.3) : étape imposée si fournie (TASK
     * uniquement si déclarée en point d'entrée, jamais END), sinon première étape START.
     * Publique pour être réutilisée telle quelle par le contrôle des rôles à l'initiation.
     */
    public WorkflowStep resolveStartStep(WorkflowDefinition definition, String startStepKey) {
        if (startStepKey != null && !startStepKey.isBlank()) {
            WorkflowStep startStep = steps
                    .findByDefinitionIdAndStepKey(definition.getId(), startStepKey)
                    .orElseThrow(() -> new WorkflowValidationException("Étape de départ '"
                            + startStepKey + "' inconnue dans la définition '"
                            + definition.getKey() + "'"));
            if (startStep.getType() == StepType.TASK
                    && !isDeclaredEntryPoint(definition, startStepKey)) {
                throw new InvalidEntryPointException("Point d'entrée '" + startStepKey
                        + "' non autorisé : absent des entryPoints de la définition '"
                        + definition.getKey() + "'.");
            }
            if (startStep.getType() == StepType.END) {
                throw new InvalidEntryPointException("Point d'entrée '" + startStepKey
                        + "' invalide : on ne démarre pas un dossier sur une étape END.");
            }
            return startStep;
        }
        return steps.findByDefinitionId(definition.getId()).stream()
                .filter(s -> s.getType() == StepType.START)
                .findFirst()
                .orElseThrow(() -> new WorkflowValidationException("La définition '"
                        + definition.getKey() + "' ne contient aucune étape START"));
    }

    /**
     * Chemin <strong>interne/de confiance</strong> (tests de service, batch) : aucun contrôle
     * de rôle d'étape n'est appliqué. Le chemin API passe par la surcharge avec
     * {@code actorRoles}, qui contrôle le rôle sous le verrou.
     */
    @Transactional
    public WorkflowInstance executeTransition(
            UUID instanceId,
            String action,
            String actorId,
            String comment,
            Map<String, Object> additionalVariables) {
        return executeTransition(
                instanceId, action, actorId, comment, additionalVariables, null);
    }

    /**
     * Fait franchir une étape au dossier en exécutant {@code action}.
     *
     * @param instanceId          identifiant du dossier
     * @param action              déclencheur (ex. "VALIDER", "REJETER")
     * @param actorId             identifiant de l'agent exécutant l'action
     * @param comment             motif (obligatoire en cas de rejet côté appelant)
     * @param additionalVariables variables à fusionner dans le dossier avant évaluation (peuvent être nulles)
     * @param actorRoles          rôles moteur de l'acteur ; {@code null} = chemin de confiance
     *                            (aucun contrôle). Non nul : le rôle de l'étape courante est
     *                            exigé <strong>sous le verrou</strong> (point 2), ce qui rend
     *                            le contrôle atomique avec la mutation.
     */
    @Transactional
    public WorkflowInstance executeTransition(
            UUID instanceId,
            String action,
            String actorId,
            String comment,
            Map<String, Object> additionalVariables,
            Set<String> actorRoles) {
        return executeTransition(
                instanceId, action, actorId, comment, additionalVariables, actorRoles, null, null);
    }

    /**
     * Fait franchir une étape au dossier en exécutant {@code action} avec contrôle de rôle et de périmètre.
     */
    @Transactional
    public WorkflowInstance executeTransition(
            UUID instanceId,
            String action,
            String actorId,
            String comment,
            Map<String, Object> additionalVariables,
            Set<String> actorRoles,
            String actorDirection,
            String actorBureau) {
        // Verrouillage pessimiste : sérialise les transitions concurrentes sur ce dossier.
        WorkflowInstance instance = instances.findByIdWithLock(instanceId)
                .orElseThrow(() -> new WorkflowInstanceNotFoundException(
                        "Aucune instance de workflow pour l'id '" + instanceId + "'"));

        // Garde 1 : seul un dossier actif peut avancer (idempotence).
        if (instance.getStatus() != InstanceStatus.ACTIVE) {
            throw new WorkflowConflictException("Le dossier " + instanceId
                    + " n'est plus actif (statut " + instance.getStatus() + ")");
        }

        // Cloisonnement organisationnel (SEC-04) : direction et bureau doivent correspondre
        if (actorRoles != null) {
            if (instance.getScopeDirection() != null
                    && (actorDirection == null || !actorDirection.equals(instance.getScopeDirection()))) {
                throw new WorkflowForbiddenException(
                        "Action non autorisée : la direction de l'agent ne correspond pas à celle du dossier.");
            }
            if (instance.getScopeBureau() != null
                    && (actorBureau == null || !actorBureau.equals(instance.getScopeBureau()))) {
                throw new WorkflowForbiddenException(
                        "Action non autorisée : le bureau de l'agent ne correspond pas à celui du dossier.");
            }
        }

        // Fusion des variables apportées par l'appel (ex. ajout du numeroOfficiel).
        if (instance.getVariables() == null) {
            instance.setVariables(new HashMap<>());
        }
        if (additionalVariables != null) {
            instance.getVariables().putAll(additionalVariables);
        }

        WorkflowStep currentStep = steps
                .findByDefinitionIdAndStepKey(
                        instance.getDefinition().getId(), instance.getCurrentStepKey())
                .orElseThrow(() -> new WorkflowValidationException("Étape courante '"
                        + instance.getCurrentStepKey() + "' inconnue dans la définition"));

        // Point 2 : contrôle du rôle d'étape SOUS le verrou (atomique avec la mutation).
        if (actorRoles != null) {
            StepRoleAuthority.require(currentStep, actorRoles, "agir sur l'étape");
        }

        // Module 6.2 : données obligatoires de l'étape courante (jamais sur un rejet,
        // qui doit toujours rester possible même dossier incomplet).
        if (!REJECT_ACTION.equalsIgnoreCase(action)) {
            enforceRequiredData(currentStep, instance.getVariables());
        }

        // Résolution : transitions partant de l'étape courante pour l'action demandée.
        // Recette 1.9 : les transitions conditionnelles sont évaluées avant la transition
        // sans condition (défaut), qui ne sert que de repli.
        List<WorkflowTransition> candidates = transitions
                .findBySourceStepIdAndAction(currentStep.getId(), action)
                .stream()
                .sorted(Comparator.comparing(WorkflowExecutionService::isUnconditional))
                .toList();
        WorkflowTransition selected = selectFirstSatisfied(instance, action, candidates);

        String fromStep = instance.getCurrentStepKey();
        String resolvedTarget = resolveTargetStep(instance, selected.getTargetStepKey());

        if (REJECT_ACTION.equalsIgnoreCase(action)) {
            // Module 6.4 : mémorise l'étape émettrice du rejet pour la reprise éventuelle.
            instance.getVariables().put(REJECTING_STEP_VARIABLE, fromStep);
        } else {
            // Module 6.4 : ré-acheminement direct vers l'étape émettrice (FROM_REJECTING_STEP).
            resolvedTarget = applyResumeStrategy(instance, resolvedTarget);
        }
        final String toStep = resolvedTarget;

        WorkflowStep targetStep = steps
                .findByDefinitionIdAndStepKey(instance.getDefinition().getId(), toStep)
                .orElseThrow(() -> new WorkflowValidationException(
                        "Étape cible '" + toStep + "' inconnue dans la définition"));

        instance.setCurrentStepKey(toStep);
        if (targetStep.getType() == StepType.END) {
            instance.setStatus(InstanceStatus.COMPLETED);
        }

        // Marqueur de traitement dynamique (setsHandler: true)
        if (selected.isSetsHandler()) {
            instance.setHandlerActorId(actorId);
            instance.setHandlerStepKey(fromStep);
        }

        String toActor = null;
        if (HANDLER_TARGET.equals(selected.getTargetStepKey())) {
            // Ré-imputation au responsable de traitement ayant traité l'étape
            if (instance.getHandlerActorId() != null) {
                toActor = instance.getHandlerActorId();
                instance.setAssigneeId(toActor);
            }
        }
        if (IMPUTE_ACTION.equalsIgnoreCase(action)) {
            // Chantier D : l'imputation désigne la personne (setsHandler, recette 5.3).
            toActor = assignedPerson(instance);
            instance.setAssigneeId(toActor);
        }
        if (FINAL_REJECT_ACTION.equalsIgnoreCase(action)) {
            // Recette 6.10 : rejet définitif (statut dédié REJECTED_FINAL), sans action ultérieure (409).
            instance.setStatus(InstanceStatus.REJECTED_FINAL);
        }

        WorkflowHistory history = new WorkflowHistory();
        history.setInstance(instance);
        history.setAction(action);
        history.setFromStep(fromStep);
        history.setToStep(toStep);
        history.setActorId(actorId);
        history.setToActorId(toActor);
        history.setComment(comment);
        histories.save(history);

        log.info("Dossier {} : '{}' par '{}' ({} -> {}), statut {}",
                instanceId, action, actorId, fromStep, toStep, instance.getStatus());
        return instance;
    }

    private static boolean isUnconditional(WorkflowTransition transition) {
        return transition.getConditionExpression() == null
                || transition.getConditionExpression().isBlank();
    }

    /**
     * Recette 1.7 — sélection stricte : un verdict {@code false} passe au candidat suivant,
     * mais toute ERREUR d'évaluation refuse explicitement l'action (HTTP 400). Le dossier
     * reste inchangé et ne bascule jamais sur la transition par défaut.
     */
    private WorkflowTransition selectFirstSatisfied(
            WorkflowInstance instance, String action, List<WorkflowTransition> candidates) {
        for (WorkflowTransition candidate : candidates) {
            final Boolean verdict;
            try {
                verdict = conditionEvaluator.evaluateStrict(
                        candidate.getConditionExpression(), instance.getVariables());
            } catch (ConditionEvaluationException e) {
                log.error("Dossier {} : condition invalide pour l'action '{}' depuis '{}' : {}"
                                + " — action refusée, dossier inchangé.",
                        instance.getId(), action, instance.getCurrentStepKey(), e.getMessage());
                throw new WorkflowValidationException("Évaluation de la condition impossible "
                        + "pour l'action '" + action + "' depuis l'étape '"
                        + instance.getCurrentStepKey() + "' : " + e.getCause().getMessage()
                        + ". Dossier inchangé.");
            }
            if (Boolean.TRUE.equals(verdict)) {
                return candidate;
            }
        }
        throw new WorkflowValidationException("Aucune transition valide pour l'action '" + action
                + "' depuis l'étape '" + instance.getCurrentStepKey() + "'");
    }

    /**
     * MOT-05 — plafond de dossiers actifs par <strong>rôle initiateur</strong> et par
     * <strong>type d'acte</strong>, contrôlé <strong>sous verrou</strong>.
     * <p>
     * Deux sources de limites, cumulées de façon la plus restrictive :
     * <ul>
     *   <li>les plafonds historiques non scopés {@code maxActiveInstancesPerInitiator} /
     *       {@code maxActiveInstancesPerType} (appliqués à tous les rôles) ;</li>
     *   <li>les règles {@code activeInstanceLimits} visant un rôle : elles ne s'appliquent
     *       qu'aux initiateurs portant ce rôle (ex. seul l'agent de bureau est plafonné,
     *       pas les chefs).</li>
     * </ul>
     * Le comptage est effectué après acquisition d'un verrou consultatif transactionnel
     * PostgreSQL, ce qui sérialise les initiations concurrentes du même initiateur sur le
     * même circuit (une seule peut franchir un plafond).
     */
    private void enforceQuota(
            WorkflowDefinition definition,
            String initiatorId,
            String natureActe,
            Set<String> actorRoles) {
        int maxPerType = readMaxActiveInstancesPerType(definition);
        int maxPerInitiator = readMaxActiveInstances(definition);

        for (Map<String, Object> limit : readActiveInstanceLimits(definition)) {
            Object role = limit.get("role");
            if (role != null && actorRoles != null && actorRoles.contains(role.toString())) {
                maxPerType = mostRestrictive(maxPerType, asInt(limit.get("maxPerType")));
                maxPerInitiator = mostRestrictive(maxPerInitiator, asInt(limit.get("maxPerInitiator")));
            }
        }

        if (maxPerType <= 0 && maxPerInitiator <= 0) {
            return;
        }

        // MOT-05 : contrôle SOUS VERROU — sérialise les initiations concurrentes.
        instances.acquireQuotaLock(quotaLockKey(definition.getKey(), initiatorId));

        if (maxPerType > 0 && natureActe != null) {
            long activeByType = instances.countActiveInstancesByInitiatorAndNatureActe(
                    initiatorId, definition.getKey(), natureActe);
            if (activeByType >= maxPerType) {
                throw new WorkflowQuotaExceededException(
                        "L'agent " + initiatorId + " a déjà un dossier en cours pour le type : " + natureActe
                        + " (Max: " + maxPerType + ", Actuels: " + activeByType + ").");
            }
        }

        if (maxPerInitiator > 0) {
            long active = instances.countByDefinitionIdAndInitiatorIdAndStatus(
                    definition.getId(), initiatorId, InstanceStatus.ACTIVE);
            if (active >= maxPerInitiator) {
                throw new WorkflowQuotaExceededException("Plafond de dossiers actifs atteint "
                        + "pour cet initiateur (Max: " + maxPerInitiator + ", Actuels: " + active + ").");
            }
        }
    }

    /** Retient la limite la plus contraignante ; 0/absent = pas de limite. */
    private static int mostRestrictive(int current, int candidate) {
        if (candidate <= 0) {
            return current;
        }
        if (current <= 0) {
            return candidate;
        }
        return Math.min(current, candidate);
    }

    private static int asInt(Object raw) {
        return raw instanceof Number number ? number.intValue() : 0;
    }

    /** Clé de verrou consultatif (circuit + initiateur) dérivée en 64 bits. */
    private static long quotaLockKey(String definitionKey, String initiatorId) {
        long definitionPart = definitionKey == null ? 0L : definitionKey.hashCode();
        long initiatorPart = initiatorId == null ? 0L : initiatorId.hashCode();
        return (definitionPart << 32) ^ (initiatorPart & 0xffffffffL);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readActiveInstanceLimits(WorkflowDefinition definition) {
        if (definition.getSettings() == null) {
            return List.of();
        }
        Object raw = definition.getSettings().get("activeInstanceLimits");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> limits = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                limits.add((Map<String, Object>) map);
            }
        }
        return limits;
    }

    private int readMaxActiveInstances(WorkflowDefinition definition) {
        if (definition.getSettings() == null) {
            return 0;
        }
        Object raw = definition.getSettings().get("maxActiveInstancesPerInitiator");
        return raw instanceof Number number ? number.intValue() : 0;
    }

    private int readMaxActiveInstancesPerType(WorkflowDefinition definition) {
        if (definition.getSettings() == null) {
            return 0;
        }
        Object raw = definition.getSettings().get("maxActiveInstancesPerType");
        return raw instanceof Number number ? number.intValue() : 0;
    }

    /** Module 6.3 — une étape TASK n'est démarrable que si déclarée dans les entryPoints. */
    private boolean isDeclaredEntryPoint(WorkflowDefinition definition, String stepKey) {
        return definition.getEntryPoints() != null && definition.getEntryPoints().stream()
                .anyMatch(e -> stepKey.equals(e.startStepKey()));
    }

    /**
     * Module 6.2 — vérifie les données obligatoires déclarées sur l'étape courante
     * (requiredData), après fusion avec les variables apportées par la requête.
     * Manquante = absente, nulle ou chaîne vide/blanche.
     */
    private void enforceRequiredData(WorkflowStep currentStep, Map<String, Object> variables) {
        List<String> required = currentStep.getRequiredData();
        if (required == null || required.isEmpty()) {
            return;
        }
        List<String> missing = required.stream().filter(key -> isMissing(variables, key)).toList();
        if (!missing.isEmpty()) {
            throw new MissingRequiredDataException("Validation impossible. Donnée(s) "
                    + "obligatoire(s) manquante(s) pour l'étape [" + currentStep.getStepKey()
                    + "] : [" + String.join(", ", missing) + "].");
        }
    }

    private boolean isMissing(Map<String, Object> variables, String key) {
        if (variables == null || !variables.containsKey(key)) {
            return true;
        }
        Object value = variables.get(key);
        return value == null || (value instanceof String text && text.isBlank());
    }

    /**
     * MOT-10 — contrôle des variables déclarées DÈS LA CRÉATION : toute variable
     * {@code required} doit être présente et non vide ; le type d'une variable déclarée
     * fournie est vérifié ({@code string}, {@code number}, {@code boolean}, {@code date}).
     */
    private void enforceCreationVariables(WorkflowDefinition definition, Map<String, Object> variables) {
        List<VariableDeclaration> declared = definition.getVariableDeclarations();
        if (declared == null || declared.isEmpty()) {
            return;
        }
        List<String> missing = declared.stream()
                .filter(VariableDeclaration::required)
                .map(VariableDeclaration::key)
                .filter(key -> isMissing(variables, key))
                .toList();
        if (!missing.isEmpty()) {
            throw new MissingRequiredDataException("Création impossible. Variable(s) "
                    + "obligatoire(s) manquante(s) : [" + String.join(", ", missing) + "].");
        }
        for (VariableDeclaration declaration : declared) {
            if (variables == null || !variables.containsKey(declaration.key())) {
                continue;
            }
            Object value = variables.get(declaration.key());
            if (value != null && !matchesVariableType(value, declaration.type())) {
                throw new WorkflowValidationException("Variable déclarée '" + declaration.key()
                        + "' : type attendu '" + declaration.type()
                        + "', valeur reçue incompatible.");
            }
        }
    }

    private boolean matchesVariableType(Object value, String type) {
        return switch (type == null ? "string" : type) {
            case "string" -> value instanceof CharSequence;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "date" -> value instanceof CharSequence text && isIsoDate(text.toString());
            default -> true;
        };
    }

    private boolean isIsoDate(String text) {
        try {
            java.time.LocalDate.parse(text.trim());
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    /**
     * Module 6.4 — ré-acheminement après rejet selon la stratégie de la définition.
     * FROM_REJECTING_STEP (défaut) : saute directement vers l'étape mémorisée dans
     * {@code rejectingStepKey} et nettoie la variable. FROM_START : circuit linéaire normal
     * (la variable est également nettoyée pour ne pas polluer le dossier).
     */
    private String applyResumeStrategy(WorkflowInstance instance, String toStep) {
        Object rejecting = instance.getVariables().remove(REJECTING_STEP_VARIABLE);
        if (!isFromRejectingStep(instance.getDefinition())) {
            return toStep;
        }
        if (rejecting instanceof String rejectingStep && !rejectingStep.isBlank()) {
            log.info("Dossier {} : reprise directe vers l'étape émettrice '{}' (stratégie FROM_REJECTING_STEP).",
                    instance.getId(), rejectingStep);
            return rejectingStep;
        }
        return toStep;
    }

    private boolean isFromRejectingStep(WorkflowDefinition definition) {
        Object raw = definition.getSettings() != null
                ? definition.getSettings().get("resumeStrategyDefault")
                : null;
        return raw == null || "FROM_REJECTING_STEP".equalsIgnoreCase(String.valueOf(raw).trim());
    }

    /**
     * Chantier D : personne désignée par une imputation (variable {@code assignee}).
     * Absente, nulle ou blanche : 400 explicite (recette 5.3 : on n'impute jamais
     * dans le vide). Le contrôle du rôle de la personne désignée attend l'annuaire (§14).
     */
    private String assignedPerson(WorkflowInstance instance) {
        Object raw = instance.getVariables() != null
                ? instance.getVariables().get(ASSIGNEE_VARIABLE)
                : null;
        if (!(raw instanceof String assignee) || assignee.isBlank()) {
            throw new MissingRequiredDataException("Imputation impossible : désignez une "
                    + "personne via la variable '" + ASSIGNEE_VARIABLE + "'.");
        }
        return assignee;
    }

    /**
     * Résout l'étape cible, y compris les cibles virtuelles :
     * <ul>
     *   <li>{@code INITIATEUR} : étape d'initiation (première entrée d'historique) ;</li>
     *   <li>{@code ETAPE_PRECEDENTE} (recette 6.3) : dernière étape réellement traversée
     *   ({@code from_step} de la dernière entrée d'historique) ;</li>
     *   <li>{@code RESPONSABLE_TRAITEMENT} (recette 6.5) : étape où le traitement a été
     *   fait, mémorisée par la transition marquée {@code setsHandler: true}.</li>
     * </ul>
     */
    private String resolveTargetStep(WorkflowInstance instance, String targetStepKey) {
        if (PREVIOUS_STEP_TARGET.equals(targetStepKey)) {
            return previousStep(instance);
        }
        if (HANDLER_TARGET.equals(targetStepKey)) {
            return handlerStep(instance);
        }
        if (!INITIATEUR_TARGET.equals(targetStepKey)) {
            return targetStepKey;
        }
        return histories.findByInstanceIdOrderByTimestampAsc(instance.getId()).stream()
                .findFirst()
                .map(WorkflowHistory::getToStep)
                .orElseGet(() -> steps.findByDefinitionId(instance.getDefinition().getId()).stream()
                        .filter(s -> s.getType() == StepType.START)
                        .findFirst()
                        .map(WorkflowStep::getStepKey)
                        .orElseThrow(() -> new WorkflowValidationException(
                                "Retour INITIATEUR impossible : aucune étape initiale trouvée")));
    }

    private String previousStep(WorkflowInstance instance) {
        List<WorkflowHistory> trail =
                histories.findByInstanceIdOrderByTimestampAsc(instance.getId());
        if (trail.isEmpty()) {
            throw new WorkflowValidationException(
                    "Retour ETAPE_PRECEDENTE impossible : historique vide.");
        }
        return trail.get(trail.size() - 1).getFromStep();
    }

    /**
     * Recette 6.5 & Phase 1 : la reprise RESPONSABLE_TRAITEMENT renvoie à l'étape où le traitement
     * a été effectivement réalisé (posé par la transition marquée {@code setsHandler: true}), que le
     * traitement ait été délégué ou fait en direct. Refus si aucune transition marquée n'a été franchie.
     */
    private String handlerStep(WorkflowInstance instance) {
        if (instance.getHandlerStepKey() != null && !instance.getHandlerStepKey().isBlank()) {
            return instance.getHandlerStepKey();
        }
        throw new WorkflowValidationException("Reprise RESPONSABLE_TRAITEMENT impossible : le circuit '"
                + instance.getDefinition().getKey() + "' n'a enregistré aucun responsable de traitement "
                + "(aucune transition marquée 'setsHandler: true' n'a été franchie).");
    }
}
