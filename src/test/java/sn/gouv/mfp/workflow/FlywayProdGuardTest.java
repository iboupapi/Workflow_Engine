package sn.gouv.mfp.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

/**
 * Garde-fou de déploiement : la configuration applicative embarquée n'active jamais
 * les migrations et ne détient jamais d'identifiants du propriétaire (recette 12.7).
 * Les migrations passent par le plugin Maven Flyway (propriétaire) ou le profil
 * {@code migrate} (tests et environnements d'intégration uniquement).
 */
class FlywayProdGuardTest {

    @Test
    @SuppressWarnings("unchecked")
    void packagedApplicationYml_shouldNeverMigrateNorHoldOwnerCredentials() throws Exception {
        Map<String, Object> config;
        try (var reader = java.nio.file.Files.newBufferedReader(
                Path.of("src/main/resources/application.yml"))) {
            config = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        }
        Map<String, Object> spring = (Map<String, Object>) config.get("spring");
        Map<String, Object> flyway = (Map<String, Object>) spring.get("flyway");

        assertThat(flyway.get("enabled")).isEqualTo(false);
        assertThat(flyway).doesNotContainKeys("user", "password");
        assertThat(flyway.keySet().stream().map(Object::toString))
                .noneMatch(key -> key.contains("OWNER") || key.contains("owner"));
    }
}
