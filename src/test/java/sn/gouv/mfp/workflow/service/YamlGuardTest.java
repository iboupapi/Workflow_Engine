package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;

/**
 * Garde YAML au niveau parseur : balises d'objets et références internes refusées,
 * usages légitimes (y compris {@code !} entre quotes) préservés.
 */
class YamlGuardTest {

    @Test
    void standardTags_shouldPass() {
        assertThatCode(() -> YamlGuard.checkStructure("name: !!str \"x\"\ncount: !!int 3\n"))
                .doesNotThrowAnyException();
    }

    @Test
    void objectTag_shouldBeRefused() {
        assertThatThrownBy(() -> YamlGuard.checkStructure("a: !!java.util.ArrayList [1]\n"))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("!!java.util.ArrayList");
    }

    @Test
    void customAndVerbatimTags_shouldBeRefused() {
        assertThatThrownBy(() -> YamlGuard.checkStructure("a: !perso x\n"))
                .isInstanceOf(WorkflowValidationException.class);
        assertThatThrownBy(
                        () -> YamlGuard.checkStructure("a: !<tag:example.com,2024:x> y\n"))
                .isInstanceOf(WorkflowValidationException.class);
    }

    @Test
    void tagOnKey_shouldBeRefused() {
        assertThatCode(() -> YamlGuard.checkStructure("? !!str cle\n: valeur\n"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> YamlGuard.checkStructure("? !perso cle\n: valeur\n"))
                .isInstanceOf(WorkflowValidationException.class);
    }

    @Test
    void bangInLiteralOrComment_shouldBeIgnored() {
        assertThatCode(() -> YamlGuard.checkStructure(
                        "name: \"l'agent !!com fictif\"\n# commentaire !!evil\ncle: 'a!!b'\n"))
                .doesNotThrowAnyException();
    }

    @Test
    void anchorAndAlias_shouldBeRefused() {
        assertThatThrownBy(() -> YamlGuard.checkStructure("x: &ancre [1]\n"))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("ancre");
        assertThatThrownBy(() -> YamlGuard.checkStructure("x: &a 1\ny: *a\n"))
                .isInstanceOf(WorkflowValidationException.class);
    }

    @Test
    void quotedAnchor_shouldBeIgnored() {
        assertThatCode(() -> YamlGuard.checkStructure("label: \"Tom & Jerry\"\n"))
                .doesNotThrowAnyException();
    }

    @Test
    void recursiveAlias_shouldBeRefused() {
        assertThatThrownBy(() -> YamlGuard.checkStructure("piege: &a\n  boucle: *a\n"))
                .isInstanceOf(WorkflowValidationException.class);
    }
}
