package sn.gouv.mfp.workflow.service.admin;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.VariableDeclaration;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.request.FormSchema;
import sn.gouv.mfp.workflow.domain.request.RequestType;
import sn.gouv.mfp.workflow.domain.request.RequestTypeStatus;
import sn.gouv.mfp.workflow.domain.request.RequestTypeVersion;
import sn.gouv.mfp.workflow.exception.WorkflowConflictException;
import sn.gouv.mfp.workflow.exception.WorkflowNotFoundException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.repository.FormSchemaRepository;
import sn.gouv.mfp.workflow.repository.RequestTypeRepository;
import sn.gouv.mfp.workflow.repository.RequestTypeVersionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.service.FormSchemaValidationService;

/**
 * Administration des E-Services (REQ-01, REQ-02, FRM-01) : créer un type de demande,
 * enregistrer son formulaire, puis publier la version qui lie formulaire et circuit.
 * Ouvre un nouveau service à l'usager sans écriture SQL directe.
 * <p>
 * <strong>Le référentiel publié est immuable</strong> (déclencheur V13) : publier ne modifie
 * jamais une version existante, cela en crée une nouvelle ({@code version + 1}). Les dossiers
 * déjà ouverts restent donc interprétables (FRM-04) et une correction de formulaire passe
 * toujours par une republication.
 */
@Service
public class AdminRequestTypeService {

    private static final Logger log = LoggerFactory.getLogger(AdminRequestTypeService.class);

    /**
     * Clé métier d'un type de demande : majuscules, chiffres et soulignés, commençant par une
     * lettre. Cette clé voyage dans les URL ({@code /api/request-types/{typeKey}/form}) et
     * sert d'identité stable au-delà des libellés.
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    private static final int MAX_LABEL_LENGTH = 255;
    private static final int MAX_CATEGORY_LENGTH = 128;

    private final RequestTypeRepository requestTypes;
    private final RequestTypeVersionRepository versions;
    private final FormSchemaRepository formSchemas;
    private final WorkflowDefinitionRepository definitions;
    private final FormSchemaValidationService schemaValidation;

    public AdminRequestTypeService(
            RequestTypeRepository requestTypes,
            RequestTypeVersionRepository versions,
            FormSchemaRepository formSchemas,
            WorkflowDefinitionRepository definitions,
            FormSchemaValidationService schemaValidation) {
        this.requestTypes = requestTypes;
        this.versions = versions;
        this.formSchemas = formSchemas;
        this.definitions = definitions;
        this.schemaValidation = schemaValidation;
    }

    /** Référentiel complet, tous statuts, avec la dernière version de chaque type. */
    @Transactional(readOnly = true)
    public List<AdminRequestTypeView> listAll() {
        return requestTypes.findAllByOrderByLabelAsc().stream()
                .map(type -> view(type, latestVersion(type.getId())))
                .toList();
    }

    /** Détail d'un type : dernière version, schéma de formulaire et liaison de champs. */
    @Transactional(readOnly = true)
    public AdminRequestTypeDetailView detail(UUID requestTypeId) {
        RequestType type = requestTypes.findById(requestTypeId)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Type de demande inconnu : aucune entrée pour l'identifiant "
                                + requestTypeId + "."));
        RequestTypeVersion version = latestVersion(type.getId());
        FormSchema schema = version == null ? null : version.getFormSchema();
        return new AdminRequestTypeDetailView(
                type.getId(),
                type.getKey(),
                type.getLabel(),
                type.getCategory(),
                type.getStatus().name(),
                version == null ? null : version.getVersion(),
                version == null ? null : version.getPublishedAt(),
                version == null ? null : version.getDefinition().getKey(),
                version == null ? null : version.getFieldMapping(),
                schema == null ? null : schema.getJsonSchema(),
                schema == null ? null : schema.getUiSchema());
    }

    /** Circuits disponibles pour une nouvelle publication : dernière version de chaque clé. */
    @Transactional(readOnly = true)
    public List<DeployedWorkflowView> deployedWorkflows() {
        return definitions.findLatestVersionPerKey().stream()
                .map(definition -> new DeployedWorkflowView(
                        definition.getKey(), definition.getName(), definition.getVersion()))
                .toList();
    }

    /**
     * Crée un type de demande en brouillon : rien n'est encore proposé à l'usager tant
     * qu'aucune version n'est publiée.
     *
     * @throws WorkflowValidationException clé ou libellé invalide
     * @throws WorkflowConflictException   clé déjà prise
     */
    @Transactional
    public AdminRequestTypeView create(String typeKey, String label, String category) {
        String key = require(typeKey, "La clé du type de demande est obligatoire.");
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new WorkflowValidationException(
                    "Clé de type de demande invalide ('" + key + "') : majuscules, chiffres et "
                            + "soulignés, commençant par une lettre, de 2 à 64 caractères "
                            + "(ex. CONGE_MATERNITE).");
        }
        String cleanLabel = require(label, "Le libellé du type de demande est obligatoire.");
        if (cleanLabel.length() > MAX_LABEL_LENGTH) {
            throw new WorkflowValidationException(
                    "Libellé trop long : " + cleanLabel.length() + " caractères, maximum "
                            + MAX_LABEL_LENGTH + ".");
        }
        String cleanCategory = blankToNull(category);
        if (cleanCategory != null && cleanCategory.length() > MAX_CATEGORY_LENGTH) {
            throw new WorkflowValidationException(
                    "Catégorie trop longue : " + cleanCategory.length() + " caractères, maximum "
                            + MAX_CATEGORY_LENGTH + ".");
        }
        if (requestTypes.existsByKey(key)) {
            throw new WorkflowConflictException(
                    "Un type de demande existe déjà pour la clé '" + key + "'.");
        }

        RequestType type = new RequestType();
        type.setKey(key);
        type.setLabel(cleanLabel);
        type.setCategory(cleanCategory);
        type.setStatus(RequestTypeStatus.DRAFT);
        RequestType saved = requestTypes.saveAndFlush(type);
        log.info("Type de demande '{}' ({}) créé en brouillon", key, saved.getId());
        return view(saved, null);
    }

    /**
     * Enregistre un schéma de formulaire après contrôle (FRM-01). Le schéma n'est pas encore
     * en vigueur : il ne le devient qu'une fois rattaché à une version publiée.
     *
     * @throws WorkflowValidationException schéma vide, invalide ou inexploitable
     */
    @Transactional
    public FormSchemaView createFormSchema(
            Map<String, Object> jsonSchema, Map<String, Object> uiSchema) {
        schemaValidation.validateDefinition(jsonSchema);
        FormSchema schema = new FormSchema();
        schema.setJsonSchema(jsonSchema);
        schema.setUiSchema(uiSchema);
        FormSchema saved = formSchemas.saveAndFlush(schema);
        log.info("Schéma de formulaire {} enregistré ({} champ(s))",
                saved.getId(), propertyNames(jsonSchema).size());
        return new FormSchemaView(
                saved.getId(), saved.getJsonSchema(), saved.getUiSchema(), saved.getCreatedAt());
    }

    /**
     * Publie une version : lie le type, le schéma de formulaire et le circuit, puis rend le
     * type proposable aux usagers. La liaison de champs est contrôlée contre les variables
     * <strong>déclarées</strong> par le circuit (REQ-02, MOT-10) : une liaison vers une
     * variable inconnue produirait une demande systématiquement refusée à la création du
     * dossier, mieux vaut la refuser ici.
     *
     * @throws WorkflowNotFoundException   type, schéma ou circuit inconnu
     * @throws WorkflowValidationException liaison de champs incohérente
     */
    @Transactional
    public AdminRequestTypeView publish(
            UUID requestTypeId,
            UUID formSchemaId,
            String workflowDefinitionKey,
            Map<String, Object> fieldMapping) {
        RequestType type = requestTypes.findById(requestTypeId)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Type de demande inconnu : aucune publication possible pour "
                                + requestTypeId + "."));
        FormSchema schema = formSchemas.findById(formSchemaId)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Schéma de formulaire inconnu : " + formSchemaId + "."));
        WorkflowDefinition definition = latestDefinition(require(
                workflowDefinitionKey, "La clé du circuit est obligatoire."));
        Map<String, Object> mapping = normalizeMapping(fieldMapping);
        validateMapping(mapping, schema, definition);

        int nextVersion = versions.findFirstByRequestTypeIdOrderByVersionDesc(type.getId())
                .map(previous -> previous.getVersion() + 1)
                .orElse(1);

        RequestTypeVersion version = new RequestTypeVersion();
        version.setRequestType(type);
        version.setVersion(nextVersion);
        version.setFormSchema(schema);
        version.setDefinition(definition);
        version.setFieldMapping(mapping.isEmpty() ? null : mapping);
        // Immuable dès l'insertion (déclencheur V13) : on n'insère que des versions publiées.
        version.setPublishedAt(Instant.now());
        RequestTypeVersion saved = versions.saveAndFlush(version);

        type.setStatus(RequestTypeStatus.PUBLISHED);
        requestTypes.saveAndFlush(type);

        log.info("Type de demande '{}' publié en version {} (circuit {} v{}, formulaire {}, "
                        + "{} liaison(s))",
                type.getKey(), nextVersion, definition.getKey(), definition.getVersion(),
                schema.getId(), mapping.size());
        return view(type, saved);
    }

    /** Dernière version déployée du circuit ; un circuit peut avoir plusieurs versions. */
    private WorkflowDefinition latestDefinition(String definitionKey) {
        return definitions.findByKey(definitionKey).stream()
                .max(Comparator.comparing(WorkflowDefinition::getVersion))
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Aucun circuit déployé pour la clé '" + definitionKey + "' : importez la "
                                + "définition avant de publier un type de demande."));
    }

    private void validateMapping(
            Map<String, Object> mapping, FormSchema schema, WorkflowDefinition definition) {
        if (mapping.isEmpty()) {
            return;
        }
        Set<String> fields = propertyNames(schema.getJsonSchema());
        Set<String> variables = definition.getVariableDeclarations() == null
                ? Set.of()
                : definition.getVariableDeclarations().stream()
                        .map(VariableDeclaration::key)
                        .collect(Collectors.toSet());

        for (Map.Entry<String, Object> binding : mapping.entrySet()) {
            if (!fields.contains(binding.getKey())) {
                throw new WorkflowValidationException(
                        "Liaison de champ invalide (REQ-02) : le champ '" + binding.getKey()
                                + "' ne figure pas dans le formulaire publié.");
            }
            String target = String.valueOf(binding.getValue());
            if (!variables.contains(target)) {
                throw new WorkflowValidationException(
                        "Liaison de champ invalide (REQ-02) : la variable '" + target
                                + "' n'est pas déclarée par le circuit '" + definition.getKey()
                                + "' (variables connues : "
                                + (variables.isEmpty() ? "aucune" : String.join(", ", variables))
                                + ").");
            }
        }
    }

    /** Champs déclarés par un schéma (`properties`), ensemble vide si le schéma est illisible. */
    private static Set<String> propertyNames(Map<String, Object> jsonSchema) {
        if (jsonSchema == null || !(jsonSchema.get("properties") instanceof Map<?, ?> properties)) {
            return Set.of();
        }
        return properties.keySet().stream().map(String::valueOf).collect(Collectors.toSet());
    }

    private static Map<String, Object> normalizeMapping(Map<String, Object> fieldMapping) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        if (fieldMapping == null) {
            return mapping;
        }
        for (Map.Entry<String, Object> binding : fieldMapping.entrySet()) {
            Object target = binding.getValue();
            if (!(target instanceof String name) || name.isBlank()) {
                throw new WorkflowValidationException(
                        "Liaison de champ invalide (REQ-02) : le champ '" + binding.getKey()
                                + "' ne désigne aucune variable moteur.");
            }
            mapping.put(binding.getKey(), name);
        }
        return mapping;
    }

    private RequestTypeVersion latestVersion(UUID requestTypeId) {
        return versions.findFirstByRequestTypeIdOrderByVersionDesc(requestTypeId).orElse(null);
    }

    private static AdminRequestTypeView view(RequestType type, RequestTypeVersion version) {
        return new AdminRequestTypeView(
                type.getId(),
                type.getKey(),
                type.getLabel(),
                type.getCategory(),
                type.getStatus().name(),
                version == null ? null : version.getVersion(),
                version == null ? null : version.getPublishedAt(),
                version == null ? null : version.getDefinition().getKey());
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new WorkflowValidationException(message);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Type de demande exposé à l'administration (dernière version comprise). */
    public record AdminRequestTypeView(
            UUID id,
            String key,
            String label,
            String category,
            String status,
            Integer version,
            Instant publishedAt,
            String definitionKey) {
    }

    /** Détail d'un type de demande : la version en vigueur et son formulaire. */
    public record AdminRequestTypeDetailView(
            UUID id,
            String key,
            String label,
            String category,
            String status,
            Integer version,
            Instant publishedAt,
            String definitionKey,
            Map<String, Object> fieldMapping,
            Map<String, Object> jsonSchema,
            Map<String, Object> uiSchema) {
    }

    /** Schéma de formulaire enregistré (identifiant à réutiliser à la publication). */
    public record FormSchemaView(
            UUID id,
            Map<String, Object> jsonSchema,
            Map<String, Object> uiSchema,
            Instant createdAt) {
    }

    /** Circuit déployé proposable à la publication. */
    public record DeployedWorkflowView(String key, String name, Integer version) {
    }
}
