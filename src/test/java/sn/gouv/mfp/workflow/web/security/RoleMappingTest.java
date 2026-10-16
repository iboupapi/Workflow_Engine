package sn.gouv.mfp.workflow.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Correspondance configurable des rôles (point 2). Règles :
 * <ul>
 *   <li>défaut sûr : <strong>aucune correspondance = aucun rôle</strong>, jamais l'identité
 *   du jeton ;</li>
 *   <li>un rôle moteur est accordé si le jeton porte l'un de ses rôles acceptés ;</li>
 *   <li>le rôle réservé {@code ADMIN} n'est jamais accordé depuis le jeton.</li>
 * </ul>
 */
class RoleMappingTest {

    @Test
    void withoutMapping_noRoleGranted_evenWithTokenRoles() {
        assertThat(JwtAuthConfig.engineRoles(List.of("AGENT_BUREAU", "ADMIN"), null)).isEmpty();
        assertThat(JwtAuthConfig.engineRoles(List.of("AGENT_BUREAU"), Map.of())).isEmpty();
    }

    @Test
    void withMapping_engineRoleGrantedWhenTokenCarriesAcceptedRole() {
        Map<String, List<String>> mapping = Map.of(
                "AGENT_BUREAU", List.of("ROLE_AGENT"),
                "CHEF_DIVISION", List.of("ROLE_CHEF", "GRADE_CHEF_DIV"));

        assertThat(JwtAuthConfig.engineRoles(List.of("ROLE_AGENT"), mapping))
                .containsExactly("AGENT_BUREAU");
        assertThat(JwtAuthConfig.engineRoles(List.of("GRADE_CHEF_DIV"), mapping))
                .containsExactly("CHEF_DIVISION");
    }

    @Test
    void withMapping_unknownTokenRoleGrantsNothing() {
        Map<String, List<String>> mapping = Map.of("AGENT_BUREAU", List.of("ROLE_AGENT"));

        assertThat(JwtAuthConfig.engineRoles(List.of("NINJA"), mapping)).isEmpty();
        assertThat(JwtAuthConfig.engineRoles(List.of("AGENT_BUREAU"), mapping)).isEmpty();
    }

    @Test
    void reservedAdminRole_neverGrantedFromToken() {
        // Tentative par identité (rôle du jeton « ADMIN »)
        assertThat(JwtAuthConfig.engineRoles(List.of("ADMIN"), Map.of("AGENT_BUREAU", List.of("X"))))
                .isEmpty();
        // Tentative par correspondance explicite vers « ADMIN »
        assertThat(JwtAuthConfig.engineRoles(
                        List.of("ROLE_SUPER"), Map.of("ADMIN", List.of("ROLE_SUPER"))))
                .isEmpty();
        // Même insensible à la casse / aux espaces
        assertThat(JwtAuthConfig.engineRoles(
                        List.of("ROLE_SUPER"), Map.of("  admin ", List.of("ROLE_SUPER"))))
                .isEmpty();
    }

    @Test
    void nullRoles_grantNothing() {
        assertThat(JwtAuthConfig.engineRoles(null, Map.of("A", List.of("X")))).isEmpty();
        assertThat(JwtAuthConfig.engineRoles(null, null)).isEmpty();
    }
}
