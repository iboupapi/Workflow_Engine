package sn.gouv.mfp.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
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
import sn.gouv.mfp.workflow.domain.definition.VariableDeclaration;
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
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.ActiveInstanceLimitConfigDTO;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.EntryPointConfigDTO;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.SettingsConfigDTO;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.StepConfigDTO;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.TransitionConfigDTO;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO.VariableConfigDTO;

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
        // MOT-10 : les déclarations (clé, type, obligatoire) sont persistées ; les clés servent
        // aussi de liste blanche pour les conditions SpEL (recette 1.5).
        List<VariableDeclaration> declarations = toVariableDeclarations(config.variables());
        Set<String> declaredVars = declarations.stream()
                .map(VariableDeclaration::key)
                .collect(Collectors.toSet());
        definition.setVariableDeclarations(declarations);
        definition.setEntryPoints(toEntryPoints(config.entryPoints(), declaredVars));
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
            transition.setSetsHandler(Boolean.TRUE.equals(transDto.setsHandler()));
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
        } catch (UnrecognizedPropertyException e) {
            throw new WorkflowValidationException("Champ YAML inconnu : '" + e.getPropertyName()
                    + "'" + unknownFieldPath(e) + ". Les champs non reconnus sont refusés : "
                    + "corrigez l'orthographe ou retirez le champ.");
        } catch (JsonProcessingException e) {
            throw new WorkflowValidationException("YAML illisible : " + e.getOriginalMessage());
        }
    }

    /** Chemin ("steps[].ownerz") du champ inconnu, pour rendre l'erreur localisable. */
    private static String unknownFieldPath(UnrecognizedPropertyException e) {
        String path = e.getPath().stream()
                .map(JsonMappingException.Reference::getFieldName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
        return path.isBlank() ? "" : " (chemin : " + path + ")";
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
        Set<String> declaredVariables = new HashSet<>();
        for (VariableConfigDTO variable : config.variables()) {
            if (variable == null || variable.key() == null || variable.key().isBlank()) {
                throw new WorkflowValidationException(
                        "YAML incohérent : déclaration de variable sans clé.");
            }
            if (!declaredVariables.add(variable.key())) {
                throw new WorkflowValidationException(
                        "YAML incohérent : variable déclarée en double '" + variable.key() + "'.");
            }
            validateVariableType(variable);
        }
        Set<String> virtualTargets = Set.of(
                WorkflowExecutionService.INITIATEUR_TARGET,
                WorkflowExecutionService.PREVIOUS_STEP_TARGET,
                WorkflowExecutionService.HANDLER_TARGET);
        boolean usesHandlerTarget = false;
        boolean hasSetsHandlerTransition = false;
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
            if (Boolean.TRUE.equals(transition.setsHandler())) {
                hasSetsHandlerTransition = true;
            }
            if (transition.condition() != null && !transition.condition().isBlank()) {
                canonicalConditions.put(
                        transition,
                        SpelSecurityValidator.validate(transition.condition(), declaredVariables));
            }
        }
        if (config.entryPoints() != null) {
            for (EntryPointConfigDTO ep : config.entryPoints()) {
                if (ep.condition() != null && !ep.condition().isBlank()) {
                    SpelSecurityValidator.validate(ep.condition(), declaredVariables);
                }
            }
        }
        if (usesHandlerTarget && !hasSetsHandlerTransition) {
            throw new WorkflowValidationException("YAML incohérent : la cible "
                    + WorkflowExecutionService.HANDLER_TARGET + " est utilisée mais aucune transition "
                    + "n'est marquée 'setsHandler: true' ; annotez la transition de traitement de "
                    + "référence (celle qui franchit l'étape de traitement).");
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

        // Module 6.1 : plafond « un dossier en cours du même type d'acte », appliqué par
        // initiateur et nature d'acte (voir WorkflowExecutionService.enforceQuota).
        if (settings != null && settings.maxActiveInstancesPerType() != null) {
            map.put("maxActiveInstancesPerType", settings.maxActiveInstancesPerType());
        }

        // MOT-05 : plafond scopé par rôle initiateur ET par type d'acte (liste de règles).
        if (settings != null && settings.activeInstanceLimits() != null
                && !settings.activeInstanceLimits().isEmpty()) {
            List<Map<String, Object>> limits = new ArrayList<>();
            for (ActiveInstanceLimitConfigDTO limit
                    : settings.activeInstanceLimits()) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("role", limit.role());
                if (limit.maxPerType() != null) {
                    entry.put("maxPerType", limit.maxPerType());
                }
                if (limit.maxPerInitiator() != null) {
                    entry.put("maxPerInitiator", limit.maxPerInitiator());
                }
                limits.add(entry);
            }
            map.put("activeInstanceLimits", limits);
        }

        return map;
    }

    private List<EntryPoint> toEntryPoints(List<EntryPointConfigDTO> dtos, Set<String> declaredVariables) {
        List<EntryPoint> points = new ArrayList<>();
        if (dtos != null) {
            for (EntryPointConfigDTO dto : dtos) {
                String canonicalCond = null;
                if (dto.condition() != null && !dto.condition().isBlank()) {
                    canonicalCond = SpelSecurityValidator.validate(dto.condition(), declaredVariables);
                }
                points.add(new EntryPoint(dto.role(), dto.startStepKey(), canonicalCond));
            }
        }
        return points;
    }

    /** Types de variable acceptés (MOT-10) ; {@code string} par défaut. */
    private static final Set<String> VARIABLE_TYPES = Set.of("string", "number", "boolean", "date");

    /** MOT-10 : refuse à l'import un type de variable inconnu (une faute de frappe ne doit
     *  pas désactiver un contrôle en silence). Un type absent vaut {@code string}. */
    private void validateVariableType(VariableConfigDTO variable) {
        String type = variable.type();
        if (type != null && !type.isBlank()
                && !VARIABLE_TYPES.contains(type.trim().toLowerCase())) {
            throw new WorkflowValidationException("YAML incohérent : type de variable inconnu '"
                    + variable.type() + "' pour '" + variable.key() + "' "
                    + "(types acceptés : " + VARIABLE_TYPES + ").");
        }
    }

    /** Normalise les déclarations : type abaissé en casse, {@code string} par défaut,
     *  {@code required} absent vaut {@code false}. */
    private List<VariableDeclaration> toVariableDeclarations(List<VariableConfigDTO> dtos) {
        List<VariableDeclaration> declarations = new ArrayList<>();
        if (dtos != null) {
            for (VariableConfigDTO dto : dtos) {
                String type = dto.type() == null || dto.type().isBlank()
                        ? "string"
                        : dto.type().trim().toLowerCase();
                declarations.add(new VariableDeclaration(
                        dto.key(), type, Boolean.TRUE.equals(dto.required())));
            }
        }
        return declarations;
    }
}
