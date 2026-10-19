package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * REQ-02 — liaison des champs du formulaire aux variables de décision : seuls les champs
 * déclarés alimentent le dossier, les valeurs absentes ou nulles sont omises, une liaison
 * incohérente refuse la soumission.
 */
class RequestFieldMappingServiceTest {

    private final RequestFieldMappingService service = new RequestFieldMappingService();

    @Test
    void declaredField_shouldBecomeWorkflowVariable() {
        Map<String, Object> mapping = map("typeActe", "natureActe");
        Map<String, Object> formData = map("typeActe", "ARRETE", "motif", "Promotion");

        assertThat(service.toWorkflowVariables(mapping, formData))
                .containsExactlyEntriesOf(map("natureActe", "ARRETE"));
    }

    @Test
    void unmappedFormField_shouldNotLeakIntoVariables() {
        Map<String, Object> variables =
                service.toWorkflowVariables(map("typeActe", "natureActe"), map("typeActe", "ARRETE"));

        assertThat(variables).containsOnlyKeys("natureActe");
    }

    @Test
    void absentField_shouldBeOmitted() {
        Map<String, Object> variables =
                service.toWorkflowVariables(map("typeActe", "natureActe"), map("motif", "Promotion"));

        assertThat(variables).isEmpty();
    }

    @Test
    void nullField_shouldBeOmittedRatherThanInjectedAsNull() {
        Map<String, Object> formData = map("typeActe", null);

        assertThat(service.toWorkflowVariables(map("typeActe", "natureActe"), formData))
                .isEmpty();
    }

    @Test
    void values_shouldKeepTheirJsonType() {
        Map<String, Object> mapping = map("pieces", "piecesJointes", "urgent", "urgent", "montant", "montant");
        Map<String, Object> formData = map(
                "pieces", List.of("acte.pdf"),
                "urgent", true,
                "montant", 12.5);

        assertThat(service.toWorkflowVariables(mapping, formData))
                .containsExactlyEntriesOf(map(
                        "piecesJointes", List.of("acte.pdf"),
                        "urgent", true,
                        "montant", 12.5));
    }

    @Test
    void withoutMappingOrWithoutData_shouldProduceNoVariable() {
        assertThat(service.toWorkflowVariables(null, map("typeActe", "ARRETE"))).isEmpty();
        assertThat(service.toWorkflowVariables(Map.of(), map("typeActe", "ARRETE"))).isEmpty();
        assertThat(service.toWorkflowVariables(map("typeActe", "natureActe"), null)).isEmpty();
        assertThat(service.toWorkflowVariables(map("typeActe", "natureActe"), Map.of())).isEmpty();
    }

    @Test
    void blankTarget_shouldBeRejectedAsConfigurationFault() {
        assertThatThrownBy(() ->
                        service.toWorkflowVariables(map("typeActe", "  "), map("typeActe", "ARRETE")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("typeActe");
    }

    @Test
    void nonTextTarget_shouldBeRejectedAsConfigurationFault() {
        assertThatThrownBy(() ->
                        service.toWorkflowVariables(map("typeActe", 42), map("typeActe", "ARRETE")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("typeActe");
    }

    @Test
    void twoFieldsForTheSameVariable_shouldBeRejected() {
        assertThatThrownBy(() -> service.toWorkflowVariables(
                        map("typeActe", "natureActe", "autreActe", "natureActe"),
                        map("typeActe", "ARRETE", "autreActe", "DECISION")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("natureActe");
    }

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return map;
    }
}
