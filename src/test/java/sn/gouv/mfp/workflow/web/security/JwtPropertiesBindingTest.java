package sn.gouv.mfp.workflow.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Garde-fou de configuration : la correspondance de rôles déclarée via
 * {@code @TestPropertySource} (forme indexée {@code role-mapping[ROLE][0]}) doit bien
 * être liée à {@link JwtProperties}. Sans ce lien, un jeton portant un rôle connu
 * n'obtiendrait aucun rôle moteur (défaut sûr) et l'accès serait refusé (403).
 * <p>
 * Test sans base de données : seul {@link JwtProperties} est chargé.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = JwtPropertiesBindingTest.Config.class)
@TestPropertySource(properties = {
    "auth.jwt.issuers.test.issuer=https://test.mfp.local",
    "auth.jwt.issuers.test.audience=workflow-api",
    "auth.jwt.issuers.test.roles-claim=roles",
    "auth.jwt.issuers.test.role-mapping[AGENT_BUREAU][0]=AGENT_BUREAU"
})
class JwtPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class Config {
    }

    @Autowired
    private JwtProperties properties;

    @Test
    void roleMapping_fromTestPropertySource_shouldBind() {
        assertThat(properties.issuers()).containsKey("test");
        assertThat(properties.issuers().get("test").roleMapping())
                .containsEntry("AGENT_BUREAU", List.of("AGENT_BUREAU"));
    }
}
