package sn.gouv.mfp.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import sn.gouv.mfp.workflow.repository.WorkflowDefinitionRepository;
import sn.gouv.mfp.workflow.service.dto.DefinitionImport;

/**
 * Cahier de recette 9.3 — deux imports simultanés : aucune version dupliquée ni corrompue.
 * Volontairement sans {@code @Transactional} : chaque thread engage sa propre transaction.
 */
@SpringBootTest
class WorkflowImportConcurrencyTest {

    @Autowired
    private WorkflowDefinitionLoaderService loaderService;

    @Autowired
    private WorkflowDefinitionRepository definitions;

    @Test
    void twoSimultaneousDifferentImports_shouldCreateTwoDistinctVersions() throws Exception {
        String key = "RACE_IMP_" + UUID.randomUUID().toString().substring(0, 8);
        String contentA = minimalYaml(key, "#variables['circuit'] == 'COURT'");
        String contentB = minimalYaml(key, "#variables['circuit'] == 'LONG'");

        List<DefinitionImport> results = runConcurrently(
                () -> loaderService.loadAndSaveYamlDefinition(contentA),
                () -> loaderService.loadAndSaveYamlDefinition(contentB));

        assertThat(results).allSatisfy(r -> assertThat(r.created()).isTrue());
        assertThat(results.get(0).definition().getVersion())
                .isNotEqualTo(results.get(1).definition().getVersion());
        assertThat(definitions.findByKey(key)).hasSize(2);
    }

    @Test
    void twoSimultaneousIdenticalImports_shouldCreateSingleVersion() throws Exception {
        String key = "RACE_IDEM_" + UUID.randomUUID().toString().substring(0, 8);
        String content = minimalYaml(key, "#variables['circuit'] == 'COURT'");

        List<DefinitionImport> results = runConcurrently(
                () -> loaderService.loadAndSaveYamlDefinition(content),
                () -> loaderService.loadAndSaveYamlDefinition(content));

        assertThat(definitions.findByKey(key)).hasSize(1);
        assertThat(results.get(0).definition().getId())
                .isEqualTo(results.get(1).definition().getId());
    }

    private List<DefinitionImport> runConcurrently(
            java.util.concurrent.Callable<DefinitionImport> first,
            java.util.concurrent.Callable<DefinitionImport> second) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DefinitionImport> futureA = pool.submit(() -> {
                gate.await(30, TimeUnit.SECONDS);
                return first.call();
            });
            Future<DefinitionImport> futureB = pool.submit(() -> {
                gate.await(30, TimeUnit.SECONDS);
                return second.call();
            });
            gate.countDown();
            return List.of(
                    futureA.get(120, TimeUnit.SECONDS), futureB.get(120, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private String minimalYaml(String key, String condition) {
        return """
                definition:
                  key: "%s"
                  name: "Circuit course"
                  version: 1
                variables:
                  - key: "circuit"
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
