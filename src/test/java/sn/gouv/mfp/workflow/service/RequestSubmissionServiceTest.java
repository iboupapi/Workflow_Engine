package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.domain.execution.InstanceStatus;
import sn.gouv.mfp.workflow.domain.execution.WorkflowHistory;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.domain.request.FormSchema;
import sn.gouv.mfp.workflow.domain.request.RequestSubmissionStatus;
import sn.gouv.mfp.workflow.domain.request.RequestType;
import sn.gouv.mfp.workflow.domain.request.RequestTypeStatus;
import sn.gouv.mfp.workflow.domain.request.RequestTypeVersion;
import sn.gouv.mfp.workflow.exception.FormSchemaValidationException;
import sn.gouv.mfp.workflow.exception.WorkflowConflictException;
import sn.gouv.mfp.workflow.exception.WorkflowForbiddenException;
import sn.gouv.mfp.workflow.exception.WorkflowNotFoundException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.repository.FormSchemaRepository;
import sn.gouv.mfp.workflow.repository.RequestSubmissionRepository;
import sn.gouv.mfp.workflow.repository.RequestTypeRepository;
import sn.gouv.mfp.workflow.repository.RequestTypeVersionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowHistoryRepository;
import sn.gouv.mfp.workflow.repository.WorkflowInstanceRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.FormView;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.SubmissionView;
import sn.gouv.mfp.workflow.service.RequestSubmissionService.TypeView;

/**
 * REQ-01 / REQ-03 — soumission d'une demande : catalogue publié, formulaire en vigueur,
 * brouillon reprenable, validation avant création du dossier, variables issues de la
 * liaison (REQ-02) et demande figée après soumission.
 */
@SpringBootTest
class RequestSubmissionServiceTest {

    private static final String AUTHOR = "test:usager-1";
    private static final String OTHER = "test:usager-2";

    private static final Map<String, Object> FORM_SCHEMA = Map.of(
            "$schema", "https://json-schema.org/draft/2020-12/schema",
            "type", "object",
            "properties", Map.of(
                    "nom", Map.of("type", "string"),
                    "motif", Map.of("type", "string")),
            "required", List.of("nom"),
            "additionalProperties", false);

    @Autowired
    private RequestSubmissionService service;

    @Autowired
    private RequestSubmissionRepository submissions;

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
    private WorkflowInstanceRepository instances;

    @Autowired
    private WorkflowHistoryRepository histories;

    @Test
    @Transactional
    void publishedTypes_shouldListOnlyThePublishedOnes() {
        RequestType published = givenType(RequestTypeStatus.PUBLISHED);
        RequestType draft = givenType(RequestTypeStatus.DRAFT);

        List<TypeView> catalog = service.publishedTypes();

        assertThat(catalog).extracting(TypeView::key).contains(published.getKey());
        assertThat(catalog).extracting(TypeView::key).doesNotContain(draft.getKey());
    }

    @Test
    @Transactional
    void activeForm_shouldServeTheHighestPublishedVersion() {
        RequestTypeVersion first = givenVersion(givenType(RequestTypeStatus.PUBLISHED), 1, FORM_SCHEMA, null);
        RequestType type = first.getRequestType();
        givenVersion(type, 2, schemaWithTitle("version 2"), null);

        FormView form = service.activeForm(type.getKey());

        assertThat(form.version()).isEqualTo(2);
        assertThat(form.schema()).containsEntry("title", "version 2");
        assertThat(form.typeKey()).isEqualTo(type.getKey());
    }

    @Test
    @Transactional
    void activeForm_shouldRejectAnUnknownOrUnavailableType() {
        assertThatThrownBy(() -> service.activeForm("INCONNU_" + suffix()))
                .isInstanceOf(WorkflowNotFoundException.class);
        assertThatThrownBy(() -> service.activeForm(" "))
                .isInstanceOf(WorkflowValidationException.class);
    }

    @Test
    @Transactional
    void submit_shouldValidateMapAndCreateTheInstance() {
        RequestTypeVersion version = givenPublishedVersion(map("nom", "nomDemandeur"));

        SubmissionView submitted =
                service.submit(version.getRequestType().getKey(), map("nom", "Awa Diop"), AUTHOR);

        assertThat(submitted.status()).isEqualTo(RequestSubmissionStatus.SUBMITTED);
        assertThat(submitted.version()).isEqualTo(1);
        assertThat(submitted.instanceId()).isNotNull();
        assertThat(submitted.submittedAt()).isNotNull();
        assertThat(submitted.createdAt()).isNotNull();
        assertThat(submitted.typeKey()).isEqualTo(version.getRequestType().getKey());

        WorkflowInstance instance = instances.findById(submitted.instanceId()).orElseThrow();
        assertThat(instance.getInitiatorId()).isEqualTo(AUTHOR);
        assertThat(instance.getCurrentStepKey()).isEqualTo("DEPOT");
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.ACTIVE);
        assertThat(instance.getVariables()).containsEntry("nomDemandeur", "Awa Diop");
        assertThat(instance.getVariables()).doesNotContainKey("nom");

        assertThat(histories.findByInstanceIdOrderByTimestampAsc(instance.getId()))
                .extracting(WorkflowHistory::getAction)
                .contains("INITIATION");
    }

    @Test
    @Transactional
    void submit_shouldRefuseInvalidDataWithoutPersistingAnything() {
        RequestTypeVersion version = givenPublishedVersion(null);
        long instancesBefore = instances.count();
        long submissionsBefore = submissions.count();

        assertThatThrownBy(() -> service.submit(
                        version.getRequestType().getKey(), map("motif", "Promotion"), AUTHOR))
                .isInstanceOf(FormSchemaValidationException.class)
                .satisfies(error -> assertThat(((FormSchemaValidationException) error).getViolations())
                        .anyMatch(violation -> violation.contains("nom")));

        assertThat(instances.count()).isEqualTo(instancesBefore);
        assertThat(submissions.count()).isEqualTo(submissionsBefore);
    }

    @Test
    @Transactional
    void submit_shouldRejectAnUnknownRequestType() {
        assertThatThrownBy(() -> service.submit("INCONNU_" + suffix(), map("nom", "Awa"), AUTHOR))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    @Test
    @Transactional
    void submit_withoutFieldMapping_shouldCreateTheInstanceWithoutVariables() {
        RequestTypeVersion version = givenPublishedVersion(null);

        SubmissionView submitted =
                service.submit(version.getRequestType().getKey(), map("nom", "Awa Diop"), AUTHOR);

        WorkflowInstance instance = instances.findById(submitted.instanceId()).orElseThrow();
        assertThat(instance.getVariables()).isEmpty();
    }

    @Test
    @Transactional
    void saveDraft_shouldKeepAnIncompleteFormWithoutInstance() {
        RequestTypeVersion version = givenPublishedVersion(map("nom", "nomDemandeur"));

        SubmissionView draft = service.saveDraft(
                version.getRequestType().getKey(), map("motif", "Promotion"), AUTHOR);

        assertThat(draft.status()).isEqualTo(RequestSubmissionStatus.DRAFT);
        assertThat(draft.instanceId()).isNull();
        assertThat(draft.submittedAt()).isNull();
        assertThat(submissions.findById(draft.id()).orElseThrow().getFormData())
                .containsEntry("motif", "Promotion");
    }

    @Test
    @Transactional
    void drafts_shouldListOnlyTheAuthorDrafts() {
        RequestTypeVersion version = givenPublishedVersion(null);
        String key = version.getRequestType().getKey();
        SubmissionView mine = service.saveDraft(key, map("motif", "un"), AUTHOR);
        service.saveDraft(key, map("motif", "deux"), OTHER);

        assertThat(service.drafts(AUTHOR)).extracting(SubmissionView::id).containsExactly(mine.id());
        assertThat(service.drafts(OTHER)).hasSize(1);
    }

    @Test
    @Transactional
    void saveDraft_twice_shouldUpdateTheSameDraft() {
        RequestTypeVersion version = givenPublishedVersion(null);
        String key = version.getRequestType().getKey();

        SubmissionView first = service.saveDraft(key, map("motif", "Promotion"), AUTHOR);
        SubmissionView second = service.saveDraft(key, map("motif", "Mutation"), AUTHOR);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(submissions.findById(first.id()).orElseThrow().getFormData())
                .containsExactlyEntriesOf(map("motif", "Mutation"));
        assertThat(service.drafts(AUTHOR)).extracting(SubmissionView::id).containsExactly(first.id());
    }

    @Test
    @Transactional
    void saveDraft_shouldKeepOneDraftPerAuthorAndPerType() {
        RequestTypeVersion first = givenPublishedVersion(null);
        RequestTypeVersion second =
                givenVersion(givenType(RequestTypeStatus.PUBLISHED), 1, FORM_SCHEMA, null);

        SubmissionView mine = service.saveDraft(first.getRequestType().getKey(), map("nom", "Awa"), AUTHOR);
        SubmissionView other = service.saveDraft(first.getRequestType().getKey(), map("nom", "Pape"), OTHER);
        SubmissionView anotherType =
                service.saveDraft(second.getRequestType().getKey(), map("nom", "Awa"), AUTHOR);

        assertThat(other.id()).isNotEqualTo(mine.id());
        assertThat(anotherType.id()).isNotEqualTo(mine.id());
        assertThat(service.drafts(AUTHOR)).extracting(SubmissionView::id)
                .containsExactlyInAnyOrder(mine.id(), anotherType.id());
    }

    @Test
    @Transactional
    void saveDraft_afterSubmission_shouldStartANewDraft() {
        RequestTypeVersion version = givenPublishedVersion(null);
        String key = version.getRequestType().getKey();
        SubmissionView submitted = service.saveDraft(key, map("nom", "Awa"), AUTHOR);
        service.submitDraft(submitted.id(), AUTHOR);

        SubmissionView draft = service.saveDraft(key, map("nom", "Awa Diop"), AUTHOR);

        assertThat(draft.id()).isNotEqualTo(submitted.id());
        assertThat(draft.status()).isEqualTo(RequestSubmissionStatus.DRAFT);
        assertThat(service.drafts(AUTHOR)).extracting(SubmissionView::id).containsExactly(draft.id());
    }

    @Test
    @Transactional
    void submitDraft_shouldCreateTheInstanceAndFreezeTheDraft() {
        RequestTypeVersion version = givenPublishedVersion(map("nom", "nomDemandeur"));
        SubmissionView draft =
                service.saveDraft(version.getRequestType().getKey(), map("nom", "Awa Diop"), AUTHOR);

        SubmissionView submitted = service.submitDraft(draft.id(), AUTHOR);

        assertThat(submitted.id()).isEqualTo(draft.id());
        assertThat(submitted.status()).isEqualTo(RequestSubmissionStatus.SUBMITTED);
        assertThat(submitted.instanceId()).isNotNull();
        assertThat(submitted.submittedAt()).isNotNull();
        assertThat(service.drafts(AUTHOR)).isEmpty();

        WorkflowInstance instance = instances.findById(submitted.instanceId()).orElseThrow();
        assertThat(instance.getVariables()).containsEntry("nomDemandeur", "Awa Diop");
    }

    @Test
    @Transactional
    void submitDraft_shouldRefuseAnotherAuthor() {
        RequestTypeVersion version = givenPublishedVersion(null);
        SubmissionView draft =
                service.saveDraft(version.getRequestType().getKey(), map("nom", "Awa"), AUTHOR);

        assertThatThrownBy(() -> service.submitDraft(draft.id(), OTHER))
                .isInstanceOf(WorkflowForbiddenException.class);
    }

    @Test
    @Transactional
    void submitDraft_shouldRefuseASecondSubmission() {
        RequestTypeVersion version = givenPublishedVersion(null);
        SubmissionView draft =
                service.saveDraft(version.getRequestType().getKey(), map("nom", "Awa"), AUTHOR);

        service.submitDraft(draft.id(), AUTHOR);

        assertThatThrownBy(() -> service.submitDraft(draft.id(), AUTHOR))
                .isInstanceOf(WorkflowConflictException.class);
    }

    @Test
    @Transactional
    void submitDraft_shouldRejectAnUnknownDraft() {
        assertThatThrownBy(() -> service.submitDraft(UUID.randomUUID(), AUTHOR))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private RequestType givenType(RequestTypeStatus status) {
        String suffix = suffix();
        RequestType type = new RequestType();
        type.setKey("DEMANDE_" + suffix);
        type.setLabel("Demande " + suffix);
        type.setCategory("ETAT_CIVIL");
        type.setStatus(status);
        return requestTypes.saveAndFlush(type);
    }

    private RequestTypeVersion givenPublishedVersion(Map<String, Object> fieldMapping) {
        return givenVersion(givenType(RequestTypeStatus.PUBLISHED), 1, FORM_SCHEMA, fieldMapping);
    }

    private RequestTypeVersion givenVersion(
            RequestType type, int number, Map<String, Object> jsonSchema, Map<String, Object> fieldMapping) {
        FormSchema schema = new FormSchema();
        schema.setJsonSchema(jsonSchema);
        formSchemas.saveAndFlush(schema);

        RequestTypeVersion version = new RequestTypeVersion();
        version.setRequestType(type);
        version.setVersion(number);
        version.setFormSchema(schema);
        version.setDefinition(givenDefinition());
        version.setFieldMapping(fieldMapping);
        version.setPublishedAt(Instant.now());
        return versions.saveAndFlush(version);
    }

    /** Circuit minimal : une étape START, point de départ du dossier créé par la soumission. */
    private WorkflowDefinition givenDefinition() {
        String key = "DEMANDE_CIRCUIT_" + suffix();
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(key);
        definition.setVersion(1);
        definition.setName("Circuit " + key);
        definition = definitions.saveAndFlush(definition);

        WorkflowStep start = new WorkflowStep();
        start.setDefinition(definition);
        start.setStepKey("DEPOT");
        start.setType(StepType.START);
        steps.saveAndFlush(start);
        return definition;
    }

    private static Map<String, Object> schemaWithTitle(String title) {
        Map<String, Object> schema = new LinkedHashMap<>(FORM_SCHEMA);
        schema.put("title", title);
        return schema;
    }

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return map;
    }
}
