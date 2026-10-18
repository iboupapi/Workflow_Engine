package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sn.gouv.mfp.workflow.service.dto.WorkflowConfigDTO;

/**
 * Empreinte canonique : insensible aux commentaires, à l'indentation, à l'ordre des blocs
 * et à la syntaxe historique des conditions ; sensible au moindre changement réel.
 * Le champ version du YAML n'entre jamais dans l'empreinte.
 */
class ImportFingerprintTest {

    @Test
    void identicalModels_shouldShareFingerprint() {
        var config = circuit("K", List.of("B", "A"), "#variables['circuit'] == 'COURT'");

        String hash = ImportFingerprint.sha256Hex(config, Map.of(transition(config), canonical(config)));

        assertThat(hash).hasSize(64);
        // Ordre des étapes différent, même graphe : même empreinte.
        var reordered = circuit("K", List.of("A", "B"), "#variables['circuit'] == 'COURT'");
        assertThat(ImportFingerprint.sha256Hex(
                        reordered, Map.of(transition(reordered), canonical(config))))
                .isEqualTo(hash);
    }

    @Test
    void realChange_shouldChangeFingerprint() {
        var before = circuit("K", List.of("A", "B"), "#variables['circuit'] == 'COURT'");
        var after = circuit("K", List.of("A", "B"), "#variables['circuit'] == 'LONG'");

        assertThat(ImportFingerprint.sha256Hex(after, Map.of(transition(after), canonical(after))))
                .isNotEqualTo(
                        ImportFingerprint.sha256Hex(before, Map.of(transition(before), canonical(before))));
    }

    private WorkflowConfigDTO circuit(String key, List<String> stepOrder, String condition) {
        var steps = stepOrder.stream()
                .map(k -> new WorkflowConfigDTO.StepConfigDTO(
                        k, k, k.equals("A") ? "START" : "END", null, null))
                .toList();
        var transition = new WorkflowConfigDTO.TransitionConfigDTO("A", "VALIDER", "B", condition);
        return new WorkflowConfigDTO(
                new WorkflowConfigDTO.DefinitionConfigDTO(key, key, 99),
                null,
                null,
                List.of(new WorkflowConfigDTO.VariableConfigDTO("circuit", null, null)),
                steps,
                List.of(transition));
    }

    private WorkflowConfigDTO.TransitionConfigDTO transition(WorkflowConfigDTO config) {
        return config.transitions().get(0);
    }

    private String canonical(WorkflowConfigDTO config) {
        return SpelSecurityValidator.validate(
                config.transitions().get(0).condition(), java.util.Set.of("circuit"));
    }
}
