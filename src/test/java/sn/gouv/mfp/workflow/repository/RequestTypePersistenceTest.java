package sn.gouv.mfp.workflow.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
import sn.gouv.mfp.workflow.domain.request.RequestType;
import sn.gouv.mfp.workflow.domain.request.RequestTypeStatus;

/**
 * Phase 3 — référentiel des types de demande : persistance, contraintes du schéma SQL
 * (clé métier, vocabulaire de statut) et immuabilité d'une version publiée.
 */
@SpringBootTest
class RequestTypePersistenceTest {

    @Autowired
    private RequestTypeRepository requestTypes;

    @Autowired
    private FormSchemaRepository formSchemas;

    @Autowired
    private WorkflowDefinitionRepository definitions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Transactional
    void newRequestType_shouldStartAsDraft() {
        RequestType type = givenRequestType("CERTIFICAT_" + suffix());

        RequestType reloaded = requestTypes.findById(type.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RequestTypeStatus.DRAFT);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getCategory()).isEqualTo("ETAT_CIVIL");
    }

    @Test
    @Transactional
    void publishedRequestTypes_shouldBeListedByLabel() {
        String suffix = suffix();
        RequestType zebu = givenRequestType("ZZZ_" + suffix);
        zebu.setLabel("Zébu");
        zebu.setStatus(RequestTypeStatus.PUBLISHED);
        requestTypes.saveAndFlush(zebu);
        RequestType acte = givenRequestType("AAA_" + suffix);
        acte.setLabel("Acte");
        acte.setStatus(RequestTypeStatus.PUBLISHED);
        requestTypes.saveAndFlush(acte);

        List<RequestType> published =
                requestTypes.findByStatusOrderByLabelAsc(RequestTypeStatus.PUBLISHED);

        assertThat(published).extracting(RequestType::getLabel).isSorted();
        assertThat(published)
                .extracting(RequestType::getKey)
                .contains(zebu.getKey(), acte.getKey());
    }

    @Test
    @Transactional
    void duplicateBusinessKey_shouldBeRejected() {
        String key = "DOUBLON_" + suffix();
        givenRequestType(key);

        assertThatThrownBy(() -> requestTypes.saveAndFlush(newType(key)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional
    void statusOutsideVocabulary_shouldBeRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO request_type (id, type_key, label, status) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(),
                        "HORS_" + suffix(),
                        "Statut hors vocabulaire",
                        "ARCHIVED"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @Transactional
    void formSchema_shouldRoundTripJsonb() {
        Map<String, Object> jsonSchema = Map.of(
                "$schema", "https://json-schema.org/draft/2020-12/schema",
                "type", "object",
                "properties", Map.of("nom", Map.of("type", "string")),
                "required", List.of("nom"));
        FormSchema schema = new FormSchema();
        schema.setJsonSchema(jsonSchema);
        schema.setUiSchema(Map.of("nom", Map.of("ui:widget", "text")));
        FormSchema saved = formSchemas.saveAndFlush(schema);

        FormSchema reloaded = formSchemas.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getJsonSchema())
                .containsEntry("$schema", "https://json-schema.org/draft/2020-12/schema")
                .containsKey("properties");
        assertThat(reloaded.getUiSchema()).containsKey("nom");
    }

    @Test
    @Transactional
    void publishedVersion_shouldBeImmutableForTheApplicationAccount() {
        UUID versionId = givenRequestTypeVersion();

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE request_type_version SET version = version + 1 WHERE id = ?",
                        versionId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM request_type_version WHERE id = ?", versionId))
                .isInstanceOf(DataAccessException.class);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private RequestType newType(String key) {
        RequestType type = new RequestType();
        type.setKey(key);
        type.setLabel("Type " + key);
        type.setCategory("ETAT_CIVIL");
        return type;
    }

    private RequestType givenRequestType(String key) {
        return requestTypes.saveAndFlush(newType(key));
    }

    /** Version publiée minimale : un type, un schéma de formulaire et un circuit persistés. */
    private UUID givenRequestTypeVersion() {
        RequestType type = givenRequestType("VERSION_" + suffix());

        FormSchema schema = new FormSchema();
        schema.setJsonSchema(Map.of("type", "object"));
        formSchemas.saveAndFlush(schema);

        String definitionKey = "RT_V_" + suffix();
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setKey(definitionKey);
        definition.setVersion(1);
        definition.setName("Circuit " + definitionKey);
        definitions.saveAndFlush(definition);

        UUID versionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO request_type_version "
                        + "(id, request_type_id, version, form_schema_id, definition_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                versionId,
                type.getId(),
                1,
                schema.getId(),
                definition.getId());
        return versionId;
    }
}
