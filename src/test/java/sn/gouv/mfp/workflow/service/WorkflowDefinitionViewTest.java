package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import sn.gouv.mfp.workflow.domain.definition.EntryPoint;
import sn.gouv.mfp.workflow.exception.WorkflowNotFoundException;
import sn.gouv.mfp.workflow.service.WorkflowDefinitionViewService.StepView;
import sn.gouv.mfp.workflow.service.WorkflowDefinitionViewService.TransitionView;

/**
 * Preuve du prérequis « endpoint de définition » (Phase 2) : la lecture structurelle d'un
 * circuit réel (E-Service) expose étapes, rôles responsables, transitions (avec le marqueur
 * de traitement) et points d'entrée — exactement ce dont le visualiseur a besoin.
 */
@SpringBootTest
@Transactional
class WorkflowDefinitionViewTest {

    @Autowired
    private WorkflowDefinitionLoaderService loaderService;

    @Autowired
    private WorkflowDefinitionViewService viewService;

    @Test
    void view_shouldExposeTheWholeCircuitStructure() throws Exception {
        String key = loadUniqueKey();
        loaderService.loadAndSaveYamlDefinition(eserviceYamlAs(key));

        var view = viewService.view(key);

        assertThat(view.key()).isEqualTo(key);
        assertThat(view.entryPoints()).extracting(EntryPoint::role).containsExactly("CITOYEN");
        assertThat(view.variables()).isEmpty();
        assertThat(view.steps()).extracting(StepView::key)
                .containsExactlyInAnyOrder(
                        "DEPOT_CITOYEN",
                        "CHEF_DIVISION",
                        "AGENT_TRAITEMENT",
                        "DLEC",
                        "DGFP",
                        "SG",
                        "DEMANDE_TRAITEE");
        // Les deux sorties du chef vers la DLEC : traitement direct marqué, validation après
        // délégation NON marquée (règle métier du rapport d'étude).
        assertThat(view.transitions())
                .filteredOn(t -> t.source().equals("CHEF_DIVISION") && t.target().equals("DLEC"))
                .extracting(TransitionView::action, TransitionView::setsHandler)
                .containsExactlyInAnyOrder(tuple("VALIDER", true), tuple("TRANSMETTRE", false));
        // Rejet des autorités supérieures : cible dynamique RESPONSABLE_TRAITEMENT.
        assertThat(view.transitions())
                .filteredOn(t -> t.target().equals("RESPONSABLE_TRAITEMENT"))
                .extracting(TransitionView::source)
                .containsExactlyInAnyOrder("DLEC", "DGFP");
        assertThat(view.steps())
                .filteredOn(s -> s.key().equals("DEPOT_CITOYEN"))
                .extracting(StepView::owners)
                .containsExactly(java.util.List.of("CITOYEN"));
    }

    @Test
    void view_unknownKey_shouldBeNotFound() {
        assertThatThrownBy(() -> viewService.view("CLÉ_INCONNUE"))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    private String loadUniqueKey() {
        return "VIEW_ESERVICE_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String eserviceYamlAs(String key) throws Exception {
        return StreamUtils.copyToString(
                        new ClassPathResource("workflows/eservice-workflow.yaml").getInputStream(),
                        StandardCharsets.UTF_8)
                .replace("\"E_SERVICE_DEMANDE\"", "\"" + key + "\"");
    }
}
