package sn.gouv.mfp.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sn.gouv.mfp.workflow.config.YamlConfig;
import sn.gouv.mfp.workflow.domain.definition.EntryPoint;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowImportAudit;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.definition.WorkflowTransition;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowImportAuditRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;
import sn.gouv.mfp.workflow.service.dto.DefinitionImport;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.EntryPointConfigDTO;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.SettingsConfigDTO;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.StepConfigDTO;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.TransitionConfigDTO;

/**
 * Chargeur de configuration YAML : rend le moteur pilotable par la configuration,
 * sans redémarrage ni modification de code Java.
 * <p>
 * Versionnage : un contenu nouveau crée la version suivante (v+1) — les dossiers en cours
 * restent rattachés à leur version d'origine ; un contenu identique au dernier déployé
 * est idempotent (recette 7.6 / 7.7 : HTTP 200, aucune nouvelle version, mais ligne d'audit).
 * L'empreinte porte sur le contenu canonique analysé (conditions normalisées), champ
 * {@code version} du YAML exclu (purement informatif, recette 7.9).
 * <p>
 * La cohérence graphique est validée avant toute persistance (transaction unique) :
 * conditions SpEL inspectées, sources et cibles rattachées au graphe, au moins une
 * étape START et une étape END.
 * <p>
 * Concurrence (recette 9.3) : les imports d'une même clé sont sérialisés par verrou
 * à bandes, avec la contrainte d'unicité (clé, version) en garde-fou et une nouvelle
 * tentative unique en cas de course perdue.
 */
@Service
public class WorkflowDefinitionLoaderService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionLoaderService.class);

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowStepRepository steps;
    private final WorkflowTransitionRepository transitions;
    private final WorkflowImportAuditRepository audits;
    private final ObjectMapper yamlMapper;
    private final TransactionTemplate writeTransaction;
    private final ConcurrentMap<String, Object> importLocks = new ConcurrentHashMap<>();

    public WorkflowDefinitionLoaderService(
            WorkflowDefinitionRepository definitions,
            WorkflowStepRepository steps,
            WorkflowTransitionRepository transitions,
            WorkflowImportAuditRepository audits,
            @Qualifier("yamlObjectMapper") ObjectMapper yamlMapper,
            PlatformTransactionManager transactionManager) {
        this.definitions = definitions;
        this.steps = steps;
        this.transitions = transitions;
        this.audits = audits;
        this.yamlMapper = yamlMapper;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Lit, valide puis déploie une définition YAML (nom de fichier inconnu).
     *
     * @param yamlContent contenu textuel du fichier YAML
     * @return la définition déployée et l'indicateur de création (201 créé, 200 déjà à jour)
     * @throws WorkflowValidationException si le YAML est illisible ou le graphe incohérent
     */
    public DefinitionImport loadAndSaveYamlDefinition(String yamlContent) {
        return loadAndSaveYamlDefinition(yamlContent, null);
    }

    /**
     * Lit, valide puis déploie une définition YAML en conservant la preuve d'import.
     * <p>
     * Sans transaction englobante : chaque tentative s'exécute dans sa propre transaction
     * (une violation de contrainte annule toute la transaction PostgreSQL, qui devient
     * inutilisable — la nouvelle tentative exige donc une transaction neuve).
     *
     * @param yamlContent contenu textuel du fichier YAML
     * @param filename    nom du fichier d'origine (null pour un corps YAML brut)
     */
    public DefinitionImport loadAndSaveYamlDefinition(String yamlContent, String filename) {
        WorkflowConfigDTO config = parseYaml(yamlContent);
        Map<TransitionConfigDTO, String> canonicalConditions = validateGraph(config);
        String fingerprint = ImportFingerprint.sha256Hex(config, canonicalConditions);
        String key = config.definition().key();

        synchronized (importLocks.computeIfAbsent(key, k -> new Object())) {
            DataIntegrityViolationException conflict = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    return writeTransaction.execute(status -> deployOnce(
                            config, canonicalConditions, fingerprint, yamlContent, filename));
                } catch (DataIntegrityViolationException e) {
                    conflict = e;
                    log.warn("Import '{}' en concurrence, tentative {}/2.", key, attempt);
                }
            }
            throw conflict;
        }
    }

    /** Déploie dans une transaction dédiée : réutilisation idempotente ou version suivante. */
    private DefinitionImport deployOnce(
            WorkflowConfigDTO config,
            Map<TransitionConfigDTO, String> canonicalConditions,
            String fingerprint,
            String yamlContent,
            String filename) {
        String key = config.definition().key();
        Optional<WorkflowDefinition> latest = latestVersion(key);
        if (latest.isPresent() && fingerprint.equals(latest.get().getContentHash())) {
            audit(latest.get(), fingerprint, yamlContent, filename, false);
            log.info("Définition '{}' inchangée (v{}) : réimport idempotent.",
                    key, latest.get().getVersion());
            return new DefinitionImport(latest.get(), false);
        }
        int version = latest.map(WorkflowDefinition::getVersion).orElse(0) + 1;
        WorkflowDefinition definition =
                persistAll(config, canonicalConditions, fingerprint, version);
        // Forcer l'INSERT dans le verrou : sans flush, la contrainte d'unicité ne serait
        // testée qu'au commit, après la libération du verrou et hors de tout rattrapage.
        definitions.flush();
        audit(definition, fingerprint, yamlContent, filename, true);
        return new DefinitionImport(definition, true);
    }

    /** Persiste la définition (contenu déjà validé), ses étapes et ses transitions. */
    private WorkflowDefinition persistAll(
            WorkflowConfigDTO config,
            Map<TransitionConfigDTO, String> canonicalConditions,
            String fingerprint,
            int version) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(config.definition().key());
        definition.setName(config.definition().name());
        definition.setVersion(version);
        definition.setContentHash(fingerprint);
        definition.setSettings(toSettingsMap(config.settings()));
        definition.setEntryPoints(toEntryPoints(config.entryPoints()));
        definition = definitions.save(definition);

        Map<String, WorkflowStep> savedSteps = new HashMap<>();
        for (StepConfigDTO stepDto : config.steps()) {
            WorkflowStep step = new WorkflowStep();
            step.setDefinition(definition);
            step.setStepKey(stepDto.key());
            step.setName(stepDto.name());
            step.setType(StepType.valueOf(stepDto.type()));
            step.setRequiredData(stepDto.requiredDataOnValidate() != null
                    ? new ArrayList<>(stepDto.requiredDataOnValidate())
                    : null);
            step.setOwners(stepDto.owners() != null ? new ArrayList<>(stepDto.owners()) : null);
            step.setProcessingStep(Boolean.TRUE.equals(stepDto.processingStep()));
            savedSteps.put(stepDto.key(), steps.save(step));
        }

        int transitionCount = 0;
        for (TransitionConfigDTO transDto : config.transitions()) {
            WorkflowStep sourceStep = savedSteps.get(transDto.source());
            WorkflowTransition transition = new WorkflowTransition();
            transition.setSourceStep(sourceStep);
            transition.setAction(transDto.action());
            transition.setTargetStepKey(transDto.target());
            transition.setConditionExpression(canonicalConditions.get(transDto));
            transitions.save(transition);
            transitionCount++;
        }

        log.info("Définition '{}' importée en v{} : {} étapes, {} transitions.",
                definition.getKey(), definition.getVersion(), savedSteps.size(), transitionCount);
        return definition;
    }

    private Optional<WorkflowDefinition> latestVersion(String key) {
        return definitions.findByKey(key).stream()
                .max(Comparator.comparing(WorkflowDefinition::getVersion));
    }

    /** Écrit la ligne d'audit d'import : auteur vérifié, horodatage, empreinte, contenu conservé. */
    private void audit(
            WorkflowDefinition definition,
            String fingerprint,
            String yamlContent,
            String filename,
            boolean created) {
        WorkflowImportAudit audit = new WorkflowImportAudit();
        audit.setDefinitionKey(definition.getKey());
        audit.setVersion(definition.getVersion());
        audit.setActorId(currentActor());
        audit.setSha256(fingerprint);
        audit.setFilename(filename);
        audit.setContent(yamlContent);
        audit.setCreated(created);
        audits.save(audit);
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getName() == null) {
            return "anonymous";
        }
        return authentication.getName();
    }

    private WorkflowConfigDTO parseYaml(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new WorkflowValidationException("Contenu YAML vide : import impossible.");
        }
        if (yamlContent.length() > YamlConfig.MAX_YAML_BYTES) {
            throw new WorkflowValidationException("Fichier YAML trop volumineux : import refusé "
                    + "(limite " + YamlConfig.MAX_YAML_BYTES + " caractères).");
        }
        YamlGuard.checkStructure(yamlContent);
        try {
            WorkflowConfigDTO config = yamlMapper.readValue(yamlContent, WorkflowConfigDTO.class);
            if (config == null
                    || config.definition() == null
                    || config.definition().key() == null
                    || config.definition().key().isBlank()) {
                throw new WorkflowValidationException(
                        "YAML invalide : bloc 'definition.key' manquant.");
            }
            return config;
        } catch (JsonProcessingException e) {
            throw new WorkflowValidationException("YAML illisible : " + e.getOriginalMessage());
        }
    }

    /**
     * Valide la cohérence graphique du circuit avant toute écriture en base, et renvoie
     * les conditions SpEL sous forme canonique normalisée (recette 1.6).
     * <p>
     * Visibilité de paquet pour permettre le test unitaire des règles de validation
     * sans base de données.
     */
    Map<TransitionConfigDTO, String> validateGraph(WorkflowConfigDTO config) {
        if (config.steps() == null || config.steps().isEmpty()) {
            throw new WorkflowValidationException("YAML invalide : aucune étape déclarée.");
        }
        if (config.transitions() == null || config.transitions().isEmpty()) {
            throw new WorkflowValidationException("YAML invalide : aucune transition déclarée.");
        }

        Set<String> stepKeys = new HashSet<>();
        boolean hasStart = false;
        boolean hasEnd = false;
        String processingStepKey = null;
        for (StepConfigDTO step : config.steps()) {
            if (step.key() == null || step.key().isBlank()) {
                throw new WorkflowValidationException("YAML invalide : étape sans clé.");
            }
            if (!stepKeys.add(step.key())) {
                throw new WorkflowValidationException(
                        "YAML incohérent : clé d'étape dupliquée '" + step.key() + "'.");
            }
            // Séparation des tâches : l'administrateur administre et supervise, il ne
            // valide jamais une étape — le rôle réservé ADMIN est refusé dans owners.
            if (step.owners() != null && step.owners().stream()
                    .anyMatch(owner -> owner != null
                            && WorkflowAuthorizationService.ADMIN_ROLE.equalsIgnoreCase(owner.trim()))) {
                throw new WorkflowValidationException("YAML invalide : le rôle réservé '"
                        + WorkflowAuthorizationService.ADMIN_ROLE + "' ne peut pas être responsable "
                        + "de l'étape '" + step.key() + "' (un administrateur n'est pas validateur).");
            }
            // Cible RESPONSABLE_TRAITEMENT : au plus une étape de traitement de référence
            // par circuit, sinon la reprise après rejet serait ambiguë.
            if (Boolean.TRUE.equals(step.processingStep())) {
                if (processingStepKey != null) {
                    throw new WorkflowValidationException("YAML incohérent : plusieurs étapes de "
                            + "traitement (processingStep: true) déclarées ('" + processingStepKey
                            + "' et '" + step.key() + "') ; une seule est autorisée par circuit.");
                }
                processingStepKey = step.key();
            }
            try {
                StepType type = StepType.valueOf(step.type());
                if (type == StepType.START) {
                    hasStart = true;
                }
                if (type == StepType.END) {
                    hasEnd = true;
                }
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new WorkflowValidationException("YAML invalide : type d'étape inconnu '"
                        + step.type() + "' pour '" + step.key() + "' (START, TASK ou END attendus).");
            }
        }
        if (!hasStart) {
            throw new WorkflowValidationException("YAML incohérent : aucune étape START.");
        }
        if (!hasEnd) {
            throw new WorkflowValidationException("YAML incohérent : aucune étape END.");
        }

        // Recette 1.5 : le bloc 'variables:' est obligatoire en production — sans lui,
        // une clé mal orthographiée en condition passerait l'import sans contrôle.
        // Un bloc vide est valide et signifie « aucune condition autorisée ».
        if (config.variables() == null) {
            throw new WorkflowValidationException(
                    "YAML invalide : bloc 'variables:' manquant "
                            + "(clés autorisées dans les conditions SpEL).");
        }
        Set<String> declaredVariables = new HashSet<>(config.variables());
        Set<String> virtualTargets = Set.of(
                WorkflowExecutionService.INITIATEUR_TARGET,
                WorkflowExecutionService.PREVIOUS_STEP_TARGET,
                WorkflowExecutionService.HANDLER_TARGET);
        boolean usesHandlerTarget = false;
        Map<TransitionConfigDTO, String> canonicalConditions = new HashMap<>();
        for (TransitionConfigDTO transition : config.transitions()) {
            if (!stepKeys.contains(transition.source())) {
                throw new WorkflowValidationException(
                        "YAML incohérent : étape source inconnue '" + transition.source() + "'.");
            }
            if (!stepKeys.contains(transition.target())
                    && !virtualTargets.contains(transition.target())) {
                throw new WorkflowValidationException("YAML incohérent : étape cible inconnue '"
                        + transition.target() + "' (transition " + transition.source()
                        + " -" + transition.action() + "-> " + transition.target() + ").");
            }
            if (WorkflowExecutionService.HANDLER_TARGET.equals(transition.target())) {
                usesHandlerTarget = true;
            }
            if (transition.condition() != null && !transition.condition().isBlank()) {
                canonicalConditions.put(
                        transition,
                        SpelSecurityValidator.validate(transition.condition(), declaredVariables));
            }
        }
        if (usesHandlerTarget && processingStepKey == null) {
            throw new WorkflowValidationException("YAML incohérent : la cible "
                    + WorkflowExecutionService.HANDLER_TARGET + " est utilisée mais aucune étape "
                    + "n'est marquée 'processingStep: true' ; annotez l'étape de traitement de "
                    + "référence (une seule par circuit).");
        }
        return canonicalConditions;
    }

    private Map<String, Object> toSettingsMap(SettingsConfigDTO settings) {
        Map<String, Object> map = new HashMap<>();
        
        // Application de la valeur par défaut "FROM_START" si non spécifiée dans le YAML
        String resumeStrategy = (settings != null && settings.resumeStrategyDefault() != null && !settings.resumeStrategyDefault().isBlank())
                ? settings.resumeStrategyDefault()
                : "FROM_START";
        map.put("resumeStrategyDefault", resumeStrategy);

        if (settings != null && settings.maxActiveInstancesPerInitiator() != null) {
            map.put("maxActiveInstancesPerInitiator", settings.maxActiveInstancesPerInitiator());
        }
        
        return map;
    }

    private List<EntryPoint> toEntryPoints(List<EntryPointConfigDTO> dtos) {
        List<EntryPoint> points = new ArrayList<>();
        if (dtos != null) {
            for (EntryPointConfigDTO dto : dtos) {
                points.add(new EntryPoint(dto.role(), dto.startStepKey()));
            }
        }
        return points;
    }
}
