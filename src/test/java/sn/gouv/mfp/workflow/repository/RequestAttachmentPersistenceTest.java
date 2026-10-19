package sn.gouv.mfp.workflow.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.request.FormSchema;
import sn.gouv.mfp.workflow.domain.request.RequestSubmission;
import sn.gouv.mfp.workflow.domain.request.RequestType;
import sn.gouv.mfp.workflow.domain.request.RequestTypeStatus;
import sn.gouv.mfp.workflow.domain.request.RequestTypeVersion;

/**
 * FRM-05 — invariants de la base pour les pièces jointes : le dépôt est scellé (nom, type,
 * taille, empreinte, clé de stockage), seul le verdict antivirus évolue et seulement depuis
 * {@code PENDING}, et rien ne se supprime.
 * <p>
 * Les tests de déclencheur s'exécutent <strong>hors transaction</strong> : un refus de la
 * base annulerait la transaction du test et rendrait invisible l'état à vérifier ensuite.
 * Les lignes ainsi écrites restent en base de recette, comme les mini-circuits de
 * {@code WorkflowHistoryImmutabilityTest} — les clés sont tirées au sort, aucune collision.
 */
@SpringBootTest
class RequestAttachmentPersistenceTest {

    /** Empreinte SHA-256 réelle de « abc » : vérifie la forme attendue en base (64 hex). */
    private static final String SHA256_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

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
    private JdbcTemplate jdbc;

    @Test
    void pendingVerdict_shouldBecomeCleanOnce() {
        UUID attachmentId = givenAttachment();

        update("UPDATE request_attachment SET scan_status = 'CLEAN' WHERE id = ?", attachmentId);

        assertThat(statusOf(attachmentId)).isEqualTo("CLEAN");
    }

    @Test
    void verdictAlreadyGiven_shouldNotChangeAnymore() {
        UUID attachmentId = givenAttachment();
        update("UPDATE request_attachment SET scan_status = 'CLEAN' WHERE id = ?", attachmentId);

        assertThatThrownBy(() -> update(
                        "UPDATE request_attachment SET scan_status = 'INFECTED' WHERE id = ?",
                        attachmentId))
                .isInstanceOf(DataAccessException.class);

        assertThat(statusOf(attachmentId)).isEqualTo("CLEAN");
    }

    @Test
    void infectedVerdict_shouldBeAllowedFromPending() {
        UUID attachmentId = givenAttachment();

        update("UPDATE request_attachment SET scan_status = 'INFECTED' WHERE id = ?", attachmentId);

        assertThat(statusOf(attachmentId)).isEqualTo("INFECTED");
    }

    @Test
    void depositedPayload_shouldBeSealed() {
        UUID attachmentId = givenAttachment();

        assertThatThrownBy(() -> update(
                        "UPDATE request_attachment SET sha256_hash = repeat('f', 64) WHERE id = ?",
                        attachmentId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> update(
                        "UPDATE request_attachment SET size_bytes = 1 WHERE id = ?", attachmentId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> update(
                        "UPDATE request_attachment SET field_path = 'autre' WHERE id = ?",
                        attachmentId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> update(
                        "UPDATE request_attachment SET storage_key = 'pirate' WHERE id = ?",
                        attachmentId))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForObject(
                        "SELECT size_bytes FROM request_attachment WHERE id = ?",
                        Number.class,
                        attachmentId)
                .longValue()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT field_path FROM request_attachment WHERE id = ?",
                        String.class,
                        attachmentId))
                .isEqualTo("piece");
    }

    @Test
    void unknownVerdict_shouldBeRejected() {
        UUID attachmentId = givenAttachment();

        assertThatThrownBy(() -> update(
                        "UPDATE request_attachment SET scan_status = 'APPROVED' WHERE id = ?",
                        attachmentId))
                .isInstanceOf(DataAccessException.class);

        assertThat(statusOf(attachmentId)).isEqualTo("PENDING");
    }

    @Test
    void delete_shouldBeForbiddenForTheAppAccount() {
        UUID attachmentId = givenAttachment();

        assertThatThrownBy(
                        () -> jdbc.update("DELETE FROM request_attachment WHERE id = ?", attachmentId))
                .isInstanceOf(DataAccessException.class);

        assertThat(statusOf(attachmentId)).isEqualTo("PENDING");
    }

    @Test
    @Transactional
    void emptyFile_shouldBeRejected() {
        released(() -> insertAttachment(
                givenSubmission(), "balise.txt", "text/plain", 0, SHA256_ABC, "k/vide"));
    }

    @Test
    @Transactional
    void shortHash_shouldBeRejected() {
        released(() -> insertAttachment(
                givenSubmission(), "note.txt", "text/plain", 3, "abc", "k/court"));
    }

    @Test
    @Transactional
    void upperCaseHash_shouldBeRejected() {
        released(() -> insertAttachment(
                givenSubmission(), "note.txt", "text/plain", 3, "Z".repeat(64), "k/majuscule"));
    }

    @Test
    @Transactional
    void storageKey_shouldBeUnique() {
        UUID submissionId = givenSubmission();
        insertAttachment(submissionId, "a.txt", "text/plain", 3, SHA256_ABC, "k/" + submissionId);

        released(() -> insertAttachment(
                submissionId, "b.txt", "text/plain", 3, SHA256_ABC, "k/" + submissionId));
    }

    @Test
    @Transactional
    void unknownSubmission_shouldBeRejected() {
        released(() -> insertAttachment(
                UUID.randomUUID(), "a.txt", "text/plain", 3, SHA256_ABC, "k/orpheline"));
    }

    /**
     * Écriture destinée à être refusée : la première erreur de la transaction la rend
     * inutilisable, le test n'en fait donc qu'une seule (et n'interroge rien ensuite).
     */
    private void released(Runnable write) {
        assertThatThrownBy(write::run).isInstanceOf(DataAccessException.class);
    }

    private void update(String sql, Object argument) {
        jdbc.update(sql, argument);
    }

    private String statusOf(UUID attachmentId) {
        return jdbc.queryForObject(
                "SELECT scan_status FROM request_attachment WHERE id = ?", String.class, attachmentId);
    }

    private UUID givenAttachment() {
        UUID submissionId = givenSubmission();
        return insertAttachment(
                submissionId, "mairie.pdf", "application/pdf", 3, SHA256_ABC, "k/" + UUID.randomUUID());
    }

    private UUID insertAttachment(
            UUID submissionId,
            String filename,
            String mimeType,
            long size,
            String sha256,
            String storageKey) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO request_attachment "
                        + "(id, submission_id, field_path, filename, mime_type, size_bytes, "
                        + "sha256_hash, storage_key, scan_status) "
                        + "VALUES (?, ?, 'piece', ?, ?, ?, ?, ?, 'PENDING')",
                id,
                submissionId,
                filename,
                mimeType,
                size,
                sha256,
                storageKey);
        return id;
    }

    private UUID givenSubmission() {
        RequestSubmission submission = new RequestSubmission();
        submission.setRequestTypeVersion(givenVersion());
        submission.setFormData(new LinkedHashMap<>(Map.of("nom", "Awa Diop")));
        // Demandeur propre à la ligne écrite : les autres tests listent les brouillons d'un
        // demandeur précis, ces lignes validées ne doivent donc jamais porter leur identité.
        submission.setSubmittedBy("test:attachments-" + suffix());
        return submissions.saveAndFlush(submission).getId();
    }

    private RequestTypeVersion givenVersion() {
        RequestType type = new RequestType();
        type.setKey("ATTACHMENT_" + suffix());
        type.setLabel("Type avec pièces jointes");
        type.setCategory("TRAVAIL");
        type.setStatus(RequestTypeStatus.DRAFT);
        type = requestTypes.saveAndFlush(type);

        FormSchema schema = new FormSchema();
        schema.setJsonSchema(Map.of(
                "type", "object",
                "properties", Map.of("piece", Map.of("type", "string"))));
        schema = formSchemas.saveAndFlush(schema);

        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey("ATTACHMENT_DEF_" + suffix());
        definition.setVersion(1);
        definition.setName("Circuit pièces jointes");
        definition = definitions.saveAndFlush(definition);

        RequestTypeVersion version = new RequestTypeVersion();
        version.setRequestType(type);
        version.setVersion(1);
        version.setFormSchema(schema);
        version.setDefinition(definition);
        return versions.saveAndFlush(version);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
