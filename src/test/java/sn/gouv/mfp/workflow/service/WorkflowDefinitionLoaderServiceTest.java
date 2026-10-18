package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import sn.gouv.mfp.workflow.domain.definition.StepType;
import sn.gouv.mfp.workflow.domain.definition.WorkflowDefinition;
import sn.gouv.mfp.workflow.domain.definition.WorkflowStep;
import sn.gouv.mfp.workflow.exception.WorkflowValidationException;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.repository.WorkflowImportAuditRepository;
import sn.gouv.mfp.workflow.repository.WorkflowStepRepository;
import sn.gouv.mfp.workflow.repository.WorkflowTransitionRepository;
import sn.gouv.mfp.workflow.service.dto.DefinitionImport;

/**
 * Chargeur YAML : import GIRAFE, versionnage, idempotence des réimports (recette 7.6 / 7.7),
 * audit (recette 7.5) et rejets des graphes incohérents. Rollback par test.
 */
@SpringBootTest
@Transactional
class WorkflowDefinitionLoaderServiceTest {

    @Autowired
    private WorkflowDefinitionLoaderService loaderService;

    @Autowired
    private WorkflowDefinitionRepository definitions;

    @Autowired
    private WorkflowImportAuditRepository audits;

    @Autowired
    private WorkflowStepRepository steps;

    @Autowired
    private WorkflowTransitionRepository transitions;

    @Test
    void importGirafe_shouldPersistFullCircuitAsNextVersion() throws Exception {
        String key = "GIRAFE_T_" + UUID.randomUUID().toString().substring(0, 8);
        String yaml = girafeYamlAs(key);

        DefinitionImport result = loaderService.loadAndSaveYamlDefinition(yaml);
        WorkflowDefinition definition = result.definition();

        assertThat(result.created()).isTrue();
        assertThat(definition.getKey()).isEqualTo(key);
        assertThat(definition.getName()).isEqualTo("Circuit de Validation des Actes Administratifs");
        assertThat(definition.getVersion()).isEqualTo(1);

        var savedSteps = steps.findByDefinitionId(definition.getId());
        // 13 nœuds : 12 niveaux (le niveau 9 est réservé, cf. commentaire YAML) + OFFICIEL_VALIDE.
        assertThat(savedSteps).hasSize(13);
        assertThat(savedSteps)
                .filteredOn(s -> s.getType() == StepType.START)
                .extracting(WorkflowStep::getStepKey)
                .containsExactly("AGENT_BUREAU");
        assertThat(steps.findByDefinitionIdAndStepKey(definition.getId(), "OFFICIEL_VALIDE"))
                .get()
                .satisfies(s -> {
                    assertThat(s.getType()).isEqualTo(StepType.END);
                    assertThat(s.getName()).isEqualTo("Acte Clôturé et Numéroté");
                });
        assertThat(steps.findByDefinitionIdAndStepKey(definition.getId(), "NUMEROTEUR"))
                .get()
                .satisfies(s -> assertThat(s.getRequiredData()).containsExactly("numeroOfficiel"));

        int transitionCount = savedSteps.stream()
                .mapToInt(s -> transitions.findBySourceStepId(s.getId()).size())
                .sum();
        assertThat(transitionCount).isEqualTo(24);

        // Aiguillage S-Visa : 3 conditions SpEL distinctes sur la même action (sans repli :
        // une valeur absente ou fautive est refusée au lieu d'être routée au hasard).
        var visaStep = steps.findByDefinitionIdAndStepKey(definition.getId(), "S_VISA").orElseThrow();
        assertThat(transitions.findBySourceStepIdAndAction(visaStep.getId(), "VALIDER"))
                .hasSize(3)
                .extracting(t -> t.getTargetStepKey() + "|" + t.getConditionExpression())
                .containsExactlyInAnyOrder(
                        "DGFP|#variables['circuit'] == 'COURT'",
                        "DPR|#variables['circuit'] == 'PLUS_LONG'",
                        "DS|#variables['circuit'] == 'LONG'");

        // Bloc settings remonté au niveau racine (il était indenté sous definition : ignoré
        // en silence par le parseur YAML). MOT-05 : plafond par rôle ET par type d'acte,
        // scopé sur le seul rôle AGENT_BUREAU (les chefs ne sont pas plafonnés).
        assertThat(definition.getSettings())
                .doesNotContainKey("maxActiveInstancesPerType")
                .doesNotContainKey("maxActiveInstancesPerInitiator")
                .containsEntry("resumeStrategyDefault", "FROM_START");
        assertThat(definition.getSettings().get("activeInstanceLimits"))
                .isEqualTo(List.of(Map.of("role", "AGENT_BUREAU", "maxPerType", 1)));
        assertThat(definition.getEntryPoints())
                .hasSize(3)
                .extracting(e -> e.role() + "->" + e.startStepKey())
                .containsExactlyInAnyOrder(
                        "AGENT_BUREAU->AGENT_BUREAU",
                        "CHEF_BUREAU->CHEF_BUREAU",
                        "CHEF_DIVISION->CHEF_DIVISION");
    }

    @Test
    void reimportIdentical_shouldReuseVersion() throws Exception {
        String key = "GIRAFE_R_" + UUID.randomUUID().toString().substring(0, 8);
        DefinitionImport first = loaderService.loadAndSaveYamlDefinition(girafeYamlAs(key));

        DefinitionImport second = loaderService.loadAndSaveYamlDefinition(girafeYamlAs(key));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.definition().getId()).isEqualTo(first.definition().getId());
        assertThat(second.definition().getVersion())
                .isEqualTo(first.definition().getVersion());
        assertThat(definitions.findByKey(key)).hasSize(1);
    }

    @Test
    void reimportWithCosmeticChanges_shouldReuseVersion() throws Exception {
        String key = "GIRAFE_C_" + UUID.randomUUID().toString().substring(0, 8);
        DefinitionImport first = loaderService.loadAndSaveYamlDefinition(girafeYamlAs(key));

        // Commentaires et indentation modifiés, contenu identique (recette 7.7).
        String cosmetic = "# commentaire ajouté pour la recette 7.7\n" + girafeYamlAs(key)
                .replace("  key: \"" + key + "\"", "  key: \"" + key + "\"  ");
        DefinitionImport second = loaderService.loadAndSaveYamlDefinition(cosmetic);

        assertThat(second.created()).isFalse();
        assertThat(second.definition().getId()).isEqualTo(first.definition().getId());
    }

    @Test
    void legacySyntax_shouldShareFingerprintWithCanonical() {
        String canonical = minimalYaml("LEGACY_A", "#variables['circuit'] == 'COURT'");
        String legacy = minimalYaml("LEGACY_A", "variables['circuit'] == 'COURT'");

        DefinitionImport first = loaderService.loadAndSaveYamlDefinition(canonical);
        DefinitionImport second = loaderService.loadAndSaveYamlDefinition(legacy);

        // L'empreinte porte sur la forme normalisée : les deux écritures sont identiques.
        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.definition().getId()).isEqualTo(first.definition().getId());
    }

    @Test
    void yamlVersionField_shouldBeIgnored() {
        String yaml = minimalYaml("VERSIONED", "#variables['circuit'] == 'COURT'")
                .replace("  version: 1", "  version: 99");
        int previousMax = definitions.findMaxVersionByKey("VERSIONED").orElse(0);

        DefinitionImport result = loaderService.loadAndSaveYamlDefinition(yaml);

        assertThat(result.created()).isTrue();
        assertThat(result.definition().getVersion()).isEqualTo(previousMax + 1);
    }

    @Test
    void import_shouldWriteAuditLine() throws Exception {
        String key = "GIRAFE_A_" + UUID.randomUUID().toString().substring(0, 8);
        DefinitionImport result = loaderService.loadAndSaveYamlDefinition(girafeYamlAs(key));
        loaderService.loadAndSaveYamlDefinition(girafeYamlAs(key));

        var lines = audits.findByDefinitionKeyOrderByImportedAtDesc(key);
        assertThat(lines)
                .hasSize(2)
                .allSatisfy(a -> {
                    assertThat(a.getVersion()).isEqualTo(result.definition().getVersion());
                    assertThat(a.getSha256()).hasSize(64);
                    assertThat(a.getContent()).contains(key);
                });
        assertThat(lines.stream().map(a -> a.isCreated()))
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    void importEserviceSkeleton_shouldRespectSchema() throws Exception {
        String key = "ESERVICE_T_" + UUID.randomUUID().toString().substring(0, 8);
        String yaml = StreamUtils.copyToString(
                        new ClassPathResource("workflows/eservice-workflow.yaml").getInputStream(),
                        StandardCharsets.UTF_8)
                .replace("\"E_SERVICE_DEMANDE\"", "\"" + key + "\"");

        DefinitionImport result = loaderService.loadAndSaveYamlDefinition(yaml);

        assertThat(result.created()).isTrue();
        assertThat(result.definition().getKey()).isEqualTo(key);
        assertThat(steps.findByDefinitionId(result.definition().getId())).hasSize(7);
    }

    @Test
    void importWithoutVariablesBlock_shouldReject() {
        String yaml = """
                definition:
                  key: "NO_VARS"
                  name: "Circuit sans bloc variables"
                steps:
                  - key: "A"
                    type: "START"
                  - key: "B"
                    type: "END"
                transitions:
                  - source: "A"
                    action: "VALIDER"
                    target: "B"
                """;

        assertThatThrownBy(() -> loaderService.loadAndSaveYamlDefinition(yaml))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("variables");
    }

    @Test
    void importWithUnknownSource_shouldReject() {
        String yaml = """
                definition:
                  key: "BROKEN_SRC"
                  name: "Circuit cassé"
                variables:
                  - "circuit"
                steps:
                  - key: "A"
                    type: "START"
                  - key: "B"
                    type: "END"
                transitions:
                  - source: "X_INCONNU"
                    action: "VALIDER"
                    target: "B"
                """;

        assertThatThrownBy(() -> loaderService.loadAndSaveYamlDefinition(yaml))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("X_INCONNU");
    }

    @Test
    void importWithUnknownTarget_shouldReject() {
        String yaml = """
                definition:
                  key: "BROKEN_TGT"
                  name: "Circuit cassé"
                variables:
                  - "circuit"
                steps:
                  - key: "A"
                    type: "START"
                  - key: "B"
                    type: "END"
                transitions:
                  - source: "A"
                    action: "VALIDER"
                    target: "Z_INCONNU"
                """;

        assertThatThrownBy(() -> loaderService.loadAndSaveYamlDefinition(yaml))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Z_INCONNU");
    }

    @Test
    void importWithInvalidSpel_shouldReject() {
        String yaml = """
                definition:
                  key: "BROKEN_SPEL"
                  name: "Circuit cassé"
                variables:
                  - "circuit"
                steps:
                  - key: "A"
                    type: "START"
                  - key: "B"
                    type: "END"
                transitions:
                  - source: "A"
                    action: "VALIDER"
                    target: "B"
                    condition: "#variables['circuit'] === 'COURT'"
                """;

        assertThatThrownBy(() -> loaderService.loadAndSaveYamlDefinition(yaml))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("SpEL");
    }

    @Test
    void importEmptyContent_shouldReject() {
        assertThatThrownBy(() -> loaderService.loadAndSaveYamlDefinition("  "))
                .isInstanceOf(WorkflowValidationException.class);
    }

    @Test
    void importWithReservedAdminOwner_shouldReject() {
        String yaml = """
                definition:
                  key: "ADMIN_OWNER"
                  name: "Circuit avec ADMIN responsable"
                variables: []
                steps:
                  - key: "A"
                    type: "START"
                    owners: ["ADMIN"]
                  - key: "B"
                    type: "END"
                transitions:
                  - source: "A"
                    action: "VALIDER"
                    target: "B"
                """;

        assertThatThrownBy(() -> loaderService.loadAndSaveYamlDefinition(yaml))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void importWithUnknownField_shouldBeRefusedNamingIt() {
        String yaml = """
                definition:
                  key: "TYPO_CIRCUIT"
                  name: "Circuit avec faute de frappe"
                variables: []
                steps:
                  - key: "A"
                    type: "START"
                    ownerz: ["AGENT_BUREAU"]
                  - key: "B"
                    type: "END"
                transitions:
                  - source: "A"
                    action: "VALIDER"
                    target: "B"
                """;

        assertThatThrownBy(() -> loaderService.loadAndSaveYamlDefinition(yaml))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Champ YAML inconnu")
                .hasMessageContaining("ownerz");
    }

    @Test
    void importWithPerTypeQuota_shouldPersistSettings() throws Exception {
        String key = "QUOTA_T_" + UUID.randomUUID().toString().substring(0, 8);
        String yaml = """
                definition:
                  key: "%s"
                  name: "Circuit quota par type"
                settings:
                  maxActiveInstancesPerType: 2
                  maxActiveInstancesPerInitiator: 5
                variables:
                  - "natureActe"
                steps:
                  - key: "A"
                    type: "START"
                  - key: "B"
                    type: "END"
                transitions:
                  - source: "A"
                    action: "VALIDER"
                    target: "B"
                """.formatted(key);

        DefinitionImport result = loaderService.loadAndSaveYamlDefinition(yaml);

        assertThat(result.created()).isTrue();
        assertThat(result.definition().getSettings())
                .containsEntry("maxActiveInstancesPerType", 2)
                .containsEntry("maxActiveInstancesPerInitiator", 5)
                .containsEntry("resumeStrategyDefault", "FROM_START");
    }

    private String girafeYaml() throws Exception {
        return StreamUtils.copyToString(
                new ClassPathResource("workflows/girafe-workflow.yaml").getInputStream(),
                StandardCharsets.UTF_8);
    }

    /** Contenu GIRAFE sous une clé unique : isole le test des données résiduelles. */
    private String girafeYamlAs(String key) throws Exception {
        return girafeYaml().replace("\"GIRAFE_ACTES\"", "\"" + key + "\"");
    }

    /** Circuit minimal valide (2 étapes, 1 transition conditionnelle) pour les tests ciblés. */
    private String minimalYaml(String key, String condition) {
        return """
                definition:
                  key: "%s"
                  name: "Circuit minimal"
                  version: 1
                variables:
                  - "circuit"
                steps:
                  - key: "A"
                    type: "START"
                  - key: "B"
                    type: "END"
                transitions:
                  - source: "A"
                    action: "VALIDER"
                    target: "B"
                    condition: "%s"
                """.formatted(key, condition);
    }
}
