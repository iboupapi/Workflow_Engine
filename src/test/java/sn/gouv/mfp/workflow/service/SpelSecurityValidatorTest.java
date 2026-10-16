package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;

/**
 * Cahier de recette, section 1 — sécurité des conditions SpEL.
 * Test unitaire pur du garde-fou appliqué à l'import (refus HTTP 400, rien persisté).
 */
class SpelSecurityValidatorTest {

    private static final Set<String> DECLARED = Set.of("circuit", "natureActe");

    // --- Recette 1.1 / 1.2 : exécution de code et instanciation ---

    @Test
    void codeExecution_shouldBeRefused() {
        assertThatThrownBy(() -> SpelSecurityValidator.validate(
                        "T(java.lang.Runtime).getRuntime().exec('ls')", DECLARED))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("T(");
    }

    @Test
    void objectInstantiation_shouldBeRefused() {
        assertThatThrownBy(() -> SpelSecurityValidator.validate(
                        "new java.io.File('/etc/passwd')", DECLARED))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("'new'");
    }

    // --- Recette 1.3 : écriture et appels de méthode ---

    @Test
    void methodCall_shouldBeRefused() {
        assertThatThrownBy(() -> SpelSecurityValidator.validate(
                        "#variables.put('role','ADMIN')", DECLARED))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("méthode");
    }

    @Test
    void assignment_shouldBeRefused() {
        assertThatThrownBy(() -> SpelSecurityValidator.validate(
                        "#variables['x'] = 1", DECLARED))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("affectation");
    }

    // --- Corpus adverse : échappements vers le réflectif et le contexte ---

    @Test
    void classAccess_shouldBeRefused() {
        assertThatThrownBy(() -> SpelSecurityValidator.validate(
                        "#variables['a'].class == 'x'", Set.of("a")))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining(".class");
        assertThatThrownBy(() -> SpelSecurityValidator.validate(
                        "#variables['a'].getClass() == 'x'", DECLARED))
                .isInstanceOf(WorkflowValidationException.class);
    }

    @Test
    void specialVariables_shouldBeRefused() {
        assertThatThrownBy(() -> SpelSecurityValidator.validate("#this == 'x'", DECLARED))
                .isInstanceOf(WorkflowValidationException.class);
        assertThatThrownBy(() -> SpelSecurityValidator.validate("#root == 'x'", DECLARED))
                .isInstanceOf(WorkflowValidationException.class);
    }

    @Test
    void collectionSelectionAndProjection_shouldBeRefused() {
        assertThatThrownBy(() -> SpelSecurityValidator.validate(
                        "#variables['l'].?[#this > 1]", DECLARED))
                .isInstanceOf(WorkflowValidationException.class);
        assertThatThrownBy(() -> SpelSecurityValidator.validate(
                        "#variables['l'].![#this]", DECLARED))
                .isInstanceOf(WorkflowValidationException.class);
    }

    @Test
    void beanReference_shouldBeRefused() {
        assertThatThrownBy(() -> SpelSecurityValidator.validate("@monBean == 1", DECLARED))
                .isInstanceOf(WorkflowValidationException.class);
    }

    @Test
    void matchesOperator_shouldBeRefused() {
        assertThatThrownBy(() -> SpelSecurityValidator.validate(
                        "#variables['code'] matches '(a+)+$'", DECLARED))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("matches");
    }

    @Test
    void normalize_shouldNotTouchQuotedAttackSurface() {
        // Une chaîne contenant variables[ entre guillemets reste un littéral inerte.
        String raw = "#variables['note'] == 'voir variables[x] en interne'";
        assertThat(SpelSecurityValidator.normalize(raw)).isEqualTo(raw);
        String canonical = SpelSecurityValidator.validate(raw, Set.of("note"));
        ConditionEvaluatorService evaluator = new ConditionEvaluatorService();
        assertThat(evaluator.evaluate(
                        canonical, Map.of("note", "voir variables[x] en interne")))
                .isTrue();
    }

    // --- Recette 1.4 : conditions légitimes ---

    @Test
    void legitimateConditions_shouldBeAccepted() {
        assertThat(SpelSecurityValidator.validate("#variables['circuit'] == 'COURT'", DECLARED))
                .isEqualTo("#variables['circuit'] == 'COURT'");
        assertThat(SpelSecurityValidator.validate("#variables['circuit'] != 'COURT'", DECLARED))
                .isNotBlank();
        assertThat(SpelSecurityValidator.validate(
                        "#variables['natureActe'] == 'ARRETE' and #variables['circuit'] == 'LONG'",
                        DECLARED))
                .isNotBlank();
        assertThat(SpelSecurityValidator.validate(
                        "#variables['circuit'] == 'LONG' or #variables['circuit'] == null",
                        DECLARED))
                .isNotBlank();
        assertThat(SpelSecurityValidator.validate(
                        "!(#variables['circuit'] == 'COURT')", DECLARED))
                .isNotBlank();
    }

    // --- Recette 1.5 : variable non déclarée ---

    @Test
    void undeclaredKey_shouldBeRefusedWhenBlockPresent() {
        assertThatThrownBy(() -> SpelSecurityValidator.validate(
                        "#variables['inconnu'] == 1", DECLARED))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("non déclarée");
    }

    @Test
    void anyKey_shouldPassWhenBlockAbsent() {
        assertThat(SpelSecurityValidator.validate("#variables['tout'] == 1", null)).isNotBlank();
    }

    @Test
    void emptyBlock_shouldRefuseAllConditions() {
        assertThatThrownBy(() -> SpelSecurityValidator.validate(
                        "#variables['tout'] == 1", Set.of()))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("non déclarée");
    }

    @Test
    void nestedIndexer_shouldOnlyCheckFirstLevelKey() {
        assertThat(SpelSecurityValidator.validate(
                        "#variables['demandeur']['matricule'] == 'SN-042'",
                        Set.of("demandeur")))
                .isNotBlank();
        assertThatThrownBy(() -> SpelSecurityValidator.validate(
                        "#variables['x']['y'] == 1", Set.of("demandeur")))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("'x'");
    }

    @Test
    void unknownVariable_shouldBeRefused() {
        assertThatThrownBy(() -> SpelSecurityValidator.validate("#autre == 1", DECLARED))
                .isInstanceOf(WorkflowValidationException.class);
    }

    @Test
    void bareIdentifier_shouldBeRefused() {
        assertThatThrownBy(() -> SpelSecurityValidator.validate("circuit == 'COURT'", DECLARED))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("#variables['cle']");
    }

    // --- Recette 1.6 : adaptateur de syntaxe historique ---

    @Test
    void legacySyntax_shouldBeNormalized() {
        assertThat(SpelSecurityValidator.normalize("variables['circuit'] == 'COURT'"))
                .isEqualTo("#variables['circuit'] == 'COURT'");
        assertThat(SpelSecurityValidator.validate("variables['circuit'] == 'COURT'", DECLARED))
                .isEqualTo("#variables['circuit'] == 'COURT'");
    }

    @Test
    void normalize_shouldNotTouchLiteralsOrPrefixedForms() {
        assertThat(SpelSecurityValidator.normalize("#variables['x'] == 'variables'"))
                .isEqualTo("#variables['x'] == 'variables'");
        assertThat(SpelSecurityValidator.normalize("#variables['circuit'] == 'COURT'"))
                .isEqualTo("#variables['circuit'] == 'COURT'");
        assertThat(SpelSecurityValidator.normalize("myvariables == 1"))
                .isEqualTo("myvariables == 1");
    }

    // --- Recette 1.10 : limites de taille et de profondeur ---

    @Test
    void oversizedCondition_shouldBeRefused() {
        String huge = "#variables['circuit'] == '" + "x".repeat(480) + "'";
        assertThat(huge.length()).isGreaterThan(SpelSecurityValidator.MAX_CONDITION_LENGTH);
        assertThatThrownBy(() -> SpelSecurityValidator.validate(huge, DECLARED))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("taille");
    }

    @Test
    void deeplyNestedCondition_shouldBeRefused() {
        String deep = "(".repeat(11) + "#variables['circuit'] == 'COURT'" + ")".repeat(11);
        assertThatThrownBy(() -> SpelSecurityValidator.validate(deep, DECLARED))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("imbrication");
    }

    @Test
    void nullOrBlankCondition_shouldPassThrough() {
        assertThat(SpelSecurityValidator.validate(null, DECLARED)).isNull();
        assertThat(SpelSecurityValidator.validate("   ", DECLARED)).isEqualTo("   ");
    }

    @Test
    void invalidSyntax_shouldBeRefused() {
        assertThatThrownBy(() -> SpelSecurityValidator.validate(
                        "#variables['circuit'] === 'COURT'", DECLARED))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("invalide");
    }

    // --- Cohérence avec l'évaluateur : le normalisé s'évalue ---

    @Test
    void normalizedCondition_shouldEvaluate() {
        ConditionEvaluatorService evaluator = new ConditionEvaluatorService();
        String canonical = SpelSecurityValidator.validate(
                "variables['circuit'] == 'COURT'", DECLARED);
        assertThat(evaluator.evaluate(canonical, Map.of("circuit", "COURT"))).isTrue();
        assertThat(evaluator.evaluate(canonical, Map.of("circuit", "LONG"))).isFalse();
    }
}
