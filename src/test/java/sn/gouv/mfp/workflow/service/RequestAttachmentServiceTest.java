package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.request.AttachmentScanStatus;
import sn.gouv.mfp.workflow.domain.request.FormSchema;
import sn.gouv.mfp.workflow.domain.request.RequestSubmission;
import sn.gouv.mfp.workflow.domain.request.RequestSubmissionStatus;
import sn.gouv.mfp.workflow.domain.request.RequestType;
import sn.gouv.mfp.workflow.domain.request.RequestTypeStatus;
import sn.gouv.mfp.workflow.domain.request.RequestTypeVersion;
import sn.gouv.mfp.workflow.exception.WorkflowNotFoundException;
import sn.gouv.mfp.workflow.exception.WorkflowPayloadTooLargeException;
import sn.gouv.mfp.workflow.exception.WorkflowUnsupportedMediaTypeException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.repository.FormSchemaRepository;
import sn.gouv.mfp.workflow.repository.RequestAttachmentRepository;
import sn.gouv.mfp.workflow.repository.RequestSubmissionRepository;
import sn.gouv.mfp.workflow.repository.RequestTypeRepository;
import sn.gouv.mfp.workflow.repository.RequestTypeVersionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.service.RequestAttachmentService.AttachmentView;
import sn.gouv.mfp.workflow.service.WorkflowReadService.Viewer;
import sn.gouv.mfp.workflow.service.attachment.AttachmentProperties;
import sn.gouv.mfp.workflow.service.attachment.LocalFileAttachmentStorage;
import sn.gouv.mfp.workflow.service.attachment.StubAntivirusScanner;

/**
 * FRM-05 — pièces jointes : type MIME réel (jamais celui déclaré), liste de types autorisés,
 * taille maximale, empreinte SHA-256, analyse antivirus <strong>avant</strong> mise à
 * disposition (un contenu refusé n'est jamais conservé) et accès gouverné par les règles du
 * dossier.
 */
@SpringBootTest
class RequestAttachmentServiceTest {

    /** Empreinte SHA-256 réelle de « abc » : valeur de référence externe, non recalculée. */
    private static final String SHA256_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static final String AUTHOR = "test:usager-1";
    private static final String OTHER = "test:usager-2";
    private static final String FIELD = "piece";

    @Autowired
    private RequestSubmissionRepository submissions;

    @Autowired
    private RequestAttachmentRepository attachments;

    @Autowired
    private RequestTypeRepository requestTypes;

    @Autowired
    private RequestTypeVersionRepository versions;

    @Autowired
    private FormSchemaRepository formSchemas;

    @Autowired
    private WorkflowDefinitionRepository definitions;

    @Autowired
    private WorkflowStepRepository steps;

    @Autowired
    private WorkflowExecutionService executionService;

    @Autowired
    private WorkflowReadService readService;

    @TempDir
    Path storageRoot;

    /** Service mis à l'épreuve, avec un stockage temporaire et les paramètres de production. */
    private RequestAttachmentService service;

    @BeforeEach
    void setUp() {
        service = serviceWith(5L * 1024 * 1024, AttachmentProperties.DEFAULT_MIME_TYPES);
    }

    @Test
    @Transactional
    void textAttachment_shouldBeStoredWithItsRealTypeAndSha256() throws IOException {
        UUID submissionId = givenDraft();

        AttachmentView view = service.store(
                submissionId,
                FIELD,
                "note.txt",
                "application/octet-stream",
                "abc".getBytes(StandardCharsets.UTF_8),
                AUTHOR);

        assertThat(view.filename()).isEqualTo("note.txt");
        assertThat(view.mimeType()).isEqualTo("text/plain");
        assertThat(view.sizeBytes()).isEqualTo(3);
        assertThat(view.sha256()).isEqualTo(SHA256_ABC);
        assertThat(view.scanStatus()).isEqualTo(AttachmentScanStatus.CLEAN);
        assertThat(Files.readAllBytes(storedFile())).isEqualTo("abc".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @Transactional
    void pdfAnnouncedAsText_shouldBeStoredUnderItsRealType() {
        UUID submissionId = givenDraft();

        AttachmentView view = service.store(
                submissionId,
                FIELD,
                "mairie.pdf",
                "text/plain",
                "%PDF-1.7 contenu".getBytes(StandardCharsets.UTF_8),
                AUTHOR);

        assertThat(view.mimeType()).isEqualTo("application/pdf");
    }

    @Test
    @Transactional
    void emptyFile_shouldBeRejectedAndNothingPersisted() {
        UUID submissionId = givenDraft();

        assertThatThrownBy(() -> service.store(
                        submissionId, FIELD, "vide.txt", "text/plain", new byte[0], AUTHOR))
                .isInstanceOf(WorkflowValidationException.class);
        assertThat(attachments.findBySubmissionIdOrderByCreatedAtAsc(submissionId)).isEmpty();
    }

    @Test
    @Transactional
    void contentAboveTheMaxSize_shouldBeRejected() {
        service = serviceWith(8, AttachmentProperties.DEFAULT_MIME_TYPES);
        UUID submissionId = givenDraft();

        assertThatThrownBy(() -> service.store(
                        submissionId,
                        FIELD,
                        "gros.txt",
                        "text/plain",
                        "neuf octets".getBytes(StandardCharsets.UTF_8),
                        AUTHOR))
                .isInstanceOf(WorkflowPayloadTooLargeException.class);
    }

    @Test
    @Transactional
    void typeOutsideTheAllowedList_shouldBeRejected() {
        service = serviceWith(5L * 1024 * 1024, List.of("application/pdf"));
        UUID submissionId = givenDraft();

        assertThatThrownBy(() -> service.store(
                        submissionId,
                        FIELD,
                        "note.txt",
                        "text/plain",
                        "abc".getBytes(StandardCharsets.UTF_8),
                        AUTHOR))
                .isInstanceOf(WorkflowUnsupportedMediaTypeException.class);
    }

    @Test
    @Transactional
    void unrecognizedBinary_shouldBeRejected() {
        UUID submissionId = givenDraft();

        assertThatThrownBy(() -> service.store(
                        submissionId,
                        FIELD,
                        "mystere.bin",
                        null,
                        new byte[] {0x00, 0x01, (byte) 0xFF, 0x00},
                        AUTHOR))
                .isInstanceOf(WorkflowUnsupportedMediaTypeException.class);
    }

    @Test
    @Transactional
    void contentRefusedByAntivirus_shouldNotBePersistedNorKept() throws IOException {
        UUID submissionId = givenDraft();
        byte[] infected = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.store(submissionId, FIELD, "virus.txt", "text/plain", infected, AUTHOR))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("antivirus");

        assertThat(attachments.findBySubmissionIdOrderByCreatedAtAsc(submissionId)).isEmpty();
        try (Stream<Path> stored = Files.walk(storageRoot)) {
            assertThat(stored.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    @Transactional
    void fieldOutsideTheForm_shouldBeRejected() {
        UUID submissionId = givenDraft();

        assertThatThrownBy(() -> service.store(
                        submissionId,
                        "inconnu",
                        "note.txt",
                        "text/plain",
                        "abc".getBytes(StandardCharsets.UTF_8),
                        AUTHOR))
                .isInstanceOf(WorkflowValidationException.class);
    }

    @Test
    @Transactional
    void attachment_shouldNotBeDepositedOnASubmittedRequest() {
        UUID submissionId = givenDraft();
        submit(submissionId);

        assertThatThrownBy(() -> service.store(
                        submissionId,
                        FIELD,
                        "note.txt",
                        "text/plain",
                        "abc".getBytes(StandardCharsets.UTF_8),
                        AUTHOR))
                .isInstanceOf(WorkflowValidationException.class);
    }

    @Test
    @Transactional
    void draft_shouldNotBeSeenByAnotherAuthor() {
        UUID submissionId = givenDraft();
        service.store(submissionId, FIELD, "note.txt", "text/plain", "abc".getBytes(StandardCharsets.UTF_8), AUTHOR);

        assertThatThrownBy(() -> service.list(submissionId, viewer(OTHER)))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    @Test
    @Transactional
    void list_shouldReturnTheStoredAttachmentsOfTheAuthor() {
        UUID submissionId = givenDraft();
        service.store(submissionId, FIELD, "note.txt", "text/plain", "abc".getBytes(StandardCharsets.UTF_8), AUTHOR);

        assertThat(service.list(submissionId, viewer(AUTHOR)))
                .singleElement()
                .satisfies(attachment -> assertThat(attachment.filename()).isEqualTo("note.txt"));
    }

    @Test
    @Transactional
    void download_shouldServeTheContentToTheAuthorOnly() throws IOException {
        UUID submissionId = givenDraft();
        AttachmentView view = service.store(
                submissionId, FIELD, "note.pdf", "application/pdf",
                "%PDF-1.7".getBytes(StandardCharsets.UTF_8), AUTHOR);

        RequestAttachmentService.AttachmentDownload download = service.download(view.id(), viewer(AUTHOR));
        assertThat(download.filename()).isEqualTo("note.pdf");
        assertThat(download.mimeType()).isEqualTo("application/pdf");
        assertThat(download.content().readAllBytes()).isEqualTo("%PDF-1.7".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.download(view.id(), viewer(OTHER)))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    @Test
    @Transactional
    void submittedRequest_shouldFollowTheDossierReadRules() {
        UUID submissionId = givenDraft();
        AttachmentView view = service.store(
                submissionId, FIELD, "note.txt", "text/plain",
                "abc".getBytes(StandardCharsets.UTF_8), AUTHOR);
        submit(submissionId);

        // L'initiateur voit son dossier : il accède à ses pièces jointes comme au dossier.
        assertThat(service.download(view.id(), viewer(AUTHOR)).sizeBytes()).isEqualTo(3);
        // Un tiers sans rôle sur l'étape courante ne voit ni la demande ni la pièce (404).
        assertThatThrownBy(() -> service.download(view.id(), viewer(OTHER)))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    private RequestAttachmentService serviceWith(long maxSizeBytes, List<String> allowedTypes) {
        return new RequestAttachmentService(
                submissions,
                attachments,
                new LocalFileAttachmentStorage(storageRoot),
                new StubAntivirusScanner(),
                new AttachmentProperties(maxSizeBytes, allowedTypes, storageRoot.toString()),
                readService);
    }

    private Path storedFile() throws IOException {
        try (Stream<Path> stored = Files.walk(storageRoot)) {
            return stored.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
    }

    private static Viewer viewer(String userId) {
        return new Viewer(userId, Set.of(), false);
    }

    /** Brouillon rattaché à une version publiée dont le formulaire déclare le champ « piece ». */
    private UUID givenDraft() {
        RequestSubmission draft = new RequestSubmission();
        draft.setRequestTypeVersion(givenVersion());
        draft.setFormData(new LinkedHashMap<>(Map.of("nom", "Awa Diop")));
        draft.setSubmittedBy(AUTHOR);
        return submissions.saveAndFlush(draft).getId();
    }

    /** Soumet le brouillon : un dossier est créé, la demande devient immuable. */
    private void submit(UUID submissionId) {
        RequestSubmission submission = submissions.findById(submissionId).orElseThrow();
        RequestTypeVersion version = submission.getRequestTypeVersion();
        submission.setInstance(executionService.startWorkflow(
                version.getDefinition().getKey(), AUTHOR, null, null));
        submission.setSubmittedAt(Instant.now());
        submission.setStatus(RequestSubmissionStatus.SUBMITTED);
        submissions.saveAndFlush(submission);
    }

    private RequestTypeVersion givenVersion() {
        RequestType type = new RequestType();
        type.setKey("ATTACH_SVC_" + suffix());
        type.setLabel("Type pièces jointes");
        type.setCategory("TRAVAIL");
        type.setStatus(RequestTypeStatus.PUBLISHED);
        type = requestTypes.saveAndFlush(type);

        FormSchema schema = new FormSchema();
        schema.setJsonSchema(Map.of(
                "$schema", "https://json-schema.org/draft/2020-12/schema",
                "type", "object",
                "properties", Map.of(
                        "nom", Map.of("type", "string"),
                        "piece", Map.of("type", "string"))));
        schema = formSchemas.saveAndFlush(schema);

        WorkflowDefinition definition = new WorkflowDefinition();
        String definitionKey = "ATTACH_DEF_" + suffix();
        definition.setKey(definitionKey);
        definition.setVersion(1);
        definition.setName("Circuit pièces jointes");
        definition = definitions.saveAndFlush(definition);

        WorkflowStep start = new WorkflowStep();
        start.setDefinition(definition);
        start.setStepKey("DEPOT");
        start.setType(StepType.START);
        steps.saveAndFlush(start);

        RequestTypeVersion version = new RequestTypeVersion();
        version.setRequestType(type);
        version.setVersion(1);
        version.setFormSchema(schema);
        version.setDefinition(definition);
        version.setPublishedAt(Instant.now());
        return versions.saveAndFlush(version);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
