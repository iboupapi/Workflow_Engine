package sn.gouv.mfp.workflow.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.domain.request.FormSchema;
import sn.gouv.mfp.workflow.domain.request.RequestSubmission;
import sn.gouv.mfp.workflow.domain.request.RequestSubmissionStatus;
import sn.gouv.mfp.workflow.domain.request.RequestType;
import sn.gouv.mfp.workflow.domain.request.RequestTypeStatus;
import sn.gouv.mfp.workflow.domain.request.RequestTypeVersion;
import sn.gouv.mfp.workflow.service.WorkflowExecutionService;

/**
 * REQ-03 — cycle de vie d'une soumission : un brouillon n'a pas de dossier, une soumission
 * en a un et devient immuable, la version publiée en vigueur est la plus élevée.
 */
@SpringBootTest
class RequestSubmissionPersistenceTest {

    @Autowired
    private RequestSubmissionRepository submissions;

    @Autowired
    private RequestTypeVersionRepository versions;

    @Autowired
    private RequestTypeRepository requestTypes;

    @Autowired
    private FormSchemaRepository formSchemas;

    @Autowired
    private WorkflowDefinitionRepository definitions;

    @Autowired
    private WorkflowStepRepository steps;

    @Autowired
    private WorkflowExecutionService executionService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Transactional
    void newSubmission_shouldStartAsDraftWithoutInstanceNorSubmittedAt() {
        RequestTypeVersion version = givenVersion(RequestTypeStatus.DRAFT, null);

        RequestSubmission submission = newSubmission(version);
        submission.setFormData(map("nom", "Awa Diop"));
        submission.setSubmittedBy("test:usager-1");
        RequestSubmission saved = submissions.saveAndFlush(submission);

        RequestSubmission reloaded = submissions.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RequestSubmissionStatus.DRAFT);
        assertThat(reloaded.isDraft()).isTrue();
        assertThat(reloaded.getInstance()).isNull();
        assertThat(reloaded.getSubmittedAt()).isNull();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getFormData()).containsEntry("nom", "Awa Diop");
    }

    @Test
    @Transactional
    void draft_shouldBeResumableByItsAuthor() {
        RequestSubmission draft = submissions.saveAndFlush(newSubmission(givenVersion(RequestTypeStatus.DRAFT, null)));

        jdbc.update(
                "UPDATE request_submission SET form_data = ?::jsonb WHERE id = ?",
                "{\"nom\":\"Awa Diop\",\"motif\":\"Promotion\"}",
                draft.getId());

        String motif = jdbc.queryForObject(
                "SELECT form_data ->> 'motif' FROM request_submission WHERE id = ?",
                String.class,
                draft.getId());
        assertThat(motif).isEqualTo("Promotion");
    }

    @Test
    @Transactional
    void draftBoundToAnInstance_shouldBeRejected() {
        RequestTypeVersion version = givenVersion(RequestTypeStatus.DRAFT, null);
        UUID instanceId = givenInstance();

        assertThatThrownBy(() -> insertSubmission(version.getId(), instanceId, "DRAFT", null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional
    void submittedWithoutInstanceNorTimestamp_shouldBeRejected() {
        RequestTypeVersion version = givenVersion(RequestTypeStatus.DRAFT, null);

        assertThatThrownBy(() -> insertSubmission(version.getId(), null, "SUBMITTED", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertSubmission(version.getId(), givenInstance(), "SUBMITTED", null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional
    void submitted_shouldAcceptAnInstanceAndATimestamp() {
        RequestTypeVersion version = givenVersion(RequestTypeStatus.DRAFT, null);
        UUID instanceId = givenInstance();

        insertSubmission(version.getId(), instanceId, "SUBMITTED", Instant.now());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM request_submission WHERE instance_id = ?",
                        Number.class,
                        instanceId)
                .intValue()).isEqualTo(1);
    }

    @Test
    @Transactional
    void submittedSubmission_shouldBeImmutable() {
        RequestTypeVersion version = givenVersion(RequestTypeStatus.DRAFT, null);
        UUID instanceId = givenInstance();
        insertSubmission(version.getId(), instanceId, "SUBMITTED", Instant.now());

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE request_submission SET form_data = '{\"pirate\":true}'::jsonb "
                                + "WHERE instance_id = ?",
                        instanceId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional
    void draftAuthorAndVersion_shouldBeImmutable() {
        RequestSubmission pending = newSubmission(givenVersion(RequestTypeStatus.DRAFT, null));
        pending.setSubmittedBy("test:usager-1");
        RequestSubmission draft = submissions.saveAndFlush(pending);

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE request_submission SET submitted_by = 'test:autre' WHERE id = ?",
                        draft.getId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE request_submission SET request_type_version_id = ? WHERE id = ?",
                        UUID.randomUUID(),
                        draft.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional
    void statusOutsideVocabulary_shouldBeRejected() {
        RequestTypeVersion version = givenVersion(RequestTypeStatus.DRAFT, null);

        assertThatThrownBy(() -> insertSubmission(version.getId(), null, "ARCHIVED", null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional
    void activeVersion_shouldBeTheHighestPublishedOneOfAPublishedType() {
        RequestType type = givenRequestType(RequestTypeStatus.PUBLISHED);
        givenVersionFor(type, 1);
        givenVersionFor(type, 2);

        RequestTypeVersion active = activeVersion(type);

        assertThat(active.getVersion()).isEqualTo(2);
    }

    @Test
    @Transactional
    void unpublishedVersion_shouldNotBeServed() {
        RequestType type = givenRequestType(RequestTypeStatus.PUBLISHED);
        givenVersionFor(type, 1, null);

        assertThat(activeVersion(type)).isNull();
    }

    @Test
    @Transactional
    void draftType_shouldNotBeServed() {
        RequestType type = givenRequestType(RequestTypeStatus.DRAFT);
        givenVersionFor(type, 1);

        assertThat(activeVersion(type)).isNull();
    }

    private RequestTypeVersion activeVersion(RequestType type) {
        return versions
                .findFirstByRequestTypeKeyAndRequestTypeStatusAndPublishedAtIsNotNullOrderByVersionDesc(
                        type.getKey(), RequestTypeStatus.PUBLISHED)
                .orElse(null);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private RequestSubmission newSubmission(RequestTypeVersion version) {
        RequestSubmission submission = new RequestSubmission();
        submission.setRequestTypeVersion(version);
        submission.setFormData(new LinkedHashMap<>());
        return submission;
    }

    private RequestType givenRequestType(RequestTypeStatus status) {
        RequestType type = new RequestType();
        type.setKey("SUBMISSION_" + suffix());
        type.setLabel("Type de soumission");
        type.setCategory("ETAT_CIVIL");
        type.setStatus(status);
        return requestTypes.saveAndFlush(type);
    }

    /** Version publiée d'un type : schéma de formulaire et circuit persistés au préalable. */
    private RequestTypeVersion givenVersion(RequestTypeStatus typeStatus, Instant publishedAt) {
        return givenVersionFor(givenRequestType(typeStatus), 1, publishedAt);
    }

    private RequestTypeVersion givenVersionFor(RequestType type, int number) {
        return givenVersionFor(type, number, Instant.now());
    }

    private RequestTypeVersion givenVersionFor(RequestType type, int number, Instant publishedAt) {
        FormSchema schema = new FormSchema();
        schema.setJsonSchema(Map.of("type", "object"));
        formSchemas.saveAndFlush(schema);

        RequestTypeVersion version = new RequestTypeVersion();
        version.setRequestType(type);
        version.setVersion(number);
        version.setFormSchema(schema);
        version.setDefinition(givenDefinition());
        version.setFieldMapping(map("typeActe", "natureActe"));
        version.setPublishedAt(publishedAt);
        return versions.saveAndFlush(version);
    }

    private WorkflowDefinition givenDefinition() {
        String key = "RT_DEF_" + suffix();
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(key);
        definition.setVersion(1);
        definition.setName("Circuit " + key);
        return definitions.saveAndFlush(definition);
    }

    /** Dossier minimal, créé par le moteur : la soumission doit pointer une instance réelle. */
    private UUID givenInstance() {
        String key = "RT_RUN_" + suffix();
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(key);
        definition.setVersion(1);
        definition.setName("Circuit " + key);
        definition = definitions.saveAndFlush(definition);

        WorkflowStep start = new WorkflowStep();
        start.setDefinition(definition);
        start.setStepKey("SRC");
        start.setType(StepType.START);
        steps.saveAndFlush(start);

        WorkflowInstance instance = executionService.startWorkflow(key, "test:usager-1", null, "SRC");
        entityManager.flush();
        return instance.getId();
    }

    private void insertSubmission(UUID versionId, UUID instanceId, String status, Instant submittedAt) {
        jdbc.update(
                "INSERT INTO request_submission "
                        + "(id, request_type_version_id, instance_id, form_data, submitted_by, "
                        + "submitted_at, status) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)",
                UUID.randomUUID(),
                versionId,
                instanceId,
                "{}",
                "test:usager-1",
                submittedAt == null ? null : java.time.OffsetDateTime.ofInstant(submittedAt, java.time.ZoneOffset.UTC),
                status);
    }

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return map;
    }
}
