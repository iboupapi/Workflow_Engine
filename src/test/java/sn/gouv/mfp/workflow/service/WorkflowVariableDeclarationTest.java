package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.execution.WorkflowInstance;
import sn.gouv.mfp.workflow.exception.MissingRequiredDataException;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;

/**
 * MOT-10 — variables déclarées, <strong>obligatoires et contrôlées dès la création</strong> du
 * dossier : le bloc {@code variables:} déclare pour chaque entrée une clé, un type
 * ({@code string}, {@code number}, {@code boolean}, {@code date}) et un caractère obligatoire.
 * La création est refusée si une variable obligatoire manque ou si le type d'une variable
 * déclarée est incompatible.
 */
@SpringBootTest
@Transactional
class WorkflowVariableDeclarationTest {

    @Autowired
    private WorkflowDefinitionLoaderService loaderService;

    @Autowired
    private WorkflowExecutionService executionService;

    @Test
    void declarations_shouldBePersistedWithTypeAndRequiredFlag() throws Exception {
        WorkflowDefinition definition = load(demandeYaml(uniqueKey())).definition();

        assertThat(definition.getVariableDeclarations())
                .extracting(d -> d.key() + ":" + d.type() + ":" + d.required())
                .containsExactly("circuit:string:true", "age:number:false");
    }

    @Test
    void creationWithoutRequiredVariable_shouldBeRefused() throws Exception {
        String key = uniqueKey();
        load(demandeYaml(key));

        // 'circuit' est obligatoire : création sans elle -> refus 400.
        assertThatThrownBy(() -> executionService.startWorkflow(key, "usager-01", null, "DEPOT"))
                .isInstanceOf(MissingRequiredDataException.class)
                .hasMessageContaining("circuit");
    }

    @Test
    void creationWithRequiredVariablePresent_shouldSucceed() throws Exception {
        String key = uniqueKey();
        load(demandeYaml(key));

        WorkflowInstance instance = executionService.startWorkflow(
                key, "usager-01", Map.of("circuit", "COURT"), "DEPOT");

        assertThat(instance.getStatus().name()).isEqualTo("ACTIVE");
        assertThat(instance.getVariables()).containsEntry("circuit", "COURT");
    }

    @Test
    void creationWithWrongType_shouldBeRefused() throws Exception {
        String key = uniqueKey();
        load(demandeYaml(key));

        // 'age' déclarée 'number' mais fournie en texte -> refus 400.
        assertThatThrownBy(() -> executionService.startWorkflow(
                key, "usager-01", Map.of("circuit", "COURT", "age", "quarante"), "DEPOT"))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("age");

        // Type conforme -> création acceptée.
        WorkflowInstance instance = executionService.startWorkflow(
                key, "usager-01", Map.of("circuit", "COURT", "age", 42), "DEPOT");
        assertThat(instance.getId()).isNotNull();
    }

    @Test
    void importWithUnknownVariableType_shouldBeRefused() {
        String yaml = demandeYaml(uniqueKey()).replace("\"number\"", "\"datex\"");

        assertThatThrownBy(() -> loaderService.loadAndSaveYamlDefinition(yaml))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("datex");
    }

    private sn.gouv.mfp.workflow.service.dto.DefinitionImport load(String yaml) {
        return loaderService.loadAndSaveYamlDefinition(yaml);
    }

    private String uniqueKey() {
        return "MOT10_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String demandeYaml(String key) {
        return """
                definition:
                  key: "%s"
                  name: "Circuit MOT-10"
                variables:
                  - key: "circuit"
                    type: "string"
                    required: true
                  - key: "age"
                    type: "number"
                steps:
                  - key: "DEPOT"
                    type: "START"
                    owners: ["USAGER"]
                  - key: "FIN"
                    type: "END"
                transitions:
                  - source: "DEPOT"
                    action: "VALIDER"
                    target: "FIN"
                """.formatted(key);
    }
}
