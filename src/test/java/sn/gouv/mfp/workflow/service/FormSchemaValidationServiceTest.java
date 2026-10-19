package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sn.gouv.mfp.workflow.domain.request.FormSchema;
import sn.gouv.mfp.workflow.exception.FormSchemaValidationException;

/**
 * FRM-03 — validation côté serveur systématique : obligatoire, bornes, formats, masque,
 * champs conditionnels. Chaque écart est vu comme un refus (aucune donnée acceptée sans
 * contrôle), et un schéma absent refuse la soumission au lieu de la laisser passer.
 */
class FormSchemaValidationServiceTest {

    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    /** Formulaire de référence (FRM-01 : texte, nombre, date, liste). */
    private static final Map<String, Object> FORM = map(
            "$schema", DRAFT_2020_12,
            "type", "object",
            "additionalProperties", false,
            "required", List.of("nom", "mail", "naissance", "age", "montant", "code", "situation"),
            "properties", map(
                    "nom", map("type", "string", "minLength", 2, "maxLength", 30),
                    "mail", map("type", "string", "format", "email"),
                    "naissance", map("type", "string", "format", "date"),
                    "age", map("type", "integer", "minimum", 18, "maximum", 70),
                    "montant", map("type", "number", "minimum", 0, "maximum", 10000),
                    "code", map("type", "string", "pattern", "^[0-9]{9}$"),
                    "situation", map("type", "string", "enum", List.of("CELIBATAIRE", "MARIE")),
                    "conjoint", map("type", "string")));

    /** Champ conditionnel (FRM-03) : le conjoint n'est obligatoire que si la situation est « MARIE ». */
    private static final Map<String, Object> CONDITIONAL = map(
            "$schema", DRAFT_2020_12,
            "type", "object",
            "required", List.of("situation"),
            "properties", map(
                    "situation", map("type", "string", "enum", List.of("CELIBATAIRE", "MARIE")),
                    "conjoint", map("type", "string")),
            "if", map("properties", map("situation", map("const", "MARIE"))),
            "then", map("required", List.of("conjoint")));

    private final FormSchemaValidationService service = new FormSchemaValidationService();

    @Test
    void validSubmission_shouldBeAccepted() {
        assertThat(violationsOf(validData())).isEmpty();
        assertThatCode(() -> service.validate(schema(FORM), validData()))
                .doesNotThrowAnyException();
    }

    @Test
    void missingRequiredField_shouldBeRejected() {
        Map<String, Object> data = new LinkedHashMap<>(validData());
        data.remove("nom");

        assertThat(violationsOf(data)).anyMatch(violation -> violation.contains("nom"));
    }

    @Test
    void emptySubmission_shouldBeRejectedOnEveryRequiredField() {
        List<String> violations = violationsOf(Map.of());

        assertThat(violations).isNotEmpty();
        assertThat(List.of("nom", "mail", "naissance", "age", "montant", "code", "situation"))
                .allSatisfy(field -> assertThat(violations)
                        .anyMatch(violation -> violation.contains(field)));
    }

    @Test
    void nullSubmission_shouldBeRejectedInsteadOfAccepted() {
        assertThatThrownBy(() -> service.validate(schema(FORM), null))
                .isInstanceOf(FormSchemaValidationException.class);
    }

    @Test
    void wrongType_shouldBeRejected() {
        assertThat(violationsOf(dataWith("age", "trente")))
                .anyMatch(violation -> violation.contains("age"));
    }

    @Test
    void boundsExceeded_shouldBeRejected() {
        Map<String, Object> data = new LinkedHashMap<>(validData());
        data.put("age", 15);
        data.put("montant", 20000);

        List<String> violations = violationsOf(data);

        assertThat(violations).hasSize(2);
        assertThat(violations).anyMatch(violation -> violation.contains("age"));
        assertThat(violations).anyMatch(violation -> violation.contains("montant"));
    }

    @Test
    void stringLengthOutOfBounds_shouldBeRejected() {
        assertThat(violationsOf(dataWith("nom", "A")))
                .anyMatch(violation -> violation.contains("nom"));
        assertThat(violationsOf(dataWith("nom", "A".repeat(31))))
                .anyMatch(violation -> violation.contains("nom"));
    }

    @Test
    void invalidDateFormat_shouldBeRejected() {
        assertThat(violationsOf(dataWith("naissance", "18/10/2026")))
                .anyMatch(violation -> violation.contains("naissance"));
    }

    @Test
    void invalidEmailFormat_shouldBeRejected() {
        assertThat(violationsOf(dataWith("mail", "pas-un-courriel")))
                .anyMatch(violation -> violation.contains("mail"));
    }

    @Test
    void valueOutsideMask_shouldBeRejected() {
        assertThat(violationsOf(dataWith("code", "12AB56789")))
                .anyMatch(violation -> violation.contains("code"));
    }

    @Test
    void valueOutsideList_shouldBeRejected() {
        assertThat(violationsOf(dataWith("situation", "DIVORCE")))
                .anyMatch(violation -> violation.contains("situation"));
    }

    @Test
    void unknownField_shouldBeRejected() {
        assertThat(violationsOf(dataWith("intrus", "valeur")))
                .anyMatch(violation -> violation.contains("intrus"));
    }

    @Test
    void conditionalField_shouldBeRequiredOnlyWhenTheConditionHolds() {
        assertThat(violationsOf(CONDITIONAL, map("situation", "CELIBATAIRE"))).isEmpty();
        assertThat(violationsOf(CONDITIONAL, map("situation", "MARIE", "conjoint", "Fatou Ndiaye")))
                .isEmpty();
        assertThat(violationsOf(CONDITIONAL, map("situation", "MARIE")))
                .anyMatch(violation -> violation.contains("conjoint"));
    }

    @Test
    void exceptionShouldCarryTheViolations() {
        Map<String, Object> data = new LinkedHashMap<>(validData());
        data.put("age", 12);

        assertThatThrownBy(() -> service.validate(schema(FORM), data))
                .isInstanceOf(FormSchemaValidationException.class)
                .hasMessageContaining("écart")
                .satisfies(thrown -> assertThat(
                                ((FormSchemaValidationException) thrown).getViolations())
                        .isNotEmpty());
    }

    @Test
    void missingSchema_shouldRefuseTheSubmission() {
        assertThatThrownBy(() -> service.validate(schema(null), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aucun schéma");
        assertThatThrownBy(() -> service.validate(schema(Map.of()), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aucun schéma");
        assertThatThrownBy(() -> service.validate(null, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aucun schéma");
    }

    @Test
    void cachedSchema_shouldKeepValidatingTheSameWay() {
        FormSchema persisted = schema(FORM);
        persisted.setId(UUID.randomUUID());

        assertThat(service.validateAndCollect(persisted, validData())).isEmpty();
        assertThat(service.validateAndCollect(persisted, dataWith("age", 12)))
                .anyMatch(violation -> violation.contains("age"));
    }

    private List<String> violationsOf(Map<String, Object> data) {
        return violationsOf(FORM, data);
    }

    private List<String> violationsOf(Map<String, Object> jsonSchema, Map<String, Object> data) {
        return service.validateAndCollect(schema(jsonSchema), data);
    }

    private static Map<String, Object> validData() {
        return map(
                "nom", "Awa Diop",
                "mail", "awa.diop@mfp.gouv.sn",
                "naissance", "1990-05-12",
                "age", 30,
                "montant", 2500.50,
                "code", "123456789",
                "situation", "CELIBATAIRE");
    }

    private static Map<String, Object> dataWith(String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<>(validData());
        data.put(key, value);
        return data;
    }

    private static FormSchema schema(Map<String, Object> jsonSchema) {
        FormSchema formSchema = new FormSchema();
        formSchema.setJsonSchema(jsonSchema);
        return formSchema;
    }

    /** Petit constructeur de map littéral, pour garder les schémas de test lisibles. */
    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return map;
    }
}
