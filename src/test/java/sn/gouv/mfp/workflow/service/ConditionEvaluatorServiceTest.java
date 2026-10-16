package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Étape 2 — test unitaire strict du résolveur SpEL (logique pure, sans Spring ni base).
 * Couvre les 4 cas d'acceptation du cahier des charges + les scénarios GIRAFE.
 */
class ConditionEvaluatorServiceTest {

    private ConditionEvaluatorService evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new ConditionEvaluatorService();
    }

    // --- Cas d'acceptation du CDC ---

    @Test
    void nullCondition_shouldReturnTrue() {
        assertThat(evaluator.evaluate(null, Map.of("circuit", "COURT"))).isTrue();
    }

    @Test
    void blankCondition_shouldReturnTrue() {
        assertThat(evaluator.evaluate("", Map.of("circuit", "COURT"))).isTrue();
        assertThat(evaluator.evaluate("   ", Map.of("circuit", "COURT"))).isTrue();
    }

    @Test
    void nominalTrue_shouldReturnTrue() {
        assertThat(evaluator.evaluate(
                        "#variables['circuit'] == 'COURT'",
                        Map.of("circuit", "COURT")))
                .isTrue();
    }

    @Test
    void nominalFalse_shouldReturnFalse() {
        assertThat(evaluator.evaluate(
                        "#variables['circuit'] == 'COURT'",
                        Map.of("circuit", "LONG")))
                .isFalse();
    }

    @Test
    void invalidSyntax_shouldReturnFalseWithoutCrashing() {
        assertThatCode(() -> assertThat(evaluator.evaluate(
                        "#variables['circuit'] === 'COURT'",
                        Map.of("circuit", "COURT")))
                .isFalse())
                .doesNotThrowAnyException();
    }

    @Test
    void missingVariableDereferenced_shouldReturnFalseWithoutCrashing() {
        assertThatCode(() -> assertThat(evaluator.evaluate(
                        "#variables['circuit'].length() > 3",
                        Map.of()))
                .isFalse())
                .doesNotThrowAnyException();
    }

    // --- Scénarios GIRAFE ---

    @Test
    void combinedConditions_shouldEvaluateConjunction() {
        Map<String, Object> variables = Map.of("natureActe", "ARRETE", "circuit", "LONG");
        assertThat(evaluator.evaluate(
                        "#variables['natureActe'] == 'ARRETE' and #variables['circuit'] == 'LONG'",
                        variables))
                .isTrue();
        assertThat(evaluator.evaluate(
                        "#variables['natureActe'] == 'ARRETE' and #variables['circuit'] == 'COURT'",
                        variables))
                .isFalse();
    }

    @Test
    void booleanVariable_shouldEvaluate() {
        assertThat(evaluator.evaluate(
                        "#variables['solde_valide'] == true",
                        Map.of("solde_valide", true)))
                .isTrue();
        assertThat(evaluator.evaluate(
                        "#variables['solde_valide'] == true",
                        Map.of("solde_valide", false)))
                .isFalse();
    }

    @Test
    void nestedMapAccess_shouldEvaluate() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("demandeur", Map.of("matricule", "SN-042"));
        assertThat(evaluator.evaluate(
                        "#variables['demandeur']['matricule'] == 'SN-042'",
                        variables))
                .isTrue();
    }
}
