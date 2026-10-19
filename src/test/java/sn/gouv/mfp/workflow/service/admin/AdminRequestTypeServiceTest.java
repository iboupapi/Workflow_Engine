package sn.gouv.mfp.workflow.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
import sn.gouv.mfp.workflow.service.admin.AdminRequestTypeService.AdminRequestTypeDetailView;
import sn.gouv.mfp.workflow.service.admin.AdminRequestTypeService.AdminRequestTypeView;
import sn.gouv.mfp.workflow.service.admin.AdminRequestTypeService.DeployedWorkflowView;
import sn.gouv.mfp.workflow.service.admin.AdminRequestTypeService.FormSchemaView;

/**
 * Administration des E-Services : création (contrôles de clé et d'unicité), enregistrement
 * de schéma (validé avant persistance) et publication (version croissante, immuable, type
 * publié, liaison de champs contrôlée contre les variables déclarées du circuit).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminRequestTypeServiceTest {

    @Mock
    private RequestTypeRepository requestTypes;

    @Mock
    private RequestTypeVersionRepository versions;

    @Mock
    private FormSchemaRepository formSchemas;

    @Mock
    private WorkflowDefinitionRepository definitions;

    @Mock
    private FormSchemaValidationService schemaValidation;

    @InjectMocks
    private AdminRequestTypeService service;

    @BeforeEach
    void persistReturnsTheSavedEntity() {
        when(requestTypes.saveAndFlush(any(RequestType.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(formSchemas.saveAndFlush(any(FormSchema.class)))
                .thenAnswer(invocation -> {
                    FormSchema schema = invocation.getArgument(0);
                    if (schema.getId() == null) {
                        schema.setId(UUID.randomUUID());
                    }
                    return schema;
                });
        when(versions.saveAndFlush(any(RequestTypeVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void create_shouldStoreADraftType() {
        AdminRequestTypeView view = service.create("  conge_test  ".toUpperCase(), "  Congé test ", "Congés");

        ArgumentCaptor<RequestType> saved = ArgumentCaptor.forClass(RequestType.class);
        verify(requestTypes).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getKey()).isEqualTo("CONGE_TEST");
        assertThat(saved.getValue().getLabel()).isEqualTo("Congé test");
        assertThat(saved.getValue().getStatus()).isEqualTo(RequestTypeStatus.DRAFT);
        assertThat(view.status()).isEqualTo("DRAFT");
        assertThat(view.version()).isNull();
    }

    @Test
    void create_shouldRejectAKeyThatIsNotTechnical() {
        assertThatThrownBy(() -> service.create("Congé Maternité", "Libellé", null))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Clé de type de demande invalide");

        verify(requestTypes, never()).saveAndFlush(any(RequestType.class));
    }

    @Test
    void create_shouldRejectAnEmptyLabel() {
        assertThatThrownBy(() -> service.create("CONGE_TEST", "   ", null))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("libellé");
    }

    @Test
    void create_shouldRejectADuplicateKey() {
        when(requestTypes.existsByKey("CERTIFICAT_TRAVAIL")).thenReturn(true);

        assertThatThrownBy(() -> service.create("CERTIFICAT_TRAVAIL", "Doublon", null))
                .isInstanceOf(WorkflowConflictException.class)
                .hasMessageContaining("existe déjà");
    }

    @Test
    void createFormSchema_shouldRejectAnUnusableSchemaBeforePersisting() {
        doThrow(new WorkflowValidationException("Un formulaire doit décrire un objet"))
                .when(schemaValidation)
                .validateDefinition(any());

        assertThatThrownBy(() -> service.createFormSchema(Map.of("type", "string"), null))
                .isInstanceOf(WorkflowValidationException.class);

        verify(formSchemas, never()).saveAndFlush(any(FormSchema.class));
    }

    @Test
    void createFormSchema_shouldReturnTheIdentifierToPublish() {
        Map<String, Object> jsonSchema = Map.of(
                "type", "object",
                "properties", Map.of("dateDebut", Map.of("type", "string", "format", "date")));

        FormSchemaView view = service.createFormSchema(jsonSchema, Map.of("ui:order", List.of("dateDebut")));

        assertThat(view.id()).isNotNull();
        assertThat(view.jsonSchema()).containsEntry("type", "object");
        assertThat(view.uiSchema()).containsKey("ui:order");
        verify(schemaValidation).validateDefinition(jsonSchema);
    }

    @Test
    void publish_shouldCreateTheFirstVersionAndPublishTheType() {
        RequestType type = givenType(RequestTypeStatus.DRAFT);
        FormSchema schema = givenSchema();
        WorkflowDefinition definition = givenDefinition("E_SERVICE_DEMANDE", 1, List.of());

        AdminRequestTypeView view = service.publish(type.getId(), schema.getId(), "E_SERVICE_DEMANDE", null);

        ArgumentCaptor<RequestTypeVersion> saved = ArgumentCaptor.forClass(RequestTypeVersion.class);
        verify(versions).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getVersion()).isEqualTo(1);
        assertThat(saved.getValue().getPublishedAt()).isNotNull();
        assertThat(saved.getValue().getFieldMapping()).isNull();
        assertThat(saved.getValue().getDefinition()).isSameAs(definition);
        assertThat(view.status()).isEqualTo("PUBLISHED");
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.definitionKey()).isEqualTo("E_SERVICE_DEMANDE");
    }

    @Test
    void publish_again_shouldCreateTheNextVersion() {
        RequestType type = givenType(RequestTypeStatus.PUBLISHED);
        FormSchema schema = givenSchema();
        givenDefinition("E_SERVICE_DEMANDE", 1, List.of());
        RequestTypeVersion previous = new RequestTypeVersion();
        previous.setId(UUID.randomUUID());
        previous.setVersion(3);
        when(versions.findFirstByRequestTypeIdOrderByVersionDesc(type.getId()))
                .thenReturn(Optional.of(previous));

        AdminRequestTypeView view = service.publish(type.getId(), schema.getId(), "E_SERVICE_DEMANDE", Map.of());

        ArgumentCaptor<RequestTypeVersion> saved = ArgumentCaptor.forClass(RequestTypeVersion.class);
        verify(versions).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getVersion()).isEqualTo(4);
        assertThat(view.version()).isEqualTo(4);
    }

    @Test
    void publish_shouldRejectAnUnknownCircuit() {
        RequestType type = givenType(RequestTypeStatus.DRAFT);
        FormSchema schema = givenSchema();
        when(definitions.findByKey("CIRCUIT_INCONNU")).thenReturn(List.of());

        assertThatThrownBy(() -> service.publish(
                        type.getId(), schema.getId(), "CIRCUIT_INCONNU", null))
                .isInstanceOf(WorkflowNotFoundException.class)
                .hasMessageContaining("Aucun circuit déployé");

        verify(versions, never()).saveAndFlush(any(RequestTypeVersion.class));
    }

    @Test
    void publish_shouldRejectAMappingToAnUndeclaredVariable() {
        RequestType type = givenType(RequestTypeStatus.DRAFT);
        FormSchema schema = givenSchema();
        givenDefinition("E_SERVICE_DEMANDE", 1, List.of(new VariableDeclaration("natureActe", "string", false)));

        assertThatThrownBy(() -> service.publish(
                        type.getId(), schema.getId(), "E_SERVICE_DEMANDE",
                        Map.of("dateDebut", "variableInconnue")))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("n'est pas déclarée par le circuit");
    }

    @Test
    void publish_shouldRejectAMappingFromAnUnknownField() {
        RequestType type = givenType(RequestTypeStatus.DRAFT);
        FormSchema schema = givenSchema();
        givenDefinition("E_SERVICE_DEMANDE", 1, List.of(new VariableDeclaration("natureActe", "string", false)));

        assertThatThrownBy(() -> service.publish(
                        type.getId(), schema.getId(), "E_SERVICE_DEMANDE",
                        Map.of("champInconnu", "natureActe")))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("ne figure pas dans le formulaire");
    }

    @Test
    void publish_shouldKeepAValidMapping() {
        RequestType type = givenType(RequestTypeStatus.DRAFT);
        FormSchema schema = givenSchema();
        givenDefinition("E_SERVICE_DEMANDE", 2, List.of(new VariableDeclaration("natureActe", "string", false)));

        service.publish(type.getId(), schema.getId(), "E_SERVICE_DEMANDE",
                Map.of("dateDebut", "natureActe"));

        ArgumentCaptor<RequestTypeVersion> saved = ArgumentCaptor.forClass(RequestTypeVersion.class);
        verify(versions).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFieldMapping()).containsEntry("dateDebut", "natureActe");
    }

    @Test
    void listAll_shouldExposeTheLatestVersionOfEachType() {
        RequestType type = givenType(RequestTypeStatus.PUBLISHED);
        when(requestTypes.findAllByOrderByLabelAsc()).thenReturn(List.of(type));
        RequestTypeVersion version = new RequestTypeVersion();
        version.setVersion(2);
        version.setPublishedAt(Instant.parse("2026-10-19T10:00:00Z"));
        version.setDefinition(givenDefinition("E_SERVICE_DEMANDE", 1, List.of()));
        when(versions.findFirstByRequestTypeIdOrderByVersionDesc(type.getId()))
                .thenReturn(Optional.of(version));

        List<AdminRequestTypeView> types = service.listAll();

        assertThat(types).singleElement().satisfies(view -> {
            assertThat(view.key()).isEqualTo(type.getKey());
            assertThat(view.status()).isEqualTo("PUBLISHED");
            assertThat(view.version()).isEqualTo(2);
        });
    }

    @Test
    void detail_shouldServeTheActiveSchemaForTheEditor() {
        RequestType type = givenType(RequestTypeStatus.PUBLISHED);
        when(requestTypes.findById(type.getId())).thenReturn(Optional.of(type));
        RequestTypeVersion version = new RequestTypeVersion();
        version.setVersion(1);
        version.setFormSchema(givenSchema());
        version.setFieldMapping(Map.of("dateDebut", "natureActe"));
        version.setDefinition(givenDefinition("E_SERVICE_DEMANDE", 1, List.of()));
        when(versions.findFirstByRequestTypeIdOrderByVersionDesc(type.getId()))
                .thenReturn(Optional.of(version));

        AdminRequestTypeDetailView detail = service.detail(type.getId());

        assertThat(detail.jsonSchema()).containsEntry("type", "object");
        assertThat(detail.fieldMapping()).containsEntry("dateDebut", "natureActe");
        assertThat(detail.definitionKey()).isEqualTo("E_SERVICE_DEMANDE");
    }

    @Test
    void detail_shouldRejectAnUnknownType() {
        UUID unknown = UUID.randomUUID();
        when(requestTypes.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(unknown))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    @Test
    void deployedWorkflows_shouldListTheLatestVersionOfEachKey() {
        WorkflowDefinition definition = givenDefinition("E_SERVICE_DEMANDE", 3, List.of());
        when(definitions.findLatestVersionPerKey()).thenReturn(List.of(definition));

        List<DeployedWorkflowView> workflows = service.deployedWorkflows();

        assertThat(workflows).singleElement().satisfies(view -> {
            assertThat(view.key()).isEqualTo("E_SERVICE_DEMANDE");
            assertThat(view.version()).isEqualTo(3);
        });
    }

    /** Type persisté, retrouvable par son identifiant (comme le ferait le référentiel). */
    private RequestType givenType(RequestTypeStatus status) {
        RequestType type = new RequestType();
        type.setId(UUID.randomUUID());
        type.setKey("CONGE_TEST");
        type.setLabel("Congé test");
        type.setStatus(status);
        when(requestTypes.findById(type.getId())).thenReturn(Optional.of(type));
        return type;
    }

    /** Schéma de formulaire persisté, retrouvable par son identifiant. */
    private FormSchema givenSchema() {
        FormSchema schema = new FormSchema();
        schema.setId(UUID.randomUUID());
        schema.setJsonSchema(Map.of(
                "type", "object",
                "properties", Map.of("dateDebut", Map.of("type", "string", "format", "date"))));
        when(formSchemas.findById(schema.getId())).thenReturn(Optional.of(schema));
        return schema;
    }

    private WorkflowDefinition givenDefinition(
            String key, int version, List<VariableDeclaration> variables) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(UUID.randomUUID());
        definition.setKey(key);
        definition.setName("Circuit " + key);
        definition.setVersion(version);
        definition.setVariableDeclarations(variables);
        when(definitions.findByKey(anyString())).thenReturn(List.of(definition));
        return definition;
    }
}
