package sn.gouv.mfp.workflow.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.request.AttachmentScanStatus;
import sn.gouv.mfp.workflow.domain.request.RequestAttachment;
import sn.gouv.mfp.workflow.domain.request.RequestSubmission;
import sn.gouv.mfp.workflow.domain.request.RequestTypeVersion;
import sn.gouv.mfp.workflow.exception.WorkflowConflictException;
import sn.gouv.mfp.workflow.exception.WorkflowNotFoundException;
import sn.gouv.mfp.workflow.exception.WorkflowPayloadTooLargeException;
import sn.gouv.mfp.workflow.exception.WorkflowUnsupportedMediaTypeException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.repository.RequestAttachmentRepository;
import sn.gouv.mfp.workflow.repository.RequestSubmissionRepository;
import sn.gouv.mfp.workflow.service.attachment.AntivirusScanner;
import sn.gouv.mfp.workflow.service.attachment.AttachmentProperties;
import sn.gouv.mfp.workflow.service.attachment.AttachmentStorage;
import sn.gouv.mfp.workflow.service.attachment.ContentTypeSniffer;
import sn.gouv.mfp.workflow.service.WorkflowReadService;
import sn.gouv.mfp.workflow.service.WorkflowReadService.Viewer;

/**
 * FRM-05 — pièces jointes d'une demande : liste de types autorisés, taille maximale,
 * analyse antivirus <strong>avant</strong> mise à disposition, empreinte SHA-256 et accès
 * gouverné par les mêmes règles que le dossier.
 * <p>
 * Trois garanties structurent ce service :
 * <ol>
 *   <li>le type MIME consigné est le type <strong>réel</strong>, déduit du contenu, et il
 *       doit appartenir à la liste autorisée — l'en-tête déclaré par le client n'engage
 *       personne ;</li>
 *   <li>aucun contenu n'est servi sans verdict antivirus favorable : une pièce dont
 *       l'analyse refuse le contenu est supprimée du stockage, jamais enregistrée, donc
 *       jamais mise à disposition ;</li>
 *   <li>une pièce jointe ne se lit pas plus largement que son dossier : avant soumission
 *       seul son auteur la voit, après soumission s'appliquent les règles de visibilité du
 *       dossier (les invisibles répondent 404, sans fuite d'existence).</li>
 * </ol>
 * Le dépôt n'est ouvert que sur un <strong>brouillon</strong>, par son auteur : les pièces
 * jointes se préparent avant la soumission. L'ajout de pièces à un dossier déjà transmis
 * relèvera des droits par champ et par étape (FRM-02), non implémentés à ce stade.
 */
@Service
public class RequestAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(RequestAttachmentService.class);

    private static final int MAX_FILENAME_LENGTH = 255;
    private static final int MAX_FIELD_PATH_LENGTH = 255;

    private final RequestSubmissionRepository submissions;
    private final RequestAttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final AntivirusScanner antivirus;
    private final AttachmentProperties properties;
    private final WorkflowReadService readService;

    public RequestAttachmentService(
            RequestSubmissionRepository submissions,
            RequestAttachmentRepository attachments,
            AttachmentStorage storage,
            AntivirusScanner antivirus,
            AttachmentProperties properties,
            WorkflowReadService readService) {
        this.submissions = submissions;
        this.attachments = attachments;
        this.storage = storage;
        this.antivirus = antivirus;
        this.properties = properties;
        this.readService = readService;
    }

    /**
     * Dépose une pièce jointe sur un brouillon.
     *
     * @throws WorkflowNotFoundException                demande inconnue, ou brouillon d'un
     *                                                  autre demandeur (invisible = 404)
     * @throws WorkflowValidationException              champ non déclaré, fichier vide, ou
     *                                                  contenu refusé par l'analyse antivirus
     * @throws WorkflowPayloadTooLargeException         taille maximale dépassée
     * @throws WorkflowUnsupportedMediaTypeException    type réel non autorisé ou non reconnu
     */
    @Transactional
    public AttachmentView store(
            UUID submissionId,
            String fieldPath,
            String filename,
            String declaredContentType,
            byte[] content,
            String actor) {
        RequestSubmission submission = writableDraft(submissionId, actor);
        requireDeclaredField(submission.getRequestTypeVersion(), fieldPath);
        requireContent(content);
        String safeFilename = safeFilename(filename);

        String realType = ContentTypeSniffer.sniff(content)
                .filter(properties::allows)
                .orElseThrow(() -> new WorkflowUnsupportedMediaTypeException(
                        "Type de fichier non autorisé pour '" + safeFilename + "' : types acceptés "
                                + allowedTypes() + "."));
        if (declaredContentType != null
                && !declaredContentType.isBlank()
                && !declaredContentType.equalsIgnoreCase(realType)) {
            log.info("Pièce jointe '{}' : type déclaré '{}' ignoré, type réel '{}' retenu (FRM-05).",
                    safeFilename, declaredContentType, realType);
        }

        String sha256 = sha256(content);
        String storageKey = storage.store(submission.getId(), safeFilename, content);
        AttachmentScanStatus verdict = antivirus.scan(safeFilename, content);
        if (verdict != AttachmentScanStatus.CLEAN) {
            storage.delete(storageKey);
            log.warn("Pièce jointe '{}' refusée par l'analyse antivirus ({}) : contenu non conservé.",
                    safeFilename, verdict);
            throw new WorkflowValidationException(
                    "La pièce jointe '" + safeFilename + "' a été refusée par l'analyse antivirus "
                            + "et n'a pas été conservée.");
        }

        RequestAttachment attachment = new RequestAttachment();
        attachment.setSubmission(submission);
        attachment.setFieldPath(fieldPath);
        attachment.setFilename(safeFilename);
        attachment.setMimeType(realType);
        attachment.setSizeBytes(content.length);
        attachment.setSha256Hash(sha256);
        attachment.setStorageKey(storageKey);
        attachment.setScanStatus(AttachmentScanStatus.CLEAN);
        attachment.setCreatedAt(Instant.now());
        attachment = attachments.save(attachment);

        log.info("Pièce jointe {} déposée par '{}' sur la demande {} ('{}', {} octets, sha256={})",
                attachment.getId(), actor, submission.getId(), safeFilename, content.length, sha256);
        return view(attachment);
    }

    /** Pièces jointes visibles d'une demande (brouillon de son auteur, ou dossier lisible). */
    @Transactional(readOnly = true)
    public List<AttachmentView> list(UUID submissionId, Viewer viewer) {
        RequestSubmission submission = readableSubmission(submissionId, viewer);
        return attachments.findBySubmissionIdOrderByCreatedAtAsc(submission.getId()).stream()
                .map(RequestAttachmentService::view)
                .toList();
    }

    /**
     * Pièces jointes d'un dossier, pour la fiche dossier : le dossier est d'abord soumis à ses
     * propres règles de visibilité (404 si invisible), puis ses pièces sont celles de la
     * demande qui l'a créé. Un dossier ouvert par le moteur seul (sans demande) n'en a aucune :
     * l'absence de demande se traduit par une liste vide, pas par un refus.
     *
     * @throws WorkflowNotFoundException dossier inconnu ou non visible
     */
    @Transactional(readOnly = true)
    public List<AttachmentView> listForInstance(UUID instanceId, Viewer viewer) {
        readService.visibleOrThrow(instanceId, viewer);
        return submissions
                .findByInstanceId(instanceId)
                .map(submission -> list(submission.getId(), viewer))
                .orElseGet(List::of);
    }

    /**
     * Contenu d'une pièce jointe, sous les règles de visibilité de son dossier.
     *
     * @throws WorkflowNotFoundException              pièce inconnue ou demande invisible
     * @throws WorkflowConflictException              verdict antivirus pas encore favorable
     */
    @Transactional(readOnly = true)
    public AttachmentDownload download(UUID attachmentId, Viewer viewer) {
        RequestAttachment attachment = attachments
                .findById(attachmentId)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Aucune pièce jointe pour l'identifiant '" + attachmentId + "'."));
        readableSubmission(attachment.getSubmission().getId(), viewer);
        if (!attachment.isAvailable()) {
            throw new WorkflowConflictException(
                    "La pièce jointe '" + attachment.getFilename() + "' n'est pas disponible "
                            + "(analyse antivirus " + attachment.getScanStatus() + ").");
        }
        return new AttachmentDownload(
                attachment.getFilename(),
                attachment.getMimeType(),
                attachment.getSizeBytes(),
                attachment.getSha256Hash(),
                storage.load(attachment.getStorageKey()));
    }

    /** Brouillon modifiable par {@code actor} : la pièce jointe se prépare avant soumission. */
    private RequestSubmission writableDraft(UUID submissionId, String actor) {
        RequestSubmission submission = readableSubmission(submissionId, new Viewer(actor, Set.of(), false));
        if (!submission.isDraft()) {
            throw new WorkflowValidationException(
                    "La demande '" + submissionId + "' a déjà été soumise : une pièce jointe ne "
                            + "peut plus y être déposée.");
        }
        return submission;
    }

    /** Demande lisible : brouillon de son auteur, ou dossier visible selon ses règles. */
    private RequestSubmission readableSubmission(UUID submissionId, Viewer viewer) {
        RequestSubmission submission = submissions
                .findById(submissionId)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Aucune demande pour l'identifiant '" + submissionId + "'."));
        if (submission.isDraft()) {
            if (viewer == null || viewer.userId() == null
                    || !viewer.userId().equals(submission.getSubmittedBy())) {
                throw new WorkflowNotFoundException(
                        "Aucune demande pour l'identifiant '" + submissionId + "'.");
            }
            return submission;
        }
        // Soumise : mêmes règles que le dossier (404 si invisible, aucune fuite d'existence).
        readService.visibleOrThrow(submission.getInstance().getId(), viewer);
        return submission;
    }

    /** Le champ visé doit exister dans le formulaire publié de la demande. */
    private void requireDeclaredField(RequestTypeVersion version, String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) {
            throw new WorkflowValidationException("Le champ de la pièce jointe est obligatoire.");
        }
        if (fieldPath.length() > MAX_FIELD_PATH_LENGTH) {
            throw new WorkflowValidationException(
                    "Le champ de la pièce jointe ne peut pas dépasser " + MAX_FIELD_PATH_LENGTH
                            + " caractères.");
        }
        Object properties = version.getFormSchema().getJsonSchema() != null
                ? version.getFormSchema().getJsonSchema().get("properties")
                : null;
        if (!(properties instanceof Map<?, ?> declared) || !declared.containsKey(fieldPath)) {
            throw new WorkflowValidationException(
                    "Le champ '" + fieldPath + "' ne fait pas partie du formulaire de cette demande.");
        }
    }

    private void requireContent(byte[] content) {
        if (content == null || content.length == 0) {
            throw new WorkflowValidationException("Une pièce jointe vide n'est pas acceptée.");
        }
        if (content.length > properties.maxSizeBytes()) {
            throw new WorkflowPayloadTooLargeException(
                    "Pièce jointe trop volumineuse : " + content.length + " octets reçus, "
                            + properties.maxSizeBytes() + " octets autorisés.");
        }
    }

    /** Nom d'origine nettoyé : sans chemin, sans caractère de contrôle, longueur bornée. */
    private static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new WorkflowValidationException("Le nom du fichier est obligatoire.");
        }
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        name = name.replaceAll("^\\.+", "");
        if (name.isBlank()) {
            throw new WorkflowValidationException("Le nom du fichier est invalide.");
        }
        return name.length() > MAX_FILENAME_LENGTH ? name.substring(0, MAX_FILENAME_LENGTH) : name;
    }

    private String allowedTypes() {
        return String.join(", ", properties.allowedMimeTypes());
    }

    /** Empreinte SHA-256 du contenu, en hexadécimal minuscule. */
    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible sur cette plateforme.", e);
        }
    }

    private static AttachmentView view(RequestAttachment attachment) {
        return new AttachmentView(
                attachment.getId(),
                attachment.getSubmission().getId(),
                attachment.getFieldPath(),
                attachment.getFilename(),
                attachment.getMimeType(),
                attachment.getSizeBytes(),
                attachment.getSha256Hash(),
                attachment.getScanStatus(),
                attachment.getCreatedAt());
    }

    /** Pièce jointe telle que suivie par le demandeur ou l'agent. */
    public record AttachmentView(
            UUID id,
            UUID submissionId,
            String fieldPath,
            String filename,
            String mimeType,
            long sizeBytes,
            String sha256,
            AttachmentScanStatus scanStatus,
            Instant createdAt) {
    }

    /** Contenu d'une pièce jointe disponible, avec ses métadonnées de service. */
    public record AttachmentDownload(
            String filename,
            String mimeType,
            long sizeBytes,
            String sha256,
            InputStream content) {
    }
}
