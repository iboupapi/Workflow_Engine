package sn.gouv.mfp.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import sn.gouv.mfp.workflow.config.WorkflowDemoRunner;

/**
 * Phase 0 — preuves de non-régression du socle.
 * <ul>
 *   <li>GAP-01 : l'exécuteur de démonstration est <strong>inactif</strong> au démarrage standard
 *       (aucun dossier fictif injecté) ; il n'existe que sous le profil {@code demo}.</li>
 *   <li>GAP-02 : Swagger / OpenAPI est <strong>désactivé par défaut</strong> ; l'endpoint
 *       {@code /v3/api-docs} n'est pas exposé tant qu'on ne l'active pas explicitement.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class Phase0GuardsTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void demoRunner_shouldNotBeActiveOutsideDemoProfile() {
        // Aucun bean WorkflowDemoRunner dans le contexte standard : rien ne s'exécute au démarrage.
        assertThat(context.getBeansOfType(WorkflowDemoRunner.class)).isEmpty();

        // Le contrat est porté par l'annotation : le runner n'est monté que sous le profil 'demo'.
        Profile profile = WorkflowDemoRunner.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("demo");
    }

    @Test
    void swagger_shouldBeDisabledByDefault() throws Exception {
        assertThat(environment.getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isFalse();

        // Comportement observable : la spécification OpenAPI n'est pas exposée.
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    }
}
