package sn.gouv.mfp.workflow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catalogue du socle SQL : migrations appliquées dans l'ordre, déclencheurs
 * d'immuabilité et index de performance présents (recette 8.9 / 11.5).
 */
@SpringBootTest
@Transactional
class WorkflowSchemaCatalogTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void appliedMigrations_shouldCoverBaselineToLatest() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);
        // Préfixe ordonné : les futures migrations s'ajoutent sans casser ce test.
        assertThat(versions).startsWith("1", "2", "3", "4", "5");
    }

    @Test
    void immutabilityTriggers_shouldCoverFiveTables() {
        Number count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_immutable'", Number.class);
        assertThat(count.longValue()).isEqualTo(5);
    }

    @Test
    void performanceIndexes_shouldExist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND tablename LIKE 'workflow%' AND indexname LIKE 'idx\\_%'",
                String.class);
        assertThat(indexes).containsExactlyInAnyOrder(
                "idx_history_instance_time",
                "idx_instance_definition",
                "idx_instance_status",
                "idx_step_definition",
                "idx_transition_source",
                "idx_audit_key_time");
    }
}
