package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;

/**
 * Cahier de recette 7.4 (partiel) — durcissement du parsing YAML : balises d'objets refusées,
 * alias récursifs refusés, fichiers surdimensionnés et clés dupliquées refusés. SnakeYAML 2.x
 * (ici 2.5) refuse la désérialisation d'objets arbitraires par défaut.
 */
@SpringBootTest
@Transactional
class YamlSecurityTest {

    @Autowired
    private WorkflowDefinitionLoaderService loaderService;

    @Autowired
    private WorkflowDefinitionRepository definitions;

    @Test
    void javaTag_shouldBeRefusedAndNothingPersisted() {
        String yaml = """
                definition:
                  key: "EVIL_TAG"
                  name: !!javax.script.ScriptEngineManager "piege"
                steps:
                  - key: "A"
                    type: "START"
                  - key: "B"
                    type: "END"
                transitions:
                  - source: "A"
                    action: "VALIDER"
                    target: "B"
                """;

        assertThatThrownBy(() -> loaderService.loadAndSaveYamlDefinition(yaml))
                .isInstanceOf(WorkflowValidationException.class);
        assertThat(definitions.findByKey("EVIL_TAG")).isEmpty();
    }

    @Test
    void recursiveAlias_shouldBeRefused() {
        String yaml = """
                definition:
                  key: "EVIL_ALIAS"
                  name: "piege"
                steps:
                  - key: "A"
                    type: "START"
                  - key: "B"
                    type: "END"
                transitions:
                  - source: "A"
                    action: "VALIDER"
                    target: "B"
                piege: &ancre
                  boucle: *ancre
                """;

        assertThatThrownBy(() -> loaderService.loadAndSaveYamlDefinition(yaml))
                .isInstanceOf(WorkflowValidationException.class);
        assertThat(definitions.findByKey("EVIL_ALIAS")).isEmpty();
    }

    @Test
    void oversizedFile_shouldBeRefused() {
        String yaml = "definition:\n  key: \"x\"\n# " + "x".repeat(1_000_001) + "\n";

        assertThatThrownBy(() -> loaderService.loadAndSaveYamlDefinition(yaml))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("volumineux");
    }

    @Test
    void duplicateKeys_shouldBeRefused() {
        String yaml = """
                definition:
                  key: "DUP"
                  key: "DUP_BIS"
                  name: "piege"
                steps:
                  - key: "A"
                    type: "START"
                  - key: "B"
                    type: "END"
                transitions:
                  - source: "A"
                    action: "VALIDER"
                    target: "B"
                """;

        assertThatThrownBy(() -> loaderService.loadAndSaveYamlDefinition(yaml))
                .isInstanceOf(WorkflowValidationException.class);
        assertThat(definitions.findByKey("DUP")).isEmpty();
        assertThat(definitions.findByKey("DUP_BIS")).isEmpty();
    }
}
