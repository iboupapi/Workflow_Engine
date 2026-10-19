package sn.gouv.mfp.workflow.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.domain.request.RequestSubmission;
import sn.gouv.mfp.workflow.domain.request.RequestSubmissionStatus;
import sn.gouv.mfp.workflow.domain.request.RequestType;
import sn.gouv.mfp.workflow.domain.request.RequestTypeStatus;
import sn.gouv.mfp.workflow.domain.request.RequestTypeVersion;
import sn.gouv.mfp.workflow.exception.FormSchemaValidationException;
import sn.gouv.mfp.workflow.exception.WorkflowConflictException;
import sn.gouv.mfp.workflow.exception.WorkflowForbiddenException;
import sn.gouv.mfp.workflow.exception.WorkflowNotFoundException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.repository.RequestSubmissionRepository;
import sn.gouv.mfp.workflow.repository.RequestTypeRepository;
import sn.gouv.mfp.workflow.repository.RequestTypeVersionRepository;

/**
 * REQ-01 / REQ-03 — soumission des demandes des usagers.
 * <p>
 * Le type de demande publié porte une version en vigueur : c'est elle qui fournit le
 * formulaire servi à l'usager ({@link #activeForm}), le schéma de validation (FRM-03), la
 * liaison champs → variables (REQ-02) et le circuit instancié.
 * <p>
 * Parcours d'une demande :
 * <ol>
 *   <li>{@link #saveDraft} enregistre une saisie incomplète, sans dossier : elle est
 *       reprenable et modifiable par son auteur ;</li>
 *   <li>{@link #submit} (saisie directe) ou {@link #submitDraft} valide la saisie : un
 *       écart au schéma refuse la soumission (400) <strong>sans rien persister</strong> ;</li>
 *   <li>la soumission valide crée le dossier sur l'étape de départ du circuit, alimenté par
 *       les seules variables issues de la liaison (REQ-02), puis fige la saisie
 *       ({@link RequestSubmissionStatus#SUBMITTED}) avec son dossier et son horodatage.</li>
 * </ol>
 * Le demandeur est toujours l'identité certifiée portée par le jeton (SEC-03) ; la
 * soumission ne fixe aucun périmètre organisationnel : le dossier est visible des rôles
 * habilités du circuit, pas d'une direction particulière.
 */
@Service
public class RequestSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(RequestSubmissionService.class);

    private final RequestTypeRepository requestTypes;
    private final RequestTypeVersionRepository versions;
    private final RequestSubmissionRepository submissions;
    private final FormSchemaValidationService schemaValidation;
    private final RequestFieldMappingService fieldMapping;
    private final WorkflowExecutionService executionService;

    public RequestSubmissionService(
            RequestTypeRepository requestTypes,
            RequestTypeVersionRepository versions,
            RequestSubmissionRepository submissions,
            FormSchemaValidationService schemaValidation,
            RequestFieldMappingService fieldMapping,
            WorkflowExecutionService executionService) {
        this.requestTypes = requestTypes;
        this.versions = versions;
        this.submissions = submissions;
        this.schemaValidation = schemaValidation;
        this.fieldMapping = fieldMapping;
        this.executionService = executionService;
    }

    /** Catalogue proposé à l'usager : types de demande publiés, triés par libellé (REQ-01). */
    @Transactional(readOnly = true)
    public List<TypeView> publishedTypes() {
        return requestTypes.findByStatusOrderByLabelAsc(RequestTypeStatus.PUBLISHED).stream()
                .map(type -> new TypeView(type.getId(), type.getKey(), type.getLabel(), type.getCategory()))
                .toList();
    }

    /**
     * Formulaire de la version publiée en vigueur d'un type de demande.
     *
     * @throws WorkflowNotFoundException type inconnu, non publié, ou sans version publiée
     */
    @Transactional(readOnly = true)
    public FormView activeForm(String typeKey) {
        RequestTypeVersion version = activeVersion(typeKey);
        RequestType type = version.getRequestType();
        return new FormView(
                type.getKey(),
                type.getLabel(),
                version.getVersion(),
                version.getFormSchema().getJsonSchema(),
                version.getFormSchema().getUiSchema());
    }

    /**
     * Enregistre un brouillon : la saisie n'est <strong>pas</strong> validée contre le schéma
     * (un brouillon est incomplet par nature) et aucun dossier n'est créé.
     */
    @Transactional
    public SubmissionView saveDraft(String typeKey, Map<String, Object> formData, String author) {
        RequestTypeVersion version = activeVersion(typeKey);

        RequestSubmission draft = new RequestSubmission();
        draft.setRequestTypeVersion(version);
        draft.setFormData(copy(formData));
        draft.setSubmittedBy(author);
        draft = submissions.save(draft);

        log.info("Brouillon {} enregistré par '{}' sur '{}' v{}",
                draft.getId(), author, typeKey, version.getVersion());
        return view(draft);
    }

    /** Brouillons d'un demandeur, du plus récent au plus ancien. */
    @Transactional(readOnly = true)
    public List<SubmissionView> drafts(String author) {
        return submissions
                .findBySubmittedByAndStatusOrderByCreatedAtDesc(author, RequestSubmissionStatus.DRAFT)
                .stream()
                .map(this::view)
                .toList();
    }

    /**
     * Brouillon complet, pour reprendre la saisie là où elle s'est arrêtée.
     *
     * @throws WorkflowNotFoundException  brouillon inconnu
     * @throws WorkflowForbiddenException brouillon d'un autre demandeur
     * @throws WorkflowConflictException  la saisie a déjà été soumise
     */
    @Transactional(readOnly = true)
    public DraftView draft(UUID draftId, String author) {
        RequestSubmission draft = readableDraft(draftId, author);
        RequestTypeVersion version = draft.getRequestTypeVersion();
        return new DraftView(
                draft.getId(),
                version.getRequestType().getKey(),
                version.getVersion(),
                draft.getFormData(),
                draft.getCreatedAt());
    }

    /**
     * Soumet directement une saisie : validation du formulaire, liaison des variables,
     * création du dossier, enregistrement de la demande soumise.
     *
     * @throws WorkflowNotFoundException      type de demande non disponible
     * @throws FormSchemaValidationException  saisie non conforme au formulaire publié
     */
    @Transactional
    public SubmissionView submit(String typeKey, Map<String, Object> formData, String author) {
        RequestTypeVersion version = activeVersion(typeKey);
        Map<String, Object> data = copy(formData);
        WorkflowInstance instance = startInstance(version, data, author);

        RequestSubmission submission = new RequestSubmission();
        submission.setRequestTypeVersion(version);
        submission.setFormData(data);
        submission.setSubmittedBy(author);
        submission.setInstance(instance);
        submission.setSubmittedAt(Instant.now());
        submission.setStatus(RequestSubmissionStatus.SUBMITTED);
        submission = submissions.save(submission);

        log.info("Demande {} soumise par '{}' sur '{}' v{} (dossier {})",
                submission.getId(), author, typeKey, version.getVersion(), instance.getId());
        return view(submission);
    }

    /**
     * Soumet un brouillon existant : il devient la demande définitive, rattachée au dossier
     * créé. Seul son auteur peut le soumettre, et une seule fois.
     *
     * @throws WorkflowNotFoundException     brouillon inconnu
     * @throws WorkflowForbiddenException    brouillon d'un autre demandeur
     * @throws WorkflowConflictException     brouillon déjà soumis
     * @throws FormSchemaValidationException saisie non conforme au formulaire publié
     */
    @Transactional
    public SubmissionView submitDraft(UUID draftId, String author) {
        RequestSubmission draft = readableDraft(draftId, author);

        WorkflowInstance instance = startInstance(draft.getRequestTypeVersion(), draft.getFormData(), author);
        draft.setInstance(instance);
        draft.setSubmittedAt(Instant.now());
        draft.setStatus(RequestSubmissionStatus.SUBMITTED);
        submissions.save(draft);

        log.info("Brouillon {} soumis par '{}' (dossier {})", draft.getId(), author, instance.getId());
        return view(draft);
    }

    /** Brouillon lisible par {@code author} : existant, encore brouillon et à lui. */
    private RequestSubmission readableDraft(UUID draftId, String author) {
        RequestSubmission draft = submissions
                .findById(draftId)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Aucune saisie de demande pour l'identifiant '" + draftId + "'."));
        if (!draft.isDraft()) {
            throw new WorkflowConflictException(
                    "La demande '" + draftId + "' a déjà été soumise le " + draft.getSubmittedAt() + ".");
        }
        if (draft.getSubmittedBy() != null && !draft.getSubmittedBy().equals(author)) {
            throw new WorkflowForbiddenException(
                    "Un brouillon n'est consultable et soumis que par son auteur.");
        }
        return draft;
    }

    /**
     * Valide la saisie (FRM-03), en dérive les variables de décision (REQ-02) et instancie
     * le circuit de la version publiée.
     * <p>
     * Limite assumée : le circuit est démarré par sa clé sur la dernière version déployée
     * (le moteur n'instancie jamais une version antérieure d'un circuit) ; la version du
     * type de demande, elle, est bien celle de la saisie.
     */
    private WorkflowInstance startInstance(
            RequestTypeVersion version, Map<String, Object> formData, String author) {
        List<String> violations =
                schemaValidation.validateAndCollect(version.getFormSchema(), formData);
        if (!violations.isEmpty()) {
            throw new FormSchemaValidationException(
                    "Les données de la demande ne respectent pas le formulaire publié.", violations);
        }
        Map<String, Object> variables =
                fieldMapping.toWorkflowVariables(version.getFieldMapping(), formData);
        return executionService.startWorkflow(
                version.getDefinition().getKey(), author, variables, null);
    }

    private RequestTypeVersion activeVersion(String typeKey) {
        if (typeKey == null || typeKey.isBlank()) {
            throw new WorkflowValidationException("Le type de demande est obligatoire.");
        }
        return versions
                .findFirstByRequestTypeKeyAndRequestTypeStatusAndPublishedAtIsNotNullOrderByVersionDesc(
                        typeKey, RequestTypeStatus.PUBLISHED)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Aucun formulaire publié pour le type de demande '" + typeKey + "'."));
    }

    private SubmissionView view(RequestSubmission submission) {
        RequestTypeVersion version = submission.getRequestTypeVersion();
        return new SubmissionView(
                submission.getId(),
                version.getRequestType().getKey(),
                version.getVersion(),
                submission.getStatus(),
                submission.getInstance() != null ? submission.getInstance().getId() : null,
                submission.getCreatedAt(),
                submission.getSubmittedAt());
    }

    private static Map<String, Object> copy(Map<String, Object> formData) {
        return formData == null ? new LinkedHashMap<>() : new LinkedHashMap<>(formData);
    }

    /** Type de demande proposé à l'usager (catalogue, REQ-01). */
    public record TypeView(UUID id, String key, String label, String category) {
    }

    /** Formulaire servi à l'usager : schéma de validation et présentation. */
    public record FormView(
            String typeKey,
            String label,
            int version,
            Map<String, Object> schema,
            Map<String, Object> uiSchema) {
    }

    /** Demande telle que suivie par le demandeur (brouillon ou soumission). */
    public record SubmissionView(
            UUID id,
            String typeKey,
            int version,
            RequestSubmissionStatus status,
            UUID instanceId,
            Instant createdAt,
            Instant submittedAt) {
    }

    /** Brouillon complet : la saisie enregistrée, pour la reprendre dans le formulaire. */
    public record DraftView(
            UUID id,
            String typeKey,
            int version,
            Map<String, Object> formData,
            Instant createdAt) {
    }
}
