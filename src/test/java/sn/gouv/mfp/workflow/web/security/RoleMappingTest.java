package sn.gouv.mfp.workflow.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Correspondance configurable des rôles (point 2) : rôles du jeton lus en LISTE, traduits
 * par la correspondance de l'émetteur ; un rôle du jeton inconnu n'accorde aucun droit.
 */
class RoleMappingTest {

    @Test
    void withoutMapping_tokenRoleIsEngineRole() {
        assertThat(JwtAuthConfig.engineRoles(List.of("A", "B"), null))
                .containsExactlyInAnyOrder("A", "B");
        assertThat(JwtAuthConfig.engineRoles(List.of("A"), Map.of()))
                .containsExactly("A");
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
    void nullRoles_grantNothing() {
        assertThat(JwtAuthConfig.engineRoles(null, Map.of("A", List.of("X")))).isEmpty();
        assertThat(JwtAuthConfig.engineRoles(null, null)).isEmpty();
    }
}
